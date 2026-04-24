from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import List, Optional


@dataclass
class UserBriefProfile:
    user_id: str = ""
    username: str = ""
    user_public_key: str = ""
    bio: str = ""
    avatar_media_id: str = ""


@dataclass
class ContactEntry:
    contact_user_id: str = ""
    is_favorite: bool = False
    created_at: int = 0
    profile: Optional[UserBriefProfile] = None


@dataclass
class BlockedEntry:
    blocked_user_id: str = ""
    blocked_at: int = 0
    profile: Optional[UserBriefProfile] = None


@dataclass
class AddContactResult:
    contact: ContactEntry = field(default_factory=ContactEntry)


@dataclass
class RemoveContactResult:
    success: bool = False


@dataclass
class GetContactsResult:
    contacts: List[ContactEntry] = field(default_factory=list)
    total_count: int = 0


@dataclass
class UpdateContactResult:
    contact: ContactEntry = field(default_factory=ContactEntry)


@dataclass
class BlockUserResult:
    success: bool = False
    created_at: int = 0


@dataclass
class UnblockUserResult:
    success: bool = False


@dataclass
class GetBlockedUsersResult:
    blocked_users: List[BlockedEntry] = field(default_factory=list)
    total_count: int = 0


@dataclass
class IsBlockedResult:
    is_blocked: bool = False


@dataclass
class ContactsHealthResult:
    status: str = "degraded"
    version: str = "1.0.0"


@dataclass
class PurgeUserResult:
    contacts_deleted: int = 0
    blocked_deleted: int = 0


class IContactsGateway(ABC):
    @abstractmethod
    async def add_contact(self, user_id: str, user_public_key: str) -> AddContactResult: ...

    @abstractmethod
    async def remove_contact(self, user_id: str, contact_user_id: str) -> RemoveContactResult: ...

    @abstractmethod
    async def get_contacts(self, user_id: str, limit: int, offset: int) -> GetContactsResult: ...

    @abstractmethod
    async def update_contact(
        self, user_id: str, contact_user_id: str, is_favorite: Optional[bool]
    ) -> UpdateContactResult: ...

    @abstractmethod
    async def block_user(self, user_id: str, blocked_user_id: str) -> BlockUserResult: ...

    @abstractmethod
    async def unblock_user(self, user_id: str, blocked_user_id: str) -> UnblockUserResult: ...

    @abstractmethod
    async def get_blocked_users(self, user_id: str, limit: int, offset: int) -> GetBlockedUsersResult: ...

    @abstractmethod
    async def is_blocked(self, user_id: str, blocked_user_id: str) -> IsBlockedResult: ...

    @abstractmethod
    async def health_check(self) -> ContactsHealthResult: ...

    @abstractmethod
    async def purge_user(self, user_id: str) -> PurgeUserResult:
        """Account-deletion fanout: remove `user_id` from every contact list
        and unblock relation in either direction. Idempotent."""
        ...
