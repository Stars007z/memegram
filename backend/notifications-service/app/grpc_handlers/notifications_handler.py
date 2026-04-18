"""Main gRPC servicer — delegates to sub-handlers."""

from app.container import Container
from app.generated import notifications_pb2_grpc
from app.grpc_handlers.health_handler import HealthHandler
from app.grpc_handlers.token_handler import TokenHandler


class NotificationsHandler(notifications_pb2_grpc.NotificationsServiceServicer):

    def __init__(self, container: Container) -> None:
        self._tokens = TokenHandler(container)
        self._health = HealthHandler(container)

    async def RegisterPushToken(self, request, context):
        return await self._tokens.register_push_token(request, context)

    async def UnregisterPushToken(self, request, context):
        return await self._tokens.unregister_push_token(request, context)

    async def HealthCheck(self, request, context):
        return await self._health.health_check(request, context)
