from pydantic import BaseModel
from typing import Optional, List


class UserProfileResponseSchema(BaseModel):
    id: str
    username: str
    avatar_media_id: Optional[str] = None
    profile_background_media_id: Optional[str] = None
    user_public_key: str
    bio: str
    last_active: int
    is_deleted: bool


class UpdateUserRequestSchema(BaseModel):
    bio: Optional[str] = None
    username: Optional[str] = None
    avatar_media_id: Optional[str] = None
    profile_background_media_id: Optional[str] = None


class DeleteUserResponseSchema(BaseModel):
    success: bool


class UserSettingsResponseSchema(BaseModel):
    id: str
    user_id: str
    theme: str
    language: str
    is_translator_active: bool
    animations_enabled: bool
    account_auto_delete_after_days: int
    profile_visible_to: str
    last_active_visible_to: str
    chat_background_media_id: Optional[str] = None
    top_bar_color: str
    ringtone_media_id: Optional[str] = None
    ringtone_vibration_strength: int
    notification_sound_media_id: Optional[str] = None
    notification_vibration_strength: int


class UpdateUserSettingsRequestSchema(BaseModel):
    theme: Optional[str] = None
    language: Optional[str] = None
    is_translator_active: Optional[bool] = None
    animations_enabled: Optional[bool] = None
    account_auto_delete_after_days: Optional[int] = None
    profile_visible_to: Optional[str] = None
    last_active_visible_to: Optional[str] = None
    chat_background_media_id: Optional[str] = None
    top_bar_color: Optional[str] = None
    ringtone_media_id: Optional[str] = None
    ringtone_vibration_strength: Optional[int] = None
    notification_sound: Optional[str] = None
    notification_vibration_strength: Optional[int] = None


# ── Settings sync (smart media caching) ──────────────────────────────


class SyncSettingsRequestSchema(BaseModel):
    """Client sends its locally cached media IDs.
    Server compares them with current values and returns
    download URLs only for items that changed."""
    chat_background_media_id: Optional[str] = None
    ringtone_media_id: Optional[str] = None
    notification_sound_media_id: Optional[str] = None


class MediaDownloadInfoSchema(BaseModel):
    field: str
    item_id: str
    download_url: str
    expires_at: int
    mime_type: str


class SyncSettingsResponseSchema(BaseModel):
    settings: UserSettingsResponseSchema
    media_updates: List[MediaDownloadInfoSchema] = []


class UserHealthResponseSchema(BaseModel):
    status: str
    db_status: str
    version: str
