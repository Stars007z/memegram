from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class RegisterRequest:
    username: str
    invite_code: str
    device_id: str
    device_name: str
    identity_key_pub: bytes
    init_key_pub: bytes
    credential_data: bytes


@dataclass
class AuthResult:
    user_id: str
    device_id: str
    device_type: str
    access_token: str
    refresh_token: str
    expires_at: int


@dataclass
class LoginInitResult:
    challenge: str
    expires_at: int
    device_id: str


@dataclass
class LoginCompleteRequest:
    device_id: str
    challenge: str
    signature: bytes
    device_name: Optional[str] = None


@dataclass
class LogoutResult:
    success: bool
    message: str


@dataclass
class HealthResult:
    status: str
    db_status: str
    redis_status: str
    version: str


@dataclass
class CreateInviteResult:
    code: str
    created_at: int
    expires_at: int
    is_used: bool
    message: str


@dataclass
class ValidateTokenResult:
    valid: bool
    user_id: str
    device_id: str
    device_type: str
    expires_at: int


@dataclass
class DeviceInfoResult:
    id: str
    user_id: str
    client_device_id: str
    device_name: str
    device_type: str
    is_active: bool
    created_at: int
    last_seen: int
    identity_key_pub: bytes
    init_key_pub: bytes
    revoked_at: int


@dataclass
class InitDeviceAdditionResult:
    registration_id: str
    expires_at: int
    registration_code: str


@dataclass
class SubmitDeviceDataResult:
    status: str
    expires_at: int


@dataclass
class DeviceAdditionStatusResult:
    status: str
    expires_at: int
    device: Optional[DeviceInfoResult]
    access_token: str
    refresh_token: str
    token_expires_at: int


@dataclass
class PendingRegistrationResult:
    registration_id: str
    registration_code: str
    expires_at: int
    status: str
    device_id: str
    device_name: str
    device_type: str
    created_at: int


@dataclass
class ConfirmDeviceAdditionResult:
    new_device_id: str
    user_id: str
    status: str
    message: str
    access_token: str
    refresh_token: str
    expires_at: int


@dataclass
class RevokeDeviceResult:
    success: bool
    message: str
    revoked_device_id: str
    revoked_at: int


@dataclass
class UpdateDeviceKeysResult:
    success: bool
    message: str
    updated_at: int


@dataclass
class RenameDeviceResult:
    success: bool
    new_name: str
    message: str


@dataclass
class VerifyDeviceResult:
    valid: bool
    message: str


@dataclass
class TransferPrimaryResult:
    success: bool
    new_primary_device_id: str
    message: str


@dataclass
class BulkRevokeDevicesResult:
    success: bool
    revoked_count: int
    revoked_device_ids: list[str] = field(default_factory=list)


@dataclass
class DeviceTypeCountResult:
    device_type: str
    count: int


@dataclass
class DeviceStatsResult:
    total_count: int
    active_count: int
    primary_count: int
    type_stats: list[DeviceTypeCountResult] = field(default_factory=list)
    last_activity_at: int = 0


class IAuthGateway(ABC):

    @abstractmethod
    async def register(self, request: RegisterRequest) -> AuthResult: ...

    @abstractmethod
    async def login_init(self, device_id: str) -> LoginInitResult: ...

    @abstractmethod
    async def login_complete(self, request: LoginCompleteRequest) -> AuthResult: ...

    @abstractmethod
    async def logout(self, access_token: str) -> LogoutResult: ...

    @abstractmethod
    async def health_check(self) -> HealthResult: ...

    @abstractmethod
    async def create_invite(self, expires_in_days: int, created_by_device_id: Optional[str]) -> CreateInviteResult: ...

    @abstractmethod
    async def validate_token(self, access_token: str) -> ValidateTokenResult: ...

    @abstractmethod
    async def init_device_addition(self, user_id: str, device_id: str) -> InitDeviceAdditionResult: ...

    @abstractmethod
    async def submit_device_data(
        self,
        registration_id: str,
        registration_code: str,
        device_id: str,
        device_name: str,
        device_type: str,
        identity_key_pub: bytes,
        init_key_pub: bytes,
        credential_data: bytes,
    ) -> SubmitDeviceDataResult: ...

    @abstractmethod
    async def get_device_addition_status(self, registration_id: str) -> DeviceAdditionStatusResult: ...

    @abstractmethod
    async def get_pending_device_additions(self, user_id: str, device_id: str) -> list[PendingRegistrationResult]: ...

    @abstractmethod
    async def confirm_device_addition(
        self,
        user_id: str,
        device_id: str,
        registration_id: str,
        confirm: bool,
        new_device_name: str,
    ) -> ConfirmDeviceAdditionResult: ...

    @abstractmethod
    async def get_devices(self, user_id: str) -> list[DeviceInfoResult]: ...

    @abstractmethod
    async def get_device(self, user_id: str, device_id: str) -> DeviceInfoResult: ...

    @abstractmethod
    async def revoke_device(
        self,
        user_id: str,
        requesting_device_id: str,
        target_device_id: str,
        reason: str,
    ) -> RevokeDeviceResult: ...

    @abstractmethod
    async def update_device_keys(
        self,
        user_id: str,
        device_id: str,
        identity_key_pub: bytes,
        init_key_pub: bytes,
        credential_data: bytes,
    ) -> UpdateDeviceKeysResult: ...

    @abstractmethod
    async def rename_device(
        self,
        user_id: str,
        requesting_device_id: str,
        target_device_id: str,
        new_name: str,
    ) -> RenameDeviceResult: ...

    @abstractmethod
    async def verify_device(self, device_id: str, signature: bytes) -> VerifyDeviceResult: ...

    @abstractmethod
    async def transfer_primary(
        self,
        user_id: str,
        requesting_device_id: str,
        target_device_id: str,
    ) -> TransferPrimaryResult: ...

    @abstractmethod
    async def bulk_revoke_devices(
        self,
        user_id: str,
        requesting_device_id: str,
        target_device_ids: list[str],
        reason: str,
    ) -> BulkRevokeDevicesResult: ...

    @abstractmethod
    async def bulk_revoke_user_devices(self, user_id: str) -> BulkRevokeDevicesResult:
        """Account-deletion fanout helper: enumerate every active device for
        `user_id` via GetDevices, then BulkRevoke them all. Returns an empty
        success result when the user has no active devices. `requesting_device_id`
        is intentionally empty since the caller's session is being torn down."""
        ...

    @abstractmethod
    async def get_device_stats(self, user_id: str) -> DeviceStatsResult: ...
