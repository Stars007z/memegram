"""gRPC client for contacts-service (IsBlocked).

Used by notifications-service to suppress push notifications to recipients
who have blocked the sender.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

import grpc.aio

from app.generated import contacts_pb2, contacts_pb2_grpc
from app.logging_config import get_logger

logger = get_logger(__name__)


class IContactsClient(ABC):
    @abstractmethod
    async def is_blocked(self, user_id: str, blocked_user_id: str) -> bool: ...


class GrpcContactsClient(IContactsClient):

    def __init__(self, channel: grpc.aio.Channel) -> None:
        self._channel = channel

    def _stub(self) -> contacts_pb2_grpc.ContactsServiceStub:
        return contacts_pb2_grpc.ContactsServiceStub(self._channel)

    async def is_blocked(self, user_id: str, blocked_user_id: str) -> bool:
        try:
            resp = await self._stub().IsBlocked(
                contacts_pb2.IsBlockedRequest(
                    user_id=user_id,
                    blocked_user_id=blocked_user_id,
                ),
                timeout=5.0,
            )
            return bool(resp.is_blocked)
        except grpc.RpcError as e:

            logger.warning("contacts.IsBlocked failed: %s", e.details() if hasattr(e, "details") else str(e))
            return False
