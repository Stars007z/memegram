import base64
from typing import Optional
from pydantic import BaseModel, Field


# ── Request schemas ───────────────────────────────────────────────────

class SubmitDeviceDataRequestSchema(BaseModel):
    device_id: str = Field(..., min_length=1)
    device_name: str = Field(..., min_length=1, max_length=128)
    device_type: str = Field(default="secondary", max_length=50)
    identity_key_pub_b64: str = Field(..., alias="identity_key_pub")
    init_key_pub_b64: str = Field(..., alias="init_key_pub")
    credential_data_b64: str = Field(..., alias="credential_data")
    registration_code: str = Field(..., min_length=1)

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


class ConfirmDeviceAdditionRequestSchema(BaseModel):
    confirm: bool
    new_device_name: Optional[str] = Field(None, max_length=128)


class RevokeDeviceRequestSchema(BaseModel):
    reason: str = Field(default="No reason provided", max_length=500)


class UpdateDeviceKeysRequestSchema(BaseModel):
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


class RenameDeviceRequestSchema(BaseModel):
    new_name: str = Field(..., min_length=1, max_length=128)


class VerifyDeviceRequestSchema(BaseModel):
    signature_b64: str = Field(..., alias="signature")

    model_config = {"populate_by_name": True}

    @property
    def signature_bytes(self) -> bytes:
        return base64.b64decode(self.signature_b64)


class TransferPrimaryRequestSchema(BaseModel):
    target_device_id: str = Field(..., min_length=1)


class BulkRevokeDevicesRequestSchema(BaseModel):
    device_ids: list[str] = Field(..., min_length=1)
    reason: str = Field(default="No reason provided", max_length=500)


# ── Response schemas ──────────────────────────────────────────────────

class DeviceInfoResponseSchema(BaseModel):
    id: str
    user_id: str
    client_device_id: str
    device_name: str
    device_type: str
    is_active: bool
    created_at: int
    last_seen: int
    identity_key_pub: str  # base64
    init_key_pub: str  # base64
    revoked_at: int


class InitDeviceAdditionResponseSchema(BaseModel):
    registration_id: str
    expires_at: int
    registration_code: str


class SubmitDeviceDataResponseSchema(BaseModel):
    status: str
    expires_at: int


class DeviceAdditionStatusResponseSchema(BaseModel):
    status: str
    expires_at: int
    device: Optional[DeviceInfoResponseSchema] = None
    access_token: str = ""
    refresh_token: str = ""
    token_expires_at: int = 0


class PendingRegistrationResponseSchema(BaseModel):
    registration_id: str
    registration_code: str
    expires_at: int
    status: str
    device_id: str
    device_name: str
    device_type: str
    created_at: int


class ConfirmDeviceAdditionResponseSchema(BaseModel):
    new_device_id: str
    user_id: str
    status: str
    message: str
    access_token: str
    refresh_token: str
    expires_at: int


class RevokeDeviceResponseSchema(BaseModel):
    success: bool
    message: str
    revoked_device_id: str
    revoked_at: int


class UpdateDeviceKeysResponseSchema(BaseModel):
    success: bool
    message: str
    updated_at: int


class RenameDeviceResponseSchema(BaseModel):
    success: bool
    new_name: str
    message: str


class VerifyDeviceResponseSchema(BaseModel):
    valid: bool
    message: str


class TransferPrimaryResponseSchema(BaseModel):
    success: bool
    new_primary_device_id: str
    message: str


class BulkRevokeDevicesResponseSchema(BaseModel):
    success: bool
    revoked_count: int
    revoked_device_ids: list[str]


class DeviceTypeCountSchema(BaseModel):
    device_type: str
    count: int


class DeviceStatsResponseSchema(BaseModel):
    total_count: int
    active_count: int
    primary_count: int
    type_stats: list[DeviceTypeCountSchema]
    last_activity_at: int
