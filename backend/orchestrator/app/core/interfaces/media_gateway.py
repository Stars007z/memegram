from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass
class MediaHealthResult:
    status: str
    db_status: str
    s3_status: str
    version: str


class IMediaGateway(ABC):
    @abstractmethod
    async def health_check(self) -> MediaHealthResult: ...

    @abstractmethod
    async def delete_object_by_media_id(self, media_id: str) -> bool:
        """Delete the S3 object + DB row identified by `media_id`.

        Idempotent: returns False if the object is unknown or already
        deleted. Used by the account-deletion fanout to clean up profile,
        background and chat-styling media objects.
        """
        ...
