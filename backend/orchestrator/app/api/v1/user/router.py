import asyncio

from fastapi import APIRouter, Depends

from app.api.dependencies import (
    get_auth_gateway,
    get_contacts_gateway,
    get_current_session,
    get_item_storage_gateway,
    get_media_gateway,
    get_messaging_gateway,
    get_user_gateway,
)
from app.api.v1.user.schemas import (
    DeleteUserResponseSchema,
    MediaDownloadInfoSchema,
    SyncSettingsRequestSchema,
    SyncSettingsResponseSchema,
    UpdateUserRequestSchema,
    UpdateUserSettingsRequestSchema,
    UserHealthResponseSchema,
    UserProfileResponseSchema,
    UserSettingsResponseSchema,
)
from app.core.interfaces.auth_gateway import IAuthGateway
from app.core.interfaces.contacts_gateway import IContactsGateway
from app.core.interfaces.item_storage_gateway import IItemStorageGateway
from app.core.interfaces.media_gateway import IMediaGateway
from app.core.interfaces.messaging_gateway import IMessagingGateway
from app.core.interfaces.user_gateway import IUserGateway, UpdateUserRequest, UpdateUserSettingsRequest
from app.core.session_context import SessionContext
from app.logging_config import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/user", tags=["user"])


async def _build_profile_with_blocks(
    profile_dc,
    requester_user_id: str,
    contacts_gw: IContactsGateway,
) -> UserProfileResponseSchema:
    """Build a UserProfileResponseSchema, populating block flags.

    For self-profile (requester == target) we skip block lookups entirely
    since they are meaningless and would just be two no-op gRPC calls."""
    target_id = profile_dc.id
    is_peer_blocked = False
    is_blocked_by_peer = False
    if target_id and target_id != requester_user_id:
        try:
            a, b = await asyncio.gather(
                contacts_gw.is_blocked(
                    user_id=requester_user_id,
                    blocked_user_id=target_id,
                ),
                contacts_gw.is_blocked(
                    user_id=target_id,
                    blocked_user_id=requester_user_id,
                ),
                return_exceptions=True,
            )
            if not isinstance(a, Exception):
                is_peer_blocked = a.is_blocked
            else:
                logger.warning("user.profile.is_blocked_lookup_failed", side="peer", error=str(a))
            if not isinstance(b, Exception):
                is_blocked_by_peer = b.is_blocked
            else:
                logger.warning("user.profile.is_blocked_lookup_failed", side="by_peer", error=str(b))
        except Exception as e:

            logger.warning("user.profile.block_flags_failed", error=str(e))
    data = profile_dc.__dict__.copy()
    data["is_peer_blocked"] = is_peer_blocked
    data["is_blocked_by_peer"] = is_blocked_by_peer
    return UserProfileResponseSchema(**data)


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
    data = result.__dict__.copy()
    data["is_peer_blocked"] = False
    data["is_blocked_by_peer"] = False
    return UserProfileResponseSchema(**data)


@router.get("/{user_id}", response_model=UserProfileResponseSchema)
async def get_user(
    user_id: str,
    session: SessionContext = Depends(get_current_session),
    gateway: IUserGateway = Depends(get_user_gateway),
    contacts_gw: IContactsGateway = Depends(get_contacts_gateway),
):
    result = await gateway.get_user(user_id=user_id, requester_user_id=session.user_id)
    return await _build_profile_with_blocks(result, session.user_id, contacts_gw)


@router.get("/by-key/{user_public_key}", response_model=UserProfileResponseSchema)
async def get_user_by_public_key(
    user_public_key: str,
    session: SessionContext = Depends(get_current_session),
    gateway: IUserGateway = Depends(get_user_gateway),
    contacts_gw: IContactsGateway = Depends(get_contacts_gateway),
):
    result = await gateway.get_user_by_public_key(
        user_public_key=user_public_key,
        requester_user_id=session.user_id,
    )
    return await _build_profile_with_blocks(result, session.user_id, contacts_gw)


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
    data = result.__dict__.copy()
    data["is_peer_blocked"] = False
    data["is_blocked_by_peer"] = False
    return UserProfileResponseSchema(**data)


@router.delete("/me", response_model=DeleteUserResponseSchema)
async def delete_me(
    session: SessionContext = Depends(get_current_session),
    user_gw: IUserGateway = Depends(get_user_gateway),
    contacts_gw: IContactsGateway = Depends(get_contacts_gateway),
    messaging_gw: IMessagingGateway = Depends(get_messaging_gateway),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
    media_gw: IMediaGateway = Depends(get_media_gateway),
):
    """Account deletion fanout.

    Order matters: user-service hard-deletes the user row FIRST and returns
    the list of media object IDs that were attached to the profile/settings.
    Only after that succeeds do we run the best-effort cleanup fanout
    (contacts, messaging memberships, device revocation, media objects).
    Sub-call failures are logged but never abort the request — the user row
    is already gone from user-service which is the authoritative source.
    """
    user_id = session.user_id
    delete_result = await user_gw.delete_user(user_id=user_id)
    if not delete_result.success:
        return DeleteUserResponseSchema(success=False, deleted_at=None)

    media_ids = delete_result.media_ids or []
    fanout_tasks = [
        contacts_gw.purge_user(user_id=user_id),
        messaging_gw.purge_user_membership(user_id=user_id),
        auth_gw.bulk_revoke_user_devices(user_id=user_id),
    ]
    fanout_tasks.extend(media_gw.delete_object_by_media_id(media_id=mid) for mid in media_ids)

    results = await asyncio.gather(*fanout_tasks, return_exceptions=True)

    step_labels = ["contacts_purge", "messaging_purge", "auth_revoke"] + [f"media_delete[{mid}]" for mid in media_ids]
    for label, result in zip(step_labels, results):
        if isinstance(result, Exception):
            step_key = label.split("[", 1)[0]
            logger.warning(
                f"user.delete.fanout.{step_key}_failed",
                user_id=user_id,
                step=label,
                error=str(result),
            )

    return DeleteUserResponseSchema(
        success=True,
        deleted_at=delete_result.deleted_at or None,
    )


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
                item_id=item_id,
                requester_user_id=session.user_id,
            )
            for _, item_id in changed
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        for (field_label, item_id), result in zip(changed, results):
            if isinstance(result, Exception):
                logger.warning(
                    "settings.sync.download_url_failed",
                    field=field_label,
                    item_id=item_id,
                    error=str(result),
                )
                continue
            media_updates.append(
                MediaDownloadInfoSchema(
                    field=field_label,
                    item_id=item_id,
                    download_url=result.download_url,
                    expires_at=result.expires_at,
                    mime_type=result.mime_type,
                )
            )

    return SyncSettingsResponseSchema(
        settings=settings_schema,
        media_updates=media_updates,
    )
