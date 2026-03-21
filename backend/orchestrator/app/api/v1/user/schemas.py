from pydantic import BaseModel
from typing import Optional


class UserProfileResponseSchema(BaseModel):
    id: str
    username: str
    avatar_data: Optional[bytes] = None           # base64 в JSON автоматически
    profile_background_data: Optional[bytes] = None
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
    chat_background_data: Optional[bytes] = None
    top_bar_color: str
    ringtone_data: Optional[bytes] = None
    ringtone_vibration_strength: int
    notification_sound_data: Optional[bytes] = None
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


class UserHealthResponseSchema(BaseModel):
    status: str
    db_status: str
    version: str
