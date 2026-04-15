import asyncio
import logging

from fastapi import APIRouter, Depends
from app.api.v1.user.schemas import (
    UserProfileResponseSchema, UpdateUserRequestSchema, DeleteUserResponseSchema,
    UserSettingsResponseSchema, UpdateUserSettingsRequestSchema, UserHealthResponseSchema,
    SyncSettingsRequestSchema, SyncSettingsResponseSchema, MediaDownloadInfoSchema,
)
from app.api.dependencies import get_current_session, get_user_gateway, get_item_storage_gateway
from app.core.interfaces.user_gateway import IUserGateway, UpdateUserRequest, UpdateUserSettingsRequest
from app.core.interfaces.item_storage_gateway import IItemStorageGateway
from app.core.session_context import SessionContext

logger = logging.getLogger(__name__)

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


_MEDIA_FIELDS = [
    ("chat_background_media_id", "chat_background"),
    ("ringtone_media_id", "ringtone"),
    ("notification_sound_media_id", "notification_sound"),
    ("top_bar_media_id", "top_bar"),
    ("my_bubble_media_id", "my_bubble"),
    ("their_bubble_media_id", "their_bubble"),
]


@router.post("/me/settings/sync", response_model=SyncSettingsResponseSchema)
async def sync_my_settings(
    body: SyncSettingsRequestSchema,
    session: SessionContext = Depends(get_current_session),
    user_gw: IUserGateway = Depends(get_user_gateway),
    storage_gw: IItemStorageGateway = Depends(get_item_storage_gateway),
):
    """Return current settings and presigned download URLs only for
    media items that differ from the client's locally cached versions."""
    settings_result = await user_gw.get_user_settings(user_id=session.user_id)
    settings_schema = UserSettingsResponseSchema(**settings_result.__dict__)

    changed: list[tuple[str, str]] = []
    for field_attr, field_label in _MEDIA_FIELDS:
        server_id = getattr(settings_result, field_attr, None)
        client_id = getattr(body, field_attr, None)
        if server_id and server_id != client_id:
            changed.append((field_label, server_id))

    media_updates: list[MediaDownloadInfoSchema] = []
    if changed:
        tasks = [
            storage_gw.get_download_url(
                item_id=item_id, requester_user_id=session.user_id,
            )
            for _, item_id in changed
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        for (field_label, item_id), result in zip(changed, results):
            if isinstance(result, Exception):
                logger.warning(
                    "Failed to get download URL for %s (%s): %s",
                    field_label, item_id, result,
                )
                continue
            media_updates.append(MediaDownloadInfoSchema(
                field=field_label,
                item_id=item_id,
                download_url=result.download_url,
                expires_at=result.expires_at,
                mime_type=result.mime_type,
            ))

    return SyncSettingsResponseSchema(
        settings=settings_schema,
        media_updates=media_updates,
    )
