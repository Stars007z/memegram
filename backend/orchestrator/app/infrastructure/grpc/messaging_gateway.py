import base64
import grpc
from typing import AsyncIterator

from app.config import Settings
from app.core.interfaces.messaging_gateway import (
    IMessagingGateway,
    DeviceWelcome,
    MemberWithWelcomes,
    ConversationMemberResult,
    MlsGroupInfoResult,
    ConversationResult,
    ConversationSummaryResult,
    GetConversationsResult,
    MessageEntryResult,
    SendMessageResult,
    GetMessagesResult,
    KeyPackageResult,
    UserDeviceKeyPackageResult,
    CommitGroupChangeResult,
    WelcomeEntryResult,
    CommitEntryResult,
    InitiateMediaUploadResult,
    GetMediaDownloadUrlResult,
    MessagingHealthResult,
)
from app.infrastructure.grpc.errors import grpc_error_to_exception
from app.infrastructure.grpc.client import GrpcChannelManager
from app.infrastructure.grpc.generated import messaging_pb2, messaging_pb2_grpc

_SERVICE = "Messaging service"


def _conversation_from_proto(r) -> ConversationResult:
    members = [
        ConversationMemberResult(
            user_id=m.user_id, role=m.role, joined_at=m.joined_at,
        )
        for m in r.members
    ]
    mls_group = None
    if r.HasField("mls_group"):
        mls_group = MlsGroupInfoResult(
            current_epoch=r.mls_group.current_epoch,
            cipher_suite=r.mls_group.cipher_suite,
        )
    return ConversationResult(
        id=r.id, type=r.type, name=r.name,
        members=members, mls_group=mls_group, created_at=r.created_at,
    )


def _message_from_proto(m) -> MessageEntryResult:
    return MessageEntryResult(
        id=m.id,
        sender_user_id=m.sender_user_id,
        sender_device_id=m.sender_device_id,
        type=m.type,
        mls_ciphertext=bytes(m.mls_ciphertext),
        media_id=m.media_id,
        reply_to_message_id=m.reply_to_message_id,
        mls_epoch=m.mls_epoch,
        created_at=m.created_at,
        edited_at=m.edited_at,
        deleted_at=m.deleted_at,
    )


def _device_welcomes_to_proto(welcomes: list[DeviceWelcome]):
    return [
        messaging_pb2.DeviceWelcome(
            device_id=w.device_id, welcome_data=w.welcome_data,
        )
        for w in welcomes
    ]


class GrpcMessagingGateway(IMessagingGateway):

    def __init__(self, channels: GrpcChannelManager, settings: Settings):
        self._channels = channels
        self._settings = settings

    def _stub(self) -> messaging_pb2_grpc.MessagingServiceStub:
        return messaging_pb2_grpc.MessagingServiceStub(self._channels.get("messaging"))

    @property
    def _timeout(self) -> float:
        return self._settings.MESSAGING_GRPC_TIMEOUT

    # ── Key Material ──────────────────────────────────────────────────

    async def upload_key_packages(
        self, user_id: str, device_id: str, key_packages: list[bytes],
    ) -> int:
        try:
            resp = await self._stub().UploadKeyPackages(
                messaging_pb2.UploadKeyPackagesRequest(
                    user_id=user_id, device_id=device_id, key_packages=key_packages,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.uploaded_count

    async def get_key_package(
        self, target_user_id: str, target_device_id: str,
    ) -> KeyPackageResult:
        try:
            resp = await self._stub().GetKeyPackage(
                messaging_pb2.GetKeyPackageRequest(
                    target_user_id=target_user_id,
                    target_device_id=target_device_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return KeyPackageResult(
            key_package_data=bytes(resp.key_package_data),
            key_package_ref=bytes(resp.key_package_ref),
        )

    async def get_key_packages_count(self, user_id: str, device_id: str) -> int:
        try:
            resp = await self._stub().GetKeyPackagesCount(
                messaging_pb2.GetKeyPackagesCountRequest(
                    user_id=user_id, device_id=device_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.available_count

    async def get_key_packages_for_user(
        self, target_user_id: str,
    ) -> list[UserDeviceKeyPackageResult]:
        try:
            resp = await self._stub().GetKeyPackagesForUser(
                messaging_pb2.GetKeyPackagesForUserRequest(
                    target_user_id=target_user_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return [
            UserDeviceKeyPackageResult(
                device_id=kp.device_id,
                key_package_data=bytes(kp.key_package_data),
                key_package_ref=bytes(kp.key_package_ref),
            )
            for kp in resp.key_packages
        ]

    # ── Conversations ─────────────────────────────────────────────────

    async def create_direct_conversation(
        self,
        initiator_user_id: str,
        initiator_device_id: str,
        recipient_user_id: str,
        welcome_messages: list[DeviceWelcome],
    ) -> ConversationResult:
        try:
            resp = await self._stub().CreateDirectConversation(
                messaging_pb2.CreateDirectConversationRequest(
                    initiator_user_id=initiator_user_id,
                    initiator_device_id=initiator_device_id,
                    recipient_user_id=recipient_user_id,
                    welcome_messages=_device_welcomes_to_proto(welcome_messages),
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return _conversation_from_proto(resp)

    async def create_group_conversation(
        self,
        creator_user_id: str,
        creator_device_id: str,
        name: str,
        members: list[MemberWithWelcomes],
    ) -> ConversationResult:
        pb_members = [
            messaging_pb2.CreateGroupConversationRequest.MemberWithWelcomes(
                user_id=m.user_id,
                welcomes=_device_welcomes_to_proto(m.welcomes),
            )
            for m in members
        ]
        try:
            resp = await self._stub().CreateGroupConversation(
                messaging_pb2.CreateGroupConversationRequest(
                    creator_user_id=creator_user_id,
                    creator_device_id=creator_device_id,
                    name=name,
                    members=pb_members,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return _conversation_from_proto(resp)

    async def get_conversations(
        self, user_id: str, limit: int, cursor: str,
    ) -> GetConversationsResult:
        try:
            resp = await self._stub().GetConversations(
                messaging_pb2.GetConversationsRequest(
                    user_id=user_id, limit=limit, cursor=cursor,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return GetConversationsResult(
            items=[
                ConversationSummaryResult(
                    id=i.id, type=i.type, name=i.name,
                    last_message_type=i.last_message_type,
                    unread_count=i.unread_count,
                    last_activity_at=i.last_activity_at,
                )
                for i in resp.items
            ],
            next_cursor=resp.next_cursor,
        )

    async def get_conversation(
        self, user_id: str, conversation_id: str,
    ) -> ConversationResult:
        try:
            resp = await self._stub().GetConversation(
                messaging_pb2.GetConversationRequest(
                    user_id=user_id, conversation_id=conversation_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return _conversation_from_proto(resp)

    async def leave_conversation(
        self, user_id: str, device_id: str, conversation_id: str, commit_data: bytes,
    ) -> bool:
        try:
            resp = await self._stub().LeaveConversation(
                messaging_pb2.LeaveConversationRequest(
                    user_id=user_id, device_id=device_id,
                    conversation_id=conversation_id, commit_data=commit_data,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.success

    # ── Messages ──────────────────────────────────────────────────────

    async def send_message(
        self,
        sender_user_id: str,
        sender_device_id: str,
        conversation_id: str,
        mls_ciphertext: bytes,
        type: str,
        media_id: str,
        reply_to_message_id: str,
        client_message_id: str,
    ) -> SendMessageResult:
        try:
            resp = await self._stub().SendMessage(
                messaging_pb2.SendMessageRequest(
                    sender_user_id=sender_user_id,
                    sender_device_id=sender_device_id,
                    conversation_id=conversation_id,
                    mls_ciphertext=mls_ciphertext,
                    type=type,
                    media_id=media_id,
                    reply_to_message_id=reply_to_message_id,
                    client_message_id=client_message_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return SendMessageResult(message_id=resp.message_id, created_at=resp.created_at)

    async def get_messages(
        self, user_id: str, conversation_id: str, before_message_id: str, limit: int,
    ) -> GetMessagesResult:
        try:
            resp = await self._stub().GetMessages(
                messaging_pb2.GetMessagesRequest(
                    user_id=user_id, conversation_id=conversation_id,
                    before_message_id=before_message_id, limit=limit,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return GetMessagesResult(
            messages=[_message_from_proto(m) for m in resp.messages],
            has_more=resp.has_more,
        )

    async def edit_message(
        self, user_id: str, device_id: str, message_id: str, new_mls_ciphertext: bytes,
    ) -> MessageEntryResult:
        try:
            resp = await self._stub().EditMessage(
                messaging_pb2.EditMessageRequest(
                    user_id=user_id, device_id=device_id,
                    message_id=message_id, new_mls_ciphertext=new_mls_ciphertext,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return _message_from_proto(resp.message)

    async def delete_message(
        self, user_id: str, message_id: str, delete_for_everyone: bool,
    ) -> bool:
        try:
            resp = await self._stub().DeleteMessage(
                messaging_pb2.DeleteMessageRequest(
                    user_id=user_id, message_id=message_id,
                    delete_for_everyone=delete_for_everyone,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.success

    async def mark_as_read(
        self, user_id: str, device_id: str, conversation_id: str, last_read_message_id: str,
    ) -> int:
        try:
            resp = await self._stub().MarkAsRead(
                messaging_pb2.MarkAsReadRequest(
                    user_id=user_id, device_id=device_id,
                    conversation_id=conversation_id,
                    last_read_message_id=last_read_message_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.unread_count

    # ── MLS Group Management ──────────────────────────────────────────

    async def commit_group_change(
        self,
        user_id: str,
        device_id: str,
        conversation_id: str,
        commit_data: bytes,
        new_epoch: int,
        welcome_messages: list[DeviceWelcome],
        ratchet_tree: bytes,
        removed_device_ids: list[str],
    ) -> CommitGroupChangeResult:
        try:
            resp = await self._stub().CommitGroupChange(
                messaging_pb2.CommitGroupChangeRequest(
                    user_id=user_id,
                    device_id=device_id,
                    conversation_id=conversation_id,
                    commit_data=commit_data,
                    new_epoch=new_epoch,
                    welcome_messages=_device_welcomes_to_proto(welcome_messages),
                    ratchet_tree=ratchet_tree,
                    removed_device_ids=removed_device_ids,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return CommitGroupChangeResult(
            new_epoch=resp.new_epoch, committed_at=resp.committed_at,
        )

    async def get_pending_welcomes(self, device_id: str) -> list[WelcomeEntryResult]:
        try:
            resp = await self._stub().GetPendingWelcomes(
                messaging_pb2.GetPendingWelcomesRequest(device_id=device_id),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return [
            WelcomeEntryResult(
                id=w.id, conversation_id=w.conversation_id,
                welcome_data=bytes(w.welcome_data), created_at=w.created_at,
            )
            for w in resp.items
        ]

    async def ack_welcome(self, device_id: str, welcome_id: str) -> bool:
        try:
            resp = await self._stub().AckWelcome(
                messaging_pb2.AckWelcomeRequest(
                    device_id=device_id, welcome_id=welcome_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.success

    async def get_pending_commits(
        self, device_id: str, conversation_id: str, since_epoch: int,
    ) -> list[CommitEntryResult]:
        try:
            resp = await self._stub().GetPendingCommits(
                messaging_pb2.GetPendingCommitsRequest(
                    device_id=device_id, conversation_id=conversation_id,
                    since_epoch=since_epoch,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return [
            CommitEntryResult(
                epoch=c.epoch, commit_data=bytes(c.commit_data), created_at=c.created_at,
            )
            for c in resp.commits
        ]

    # ── Media (through messaging service) ─────────────────────────────

    async def initiate_media_upload(
        self,
        user_id: str,
        conversation_id: str,
        mime_type: str,
        encrypted_size: int,
        encryption_metadata: bytes,
    ) -> InitiateMediaUploadResult:
        try:
            resp = await self._stub().InitiateMediaUpload(
                messaging_pb2.InitiateMediaUploadRequest(
                    user_id=user_id, conversation_id=conversation_id,
                    mime_type=mime_type, encrypted_size=encrypted_size,
                    encryption_metadata=encryption_metadata,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return InitiateMediaUploadResult(
            media_id=resp.media_id, upload_url=resp.upload_url, expires_in=resp.expires_in,
        )

    async def confirm_media_upload(self, user_id: str, media_id: str) -> bool:
        try:
            resp = await self._stub().ConfirmMediaUpload(
                messaging_pb2.ConfirmMediaUploadRequest(
                    user_id=user_id, media_id=media_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.success

    async def get_media_download_url(
        self, user_id: str, media_id: str,
    ) -> GetMediaDownloadUrlResult:
        try:
            resp = await self._stub().GetMediaDownloadUrl(
                messaging_pb2.GetMediaDownloadUrlRequest(
                    user_id=user_id, media_id=media_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return GetMediaDownloadUrlResult(
            download_url=resp.download_url,
            expires_in=resp.expires_in,
            encryption_metadata=bytes(resp.encryption_metadata),
        )

    # ── Presence ──────────────────────────────────────────────────────

    async def set_typing(
        self, user_id: str, device_id: str, conversation_id: str, is_typing: bool,
    ) -> bool:
        try:
            resp = await self._stub().SetTyping(
                messaging_pb2.SetTypingRequest(
                    user_id=user_id, device_id=device_id,
                    conversation_id=conversation_id, is_typing=is_typing,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.success

    async def set_online(self, user_id: str, device_id: str) -> bool:
        try:
            resp = await self._stub().SetOnline(
                messaging_pb2.SetOnlineRequest(
                    user_id=user_id, device_id=device_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.success

    # ── Streaming ─────────────────────────────────────────────────────

    async def subscribe_to_conversations(
        self, user_id: str, device_id: str, conversation_ids: list[str],
    ) -> AsyncIterator[dict]:
        stub = self._stub()
        stream = stub.SubscribeToConversation(
            messaging_pb2.SubscribeRequest(
                user_id=user_id, device_id=device_id,
                conversation_ids=conversation_ids,
            ),
        )
        async for event in stream:
            yield self._event_to_dict(event)

    @staticmethod
    def _event_to_dict(event) -> dict:
        result: dict = {"conversation_id": event.conversation_id}
        which = event.WhichOneof("event")
        if which == "new_message":
            m = event.new_message
            result["type"] = "new_message"
            result["data"] = {
                "id": m.id,
                "sender_user_id": m.sender_user_id,
                "sender_device_id": m.sender_device_id,
                "type": m.type,
                "mls_ciphertext": base64.b64encode(m.mls_ciphertext).decode(),
                "media_id": m.media_id,
                "reply_to_message_id": m.reply_to_message_id,
                "mls_epoch": m.mls_epoch,
                "created_at": m.created_at,
                "edited_at": m.edited_at,
                "deleted_at": m.deleted_at,
            }
        elif which == "message_edited":
            e = event.message_edited
            result["type"] = "message_edited"
            result["data"] = {
                "message_id": e.message_id,
                "new_mls_ciphertext": base64.b64encode(e.new_mls_ciphertext).decode(),
                "edited_at": e.edited_at,
            }
        elif which == "message_deleted":
            result["type"] = "message_deleted"
            result["data"] = {"message_id": event.message_deleted.message_id}
        elif which == "typing":
            t = event.typing
            result["type"] = "typing"
            result["data"] = {"user_id": t.user_id, "is_typing": t.is_typing}
        elif which == "member_joined":
            result["type"] = "member_joined"
            result["data"] = {"user_id": event.member_joined.user_id}
        elif which == "member_left":
            result["type"] = "member_left"
            result["data"] = {"user_id": event.member_left.user_id}
        elif which == "epoch_changed":
            result["type"] = "epoch_changed"
            result["data"] = {"new_epoch": event.epoch_changed.new_epoch}
        elif which == "device_revoked":
            dr = event.device_revoked
            result["type"] = "device_revoked"
            result["data"] = {
                "user_id": dr.user_id,
                "revoked_device_id": dr.revoked_device_id,
                "conversation_ids": list(dr.conversation_ids),
            }
        return result

    # ── Device revocation notification ────────────────────────────────

    async def notify_device_revoked(self, user_id: str, revoked_device_id: str) -> int:
        try:
            resp = await self._stub().NotifyDeviceRevoked(
                messaging_pb2.NotifyDeviceRevokedRequest(
                    user_id=user_id,
                    revoked_device_id=revoked_device_id,
                ),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return resp.notified_conversations_count

    # ── Health ────────────────────────────────────────────────────────

    async def health_check(self) -> MessagingHealthResult:
        try:
            resp = await self._stub().HealthCheck(
                messaging_pb2.HealthCheckRequest(),
                timeout=self._timeout,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return MessagingHealthResult(
            status=resp.status,
            db_status=resp.db_status,
            redis_status=resp.redis_status,
            media_service_status=resp.media_service_status,
            version=resp.version,
        )
