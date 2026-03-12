from app.core.interfaces.auth_gateway import IAuthGateway, RegisterRequest, AuthResult


class RegisterUseCase:
    def __init__(self, auth_gateway: IAuthGateway):
        self._gateway = auth_gateway

    async def execute(
        self,
        username: str,
        invite_code: str,
        device_id: str,
        device_name: str,
        identity_key_pub: bytes,
        init_key_pub: bytes,
        credential_data: bytes,
    ) -> AuthResult:
        request = RegisterRequest(
            username=username,
            invite_code=invite_code,
            device_id=device_id,
            device_name=device_name,
            identity_key_pub=identity_key_pub,
            init_key_pub=init_key_pub,
            credential_data=credential_data,
        )
        return await self._gateway.register(request)