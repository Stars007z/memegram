import base64
import dataclasses
from typing import Optional

from app.logging_config import get_logger

from fastapi import APIRouter, Depends

from app.api.dependencies import (
    get_auth_gateway,
    get_messaging_gateway,
    get_current_session,
    require_device_type,
)
from app.api.v1.devices.schemas import (
    SubmitDeviceDataRequestSchema,
    ConfirmDeviceAdditionRequestSchema,
    RevokeDeviceRequestSchema,
    UpdateDeviceKeysRequestSchema,
    RenameDeviceRequestSchema,
    VerifyDeviceRequestSchema,
    TransferPrimaryRequestSchema,
    BulkRevokeDevicesRequestSchema,
    DeviceInfoResponseSchema,
    InitDeviceAdditionResponseSchema,
    SubmitDeviceDataResponseSchema,
    DeviceAdditionStatusResponseSchema,
    PendingRegistrationResponseSchema,
    ConfirmDeviceAdditionResponseSchema,
    RevokeDeviceResponseSchema,
    UpdateDeviceKeysResponseSchema,
    RenameDeviceResponseSchema,
    VerifyDeviceResponseSchema,
    TransferPrimaryResponseSchema,
    BulkRevokeDevicesResponseSchema,
    DeviceStatsResponseSchema,
    DeviceTypeCountSchema,
)
from app.core.interfaces.auth_gateway import IAuthGateway
from app.core.interfaces.messaging_gateway import IMessagingGateway
from app.core.session_context import SessionContext

logger = get_logger(__name__)

router = APIRouter(prefix="/devices", tags=["devices"])

def _device_result_to_response(d) -> DeviceInfoResponseSchema:
    return DeviceInfoResponseSchema(
        id=d.id,
        user_id=d.user_id,
        client_device_id=d.client_device_id,
        device_name=d.device_name,
        device_type=d.device_type,
        is_active=d.is_active,
        created_at=d.created_at,
        last_seen=d.last_seen,
        identity_key_pub=base64.b64encode(d.identity_key_pub).decode() if d.identity_key_pub else "",
        init_key_pub=base64.b64encode(d.init_key_pub).decode() if d.init_key_pub else "",
        revoked_at=d.revoked_at,
    )

@router.post("/init-addition", response_model=InitDeviceAdditionResponseSchema, status_code=201)
async def init_device_addition(
    session: SessionContext = Depends(require_device_type("primary")),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> InitDeviceAdditionResponseSchema:
    result = await auth_gw.init_device_addition(
        user_id=session.user_id,
        device_id=session.device_id,
    )
    return InitDeviceAdditionResponseSchema(**dataclasses.asdict(result))

@router.post("/{registration_id}/submit", response_model=SubmitDeviceDataResponseSchema)
async def submit_device_data(
    registration_id: str,
    body: SubmitDeviceDataRequestSchema,
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> SubmitDeviceDataResponseSchema:
    result = await auth_gw.submit_device_data(
        registration_id=registration_id,
        registration_code=body.registration_code,
        device_id=body.device_id,
        device_name=body.device_name,
        device_type=body.device_type,
        identity_key_pub=body.identity_key_pub_bytes,
        init_key_pub=body.init_key_pub_bytes,
        credential_data=body.credential_data_bytes,
    )
    return SubmitDeviceDataResponseSchema(**dataclasses.asdict(result))

@router.get("/addition/{registration_id}/status", response_model=DeviceAdditionStatusResponseSchema)
async def get_device_addition_status(
    registration_id: str,
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> DeviceAdditionStatusResponseSchema:
    result = await auth_gw.get_device_addition_status(registration_id=registration_id)
    device_resp = _device_result_to_response(result.device) if result.device else None
    return DeviceAdditionStatusResponseSchema(
        status=result.status,
        expires_at=result.expires_at,
        device=device_resp,
        access_token=result.access_token,
        refresh_token=result.refresh_token,
        token_expires_at=result.token_expires_at,
    )

@router.get("/addition/pending", response_model=list[PendingRegistrationResponseSchema])
async def get_pending_device_additions(
    session: SessionContext = Depends(require_device_type("primary")),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> list[PendingRegistrationResponseSchema]:
    items = await auth_gw.get_pending_device_additions(
        user_id=session.user_id,
        device_id=session.device_id,
    )
    return [PendingRegistrationResponseSchema(**dataclasses.asdict(r)) for r in items]

@router.post(
    "/addition/{registration_id}/confirm",
    response_model=ConfirmDeviceAdditionResponseSchema,
)
async def confirm_device_addition(
    registration_id: str,
    body: ConfirmDeviceAdditionRequestSchema,
    session: SessionContext = Depends(require_device_type("primary")),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
    msg_gw: IMessagingGateway = Depends(get_messaging_gateway),
) -> ConfirmDeviceAdditionResponseSchema:
    result = await auth_gw.confirm_device_addition(
        user_id=session.user_id,
        device_id=session.device_id,
        registration_id=registration_id,
        confirm=body.confirm,
        new_device_name=body.new_device_name or "",
    )

    return ConfirmDeviceAdditionResponseSchema(**dataclasses.asdict(result))

@router.get("", response_model=list[DeviceInfoResponseSchema])
async def get_devices(
    session: SessionContext = Depends(get_current_session),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> list[DeviceInfoResponseSchema]:
    devices = await auth_gw.get_devices(user_id=session.user_id)
    return [_device_result_to_response(d) for d in devices]

@router.get("/stats", response_model=DeviceStatsResponseSchema)
async def get_device_stats(
    session: SessionContext = Depends(get_current_session),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> DeviceStatsResponseSchema:
    stats = await auth_gw.get_device_stats(user_id=session.user_id)
    return DeviceStatsResponseSchema(
        total_count=stats.total_count,
        active_count=stats.active_count,
        primary_count=stats.primary_count,
        type_stats=[
            DeviceTypeCountSchema(device_type=ts.device_type, count=ts.count)
            for ts in stats.type_stats
        ],
        last_activity_at=stats.last_activity_at,
    )

@router.get("/{device_id}", response_model=DeviceInfoResponseSchema)
async def get_device(
    device_id: str,
    session: SessionContext = Depends(get_current_session),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> DeviceInfoResponseSchema:
    result = await auth_gw.get_device(user_id=session.user_id, device_id=device_id)
    return _device_result_to_response(result)

@router.delete("/{device_id}", response_model=RevokeDeviceResponseSchema)
async def revoke_device(
    device_id: str,
    body: RevokeDeviceRequestSchema,
    session: SessionContext = Depends(require_device_type("primary")),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
    msg_gw: IMessagingGateway = Depends(get_messaging_gateway),
) -> RevokeDeviceResponseSchema:
    result = await auth_gw.revoke_device(
        user_id=session.user_id,
        requesting_device_id=session.device_id,
        target_device_id=device_id,
        reason=body.reason,
    )

    if result.success:
        try:
            await msg_gw.notify_device_revoked(
                user_id=session.user_id,
                revoked_device_id=device_id,
            )
        except Exception:
            logger.warning(
                        "device.revoke.notify_failed",
                        revoked_device_id=device_id,
                        user_id=session.user_id,
                    )

    return RevokeDeviceResponseSchema(**dataclasses.asdict(result))

@router.put("/{device_id}/update-keys", response_model=UpdateDeviceKeysResponseSchema)
async def update_device_keys(
    device_id: str,
    body: UpdateDeviceKeysRequestSchema,
    session: SessionContext = Depends(get_current_session),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> UpdateDeviceKeysResponseSchema:
    result = await auth_gw.update_device_keys(
        user_id=session.user_id,
        device_id=device_id,
        identity_key_pub=body.identity_key_pub_bytes,
        init_key_pub=body.init_key_pub_bytes,
        credential_data=body.credential_data_bytes,
    )
    return UpdateDeviceKeysResponseSchema(**dataclasses.asdict(result))

@router.put("/{device_id}/rename", response_model=RenameDeviceResponseSchema)
async def rename_device(
    device_id: str,
    body: RenameDeviceRequestSchema,
    session: SessionContext = Depends(get_current_session),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> RenameDeviceResponseSchema:
    result = await auth_gw.rename_device(
        user_id=session.user_id,
        requesting_device_id=session.device_id,
        target_device_id=device_id,
        new_name=body.new_name,
    )
    return RenameDeviceResponseSchema(**dataclasses.asdict(result))

@router.post("/{device_id}/verify", response_model=VerifyDeviceResponseSchema)
async def verify_device(
    device_id: str,
    body: VerifyDeviceRequestSchema,
    session: SessionContext = Depends(get_current_session),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> VerifyDeviceResponseSchema:
    result = await auth_gw.verify_device(
        device_id=device_id,
        signature=body.signature_bytes,
    )
    return VerifyDeviceResponseSchema(**dataclasses.asdict(result))

@router.post("/primary/transfer", response_model=TransferPrimaryResponseSchema)
async def transfer_primary(
    body: TransferPrimaryRequestSchema,
    session: SessionContext = Depends(require_device_type("primary")),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
) -> TransferPrimaryResponseSchema:
    result = await auth_gw.transfer_primary(
        user_id=session.user_id,
        requesting_device_id=session.device_id,
        target_device_id=body.target_device_id,
    )
    return TransferPrimaryResponseSchema(**dataclasses.asdict(result))

@router.post("/bulk-revoke", response_model=BulkRevokeDevicesResponseSchema)
async def bulk_revoke_devices(
    body: BulkRevokeDevicesRequestSchema,
    session: SessionContext = Depends(require_device_type("primary")),
    auth_gw: IAuthGateway = Depends(get_auth_gateway),
    msg_gw: IMessagingGateway = Depends(get_messaging_gateway),
) -> BulkRevokeDevicesResponseSchema:
    result = await auth_gw.bulk_revoke_devices(
        user_id=session.user_id,
        requesting_device_id=session.device_id,
        target_device_ids=body.device_ids,
        reason=body.reason,
    )

    if result.success:
        for revoked_id in result.revoked_device_ids:
            try:
                await msg_gw.notify_device_revoked(
                    user_id=session.user_id,
                    revoked_device_id=revoked_id,
                )
            except Exception:
                logger.warning(
                        "device.bulk_revoke.notify_failed",
                        revoked_device_id=revoked_id,
                        user_id=session.user_id,
                    )

    return BulkRevokeDevicesResponseSchema(**dataclasses.asdict(result))
