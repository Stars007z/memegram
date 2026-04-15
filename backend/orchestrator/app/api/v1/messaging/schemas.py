import base64
from typing import Optional, List
from pydantic import BaseModel, Field, field_serializer


# ── Helpers ───────────────────────────────────────────────────────────

def b64_to_bytes(s: str) -> bytes:
    return base64.b64decode(s)


# ── Key Packages ──────────────────────────────────────────────────────

class UploadKeyPackagesRequestSchema(BaseModel):
    key_packages: List[str] = Field(..., min_length=1, description="base64-encoded KeyPackages")


class UploadKeyPackagesResponseSchema(BaseModel):
    uploaded_count: int


class KeyPackageResponseSchema(BaseModel):
    key_package_data: bytes
    key_package_ref: bytes

    @field_serializer('key_package_data', 'key_package_ref')
    def _b64(self, v: bytes) -> str: return base64.b64encode(v).decode()


class UserDeviceKeyPackageSchema(BaseModel):
    device_id: str
    key_package_data: bytes
    key_package_ref: bytes

    @field_serializer('key_package_data', 'key_package_ref')
    def _b64(self, v: bytes) -> str:
        return base64.b64encode(v).decode()


class GetKeyPackagesForUserResponseSchema(BaseModel):
    key_packages: List[UserDeviceKeyPackageSchema]


class KeyPackagesCountResponseSchema(BaseModel):
    available_count: int


# ── Conversations ─────────────────────────────────────────────────────

class DeviceWelcomeSchema(BaseModel):
    device_id: str = Field(..., min_length=1)
    welcome_data: str = Field(..., min_length=1, description="base64")


class CreateDirectConversationRequestSchema(BaseModel):
    recipient_user_id: str = Field(..., min_length=1)
    welcome_messages: List[DeviceWelcomeSchema] = Field(default_factory=list)


class MemberWelcomesSchema(BaseModel):
    user_id: str = Field(..., min_length=1)
    welcomes: List[DeviceWelcomeSchema] = Field(default_factory=list)


class CreateGroupConversationRequestSchema(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)
    members: List[MemberWelcomesSchema] = Field(..., min_length=1)


class ConversationMemberSchema(BaseModel):
    user_id: str
    role: str
    joined_at: int


class MlsGroupInfoSchema(BaseModel):
    current_epoch: int
    cipher_suite: int


class ConversationResponseSchema(BaseModel):
    id: str
    type: str
    name: str
    members: List[ConversationMemberSchema]
    mls_group: Optional[MlsGroupInfoSchema] = None
    created_at: int
    avatar_media_id: str = ""


class ConversationSummarySchema(BaseModel):
    id: str
    type: str
    name: str
    last_message_type: str
    unread_count: int
    last_activity_at: int
    avatar_media_id: str = ""


class GetConversationsResponseSchema(BaseModel):
    items: List[ConversationSummarySchema]
    next_cursor: str


class LeaveConversationRequestSchema(BaseModel):
    commit_data: str = Field(default="", description="Deprecated – ignored by server. Remaining members create the Remove Commit.")


class LeaveConversationResponseSchema(BaseModel):
    success: bool


class KickMemberResponseSchema(BaseModel):
    success: bool


class UpdateMemberRoleRequestSchema(BaseModel):
    new_role: str = Field(..., pattern=r"^(admin|member)$", description="'admin' or 'member'")


class UpdateGroupAvatarRequestSchema(BaseModel):
    avatar_media_id: str = Field(default="", description="item-storage-service item_id; empty to remove")


class UpdateGroupNameRequestSchema(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)


class UpdateMemberRoleResponseSchema(BaseModel):
    success: bool


# ── Messages ──────────────────────────────────────────────────────────

class SendMessageRequestSchema(BaseModel):
    mls_ciphertext: str = Field(..., min_length=1, description="base64")
    type: str = Field(..., min_length=1)
    media_id: Optional[str] = None
    reply_to_message_id: Optional[str] = None
    client_message_id: str = Field(..., min_length=1)


class SendMessageResponseSchema(BaseModel):
    message_id: str
    created_at: int


class MessageEntrySchema(BaseModel):
    id: str
    sender_user_id: str
    sender_device_id: str
    type: str
    mls_ciphertext: bytes
    media_id: str
    reply_to_message_id: str
    mls_epoch: int
    created_at: int
    edited_at: int
    deleted_at: int

    @field_serializer('mls_ciphertext')
    def _b64(self, v: bytes) -> str: return base64.b64encode(v).decode()


class GetMessagesResponseSchema(BaseModel):
    messages: List[MessageEntrySchema]
    has_more: bool


class EditMessageRequestSchema(BaseModel):
    new_mls_ciphertext: str = Field(..., min_length=1, description="base64")


class DeleteMessageRequestSchema(BaseModel):
    delete_for_everyone: bool = False


class MarkAsReadRequestSchema(BaseModel):
    last_read_message_id: str = Field(..., min_length=1)


class MarkAsReadResponseSchema(BaseModel):
    unread_count: int


class DeleteMessageResponseSchema(BaseModel):
    success: bool


# ── MLS Group Management ─────────────────────────────────────────────

class CommitGroupChangeRequestSchema(BaseModel):
    commit_data: str = Field(..., min_length=1, description="base64")
    new_epoch: int
    welcome_messages: List[DeviceWelcomeSchema] = Field(default_factory=list)
    ratchet_tree: Optional[str] = Field(None, description="base64")
    removed_device_ids: List[str] = Field(default_factory=list)
    added_user_ids: Optional[List[str]] = Field(default=None)


class CommitGroupChangeResponseSchema(BaseModel):
    new_epoch: int
    committed_at: int


class WelcomeEntrySchema(BaseModel):
    id: str
    conversation_id: str
    welcome_data: bytes
    created_at: int

    @field_serializer('welcome_data')
    def _b64(self, v: bytes) -> str: return base64.b64encode(v).decode()


class GetPendingWelcomesResponseSchema(BaseModel):
    items: List[WelcomeEntrySchema]


class AckWelcomeResponseSchema(BaseModel):
    success: bool


class CommitEntrySchema(BaseModel):
    epoch: int
    commit_data: bytes
    created_at: int

    @field_serializer('commit_data')
    def _b64(self, v: bytes) -> str: return base64.b64encode(v).decode()


class GetPendingCommitsResponseSchema(BaseModel):
    commits: List[CommitEntrySchema]


# ── Media ─────────────────────────────────────────────────────────────

class InitiateMediaUploadRequestSchema(BaseModel):
    conversation_id: str = Field(..., min_length=1)
    mime_type: str = Field(..., min_length=1)
    encrypted_size: int = Field(..., gt=0)
    encryption_metadata: str = Field(..., min_length=1, description="base64")


class InitiateMediaUploadResponseSchema(BaseModel):
    media_id: str
    upload_url: str
    expires_in: int


class ConfirmMediaUploadResponseSchema(BaseModel):
    success: bool


class GetMediaDownloadUrlResponseSchema(BaseModel):
    download_url: str
    expires_in: int
    encryption_metadata: bytes

    @field_serializer('encryption_metadata')
    def _b64(self, v: bytes) -> str: return base64.b64encode(v).decode()


# ── Presence ──────────────────────────────────────────────────────────

class SetTypingRequestSchema(BaseModel):
    conversation_id: str = Field(..., min_length=1)
    is_typing: bool


class SuccessResponseSchema(BaseModel):
    success: bool


# ── Health ────────────────────────────────────────────────────────────

class MessagingHealthResponseSchema(BaseModel):
    status: str
    db_status: str
    redis_status: str
    media_service_status: str
    version: str


class MediaHealthResponseSchema(BaseModel):
    status: str
    db_status: str
    s3_status: str
    version: str
