"""Unit tests for `app.services.event_consumer.EventConsumer`.

Проверяет основные ветви обработки Redis Stream событий: дедупликацию,
разветвление по типу события, фильтрацию заблокированных получателей,
кэширование членов/пользователей/URL аватара и корректную отправку пушей
с обновлением состояния токенов устройств.
"""

from __future__ import annotations

import json
import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.infrastructure.messaging_client import MemberInfo
from app.infrastructure.user_client import UserInfo
from app.services.event_consumer import EventConsumer
from app.services.push_sender import PushErrorType, PushPlatform, PushResult


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_consumer() -> EventConsumer:
    """Собирает `EventConsumer` со всеми зависимостями на AsyncMock."""
    messaging_redis = MagicMock()
    messaging_redis.xgroup_create = AsyncMock()
    messaging_redis.xreadgroup = AsyncMock(return_value=[])
    messaging_redis.xack = AsyncMock()
    messaging_redis.xautoclaim = AsyncMock(return_value=(b"0-0", [], []))

    own_redis = MagicMock()
    own_redis.get = AsyncMock(return_value=None)
    own_redis.set = AsyncMock(return_value=True)

    messaging_client = MagicMock()
    messaging_client.get_conversation_members = AsyncMock(return_value=[])

    user_client = MagicMock()
    user_client.get_users_batch = AsyncMock(return_value=[])

    item_storage_client = MagicMock()
    item_storage_client.get_download_url = AsyncMock(return_value=None)

    contacts_client = MagicMock()
    contacts_client.is_blocked = AsyncMock(return_value=False)

    return EventConsumer(
        messaging_redis=messaging_redis,
        own_redis=own_redis,
        messaging_client=messaging_client,
        user_client=user_client,
        item_storage_client=item_storage_client,
        contacts_client=contacts_client,
    )


@pytest.fixture
def consumer() -> EventConsumer:
    return _make_consumer()


# ---------------------------------------------------------------------------
# start / stop
# ---------------------------------------------------------------------------
class TestStartStop:
    async def test_start_creates_group_then_stops_cleanly(self, consumer):
        # Arrange
        stopped_early = AsyncMock()

        async def _fake_consume_loop() -> None:
            # Имитируем один цикл и сразу завершаемся.
            consumer._running = False
            await stopped_early()

        async def _fake_claim_loop() -> None:
            return

        # Act
        with (
            patch.object(consumer, "_consume_loop", new=_fake_consume_loop),
            patch.object(consumer, "_claim_loop", new=_fake_claim_loop),
        ):
            await consumer.start()

        # Assert
        consumer._messaging_redis.xgroup_create.assert_awaited_once_with(
            consumer._stream,
            consumer._group,
            id="0",
            mkstream=True,
        )
        stopped_early.assert_awaited_once()
        assert consumer._running is False

    async def test_start_swallows_existing_group_error(self, consumer):
        # Arrange
        consumer._messaging_redis.xgroup_create.side_effect = RuntimeError("BUSYGROUP")

        async def _noop() -> None:
            return

        # Act
        with (
            patch.object(consumer, "_consume_loop", new=_noop),
            patch.object(consumer, "_claim_loop", new=_noop),
        ):
            await consumer.start()

        # Assert
        assert consumer._running is True

    async def test_stop_sets_running_false(self, consumer):
        # Arrange
        consumer._running = True

        # Act
        await consumer.stop()

        # Assert
        assert consumer._running is False


# ---------------------------------------------------------------------------
# _process_entry: dedup + dispatch
# ---------------------------------------------------------------------------
class TestProcessEntry:
    async def test_process_entry_routes_new_message(self, consumer):
        # Arrange
        payload = {"conversation_id": "c-1", "sender_user_id": "u-1"}
        fields = {b"type": b"new_message", b"payload": json.dumps(payload).encode()}

        # Act
        with patch.object(
            consumer,
            "_handle_new_message",
            new=AsyncMock(),
        ) as handler:
            await consumer._process_entry(b"1-0", fields)

        # Assert
        handler.assert_awaited_once_with(payload)
        consumer._messaging_redis.xack.assert_awaited_once()

    async def test_process_entry_routes_member_added(self, consumer):
        # Arrange
        payload = {"conversation_id": "c-1", "added_user_ids": ["u-2"]}
        fields = {"type": "member_added", "payload": json.dumps(payload)}

        # Act
        with patch.object(
            consumer,
            "_handle_member_added",
            new=AsyncMock(),
        ) as handler:
            await consumer._process_entry("1-0", fields)

        # Assert
        handler.assert_awaited_once_with(payload)

    async def test_process_entry_routes_member_kicked(self, consumer):
        # Arrange
        payload = {"conversation_id": "c-1", "kicked_user_id": "u-3"}
        fields = {"type": "member_kicked", "payload": json.dumps(payload)}

        # Act
        with patch.object(
            consumer,
            "_handle_member_kicked",
            new=AsyncMock(),
        ) as handler:
            await consumer._process_entry("2-0", fields)

        # Assert
        handler.assert_awaited_once_with(payload)

    async def test_process_entry_skips_duplicate(self, consumer):
        # Arrange
        consumer._own_redis.set = AsyncMock(return_value=None)  # nx фейл — дубль
        fields = {"type": "new_message", "payload": "{}"}

        # Act
        with patch.object(
            consumer,
            "_handle_new_message",
            new=AsyncMock(),
        ) as handler:
            await consumer._process_entry("3-0", fields)

        # Assert
        handler.assert_not_awaited()
        consumer._messaging_redis.xack.assert_awaited_once()

    async def test_process_entry_unknown_event_type_is_acked(self, consumer):
        # Arrange
        fields = {"type": "wat", "payload": "{}"}

        # Act
        await consumer._process_entry("4-0", fields)

        # Assert
        consumer._messaging_redis.xack.assert_awaited_once()

    async def test_process_entry_swallows_exceptions(self, consumer):
        # Arrange: некорректный JSON → json.loads упадёт → только лог.
        fields = {"type": "new_message", "payload": "{not-json"}

        # Act
        await consumer._process_entry("5-0", fields)

        # Assert: xack НЕ вызван, потому что исключение до него.
        consumer._messaging_redis.xack.assert_not_awaited()


# ---------------------------------------------------------------------------
# _handle_new_message
# ---------------------------------------------------------------------------
class TestHandleNewMessage:
    async def test_no_recipients_returns_without_sending(self, consumer):
        # Arrange: только отправитель в беседе.
        consumer._messaging_client.get_conversation_members = AsyncMock(
            return_value=[MemberInfo(user_id="u-sender", role="member")],
        )

        # Act
        with patch.object(
            consumer,
            "_send_push_to_users",
            new=AsyncMock(),
        ) as send:
            await consumer._handle_new_message(
                {"conversation_id": "c-1", "sender_user_id": "u-sender"},
            )

        # Assert
        send.assert_not_awaited()

    async def test_direct_chat_uses_sender_name_as_title(self, consumer):
        # Arrange
        consumer._messaging_client.get_conversation_members = AsyncMock(
            return_value=[
                MemberInfo(user_id="u-sender", role="member"),
                MemberInfo(user_id="u-recipient", role="member"),
            ],
        )
        consumer._user_client.get_users_batch = AsyncMock(
            return_value=[
                UserInfo(
                    user_id="u-sender",
                    display_name="Alice",
                    username="alice",
                    avatar_media_id="",
                ),
            ],
        )

        # Act
        with patch.object(
            consumer,
            "_send_push_to_users",
            new=AsyncMock(),
        ) as send:
            await consumer._handle_new_message(
                {
                    "conversation_id": "c-1",
                    "conversation_type": "direct",
                    "sender_user_id": "u-sender",
                    "message_type": "text",
                },
            )

        # Assert
        send.assert_awaited_once()
        kwargs = send.call_args.kwargs
        assert kwargs["title"] == "Alice"
        assert kwargs["body"] == "Новое сообщение"
        assert kwargs["recipient_user_ids"] == ["u-recipient"]

    async def test_group_chat_uses_conversation_name_and_typed_label(self, consumer):
        # Arrange
        consumer._messaging_client.get_conversation_members = AsyncMock(
            return_value=[
                MemberInfo(user_id="u-sender", role="member"),
                MemberInfo(user_id="u-rec", role="member"),
            ],
        )
        consumer._user_client.get_users_batch = AsyncMock(
            return_value=[
                UserInfo(
                    user_id="u-sender",
                    display_name="Bob",
                    username="bob",
                    avatar_media_id="",
                ),
            ],
        )

        # Act
        with patch.object(
            consumer,
            "_send_push_to_users",
            new=AsyncMock(),
        ) as send:
            await consumer._handle_new_message(
                {
                    "conversation_id": "c-2",
                    "conversation_type": "group",
                    "conversation_name": "DevTeam",
                    "sender_user_id": "u-sender",
                    "message_type": "image",
                },
            )

        # Assert
        kwargs = send.call_args.kwargs
        assert kwargs["title"] == "DevTeam"
        assert kwargs["body"] == "Bob: Фото"

    async def test_all_recipients_blocked_sender_skips_send(self, consumer):
        # Arrange
        consumer._messaging_client.get_conversation_members = AsyncMock(
            return_value=[
                MemberInfo(user_id="u-sender", role="member"),
                MemberInfo(user_id="u-rec", role="member"),
            ],
        )
        consumer._contacts_client.is_blocked = AsyncMock(return_value=True)

        # Act
        with patch.object(
            consumer,
            "_send_push_to_users",
            new=AsyncMock(),
        ) as send:
            await consumer._handle_new_message(
                {
                    "conversation_id": "c-1",
                    "conversation_type": "direct",
                    "sender_user_id": "u-sender",
                },
            )

        # Assert
        send.assert_not_awaited()


# ---------------------------------------------------------------------------
# _handle_member_added / _handle_member_kicked
# ---------------------------------------------------------------------------
class TestHandleMemberEvents:
    async def test_member_added_sends_push_to_added_users(self, consumer):
        # Arrange
        event = {
            "conversation_id": "c-1",
            "conversation_name": "Друзья",
            "added_user_ids": ["u-1", "u-2"],
        }

        # Act
        with patch.object(
            consumer,
            "_send_push_to_users",
            new=AsyncMock(),
        ) as send:
            await consumer._handle_member_added(event)

        # Assert
        send.assert_awaited_once()
        kwargs = send.call_args.kwargs
        assert kwargs["recipient_user_ids"] == ["u-1", "u-2"]
        assert kwargs["title"] == "Друзья"
        assert kwargs["body"] == "Вас добавили в группу"

    async def test_member_added_noop_when_no_users(self, consumer):
        # Arrange
        event = {"conversation_id": "c-1", "added_user_ids": []}

        # Act
        with patch.object(
            consumer,
            "_send_push_to_users",
            new=AsyncMock(),
        ) as send:
            await consumer._handle_member_added(event)

        # Assert
        send.assert_not_awaited()

    async def test_member_kicked_sends_push_to_kicked_user(self, consumer):
        # Arrange
        event = {
            "conversation_id": "c-1",
            "conversation_name": "Тим",
            "kicked_user_id": "u-7",
        }

        # Act
        with patch.object(
            consumer,
            "_send_push_to_users",
            new=AsyncMock(),
        ) as send:
            await consumer._handle_member_kicked(event)

        # Assert
        kwargs = send.call_args.kwargs
        assert kwargs["recipient_user_ids"] == ["u-7"]
        assert kwargs["body"] == "Вас удалили из группы"

    async def test_member_kicked_noop_when_no_user(self, consumer):
        # Arrange
        event = {"conversation_id": "c-1", "kicked_user_id": ""}

        # Act
        with patch.object(
            consumer,
            "_send_push_to_users",
            new=AsyncMock(),
        ) as send:
            await consumer._handle_member_kicked(event)

        # Assert
        send.assert_not_awaited()


# ---------------------------------------------------------------------------
# _filter_blocked_recipients
# ---------------------------------------------------------------------------
class TestFilterBlockedRecipients:
    async def test_uses_cache_when_present(self, consumer):
        # Arrange: в кэше — "1" для первого, "0" для второго.
        async def fake_get(key: str):
            if key.endswith("u-a:u-s"):
                return b"1"
            return b"0"

        consumer._own_redis.get = AsyncMock(side_effect=fake_get)

        # Act
        result = await consumer._filter_blocked_recipients(["u-a", "u-b"], "u-s")

        # Assert
        assert result == ["u-b"]
        consumer._contacts_client.is_blocked.assert_not_awaited()

    async def test_falls_back_to_contacts_client_and_writes_cache(self, consumer):
        # Arrange
        consumer._own_redis.get = AsyncMock(return_value=None)
        consumer._contacts_client.is_blocked = AsyncMock(
            side_effect=[False, True],
        )

        # Act
        result = await consumer._filter_blocked_recipients(["u-a", "u-b"], "u-s")

        # Assert
        assert result == ["u-a"]
        assert consumer._contacts_client.is_blocked.await_count == 2
        assert consumer._own_redis.set.await_count == 2


# ---------------------------------------------------------------------------
# _get_members_cached / _get_user_cached / _get_avatar_url_cached
# ---------------------------------------------------------------------------
class TestCacheHelpers:
    async def test_members_cache_hit_skips_grpc(self, consumer):
        # Arrange
        cached = json.dumps([{"user_id": "u-1", "role": "admin"}])
        consumer._own_redis.get = AsyncMock(return_value=cached)

        # Act
        members = await consumer._get_members_cached("c-1")

        # Assert
        assert members == [MemberInfo(user_id="u-1", role="admin")]
        consumer._messaging_client.get_conversation_members.assert_not_awaited()

    async def test_members_cache_miss_calls_grpc_and_stores(self, consumer):
        # Arrange
        consumer._own_redis.get = AsyncMock(return_value=None)
        consumer._messaging_client.get_conversation_members = AsyncMock(
            return_value=[MemberInfo(user_id="u-9", role="member")],
        )

        # Act
        members = await consumer._get_members_cached("c-1")

        # Assert
        assert members == [MemberInfo(user_id="u-9", role="member")]
        consumer._own_redis.set.assert_awaited_once()

    async def test_user_cache_hit_returns_without_grpc(self, consumer):
        # Arrange
        cached = json.dumps(
            {
                "user_id": "u-1",
                "display_name": "Sam",
                "username": "sam",
                "avatar_media_id": "m-1",
            }
        )
        consumer._own_redis.get = AsyncMock(return_value=cached)

        # Act
        user = await consumer._get_user_cached("u-1")

        # Assert
        assert user == UserInfo(
            user_id="u-1",
            display_name="Sam",
            username="sam",
            avatar_media_id="m-1",
        )
        consumer._user_client.get_users_batch.assert_not_awaited()

    async def test_user_cache_miss_fetches_and_returns_first(self, consumer):
        # Arrange
        consumer._own_redis.get = AsyncMock(return_value=None)
        consumer._user_client.get_users_batch = AsyncMock(
            return_value=[
                UserInfo(
                    user_id="u-1",
                    display_name="Sam",
                    username="sam",
                    avatar_media_id="m-1",
                ),
            ],
        )

        # Act
        user = await consumer._get_user_cached("u-1")

        # Assert
        assert user.display_name == "Sam"
        consumer._own_redis.set.assert_awaited_once()

    async def test_user_cache_miss_returns_none_when_no_user(self, consumer):
        # Arrange
        consumer._own_redis.get = AsyncMock(return_value=None)
        consumer._user_client.get_users_batch = AsyncMock(return_value=[])

        # Act
        user = await consumer._get_user_cached("u-x")

        # Assert
        assert user is None

    async def test_avatar_url_returns_none_for_empty_media_id(self, consumer):
        # Act
        url = await consumer._get_avatar_url_cached("")

        # Assert
        assert url is None

    async def test_avatar_url_cache_miss_uses_item_storage(self, consumer):
        # Arrange
        consumer._own_redis.get = AsyncMock(return_value=None)
        consumer._item_storage_client.get_download_url = AsyncMock(
            return_value=SimpleNamespace(
                download_url="https://cdn/x.png",
                expires_at=0,
                mime_type="image/png",
            ),
        )

        # Act
        url = await consumer._get_avatar_url_cached("m-1")

        # Assert
        assert url == "https://cdn/x.png"
        consumer._own_redis.set.assert_awaited_once()


# ---------------------------------------------------------------------------
# _send_and_handle
# ---------------------------------------------------------------------------
class TestSendAndHandle:
    async def test_success_marks_token_success(self, consumer):
        # Arrange
        sender = MagicMock()
        repo = MagicMock()
        repo.mark_success = AsyncMock()
        repo.deactivate_token = AsyncMock()
        repo.increment_failure = AsyncMock()
        token_id = uuid.uuid4()
        payload = SimpleNamespace()

        # Act
        with patch(
            "app.services.event_consumer.send_with_retry",
            new=AsyncMock(return_value=PushResult(success=True)),
        ):
            await consumer._send_and_handle(sender, payload, token_id, repo)

        # Assert
        repo.mark_success.assert_awaited_once_with(token_id)
        repo.deactivate_token.assert_not_awaited()

    async def test_permanent_token_deactivates(self, consumer):
        # Arrange
        sender = MagicMock()
        repo = MagicMock()
        repo.mark_success = AsyncMock()
        repo.deactivate_token = AsyncMock()
        repo.increment_failure = AsyncMock()
        token_id = uuid.uuid4()

        # Act
        with patch(
            "app.services.event_consumer.send_with_retry",
            new=AsyncMock(
                return_value=PushResult(
                    success=False,
                    error_type=PushErrorType.PERMANENT_TOKEN,
                    error_code="UNREGISTERED",
                ),
            ),
        ):
            await consumer._send_and_handle(sender, SimpleNamespace(), token_id, repo)

        # Assert
        repo.deactivate_token.assert_awaited_once_with(token_id)
        repo.increment_failure.assert_not_awaited()

    async def test_transient_increments_failure(self, consumer):
        # Arrange
        sender = MagicMock()
        repo = MagicMock()
        repo.mark_success = AsyncMock()
        repo.deactivate_token = AsyncMock()
        repo.increment_failure = AsyncMock()
        token_id = uuid.uuid4()

        # Act
        with patch(
            "app.services.event_consumer.send_with_retry",
            new=AsyncMock(
                return_value=PushResult(
                    success=False,
                    error_type=PushErrorType.TRANSIENT,
                    error_code="net",
                ),
            ),
        ):
            await consumer._send_and_handle(sender, SimpleNamespace(), token_id, repo)

        # Assert
        repo.increment_failure.assert_awaited_once()
        repo.deactivate_token.assert_not_awaited()


# ---------------------------------------------------------------------------
# _send_push_to_users
# ---------------------------------------------------------------------------
class TestSendPushToUsers:
    async def test_no_active_tokens_returns_early(self, consumer):
        # Arrange
        repo_instance = MagicMock()
        repo_instance.get_active_tokens_for_users = AsyncMock(return_value=[])

        session_ctx = MagicMock()
        session_ctx.__aenter__ = AsyncMock(return_value=MagicMock())
        session_ctx.__aexit__ = AsyncMock(return_value=False)

        # Act
        with (
            patch(
                "app.services.event_consumer.get_session",
                return_value=session_ctx,
            ),
            patch(
                "app.services.event_consumer.DevicePushTokenRepository",
                return_value=repo_instance,
            ),
            patch.object(consumer, "_send_and_handle", new=AsyncMock()) as send,
        ):
            await consumer._send_push_to_users(
                recipient_user_ids=[str(uuid.uuid4())],
                title="t",
                body="b",
                data={},
                thread_id=None,
                avatar_url=None,
                event_type="new_message",
                conversation_id="c-1",
            )

        # Assert
        send.assert_not_awaited()

    async def test_dispatches_per_token_with_platform_specific_sender(self, consumer):
        # Arrange
        android_token = SimpleNamespace(
            id=uuid.uuid4(),
            push_token="t-a",
            platform="android",
        )
        ios_token = SimpleNamespace(
            id=uuid.uuid4(),
            push_token="t-i",
            platform="ios",
        )
        repo_instance = MagicMock()
        repo_instance.get_active_tokens_for_users = AsyncMock(
            return_value=[android_token, ios_token],
        )

        session_ctx = MagicMock()
        session_ctx.__aenter__ = AsyncMock(return_value=MagicMock())
        session_ctx.__aexit__ = AsyncMock(return_value=False)

        user_id = str(uuid.uuid4())

        # Act
        with (
            patch(
                "app.services.event_consumer.get_session",
                return_value=session_ctx,
            ),
            patch(
                "app.services.event_consumer.DevicePushTokenRepository",
                return_value=repo_instance,
            ),
            patch.object(consumer, "_send_and_handle", new=AsyncMock()) as send,
        ):
            await consumer._send_push_to_users(
                recipient_user_ids=[user_id],
                title="t",
                body="b",
                data={"event_type": "new_message"},
                thread_id="c-1",
                avatar_url=None,
                event_type="new_message",
                conversation_id="c-1",
            )

        # Assert: оба токена пошли в _send_and_handle с правильными сендерами.
        assert send.await_count == 2
        senders_used = [call.args[0] for call in send.await_args_list]
        assert consumer._fcm_sender in senders_used
        assert consumer._apns_sender in senders_used
        # Проверяем, что платформа в payload действительно распозналась.
        payloads = [call.args[1] for call in send.await_args_list]
        platforms = {p.platform for p in payloads}
        assert platforms == {PushPlatform.ANDROID, PushPlatform.IOS}
