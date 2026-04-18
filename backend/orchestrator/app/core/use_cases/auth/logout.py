from app.core.interfaces.auth_gateway import IAuthGateway, LogoutResult


class LogoutUseCase:
    def __init__(self, auth_gateway: IAuthGateway):
        self._gateway = auth_gateway

    async def execute(self, access_token: str) -> LogoutResult:
        return await self._gateway.logout(access_token)
