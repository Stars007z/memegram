from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional
import uuid


@dataclass
class MessageResult:
    id: uuid.UUID
    sender_user_id: uuid.UUID
    sender_device_id: uuid.UUID
    type: str
    mls_ciphertext: bytes
    media_id: Optional[uuid.UUID]
    reply_to_message_id: Optional[uuid.UUID]
    mls_epoch: Optional[int]
    created_at: float
    edited_at: Optional[float]
    deleted_at: Optional[float]


@dataclass
class SendResult:
    message_id: uuid.UUID
    created_at: float


@dataclass
class MessageListResult:
    messages: list[MessageResult]
    has_more: bool


class IMessageService(ABC):

    @abstractmethod
    async def send_message(
        self,
        sender_user_id: uuid.UUID,
        sender_device_id: uuid.UUID,
        conversation_id: uuid.UUID,
        mls_ciphertext: bytes,
        type: str,
        client_message_id: uuid.UUID,
        media_id: Optional[uuid.UUID] = None,
        reply_to_message_id: Optional[uuid.UUID] = None,
    ) -> SendResult:
        ...

    @abstractmethod
    async def get_messages(
        self,
        user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        limit: int,
        before_message_id: Optional[uuid.UUID] = None,
    ) -> MessageListResult:
        ...

    @abstractmethod
    async def edit_message(
        self,
        user_id: uuid.UUID,
        message_id: uuid.UUID,
        new_mls_ciphertext: bytes,
    ) -> MessageResult:
        ...

    @abstractmethod
    async def delete_message(
        self,
        user_id: uuid.UUID,
        message_id: uuid.UUID,
        delete_for_everyone: bool,
    ) -> bool:
        ...

    @abstractmethod
    async def mark_as_read(
        self,
        user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        last_read_message_id: uuid.UUID,
    ) -> int:
        """Returns remaining unread count."""
        ...
