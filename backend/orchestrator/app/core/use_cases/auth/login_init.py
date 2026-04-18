from app.core.interfaces.auth_gateway import IAuthGateway, LoginInitResult

class LoginInitUseCase:
    def __init__(self, auth_gateway: IAuthGateway):
        self._gateway = auth_gateway

    async def execute(self, device_id: str) -> LoginInitResult:
        return await self._gateway.login_init(device_id)