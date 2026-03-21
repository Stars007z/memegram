from fastapi import APIRouter, Depends
from app.api.v1.user.schemas import (
    UserProfileResponseSchema, UpdateUserRequestSchema, DeleteUserResponseSchema,
    UserSettingsResponseSchema, UpdateUserSettingsRequestSchema, UserHealthResponseSchema,
)
from app.api.dependencies import get_current_session, get_user_gateway
from app.core.interfaces.user_gateway import IUserGateway, UpdateUserRequest, UpdateUserSettingsRequest
from app.core.session_context import SessionContext

router = APIRouter(prefix="/user", tags=["user"])


@router.get("/health", response_model=UserHealthResponseSchema)
async def user_health(gateway: IUserGateway = Depends(get_user_gateway)):
    result = await gateway.health_check()
    return UserHealthResponseSchema(**result)


@router.get("/me", response_model=UserProfileResponseSchema)
async def get_me(
    session: SessionContext = Depends(get_current_session),
    gateway: IUserGateway = Depends(get_user_gateway),
):
    result = await gateway.get_user(user_id=session.user_id, requester_user_id=session.user_id)
    return UserProfileResponseSchema(**result.__dict__)


@router.get("/{user_id}", response_model=UserProfileResponseSchema)
async def get_user(
    user_id: str,
    session: SessionContext = Depends(get_current_session),
    gateway: IUserGateway = Depends(get_user_gateway),
):
    result = await gateway.get_user(user_id=user_id, requester_user_id=session.user_id)
    return UserProfileResponseSchema(**result.__dict__)


@router.get("/by-key/{user_public_key}", response_model=UserProfileResponseSchema)
async def get_user_by_public_key(
    user_public_key: str,
    session: SessionContext = Depends(get_current_session),
    gateway: IUserGateway = Depends(get_user_gateway),
):
    result = await gateway.get_user_by_public_key(
        user_public_key=user_public_key,
        requester_user_id=session.user_id,
    )
    return UserProfileResponseSchema(**result.__dict__)


@router.patch("/me", response_model=UserProfileResponseSchema)
async def update_me(
    body: UpdateUserRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gateway: IUserGateway = Depends(get_user_gateway),
):
    request = UpdateUserRequest(
        user_id=session.user_id,
        bio=body.bio,
        username=body.username,
        avatar_media_id=body.avatar_media_id,
        profile_background_media_id=body.profile_background_media_id,
    )
    result = await gateway.update_user(request)
    return UserProfileResponseSchema(**result.__dict__)


@router.delete("/me", response_model=DeleteUserResponseSchema)
async def delete_me(
    session: SessionContext = Depends(get_current_session),
    gateway: IUserGateway = Depends(get_user_gateway),
):
    success = await gateway.delete_user(user_id=session.user_id)
    return DeleteUserResponseSchema(success=success)


@router.get("/me/settings", response_model=UserSettingsResponseSchema)
async def get_my_settings(
    session: SessionContext = Depends(get_current_session),
    gateway: IUserGateway = Depends(get_user_gateway),
):
    result = await gateway.get_user_settings(user_id=session.user_id)
    return UserSettingsResponseSchema(**result.__dict__)


@router.patch("/me/settings", response_model=UserSettingsResponseSchema)
async def update_my_settings(
    body: UpdateUserSettingsRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gateway: IUserGateway = Depends(get_user_gateway),
):
    request = UpdateUserSettingsRequest(
        user_id=session.user_id,
        **body.model_dump(exclude_none=True),
    )
    result = await gateway.update_user_settings(request)
    return UserSettingsResponseSchema(**result.__dict__)
