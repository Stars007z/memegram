import grpc
from typing import Optional

from app.config import settings
from app.core.interfaces.auth_gateway import (
    IAuthGateway,
    RegisterRequest,
    AuthResult,
    LoginInitResult,
    LoginCompleteRequest,
    LogoutResult,
    HealthResult,
    CreateInviteResult,
    ValidateTokenResult
)
from app.exceptions import GatewayError, NotFoundError, ValidationError, PermissionDeniedError
from app.infrastructure.grpc.client import get_grpc_channel
from app.infrastructure.grpc.generated import auth_pb2, auth_pb2_grpc



def _grpc_error_to_exception(e: grpc.RpcError) -> Exception:
    code = e.code()
    details = e.details() or "Unknown gRPC error"

    if code == grpc.StatusCode.INVALID_ARGUMENT:
        return ValidationError(details)
    if code == grpc.StatusCode.NOT_FOUND:
        return NotFoundError(details)
    if code == grpc.StatusCode.PERMISSION_DENIED:
        return PermissionDeniedError(details)
    if code == grpc.StatusCode.UNAVAILABLE:
        return GatewayError("Auth service is unavailable", code=503)
    return GatewayError(f"Auth service error: {details}", code=502)


class GrpcAuthGateway(IAuthGateway):

    async def _get_stub(self) -> auth_pb2_grpc.AuthServiceStub:
        channel = await get_grpc_channel()
        return auth_pb2_grpc.AuthServiceStub(channel)

    async def register(self, request: RegisterRequest) -> AuthResult:
        stub = await self._get_stub()
        try:
            response = await stub.Register(
                auth_pb2.RegisterRequest(
                    username=request.username,
                    invite_code=request.invite_code,
                    device_id=request.device_id,
                    device_name=request.device_name,
                    identity_key_pub=request.identity_key_pub,
                    init_key_pub=request.init_key_pub,
                    credential_data=request.credential_data,
                ),
                timeout=settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

        return AuthResult(
            user_id=response.user_id,
            device_id=response.device_id,
            is_primary=response.is_primary,
            access_token=response.access_token,
            refresh_token=response.refresh_token,
            expires_at=response.expires_at,
        )

    async def login_init(self, device_id: str) -> LoginInitResult:
        stub = await self._get_stub()
        try:
            response = await stub.LoginInit(
                auth_pb2.LoginInitRequest(device_id=device_id),
                timeout=settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

        return LoginInitResult(
            challenge=response.challenge,
            expires_at=response.expires_at,
            device_id=response.device_id,
        )

    async def login_complete(self, request: LoginCompleteRequest) -> AuthResult:
        stub = await self._get_stub()
        try:
            response = await stub.LoginComplete(
                auth_pb2.LoginCompleteRequest(
                    device_id=request.device_id,
                    challenge=request.challenge,
                    signature=request.signature,
                    device_name=request.device_name or "",
                ),
                timeout=settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

        return AuthResult(
            user_id=response.user_id,
            device_id=response.device_id,
            is_primary=response.is_primary,
            access_token=response.access_token,
            refresh_token=response.refresh_token,
            expires_at=response.expires_at,
        )

    async def logout(self, access_token: str) -> LogoutResult:
        stub = await self._get_stub()
        try:
            response = await stub.Logout(
                auth_pb2.LogoutRequest(access_token=access_token),
                timeout=settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

        return LogoutResult(success=response.success, message=response.message)

    async def health_check(self) -> HealthResult:
        stub = await self._get_stub()
        try:
            response = await stub.HealthCheck(
                auth_pb2.HealthCheckRequest(),
                timeout=settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

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
        stub = await self._get_stub()
        pb_request = auth_pb2.CreateInviteRequest(expires_in_days=expires_in_days)
        if created_by_device_id:
            pb_request.created_by_device_id = created_by_device_id
        try:
            response = await stub.CreateInvite(pb_request, timeout=settings.AUTH_GRPC_TIMEOUT)
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

        return CreateInviteResult(
            code=response.code,
            created_at=response.created_at,
            expires_at=response.expires_at,
            is_used=response.is_used,
            message=response.message,
        )

    async def validate_token(self, access_token: str) -> ValidateTokenResult:
        stub = await self.get_stub()
        try:
            response = await stub.ValidateToken(
                authpb2.ValidateTokenRequest(access_token=access_token),
                timeout=settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e)
        return ValidateTokenResult(
            valid=response.valid,
            user_id=response.user_id,
            device_id=response.device_id,
            device_type=response.device_type,
            expires_at=response.expires_at,
        )
