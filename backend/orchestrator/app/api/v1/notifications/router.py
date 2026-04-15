from fastapi import APIRouter, Depends

from app.api.dependencies import get_notifications_gateway, get_current_session
from app.api.v1.notifications.schemas import (
    RegisterPushTokenRequestSchema,
    RegisterPushTokenResponseSchema,
    UnregisterPushTokenResponseSchema,
    NotificationsHealthResponseSchema,
)
from app.core.interfaces.notifications_gateway import INotificationsGateway
from app.core.session_context import SessionContext

router = APIRouter(prefix="/notifications", tags=["notifications"])


@router.get("/health", response_model=NotificationsHealthResponseSchema)
async def notifications_health(
    gw: INotificationsGateway = Depends(get_notifications_gateway),
):
    result = await gw.health_check()
    return NotificationsHealthResponseSchema(**result.__dict__)


@router.post("/push-token", response_model=RegisterPushTokenResponseSchema)
async def register_push_token(
    body: RegisterPushTokenRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: INotificationsGateway = Depends(get_notifications_gateway),
):
    result = await gw.register_push_token(
        user_id=session.user_id,
        device_id=session.device_id,
        platform=body.platform,
        push_token=body.push_token,
    )
    return RegisterPushTokenResponseSchema(success=result.success)


@router.delete("/push-token", response_model=UnregisterPushTokenResponseSchema)
async def unregister_push_token(
    session: SessionContext = Depends(get_current_session),
    gw: INotificationsGateway = Depends(get_notifications_gateway),
):
    result = await gw.unregister_push_token(
        user_id=session.user_id,
        device_id=session.device_id,
    )
    return UnregisterPushTokenResponseSchema(success=result.success)
