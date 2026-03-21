from abc import ABC, abstractmethod
from dataclasses import dataclass
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
    is_primary: bool
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

class IAuthGateway(ABC):

    @abstractmethod
    async def register(self, request: RegisterRequest) -> AuthResult:
        ...

    @abstractmethod
    async def login_init(self, device_id: str) -> LoginInitResult:
        ...

    @abstractmethod
    async def login_complete(self, request: LoginCompleteRequest) -> AuthResult:
        ...

    @abstractmethod
    async def logout(self, access_token: str) -> LogoutResult:
        ...

    @abstractmethod
    async def health_check(self) -> HealthResult:
        ...

    @abstractmethod
    async def create_invite(self, expires_in_days: int, created_by_device_id: Optional[str]) -> CreateInviteResult:
        ...

    @abstractmethod
    async def validate_token(self, access_token: str) -> ValidateTokenResult:
        ...