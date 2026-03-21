from fastapi import APIRouter, Depends
from app.api.dependencies import get_user_gateway
from app.core.interfaces.user_gateway import IUserGateway, AutoDeleteResult

router = APIRouter(prefix="/admin", tags=["admin"])


@router.post(
    "/users/auto-delete",
    response_model=AutoDeleteResult,
    summary="Trigger auto-delete cron (call from external scheduler)",
)
async def trigger_auto_delete(
    gateway: IUserGateway = Depends(get_user_gateway),
) -> AutoDeleteResult:
    return await gateway.check_and_process_auto_delete()