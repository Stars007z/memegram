import base64
import uuid
from datetime import datetime
from typing import Optional

import redis.asyncio as aioredis
from sqlalchemy import text

from app.infrastructure.contacts_client import IContactsClient
from app.logging_config import get_logger
from app.repositories.conversation_repo import ConversationRepository
from app.repositories.member_repo import MemberRepository
from app.repositories.message_repo import MessageRepository
from app.repositories.mls_commit_repo import MlsCommitRepository
from app.repositories.mls_group_repo import MlsGroupRepository
from app.repositories.mls_welcome_repo import MlsWelcomeRepository
from app.services.interfaces.conversation_service import (
    ConversationListResult,
    ConversationResult,
    ConversationSummaryResult,
    IConversationService,
    MemberResult,
    MlsGroupResult,
)
from app.services.interfaces.stream_service import IStreamService

logger = get_logger(__name__)


class ConversationServiceImpl(IConversationService):

    def __init__(
        self,
        conversation_repo: ConversationRepository,
        member_repo: MemberRepository,
        mls_group_repo: MlsGroupRepository,
        mls_welcome_repo: MlsWelcomeRepository,
        commit_repo: MlsCommitRepository,
        message_repo: MessageRepository,
        contacts_client: IContactsClient,
        redis: aioredis.Redis,
        stream_service: IStreamService,
    ) -> None:
        self._conversations = conversation_repo
        self._members = member_repo
        self._mls_groups = mls_group_repo
        self._welcomes = mls_welcome_repo
        self._commits = commit_repo
        self._messages = message_repo
        self._contacts = contacts_client
        self._redis = redis
        self._stream = stream_service

    async def create_direct(
        self,
        initiator_user_id: uuid.UUID,
        initiator_device_id: uuid.UUID,
        recipient_user_id: uuid.UUID,
        welcome_messages: list[tuple[uuid.UUID, bytes]],
    ) -> ConversationResult:
        await self._check_blocks_both_ways(initiator_user_id, recipient_user_id)

        existing = await self._conversations.find_direct_between(
            initiator_user_id,
            recipient_user_id,
        )
        if existing:

            existing_members = await self._members.get_active_members(existing.id)
            existing_mls = await self._mls_groups.get_by_conversation_id(existing.id)
            logger.info(
                "conversation.direct.create_idempotent_hit",
                conversation_id=str(existing.id),
                initiator_user_id=str(initiator_user_id),
                recipient_user_id=str(recipient_user_id),
            )
            return self._build_conversation_result(
                existing,
                existing_members,
                epoch=existing_mls.current_epoch if existing_mls else 1,
                cipher_suite=existing_mls.cipher_suite if existing_mls else 1,
            )

        conv = await self._conversations.create(
            {
                "type": "direct",
                "created_by_user_id": initiator_user_id,
            }
        )

        initiator_member = await self._members.create(
            {
                "conversation_id": conv.id,
                "user_id": initiator_user_id,
                "role": "owner",
            }
        )
        recipient_member = await self._members.create(
            {
                "conversation_id": conv.id,
                "user_id": recipient_user_id,
                "role": "member",
            }
        )

        mls_group_id = uuid.uuid4().bytes
        await self._mls_groups.create(
            {
                "id": conv.id,
                "mls_group_id": mls_group_id,
                "current_epoch": 1,
                "cipher_suite": 1,
            }
        )

        for device_id, welcome_data in welcome_messages:
            await self._welcomes.create(
                {
                    "recipient_device_id": device_id,
                    "conversation_id": conv.id,
                    "welcome_data": welcome_data,
                }
            )

        logger.info(
            "conversation.direct.created",
            conversation_id=str(conv.id),
            initiator_user_id=str(initiator_user_id),
            recipient_user_id=str(recipient_user_id),
        )

        return self._build_conversation_result(
            conv,
            [initiator_member, recipient_member],
            epoch=1,
            cipher_suite=1,
        )

    async def create_group(
        self,
        creator_user_id: uuid.UUID,
        creator_device_id: uuid.UUID,
        name: str,
        members: list[tuple[uuid.UUID, list[tuple[uuid.UUID, bytes]]]],
    ) -> ConversationResult:
        for member_user_id, _ in members:
            await self._check_blocks_both_ways(creator_user_id, member_user_id)

        conv = await self._conversations.create(
            {
                "type": "group",
                "name": name,
                "created_by_user_id": creator_user_id,
            }
        )

        all_members = []

        creator_member = await self._members.create(
            {
                "conversation_id": conv.id,
                "user_id": creator_user_id,
                "role": "owner",
            }
        )
        all_members.append(creator_member)

        for member_user_id, welcomes in members:
            member = await self._members.create(
                {
                    "conversation_id": conv.id,
                    "user_id": member_user_id,
                    "role": "member",
                }
            )
            all_members.append(member)

            for device_id, welcome_data in welcomes:
                await self._welcomes.create(
                    {
                        "recipient_device_id": device_id,
                        "conversation_id": conv.id,
                        "welcome_data": welcome_data,
                    }
                )

        mls_group_id = uuid.uuid4().bytes
        await self._mls_groups.create(
            {
                "id": conv.id,
                "mls_group_id": mls_group_id,
                "current_epoch": 0,
                "cipher_suite": 1,
            }
        )

        logger.info(
            "conversation.group.created",
            conversation_id=str(conv.id),
            creator_user_id=str(creator_user_id),
            member_count=len(all_members),
        )

        return self._build_conversation_result(
            conv,
            all_members,
            epoch=0,
            cipher_suite=1,
        )

    async def get_conversations(
        self,
        user_id: uuid.UUID,
        limit: int,
        cursor: Optional[str],
    ) -> ConversationListResult:
        cursor_time, cursor_id = self._decode_cursor(cursor)

        conversations = await self._conversations.get_user_conversations(
            user_id,
            limit + 1,
            cursor_time,
            cursor_id,
        )

        has_next = len(conversations) > limit
        if has_next:
            conversations = conversations[:limit]

        items: list[ConversationSummaryResult] = []
        for conv in conversations:
            last_msg = await self._messages.get_last_message(conv.id)
            unread = await self._get_unread_count(user_id, conv.id)
            items.append(
                ConversationSummaryResult(
                    id=conv.id,
                    type=conv.type,
                    name=conv.name,
                    last_message_type=last_msg.type if last_msg else None,
                    unread_count=unread,
                    last_activity_at=conv.last_activity_at.timestamp(),
                    avatar_media_id=conv.avatar_media_id,
                )
            )

        next_cursor = None
        if has_next and conversations:
            last = conversations[-1]
            next_cursor = self._encode_cursor(last.last_activity_at, last.id)

        return ConversationListResult(items=items, next_cursor=next_cursor)

    async def get_conversation(
        self,
        user_id: uuid.UUID,
        conversation_id: uuid.UUID,
    ) -> ConversationResult:
        if not await self._members.is_member(conversation_id, user_id):
            raise ValueError("NOT_FOUND: Not a member of this conversation")

        conv = await self._conversations.get_by_id(conversation_id)
        if not conv:
            raise ValueError("NOT_FOUND: Conversation not found")

        members = await self._members.get_active_members(conversation_id)
        mls_group = await self._mls_groups.get_by_conversation_id(conversation_id)

        return self._build_conversation_result(
            conv,
            members,
            epoch=mls_group.current_epoch if mls_group else 0,
            cipher_suite=mls_group.cipher_suite if mls_group else 1,
        )

    async def leave_conversation(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        conversation_id: uuid.UUID,
        commit_data: bytes,
    ) -> bool:
        member = await self._members.get_active_member(conversation_id, user_id)
        if not member:
            raise ValueError("NOT_FOUND: Not a member of this conversation")

        await self._members.update(member, {"left_at": datetime.utcnow()})

        logger.info(
            "conversation.member.left",
            conversation_id=str(conversation_id),
            user_id=str(user_id),
        )

        await self._stream.publish_event(
            conversation_id,
            {
                "event_type": "member_left",
                "user_id": str(user_id),
            },
        )

        return True

    async def kick_member(
        self,
        caller_user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        target_user_id: uuid.UUID,
    ) -> bool:

        caller = await self._members.get_active_member(conversation_id, caller_user_id)
        if not caller:
            raise ValueError("NOT_FOUND: Not a member of this conversation")
        if caller.role not in ("owner", "admin"):
            raise ValueError("PERMISSION_DENIED: Only admins can kick members")

        if caller_user_id == target_user_id:
            raise ValueError("INVALID_ARGUMENT: Cannot kick yourself — use leave instead")

        target = await self._members.get_active_member(conversation_id, target_user_id)
        if not target:
            raise ValueError("NOT_FOUND: Target user is not an active member")

        if target.role == "owner":
            raise ValueError("PERMISSION_DENIED: Cannot kick the group owner")

        if target.role == "admin" and caller.role != "owner":
            raise ValueError("PERMISSION_DENIED: Only the owner can kick admins")

        await self._members.update(target, {"left_at": datetime.utcnow()})

        logger.info(
            "conversation.member.kicked",
            conversation_id=str(conversation_id),
            target_user_id=str(target_user_id),
            kicked_by=str(caller_user_id),
        )

        await self._stream.publish_event(
            conversation_id,
            {
                "event_type": "member_kicked",
                "user_id": str(target_user_id),
                "kicked_by": str(caller_user_id),
            },
        )

        return True

    async def update_member_role(
        self,
        caller_user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        target_user_id: uuid.UUID,
        new_role: str,
    ) -> bool:
        if new_role not in ("admin", "member"):
            raise ValueError("INVALID_ARGUMENT: Role must be 'admin' or 'member'")

        caller = await self._members.get_active_member(conversation_id, caller_user_id)
        if not caller:
            raise ValueError("NOT_FOUND: Not a member of this conversation")
        if caller.role not in ("owner", "admin"):
            raise ValueError("PERMISSION_DENIED: Only admins can change roles")

        if caller_user_id == target_user_id:
            raise ValueError("INVALID_ARGUMENT: Cannot change your own role")

        target = await self._members.get_active_member(conversation_id, target_user_id)
        if not target:
            raise ValueError("NOT_FOUND: Target user is not an active member")

        if target.role == "owner":
            raise ValueError("PERMISSION_DENIED: Cannot change the owner's role")

        if target.role == "admin" and new_role == "member" and caller.role != "owner":
            raise ValueError("PERMISSION_DENIED: Only the owner can demote admins")

        if target.role == new_role:
            return True

        await self._members.update_role(conversation_id, target_user_id, new_role)

        logger.info(
            "conversation.role.changed",
            conversation_id=str(conversation_id),
            target_user_id=str(target_user_id),
            new_role=new_role,
            changed_by=str(caller_user_id),
        )

        await self._stream.publish_event(
            conversation_id,
            {
                "event_type": "role_changed",
                "user_id": str(target_user_id),
                "new_role": new_role,
            },
        )

        return True

    async def update_group_avatar(
        self,
        caller_user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        avatar_media_id: Optional[uuid.UUID],
    ) -> bool:
        conv = await self._conversations.get_by_id(conversation_id)
        if not conv:
            raise ValueError("NOT_FOUND: Conversation not found")

        if conv.type != "group":
            raise ValueError("FAILED_PRECONDITION: Avatars are only supported for group conversations")

        caller = await self._members.get_active_member(conversation_id, caller_user_id)
        if not caller:
            raise ValueError("NOT_FOUND: Not a member of this conversation")
        if caller.role not in ("owner", "admin"):
            raise ValueError("PERMISSION_DENIED: Only owner or admin can change the group avatar")

        await self._conversations.update_avatar(conversation_id, avatar_media_id)

        await self._stream.publish_event(
            conversation_id,
            {
                "event_type": "group_avatar_changed",
                "avatar_media_id": str(avatar_media_id) if avatar_media_id else "",
                "changed_by": str(caller_user_id),
            },
        )

        return True

    async def update_group_name(
        self,
        caller_user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        name: str,
    ) -> bool:
        if not name or not name.strip():
            raise ValueError("INVALID_ARGUMENT: Name must not be empty")

        conv = await self._conversations.get_by_id(conversation_id)
        if not conv:
            raise ValueError("NOT_FOUND: Conversation not found")

        if conv.type != "group":
            raise ValueError("FAILED_PRECONDITION: Name can only be changed for group conversations")

        caller = await self._members.get_active_member(conversation_id, caller_user_id)
        if not caller:
            raise ValueError("NOT_FOUND: Not a member of this conversation")
        if caller.role not in ("owner", "admin"):
            raise ValueError("PERMISSION_DENIED: Only owner or admin can change the group name")

        await self._conversations.update_name(conversation_id, name.strip())

        await self._stream.publish_event(
            conversation_id,
            {
                "event_type": "group_name_changed",
                "name": name.strip(),
                "changed_by": str(caller_user_id),
            },
        )

        return True

    async def delete_conversation(
        self,
        caller_user_id: uuid.UUID,
        conversation_id: uuid.UUID,
    ) -> bool:
        conv = await self._conversations.get_by_id(conversation_id)
        if not conv:
            raise ValueError("NOT_FOUND: Conversation not found")

        caller = await self._members.get_active_member(conversation_id, caller_user_id)
        if not caller:
            raise ValueError("NOT_FOUND: Not a member of this conversation")

        if conv.type == "group" and caller.role != "owner":
            raise ValueError(
                "PERMISSION_DENIED: Only the group owner can delete the group; " "use leave instead",
            )

        await self._stream.publish_event(
            conversation_id,
            {
                "event_type": "conversation_deleted",
                "deleted_by": str(caller_user_id),
            },
        )

        session = self._conversations.session
        cid = conversation_id

        await session.execute(
            text("DELETE FROM mls_welcome_messages WHERE conversation_id = :cid"),
            {"cid": cid},
        )
        await session.execute(
            text("DELETE FROM mls_commit_messages WHERE conversation_id = :cid"),
            {"cid": cid},
        )
        await session.execute(
            text("DELETE FROM mls_groups WHERE id = :cid"),
            {"cid": cid},
        )
        await session.execute(
            text("DELETE FROM media_attachments WHERE conversation_id = :cid"),
            {"cid": cid},
        )
        await session.execute(
            text("DELETE FROM messages WHERE conversation_id = :cid"),
            {"cid": cid},
        )
        await session.execute(
            text("DELETE FROM conversations WHERE id = :cid"),
            {"cid": cid},
        )
        await session.flush()

        logger.info(
            "conversation.deleted",
            conversation_id=str(conversation_id),
            deleted_by=str(caller_user_id),
            type=conv.type,
        )

        return True

    async def _check_blocks_both_ways(
        self,
        user_a: uuid.UUID,
        user_b: uuid.UUID,
    ) -> None:
        if await self._contacts.is_blocked(user_a, user_b):
            raise ValueError("NOT_FOUND: User not found")
        if await self._contacts.is_blocked(user_b, user_a):
            raise ValueError("NOT_FOUND: User not found")

    async def _get_unread_count(
        self,
        user_id: uuid.UUID,
        conversation_id: uuid.UUID,
    ) -> int:
        key = f"unread:{user_id}:{conversation_id}"
        cached = await self._redis.get(key)
        if cached is not None:
            return int(cached)
        return 0

    @staticmethod
    def _encode_cursor(ts: datetime, id_: uuid.UUID) -> str:
        raw = f"{ts.isoformat()}|{id_}"
        return base64.urlsafe_b64encode(raw.encode()).decode()

    @staticmethod
    def _decode_cursor(cursor: Optional[str]) -> tuple[Optional[datetime], Optional[uuid.UUID]]:
        if not cursor:
            return None, None
        try:
            raw = base64.urlsafe_b64decode(cursor.encode()).decode()
            ts_str, id_str = raw.split("|", 1)
            return datetime.fromisoformat(ts_str), uuid.UUID(id_str)
        except Exception:
            return None, None

    @staticmethod
    def _build_conversation_result(conv, members, epoch: int, cipher_suite: int) -> ConversationResult:
        return ConversationResult(
            id=conv.id,
            type=conv.type,
            name=conv.name,
            members=[
                MemberResult(
                    user_id=m.user_id,
                    role=m.role,
                    joined_at=m.joined_at.timestamp(),
                )
                for m in members
            ],
            mls_group=MlsGroupResult(current_epoch=epoch, cipher_suite=cipher_suite),
            created_at=conv.created_at.timestamp(),
            avatar_media_id=conv.avatar_media_id,
        )
