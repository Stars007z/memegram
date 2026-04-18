import grpc

from app.config import Settings
from app.core.interfaces.notifications_gateway import (
    INotificationsGateway,
    NotificationsHealthResult,
    RegisterPushTokenResult,
    UnregisterPushTokenResult,
)
from app.infrastructure.grpc.client import GrpcChannelManager
from app.infrastructure.grpc.errors import grpc_error_to_exception
from app.infrastructure.grpc.generated import notifications_pb2, notifications_pb2_grpc

_SERVICE = "Notifications service"


class GrpcNotificationsGateway(INotificationsGateway):

    def __init__(self, channels: GrpcChannelManager, settings: Settings):
        self._channels = channels
        self._settings = settings

    def _stub(self) -> notifications_pb2_grpc.NotificationsServiceStub:
        return notifications_pb2_grpc.NotificationsServiceStub(
            self._channels.get("notifications"),
        )

    async def register_push_token(
        self,
        user_id: str,
        device_id: str,
        platform: str,
        push_token: str,
    ) -> RegisterPushTokenResult:
        try:
            resp = await self._stub().RegisterPushToken(
                notifications_pb2.RegisterPushTokenRequest(
                    user_id=user_id,
                    device_id=device_id,
                    platform=platform,
                    push_token=push_token,
                ),
                timeout=self._settings.NOTIFICATIONS_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return RegisterPushTokenResult(success=resp.success)

    async def unregister_push_token(
        self,
        user_id: str,
        device_id: str,
    ) -> UnregisterPushTokenResult:
        try:
            resp = await self._stub().UnregisterPushToken(
                notifications_pb2.UnregisterPushTokenRequest(
                    user_id=user_id,
                    device_id=device_id,
                ),
                timeout=self._settings.NOTIFICATIONS_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return UnregisterPushTokenResult(success=resp.success)

    async def health_check(self) -> NotificationsHealthResult:
        try:
            resp = await self._stub().HealthCheck(
                notifications_pb2.HealthCheckRequest(),
                timeout=self._settings.NOTIFICATIONS_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return NotificationsHealthResult(
            status=resp.status,
            db_status=resp.db_status,
            redis_status=resp.redis_status,
            fcm_status=resp.fcm_status,
            apns_status=resp.apns_status,
            version=resp.version,
        )
