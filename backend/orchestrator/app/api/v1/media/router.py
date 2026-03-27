from fastapi import APIRouter, Depends

from app.api.dependencies import get_media_gateway
from app.api.v1.messaging.schemas import MediaHealthResponseSchema
from app.core.interfaces.media_gateway import IMediaGateway

router = APIRouter(prefix="/media", tags=["media"])


@router.get("/health", response_model=MediaHealthResponseSchema)
async def media_health(
    gw: IMediaGateway = Depends(get_media_gateway),
):
    result = await gw.health_check()
    return MediaHealthResponseSchema(**result.__dict__)
