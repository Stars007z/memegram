import grpc

from app.config import Settings
from app.core.interfaces.item_storage_gateway import (
    IItemStorageGateway,
    ItemStorageHealthResult,
    DownloadUrlResult,
    InitiateUploadResult,
    ConfirmUploadResult,
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

    async def initiate_upload(
        self,
        owner_user_id: str,
        item_type: str,
        mime_type: str,
        size_bytes: int,
    ) -> InitiateUploadResult:
        try:
            resp = await self._stub().InitiateUpload(
                item_storage_pb2.InitiateUploadRequest(
                    owner_user_id=owner_user_id,
                    item_type=item_type,
                    mime_type=mime_type,
                    size_bytes=size_bytes,
                ),
                timeout=self._settings.ITEM_STORAGE_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return InitiateUploadResult(
            item_id=resp.item_id,
            upload_url=resp.upload_url,
            expires_at=resp.expires_at,
        )

    async def confirm_upload(
        self, owner_user_id: str, item_id: str,
    ) -> ConfirmUploadResult:
        try:
            resp = await self._stub().ConfirmUpload(
                item_storage_pb2.ConfirmUploadRequest(
                    owner_user_id=owner_user_id,
                    item_id=item_id,
                ),
                timeout=self._settings.ITEM_STORAGE_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return ConfirmUploadResult(success=resp.success)
