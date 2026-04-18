from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.container import Container
from app.core.interfaces.auth_gateway import IAuthGateway
from app.core.interfaces.contacts_gateway import IContactsGateway
from app.core.interfaces.item_storage_gateway import IItemStorageGateway
from app.core.interfaces.media_gateway import IMediaGateway
from app.core.interfaces.messaging_gateway import IMessagingGateway
from app.core.interfaces.notifications_gateway import INotificationsGateway
from app.core.interfaces.user_gateway import IUserGateway
from app.core.session_context import SessionContext
from app.core.use_cases.auth.create_invite import CreateInviteUseCase
from app.core.use_cases.auth.login_complete import LoginCompleteUseCase
from app.core.use_cases.auth.login_init import LoginInitUseCase
from app.core.use_cases.auth.register import RegisterUseCase

bearer = HTTPBearer()


def _container(request: Request) -> Container:
    return request.app.state.container


def get_auth_gateway(request: Request) -> IAuthGateway:
    return _container(request).auth_gateway


def get_user_gateway(request: Request) -> IUserGateway:
    return _container(request).user_gateway


def get_contacts_gateway(request: Request) -> IContactsGateway:
    return _container(request).contacts_gateway


def get_messaging_gateway(request: Request) -> IMessagingGateway:
    return _container(request).messaging_gateway


def get_media_gateway(request: Request) -> IMediaGateway:
    return _container(request).media_gateway


def get_item_storage_gateway(request: Request) -> IItemStorageGateway:
    return _container(request).item_storage_gateway


def get_notifications_gateway(request: Request) -> INotificationsGateway:
    return _container(request).notifications_gateway


async def get_current_session(
    request: Request,
    credentials: HTTPAuthorizationCredentials = Depends(bearer),
    gateway: IAuthGateway = Depends(get_auth_gateway),
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
                status_code=403,
                detail=f"This action requires device type: {', '.join(allowed_types)}",
            )
        return session

    return dependency


def get_register_use_case(request: Request) -> RegisterUseCase:
    c = _container(request)
    return RegisterUseCase(auth_gateway=c.auth_gateway, user_gateway=c.user_gateway)


def get_login_init_use_case(request: Request) -> LoginInitUseCase:
    return LoginInitUseCase(_container(request).auth_gateway)


def get_login_complete_use_case(request: Request) -> LoginCompleteUseCase:
    return LoginCompleteUseCase(_container(request).auth_gateway)


def get_create_invite_use_case(request: Request) -> CreateInviteUseCase:
    return CreateInviteUseCase(_container(request).auth_gateway)
