"""gRPC handler for RegisterPushToken / UnregisterPushToken."""

import uuid

import grpc

from app.container import Container
from app.generated import notifications_pb2
from app.repositories.device_push_token_repo import DevicePushTokenRepository


class TokenHandler:

    def __init__(self, container: Container) -> None:
        self._container = container

    async def register_push_token(self, request, context):
        # Validate platform
        if request.platform not in ("ios", "android"):
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("platform must be 'ios' or 'android'")
            return notifications_pb2.RegisterPushTokenResponse(success=False)

        # Validate push_token
        if not request.push_token or not request.push_token.strip():
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("push_token is required")
            return notifications_pb2.RegisterPushTokenResponse(success=False)

        if not request.user_id or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and device_id are required")
            return notifications_pb2.RegisterPushTokenResponse(success=False)

        try:
            async with self._container.request_scope() as scope:
                repo = DevicePushTokenRepository(scope._session)
                await repo.upsert(
                    user_id=uuid.UUID(request.user_id),
                    device_id=uuid.UUID(request.device_id),
                    platform=request.platform,
                    push_token=request.push_token,
                )
            return notifications_pb2.RegisterPushTokenResponse(success=True)

        except ValueError as e:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details(f"Invalid UUID: {e}")
            return notifications_pb2.RegisterPushTokenResponse(success=False)

        except Exception as e:
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(f"Internal error: {e}")
            return notifications_pb2.RegisterPushTokenResponse(success=False)

    async def unregister_push_token(self, request, context):
        if not request.user_id or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and device_id are required")
            return notifications_pb2.UnregisterPushTokenResponse(success=False)

        try:
            async with self._container.request_scope() as scope:
                repo = DevicePushTokenRepository(scope._session)
                updated = await repo.deactivate(
                    user_id=uuid.UUID(request.user_id),
                    device_id=uuid.UUID(request.device_id),
                )
                if not updated:
                    context.set_code(grpc.StatusCode.NOT_FOUND)
                    context.set_details("Push token not found or permission denied")
                    return notifications_pb2.UnregisterPushTokenResponse(success=False)

            return notifications_pb2.UnregisterPushTokenResponse(success=True)

        except ValueError as e:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details(f"Invalid UUID: {e}")
            return notifications_pb2.UnregisterPushTokenResponse(success=False)

        except Exception as e:
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(f"Internal error: {e}")
            return notifications_pb2.UnregisterPushTokenResponse(success=False)
