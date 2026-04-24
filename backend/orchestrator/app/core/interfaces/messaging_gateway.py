from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import AsyncIterator, Optional


@dataclass
class DeviceWelcome:
    device_id: str
    welcome_data: bytes


@dataclass
class MemberWithWelcomes:
    user_id: str
    welcomes: list[DeviceWelcome] = field(default_factory=list)


@dataclass
class ConversationMemberResult:
    user_id: str
    role: str
    joined_at: int
    last_read_message_id: str = ""


@dataclass
class MlsGroupInfoResult:
    current_epoch: int
    cipher_suite: int


@dataclass
class ConversationResult:
    id: str
    type: str
    name: str
    members: list[ConversationMemberResult] = field(default_factory=list)
    mls_group: Optional[MlsGroupInfoResult] = None
    created_at: int = 0
    avatar_media_id: str = ""


@dataclass
class ConversationSummaryResult:
    id: str
    type: str
    name: str
    last_message_type: str
    unread_count: int
    last_activity_at: int
    avatar_media_id: str = ""


@dataclass
class GetConversationsResult:
    items: list[ConversationSummaryResult] = field(default_factory=list)
    next_cursor: str = ""


@dataclass
class MessageEntryResult:
    id: str
    sender_user_id: str
    sender_device_id: str
    type: str
    mls_ciphertext: bytes = b""
    media_id: str = ""
    reply_to_message_id: str = ""
    mls_epoch: int = 0
    created_at: int = 0
    edited_at: int = 0
    deleted_at: int = 0


@dataclass
class SendMessageResult:
    message_id: str
    created_at: int


@dataclass
class GetMessagesResult:
    messages: list[MessageEntryResult] = field(default_factory=list)
    has_more: bool = False


@dataclass
class KeyPackageResult:
    key_package_data: bytes
    key_package_ref: bytes


@dataclass
class UserDeviceKeyPackageResult:
    device_id: str
    key_package_data: bytes
    key_package_ref: bytes


@dataclass
class CommitGroupChangeResult:
    new_epoch: int
    committed_at: int


@dataclass
class WelcomeEntryResult:
    id: str
    conversation_id: str
    welcome_data: bytes
    created_at: int


@dataclass
class CommitEntryResult:
    epoch: int
    commit_data: bytes
    created_at: int


@dataclass
class InitiateMediaUploadResult:
    media_id: str
    upload_url: str
    expires_in: int


@dataclass
class GetMediaDownloadUrlResult:
    download_url: str
    expires_in: int
    encryption_metadata: bytes


@dataclass
class MessagingHealthResult:
    status: str
    db_status: str
    redis_status: str
    media_service_status: str
    version: str


@dataclass
class PurgeUserMembershipResult:
    groups_left: int = 0
    directs_purged: int = 0


class IMessagingGateway(ABC):

    @abstractmethod
    async def upload_key_packages(
        self,
        user_id: str,
        device_id: str,
        key_packages: list[bytes],
    ) -> int: ...

    @abstractmethod
    async def get_key_package(
        self,
        target_user_id: str,
        target_device_id: str,
    ) -> KeyPackageResult: ...

    @abstractmethod
    async def get_key_packages_count(
        self,
        user_id: str,
        device_id: str,
    ) -> int: ...

    @abstractmethod
    async def get_key_packages_for_user(
        self,
        target_user_id: str,
    ) -> list[UserDeviceKeyPackageResult]: ...

    @abstractmethod
    async def delete_key_packages_for_device(
        self,
        user_id: str,
        device_id: str,
    ) -> int: ...

    @abstractmethod
    async def create_direct_conversation(
        self,
        initiator_user_id: str,
        initiator_device_id: str,
        recipient_user_id: str,
        welcome_messages: list[DeviceWelcome],
    ) -> ConversationResult: ...

    @abstractmethod
    async def create_group_conversation(
        self,
        creator_user_id: str,
        creator_device_id: str,
        name: str,
        members: list[MemberWithWelcomes],
    ) -> ConversationResult: ...

    @abstractmethod
    async def get_conversations(
        self,
        user_id: str,
        limit: int,
        cursor: str,
    ) -> GetConversationsResult: ...

    @abstractmethod
    async def get_conversation(
        self,
        user_id: str,
        conversation_id: str,
    ) -> ConversationResult: ...

    @abstractmethod
    async def leave_conversation(
        self,
        user_id: str,
        device_id: str,
        conversation_id: str,
        commit_data: bytes,
    ) -> bool: ...

    @abstractmethod
    async def kick_member(
        self,
        user_id: str,
        conversation_id: str,
        target_user_id: str,
    ) -> bool: ...

    @abstractmethod
    async def update_member_role(
        self,
        user_id: str,
        conversation_id: str,
        target_user_id: str,
        new_role: str,
    ) -> bool: ...

    @abstractmethod
    async def update_group_avatar(
        self,
        user_id: str,
        conversation_id: str,
        avatar_media_id: str,
    ) -> bool: ...

    @abstractmethod
    async def update_group_name(
        self,
        user_id: str,
        conversation_id: str,
        name: str,
    ) -> bool: ...

    @abstractmethod
    async def delete_conversation(
        self,
        user_id: str,
        conversation_id: str,
    ) -> bool: ...

    @abstractmethod
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
    ) -> SendMessageResult: ...

    @abstractmethod
    async def get_messages(
        self,
        user_id: str,
        conversation_id: str,
        before_message_id: str,
        limit: int,
    ) -> GetMessagesResult: ...

    @abstractmethod
    async def edit_message(
        self,
        user_id: str,
        device_id: str,
        message_id: str,
        new_mls_ciphertext: bytes,
    ) -> MessageEntryResult: ...

    @abstractmethod
    async def delete_message(
        self,
        user_id: str,
        message_id: str,
        delete_for_everyone: bool,
    ) -> bool: ...

    @abstractmethod
    async def mark_as_read(
        self,
        user_id: str,
        device_id: str,
        conversation_id: str,
        last_read_message_id: str,
    ) -> int: ...

    @abstractmethod
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
    ) -> CommitGroupChangeResult: ...

    @abstractmethod
    async def get_pending_welcomes(self, device_id: str) -> list[WelcomeEntryResult]: ...

    @abstractmethod
    async def ack_welcome(self, device_id: str, welcome_id: str) -> bool: ...

    @abstractmethod
    async def get_pending_commits(
        self,
        device_id: str,
        conversation_id: str,
        since_epoch: int,
    ) -> list[CommitEntryResult]: ...

    @abstractmethod
    async def initiate_media_upload(
        self,
        user_id: str,
        conversation_id: str,
        mime_type: str,
        encrypted_size: int,
        encryption_metadata: bytes,
    ) -> InitiateMediaUploadResult: ...

    @abstractmethod
    async def confirm_media_upload(self, user_id: str, media_id: str) -> bool: ...

    @abstractmethod
    async def get_media_download_url(
        self,
        user_id: str,
        media_id: str,
    ) -> GetMediaDownloadUrlResult: ...

    @abstractmethod
    async def set_typing(
        self,
        user_id: str,
        device_id: str,
        conversation_id: str,
        is_typing: bool,
    ) -> bool: ...

    @abstractmethod
    async def set_online(self, user_id: str, device_id: str) -> bool: ...

    @abstractmethod
    def subscribe_to_conversations(
        self,
        user_id: str,
        device_id: str,
        conversation_ids: list[str],
    ) -> AsyncIterator[dict]: ...

    @abstractmethod
    async def notify_device_revoked(self, user_id: str, revoked_device_id: str) -> int: ...

    @abstractmethod
    async def purge_user_membership(self, user_id: str) -> PurgeUserMembershipResult:
        """Account-deletion fanout: detach `user_id` from every conversation.

        Groups: mark membership as left (no MLS commit). Directs: hard-delete
        the membership row (peer keeps history). Idempotent.
        """
        ...

    @abstractmethod
    async def health_check(self) -> MessagingHealthResult: ...
