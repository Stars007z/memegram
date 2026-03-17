from functools import lru_cache
from app.core.interfaces.auth_gateway import IAuthGateway
from app.infrastructure.grpc.auth_gateway import GrpcAuthGateway
from app.core.use_cases.auth.register import RegisterUseCase
from app.core.use_cases.auth.login_init import LoginInitUseCase
from app.core.use_cases.auth.login_complete import LoginCompleteUseCase
from app.core.use_cases.auth.logout import LogoutUseCase
from app.core.use_cases.auth.create_invite import CreateInviteUseCase

from fastapi import Depends, HTTPException, status, Request
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from app.core.session_context import SessionContext
from app.core.interfaces.auth_gateway import IAuthGateway

_bearer = HTTPBearer()

async def get_current_session(
    request: Request,
    credentials: HTTPAuthorizationCredentials = Depends(_bearer),
    gateway: IAuthGateway = Depends(lambda: _get_cached_gateway()),
) -> SessionContext:
    result = await gateway.validate_token(credentials.credentials)
    if not result.valid:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    session = SessionContext(
        userid=result.userid,
        deviceid=result.deviceid,
        devicetype=result.devicetype,
        expiresat=result.expiresat,
    )
    request.state.session = session  # ← добавить
    return session


def require_device_type(*allowed_types: str):
    async def dependency(session: SessionContext = Depends(get_current_session)) -> SessionContext:
        if session.device_type not in allowed_types:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"This action requires device type: {', '.join(allowed_types)}",
            )
        return session
    return dependency


@lru_cache
def _get_cached_gateway() -> IAuthGateway:
    return GrpcAuthGateway()


def get_auth_gateway() -> IAuthGateway:
    return _get_cached_gateway()


def get_register_use_case() -> RegisterUseCase:
    return RegisterUseCase(get_auth_gateway())


def get_login_init_use_case() -> LoginInitUseCase:
    return LoginInitUseCase(get_auth_gateway())


def get_login_complete_use_case() -> LoginCompleteUseCase:
    return LoginCompleteUseCase(get_auth_gateway())


def get_logout_use_case() -> LogoutUseCase:
    return LogoutUseCase(get_auth_gateway())


def get_create_invite_use_case() -> CreateInviteUseCase:
    return CreateInviteUseCase(get_auth_gateway())