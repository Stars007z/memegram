from abc import ABC, abstractmethod
from typing import Any, AsyncIterator
import uuid

class IStreamService(ABC):

    @abstractmethod
    async def subscribe(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        conversation_ids: list[uuid.UUID],
    ) -> AsyncIterator[dict[str, Any]]:
        """Yields dicts with 'conversation_id' and 'event_type' + payload."""
        ...

    @abstractmethod
    async def publish_event(
        self,
        conversation_id: uuid.UUID,
        event: dict[str, Any],
    ) -> None:
        ...
