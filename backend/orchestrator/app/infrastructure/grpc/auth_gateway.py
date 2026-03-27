import grpc
from typing import Optional

from app.config import Settings
from app.core.interfaces.auth_gateway import (
    IAuthGateway,
    RegisterRequest,
    AuthResult,
    LoginInitResult,
    LoginCompleteRequest,
    LogoutResult,
    HealthResult,
    CreateInviteResult,
    ValidateTokenResult,
)
from app.infrastructure.grpc.errors import grpc_error_to_exception
from app.infrastructure.grpc.client import GrpcChannelManager
from app.infrastructure.grpc.generated import auth_pb2, auth_pb2_grpc

_SERVICE = "Auth service"


class GrpcAuthGateway(IAuthGateway):

    def __init__(self, channels: GrpcChannelManager, settings: Settings):
        self._channels = channels
        self._settings = settings

    def _stub(self) -> auth_pb2_grpc.AuthServiceStub:
        return auth_pb2_grpc.AuthServiceStub(self._channels.get("auth"))

    async def register(self, request: RegisterRequest) -> AuthResult:
        try:
            response = await self._stub().Register(
                auth_pb2.RegisterRequest(
                    username=request.username,
                    invite_code=request.invite_code,
                    device_id=request.device_id,
                    device_name=request.device_name,
                    identity_key_pub=request.identity_key_pub,
                    init_key_pub=request.init_key_pub,
                    credential_data=request.credential_data,
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return AuthResult(
            user_id=response.user_id,
            device_id=response.device_id,
            is_primary=response.is_primary,
            access_token=response.access_token,
            refresh_token=response.refresh_token,
            expires_at=response.expires_at,
        )

    async def login_init(self, device_id: str) -> LoginInitResult:
        try:
            response = await self._stub().LoginInit(
                auth_pb2.LoginInitRequest(device_id=device_id),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return LoginInitResult(
            challenge=response.challenge,
            expires_at=response.expires_at,
            device_id=response.device_id,
        )

    async def login_complete(self, request: LoginCompleteRequest) -> AuthResult:
        try:
            response = await self._stub().LoginComplete(
                auth_pb2.LoginCompleteRequest(
                    device_id=request.device_id,
                    challenge=request.challenge,
                    signature=request.signature,
                    device_name=request.device_name or "",
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return AuthResult(
            user_id=response.user_id,
            device_id=response.device_id,
            is_primary=response.is_primary,
            access_token=response.access_token,
            refresh_token=response.refresh_token,
            expires_at=response.expires_at,
        )

    async def logout(self, access_token: str) -> LogoutResult:
        try:
            response = await self._stub().Logout(
                auth_pb2.LogoutRequest(access_token=access_token),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return LogoutResult(success=response.success, message=response.message)

    async def health_check(self) -> HealthResult:
        try:
            response = await self._stub().HealthCheck(
                auth_pb2.HealthCheckRequest(),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return HealthResult(
            status=response.status,
            db_status=response.db_status,
            redis_status=response.redis_status,
            version=response.version,
        )

    async def create_invite(
        self,
        expires_in_days: int,
        created_by_device_id: Optional[str] = None,
    ) -> CreateInviteResult:
        pb_request = auth_pb2.CreateInviteRequest(expires_in_days=expires_in_days)
        if created_by_device_id:
            pb_request.created_by_device_id = created_by_device_id
        try:
            response = await self._stub().CreateInvite(
                pb_request, timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return CreateInviteResult(
            code=response.code,
            created_at=response.created_at,
            expires_at=response.expires_at,
            is_used=response.is_used,
            message=response.message,
        )

    async def validate_token(self, access_token: str) -> ValidateTokenResult:
        try:
            response = await self._stub().ValidateToken(
                auth_pb2.ValidateTokenRequest(access_token=access_token),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return ValidateTokenResult(
            valid=response.valid,
            user_id=response.user_id,
            device_id=response.device_id,
            device_type=response.device_type,
            expires_at=response.expires_at,
        )
