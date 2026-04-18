from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Optional

@dataclass
class UserProfileResult:
    id: str
    username: str
    avatar_media_id: Optional[str] = None
    profile_background_media_id: Optional[str] = None
    user_public_key: str = ""
    bio: str = ""
    last_active: int = 0
    is_deleted: bool = False

@dataclass
class UserSettingsResult:
    id: str
    user_id: str
    theme: str = "system"
    language: str = "en"
    is_translator_active: bool = False
    animations_enabled: bool = True
    account_auto_delete_after_days: int = 0
    profile_visible_to: str = "everybody"
    last_active_visible_to: str = "everybody"
    chat_background_media_id: Optional[str] = None
    top_bar_color: str = ""
    ringtone_media_id: Optional[str] = None
    ringtone_vibration_strength: int = 1
    notification_sound_media_id: Optional[str] = None
    notification_vibration_strength: int = 1
    top_bar_media_id: Optional[str] = None
    my_bubble_media_id: Optional[str] = None
    their_bubble_media_id: Optional[str] = None

@dataclass
class CreateUserResult:
    id: str
    username: str

@dataclass
class UpdateUserRequest:
    user_id: str
    bio: Optional[str] = None
    username: Optional[str] = None
    avatar_media_id: Optional[str] = None
    profile_background_media_id: Optional[str] = None

@dataclass
class UpdateUserSettingsRequest:
    user_id: str
    theme: Optional[str] = None
    language: Optional[str] = None
    is_translator_active: Optional[bool] = None
    animations_enabled: Optional[bool] = None
    account_auto_delete_after_days: Optional[int] = None
    profile_visible_to: Optional[str] = None
    last_active_visible_to: Optional[str] = None
    chat_background_media_id: Optional[str] = None
    top_bar_color: Optional[str] = None
    ringtone_media_id: Optional[str] = None
    ringtone_vibration_strength: Optional[int] = None
    notification_sound: Optional[str] = None
    notification_vibration_strength: Optional[int] = None
    top_bar_media_id: Optional[str] = None
    my_bubble_media_id: Optional[str] = None
    their_bubble_media_id: Optional[str] = None

@dataclass
class AutoDeleteResult:
    deleted_count: int
    user_ids: list[str] = field(default_factory=list)

class IUserGateway(ABC):
    @abstractmethod
    async def create_user(self, user_id: str, username: str) -> CreateUserResult: ...

    @abstractmethod
    async def get_user(self, user_id: str, requester_user_id: str) -> UserProfileResult: ...

    @abstractmethod
    async def get_user_by_public_key(
        self, user_public_key: str, requester_user_id: str
    ) -> UserProfileResult: ...

    @abstractmethod
    async def update_user(self, request: UpdateUserRequest) -> UserProfileResult: ...

    @abstractmethod
    async def delete_user(self, user_id: str) -> bool: ...

    @abstractmethod
    async def get_user_settings(self, user_id: str) -> UserSettingsResult: ...

    @abstractmethod
    async def update_user_settings(
        self, request: UpdateUserSettingsRequest
    ) -> UserSettingsResult: ...

    @abstractmethod
    async def health_check(self) -> dict: ...

    @abstractmethod
    async def update_last_active(self, user_id: str) -> bool: ...

    @abstractmethod
    async def check_and_process_auto_delete(self) -> AutoDeleteResult: ...
