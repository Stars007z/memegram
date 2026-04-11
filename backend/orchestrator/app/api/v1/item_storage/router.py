from fastapi import APIRouter, Depends

from app.api.dependencies import get_item_storage_gateway
from app.api.v1.item_storage.schemas import ItemStorageHealthResponseSchema
from app.core.interfaces.item_storage_gateway import IItemStorageGateway

router = APIRouter(prefix="/item-storage", tags=["item-storage"])


@router.get("/health", response_model=ItemStorageHealthResponseSchema)
async def item_storage_health(
    gw: IItemStorageGateway = Depends(get_item_storage_gateway),
):
    result = await gw.health_check()
    return ItemStorageHealthResponseSchema(**result.__dict__)
