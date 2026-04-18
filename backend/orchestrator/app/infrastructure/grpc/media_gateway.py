import grpc

from app.config import Settings
from app.core.interfaces.media_gateway import IMediaGateway, MediaHealthResult
from app.infrastructure.grpc.client import GrpcChannelManager
from app.infrastructure.grpc.errors import grpc_error_to_exception
from app.infrastructure.grpc.generated import media_pb2, media_pb2_grpc

_SERVICE = "Media service"


class GrpcMediaGateway(IMediaGateway):

    def __init__(self, channels: GrpcChannelManager, settings: Settings):
        self._channels = channels
        self._settings = settings

    def _stub(self) -> media_pb2_grpc.MediaServiceStub:
        return media_pb2_grpc.MediaServiceStub(self._channels.get("media"))

    async def health_check(self) -> MediaHealthResult:
        try:
            resp = await self._stub().HealthCheck(
                media_pb2.MediaHealthCheckRequest(),
                timeout=self._settings.MEDIA_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return MediaHealthResult(
            status=resp.status,
            db_status=resp.db_status,
            s3_status=resp.s3_status,
            version=resp.version,
        )
