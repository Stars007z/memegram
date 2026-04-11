from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional


@dataclass
class ItemStorageHealthResult:
    status: str
    db_status: str
    s3_status: str
    version: str


@dataclass
class DownloadUrlResult:
    download_url: str
    expires_at: int
    mime_type: str


class IItemStorageGateway(ABC):
    @abstractmethod
    async def health_check(self) -> ItemStorageHealthResult: ...

    @abstractmethod
    async def get_download_url(
        self, item_id: str, requester_user_id: str,
    ) -> DownloadUrlResult: ...
