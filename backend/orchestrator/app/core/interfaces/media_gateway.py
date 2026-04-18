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
