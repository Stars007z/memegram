from pydantic import BaseModel
from typing import Optional


class ItemStorageHealthResponseSchema(BaseModel):
    status: str
    db_status: str
    s3_status: str
    version: str


class InitiateUploadRequestSchema(BaseModel):
    item_type: str       # avatar, profile_background, chat_background, notification_sound, ringtone
    mime_type: str       # e.g. image/jpeg, image/png, audio/mpeg
    size_bytes: int


class InitiateUploadResponseSchema(BaseModel):
    item_id: str
    upload_url: str
    expires_at: int


class ConfirmUploadResponseSchema(BaseModel):
    success: bool
    item_id: str


class DownloadUrlResponseSchema(BaseModel):
    download_url: str
    expires_at: int
    mime_type: str
