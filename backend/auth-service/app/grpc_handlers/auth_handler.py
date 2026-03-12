import grpc
from app.generated import auth_pb2, auth_pb2_grpc
from app.services.auth_service import AuthService
from app.database.redis import check_redis_health
from sqlalchemy.ext.asyncio import AsyncSession


class AuthHandler(auth_pb2_grpc.AuthServiceServicer):
    def __init__(self, get_session):
        self.get_session = get_session

    async def Register(self, request, context):
        """Регистрация нового пользователя"""
        if not request.invite_code or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("Missing required fields")
            return auth_pb2.AuthResponse()

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.register(
                    username=request.username,
                    invite_code=request.invite_code,
                    device_id=request.device_id,
                    device_name=request.device_name,
                    identity_key_pub=request.identity_key_pub,
                    init_key_pub=request.init_key_pub,
                    credential_data=request.credential_data,
                )
                return auth_pb2.AuthResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.AuthResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.AuthResponse()

    async def LoginInit(self, request, context):
        """Этап 1: Генерация challenge"""
        if not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("device_id is required")
            return auth_pb2.LoginInitResponse()

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.login_init(device_id=request.device_id)
                return auth_pb2.LoginInitResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.LoginInitResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.LoginInitResponse()

    async def LoginComplete(self, request, context):
        """Этап 2: Верификация и выдача токенов"""
        if not request.device_id or not request.challenge or not request.signature:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("Missing required fields")
            return auth_pb2.AuthResponse()

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.login_complete(
                    device_id=request.device_id,
                    challenge=request.challenge,
                    signature=request.signature,
                    device_name=request.device_name if request.device_name else None,
                )
                return auth_pb2.AuthResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.AuthResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.AuthResponse()

    async def Logout(self, request, context):
        """Завершение сессии"""
        if not request.access_token:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("access_token is required")
            return auth_pb2.LogoutResponse(success=False, message="Missing access token")

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.logout(access_token=request.access_token)
                return auth_pb2.LogoutResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.LogoutResponse(success=False, message=str(e))
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.LogoutResponse(success=False, message=str(e))

    async def HealthCheck(self, request, context):
        """Проверка работоспособности"""
        db_status = "unknown"
        redis_status = "unknown"

        try:
            async with self.get_session() as session:
                from sqlalchemy import text
                await session.execute(text("SELECT 1"))
                db_status = "connected"
        except Exception as e:
            db_status = f"failed: {str(e)}"

        try:
            redis_ok = await check_redis_health()
            redis_status = "connected" if redis_ok else "disconnected"
        except Exception as e:
            redis_status = f"failed: {str(e)}"

        return auth_pb2.HealthCheckResponse(
            status="ok" if db_status == "connected" and redis_status == "connected" else "degraded",
            db_status=db_status,
            redis_status=redis_status,
            version="1.0.0"
        )

    async def CreateInvite(self, request, context):
        """Создание нового инвайт-кода (админ)"""

        if not request.expires_in_days:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("expires_in_days is required")
            return auth_pb2.CreateInviteResponse()

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.create_invite(
                    expires_in_days=request.expires_in_days,
                    created_by_device_id=request.created_by_device_id if request.created_by_device_id else None
                )
                return auth_pb2.CreateInviteResponse(**result)

            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.CreateInviteResponse()
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.CreateInviteResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.CreateInviteResponse()