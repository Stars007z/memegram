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


@dataclass
class InitiateUploadResult:
    item_id: str
    upload_url: str
    expires_at: int


@dataclass
class ConfirmUploadResult:
    success: bool


class IItemStorageGateway(ABC):
    @abstractmethod
    async def health_check(self) -> ItemStorageHealthResult: ...

    @abstractmethod
    async def get_download_url(
        self,
        item_id: str,
        requester_user_id: str,
    ) -> DownloadUrlResult: ...

    @abstractmethod
    async def initiate_upload(
        self,
        owner_user_id: str,
        item_type: str,
        mime_type: str,
        size_bytes: int,
    ) -> InitiateUploadResult: ...

    @abstractmethod
    async def confirm_upload(
        self,
        owner_user_id: str,
        item_id: str,
    ) -> ConfirmUploadResult: ...

    @abstractmethod
    async def delete_item(
        self,
        owner_user_id: str,
        item_id: str,
    ) -> bool: ...
