from typing import Optional

from app.core.interfaces.auth_gateway import AuthResult, IAuthGateway, LoginCompleteRequest


class LoginCompleteUseCase:
    def __init__(self, auth_gateway: IAuthGateway):
        self._gateway = auth_gateway

    async def execute(
        self,
        device_id: str,
        challenge: str,
        signature: bytes,
        device_name: Optional[str] = None,
    ) -> AuthResult:
        request = LoginCompleteRequest(
            device_id=device_id,
            challenge=challenge,
            signature=signature,
            device_name=device_name,
        )
        return await self._gateway.login_complete(request)
