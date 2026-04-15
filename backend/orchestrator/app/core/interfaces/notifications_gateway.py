from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass
class RegisterPushTokenResult:
    success: bool


@dataclass
class UnregisterPushTokenResult:
    success: bool


@dataclass
class NotificationsHealthResult:
    status: str
    db_status: str
    redis_status: str
    fcm_status: str
    apns_status: str
    version: str


class INotificationsGateway(ABC):
    @abstractmethod
    async def register_push_token(
        self,
        user_id: str,
        device_id: str,
        platform: str,
        push_token: str,
    ) -> RegisterPushTokenResult: ...

    @abstractmethod
    async def unregister_push_token(
        self, user_id: str, device_id: str,
    ) -> UnregisterPushTokenResult: ...

    @abstractmethod
    async def health_check(self) -> NotificationsHealthResult: ...
