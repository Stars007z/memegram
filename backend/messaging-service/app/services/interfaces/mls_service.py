from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional
import uuid


@dataclass
class KeyPackageResult:
    key_package_data: bytes
    key_package_ref: bytes


@dataclass
class UserDeviceKeyPackageResult:
    device_id: uuid.UUID
    key_package_data: bytes
    key_package_ref: bytes


@dataclass
class CommitResult:
    new_epoch: int
    committed_at: float


@dataclass
class WelcomeEntryResult:
    id: uuid.UUID
    conversation_id: uuid.UUID
    welcome_data: bytes
    created_at: float


@dataclass
class CommitEntryResult:
    epoch: int
    commit_data: bytes
    created_at: float


class IMlsService(ABC):

    @abstractmethod
    async def upload_key_packages(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        key_packages: list[bytes],
    ) -> int:
        """Returns count of uploaded packages."""
        ...

    @abstractmethod
    async def get_key_package(
        self,
        target_user_id: uuid.UUID,
        target_device_id: uuid.UUID,
    ) -> KeyPackageResult:
        ...

    @abstractmethod
    async def get_key_packages_count(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
    ) -> int:
        ...

    @abstractmethod
    async def get_key_packages_for_user(
        self,
        target_user_id: uuid.UUID,
    ) -> list[UserDeviceKeyPackageResult]:
        """Fetch one key package per active device of the given user."""
        ...

    @abstractmethod
    async def commit_group_change(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        conversation_id: uuid.UUID,
        commit_data: bytes,
        new_epoch: int,
        welcome_messages: Optional[list[tuple[uuid.UUID, bytes]]] = None,
        ratchet_tree: Optional[bytes] = None,
        removed_device_ids: Optional[list[uuid.UUID]] = None,
    ) -> CommitResult:
        ...

    @abstractmethod
    async def get_pending_welcomes(
        self,
        device_id: uuid.UUID,
    ) -> list[WelcomeEntryResult]:
        ...

    @abstractmethod
    async def ack_welcome(
        self,
        device_id: uuid.UUID,
        welcome_id: uuid.UUID,
    ) -> bool:
        ...

    @abstractmethod
    async def get_pending_commits(
        self,
        conversation_id: uuid.UUID,
        since_epoch: int,
    ) -> list[CommitEntryResult]:
        ...

    @abstractmethod
    async def notify_device_revoked(
        self,
        user_id: uuid.UUID,
        revoked_device_id: uuid.UUID,
    ) -> int:
        """Returns count of notified conversations."""
        ...
