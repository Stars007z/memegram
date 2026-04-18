from pydantic import BaseModel
from typing import Optional

class ItemStorageHealthResponseSchema(BaseModel):
    status: str
    db_status: str
    s3_status: str
    version: str

class InitiateUploadRequestSchema(BaseModel):
    item_type: str
    mime_type: str
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
