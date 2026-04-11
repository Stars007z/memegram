from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Optional
import uuid


@dataclass
class MemberResult:
    user_id: uuid.UUID
    role: str
    joined_at: float


@dataclass
class MlsGroupResult:
    current_epoch: int
    cipher_suite: int


@dataclass
class ConversationResult:
    id: uuid.UUID
    type: str
    name: Optional[str]
    members: list[MemberResult]
    mls_group: Optional[MlsGroupResult]
    created_at: float


@dataclass
class ConversationSummaryResult:
    id: uuid.UUID
    type: str
    name: Optional[str]
    last_message_type: Optional[str]
    unread_count: int
    last_activity_at: float


@dataclass
class ConversationListResult:
    items: list[ConversationSummaryResult]
    next_cursor: Optional[str]


class IConversationService(ABC):

    @abstractmethod
    async def create_direct(
        self,
        initiator_user_id: uuid.UUID,
        initiator_device_id: uuid.UUID,
        recipient_user_id: uuid.UUID,
        welcome_messages: list[tuple[uuid.UUID, bytes]],
    ) -> ConversationResult:
        ...

    @abstractmethod
    async def create_group(
        self,
        creator_user_id: uuid.UUID,
        creator_device_id: uuid.UUID,
        name: str,
        members: list[tuple[uuid.UUID, list[tuple[uuid.UUID, bytes]]]],
    ) -> ConversationResult:
        ...

    @abstractmethod
    async def get_conversations(
        self,
        user_id: uuid.UUID,
        limit: int,
        cursor: Optional[str],
    ) -> ConversationListResult:
        ...

    @abstractmethod
    async def get_conversation(
        self,
        user_id: uuid.UUID,
        conversation_id: uuid.UUID,
    ) -> ConversationResult:
        ...

    @abstractmethod
    async def leave_conversation(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        conversation_id: uuid.UUID,
        commit_data: bytes,
    ) -> bool:
        ...

    @abstractmethod
    async def kick_member(
        self,
        caller_user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        target_user_id: uuid.UUID,
    ) -> bool:
        ...

    @abstractmethod
    async def update_member_role(
        self,
        caller_user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        target_user_id: uuid.UUID,
        new_role: str,
    ) -> bool:
        ...
