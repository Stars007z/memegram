from typing import Optional
from app.core.interfaces.auth_gateway import IAuthGateway, CreateInviteResult


class CreateInviteUseCase:
    def __init__(self, auth_gateway: IAuthGateway):
        self._gateway = auth_gateway

    async def execute(
        self,
        expires_in_days: int,
        created_by_device_id: Optional[str] = None,
    ) -> CreateInviteResult:
        return await self._gateway.create_invite(
            expires_in_days=expires_in_days,
            created_by_device_id=created_by_device_id,
        )