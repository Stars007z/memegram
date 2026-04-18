from fastapi import APIRouter, Depends

from app.api.dependencies import get_item_storage_gateway, get_current_session
from app.api.v1.item_storage.schemas import (
    ItemStorageHealthResponseSchema,
    InitiateUploadRequestSchema,
    InitiateUploadResponseSchema,
    ConfirmUploadResponseSchema,
    DownloadUrlResponseSchema,
)
from app.core.interfaces.item_storage_gateway import IItemStorageGateway
from app.core.session_context import SessionContext

router = APIRouter(prefix="/item-storage", tags=["item-storage"])

@router.get("/health", response_model=ItemStorageHealthResponseSchema)
async def item_storage_health(
    gw: IItemStorageGateway = Depends(get_item_storage_gateway),
):
    result = await gw.health_check()
    return ItemStorageHealthResponseSchema(**result.__dict__)

@router.post("/upload/initiate", response_model=InitiateUploadResponseSchema)
async def initiate_upload(
    body: InitiateUploadRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IItemStorageGateway = Depends(get_item_storage_gateway),
):
    result = await gw.initiate_upload(
        owner_user_id=session.user_id,
        item_type=body.item_type,
        mime_type=body.mime_type,
        size_bytes=body.size_bytes,
    )
    return InitiateUploadResponseSchema(
        item_id=result.item_id,
        upload_url=result.upload_url,
        expires_at=result.expires_at,
    )

@router.post("/upload/{item_id}/confirm", response_model=ConfirmUploadResponseSchema)
async def confirm_upload(
    item_id: str,
    session: SessionContext = Depends(get_current_session),
    gw: IItemStorageGateway = Depends(get_item_storage_gateway),
):
    result = await gw.confirm_upload(
        owner_user_id=session.user_id,
        item_id=item_id,
    )
    return ConfirmUploadResponseSchema(success=result.success, item_id=item_id)

@router.get("/{item_id}/download", response_model=DownloadUrlResponseSchema)
async def get_download_url(
    item_id: str,
    session: SessionContext = Depends(get_current_session),
    gw: IItemStorageGateway = Depends(get_item_storage_gateway),
):
    result = await gw.get_download_url(
        item_id=item_id,
        requester_user_id=session.user_id,
    )
    return DownloadUrlResponseSchema(
        download_url=result.download_url,
        expires_at=result.expires_at,
        mime_type=result.mime_type,
    )
