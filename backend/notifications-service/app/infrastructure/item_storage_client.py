"""gRPC client for item-storage-service (GetDownloadUrl)."""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass

import grpc.aio

from app.generated import item_storage_pb2, item_storage_pb2_grpc

logger = logging.getLogger(__name__)


@dataclass
class DownloadUrlResult:
    download_url: str
    expires_at: int
    mime_type: str


class IItemStorageClient(ABC):
    @abstractmethod
    async def get_download_url(self, item_id: str, requester_user_id: str) -> DownloadUrlResult | None:
        ...


class GrpcItemStorageClient(IItemStorageClient):

    def __init__(self, channel: grpc.aio.Channel) -> None:
        self._channel = channel

    def _stub(self) -> item_storage_pb2_grpc.ItemStorageServiceStub:
        return item_storage_pb2_grpc.ItemStorageServiceStub(self._channel)

    async def get_download_url(self, item_id: str, requester_user_id: str) -> DownloadUrlResult | None:
        try:
            resp = await self._stub().GetDownloadUrl(
                item_storage_pb2.GetDownloadUrlRequest(
                    item_id=item_id,
                    requester_user_id=requester_user_id,
                ),
                timeout=5.0,
            )
            return DownloadUrlResult(
                download_url=resp.download_url,
                expires_at=resp.expires_at,
                mime_type=resp.mime_type,
            )
        except grpc.RpcError as e:
            logger.error("item_storage.GetDownloadUrl failed: %s", e.details())
            return None
