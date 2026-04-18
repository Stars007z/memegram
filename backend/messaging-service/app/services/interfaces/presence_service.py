import uuid
from abc import ABC, abstractmethod


class IPresenceService(ABC):

    @abstractmethod
    async def set_typing(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        conversation_id: uuid.UUID,
        is_typing: bool,
    ) -> bool: ...

    @abstractmethod
    async def set_online(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
    ) -> bool: ...
