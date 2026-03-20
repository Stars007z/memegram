from app.core.interfaces.auth_gateway import IAuthGateway, RegisterRequest, AuthResult
from app.core.interfaces.user_gateway import IUserGateway
from app.exceptions import GatewayError


class RegisterUseCase:
    def __init__(self, auth_gateway: IAuthGateway, user_gateway: IUserGateway):
        self.auth_gateway = auth_gateway
        self.user_gateway = user_gateway

    async def execute(self, request: RegisterRequest) -> AuthResult:
        auth_result = await self.auth_gateway.register(request)

        try:
            await self.user_gateway.create_user(
                user_id=auth_result.user_id,
                username=request.username,
            )
        except Exception as e:
            try:
                await self.auth_gateway.revoke_registration(
                    user_id=auth_result.user_id,
                    access_token=auth_result.access_token,
                )
            except Exception:
                pass

            raise GatewayError(
                f"User profile creation failed after auth registration: {e}. "
                f"Auth session has been revoked. Please retry registration."
            )

        return auth_result
