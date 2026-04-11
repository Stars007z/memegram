import grpc

from app.config import Settings
from app.core.interfaces.item_storage_gateway import (
    IItemStorageGateway,
    ItemStorageHealthResult,
    DownloadUrlResult,
)
from app.infrastructure.grpc.errors import grpc_error_to_exception
from app.infrastructure.grpc.client import GrpcChannelManager
from app.infrastructure.grpc.generated import item_storage_pb2, item_storage_pb2_grpc

_SERVICE = "Item-storage service"


class GrpcItemStorageGateway(IItemStorageGateway):

    def __init__(self, channels: GrpcChannelManager, settings: Settings):
        self._channels = channels
        self._settings = settings

    def _stub(self) -> item_storage_pb2_grpc.ItemStorageServiceStub:
        return item_storage_pb2_grpc.ItemStorageServiceStub(
            self._channels.get("item_storage"),
        )

    async def health_check(self) -> ItemStorageHealthResult:
        try:
            resp = await self._stub().HealthCheck(
                item_storage_pb2.HealthCheckRequest(),
                timeout=self._settings.ITEM_STORAGE_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return ItemStorageHealthResult(
            status=resp.status,
            db_status=resp.db_status,
            s3_status=resp.s3_status,
            version=resp.version,
        )

    async def get_download_url(
        self, item_id: str, requester_user_id: str,
    ) -> DownloadUrlResult:
        try:
            resp = await self._stub().GetDownloadUrl(
                item_storage_pb2.GetDownloadUrlRequest(
                    item_id=item_id,
                    requester_user_id=requester_user_id,
                ),
                timeout=self._settings.ITEM_STORAGE_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return DownloadUrlResult(
            download_url=resp.download_url,
            expires_at=resp.expires_at,
            mime_type=resp.mime_type,
        )
