from functools import lru_cache

from fastapi import Depends, HTTPException, status, Request
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

from app.core.interfaces.auth_gateway import IAuthGateway
from app.core.interfaces.user_gateway import IUserGateway
from app.core.interfaces.contacts_gateway import IContactsGateway
from app.core.session_context import SessionContext
from app.core.use_cases.auth.register import RegisterUseCase
from app.core.use_cases.auth.login_init import LoginInitUseCase
from app.core.use_cases.auth.login_complete import LoginCompleteUseCase
from app.core.use_cases.auth.logout import LogoutUseCase
from app.core.use_cases.auth.create_invite import CreateInviteUseCase
from app.infrastructure.grpc.auth_gateway import GrpcAuthGateway
from app.infrastructure.grpc.user_gateway import GrpcUserGateway
from app.infrastructure.grpc.contacts_gateway import GrpcContactsGateway


@lru_cache(maxsize=1)
def get_cached_gateway() -> IAuthGateway:
    return GrpcAuthGateway()


@lru_cache(maxsize=1)
def get_cached_user_gateway() -> IUserGateway:
    return GrpcUserGateway()


@lru_cache(maxsize=1)
def get_cached_contacts_gateway() -> IContactsGateway:
    return GrpcContactsGateway()


def get_auth_gateway() -> IAuthGateway:
    return get_cached_gateway()


def get_user_gateway() -> IUserGateway:
    return get_cached_user_gateway()


def get_contacts_gateway() -> IContactsGateway:
    return get_cached_contacts_gateway()


bearer = HTTPBearer()


async def get_current_session(
    request: Request,
    credentials: HTTPAuthorizationCredentials = Depends(bearer),
    gateway: IAuthGateway = Depends(get_cached_gateway),
) -> SessionContext:
    result = await gateway.validate_token(credentials.credentials)
    if not result.valid:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    session = SessionContext(
        user_id=result.user_id,
        device_id=result.device_id,
        device_type=result.device_type,
        expires_at=result.expires_at,
    )
    request.state.session = session
    return session


def require_device_type(*allowed_types: str):
    async def dependency(
        session: SessionContext = Depends(get_current_session),
    ) -> SessionContext:
        if session.device_type not in allowed_types:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"This action requires device type: {', '.join(allowed_types)}",
            )
        return session
    return dependency


def get_register_use_case() -> RegisterUseCase:
    return RegisterUseCase(
        auth_gateway=get_cached_gateway(),
        user_gateway=get_cached_user_gateway(),
    )


def get_login_init_use_case() -> LoginInitUseCase:
    return LoginInitUseCase(get_cached_gateway())


def get_login_complete_use_case() -> LoginCompleteUseCase:
    return LoginCompleteUseCase(get_cached_gateway())


def get_logout_use_case() -> LogoutUseCase:
    return LogoutUseCase(get_cached_gateway())


def get_create_invite_use_case() -> CreateInviteUseCase:
    return CreateInviteUseCase(get_cached_gateway())
