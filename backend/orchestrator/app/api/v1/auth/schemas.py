import base64
from typing import Optional
from pydantic import BaseModel, Field, field_validator



class RegisterRequestSchema(BaseModel):
    username: str = Field(..., min_length=3, max_length=64)
    invite_code: str = Field(..., min_length=1)
    device_id: str = Field(..., min_length=1)
    device_name: str = Field(..., min_length=1, max_length=128)
    identity_key_pub_b64: str = Field(..., alias="identity_key_pub")
    init_key_pub_b64: str = Field(..., alias="init_key_pub")
    credential_data_b64: str = Field(..., alias="credential_data")

    model_config = {"populate_by_name": True}

    @property
    def identity_key_pub_bytes(self) -> bytes:
        return base64.b64decode(self.identity_key_pub_b64)

    @property
    def init_key_pub_bytes(self) -> bytes:
        return base64.b64decode(self.init_key_pub_b64)

    @property
    def credential_data_bytes(self) -> bytes:
        return base64.b64decode(self.credential_data_b64)


class LoginInitRequestSchema(BaseModel):
    device_id: str = Field(..., min_length=1)


class LoginCompleteRequestSchema(BaseModel):
    device_id: str = Field(..., min_length=1)
    challenge: str = Field(..., min_length=1)
    signature_b64: str = Field(..., alias="signature")
    device_name: Optional[str] = Field(None, max_length=128)

    model_config = {"populate_by_name": True}

    @property
    def signature_bytes(self) -> bytes:
        return base64.b64decode(self.signature_b64)


class LogoutRequestSchema(BaseModel):
    access_token: str = Field(..., min_length=1)


class CreateInviteRequestSchema(BaseModel):
    expires_in_days: int = Field(..., ge=1, le=365)
    created_by_device_id: Optional[str] = None



class AuthResponseSchema(BaseModel):
    user_id: str
    device_id: str
    is_primary: bool
    access_token: str
    refresh_token: str
    expires_at: int


class LoginInitResponseSchema(BaseModel):
    challenge: str
    expires_at: int
    device_id: str


class LogoutResponseSchema(BaseModel):
    success: bool
    message: str


class HealthResponseSchema(BaseModel):
    status: str
    db_status: str
    redis_status: str
    version: str


class CreateInviteResponseSchema(BaseModel):
    code: str
    created_at: int
    expires_at: int
    is_used: bool
    message: str