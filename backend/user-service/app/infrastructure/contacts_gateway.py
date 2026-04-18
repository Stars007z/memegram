"""gRPC gateway to contacts-service.

Used by user-service to enforce profile privacy:
- if A blocked B, B must not see A's profile (and vice versa).

Fail-open on transport errors so a contacts-service outage does not break
unrelated user reads.
"""

import os
from typing import Optional

import grpc

from app.logging_config import get_logger

logger = get_logger(__name__)


class ContactsGateway:

    def __init__(self):
        host = os.getenv("CONTACTS_GRPC_HOST", "contacts-service")
        port = os.getenv("CONTACTS_GRPC_PORT", "50053")
        self._address = f"{host}:{port}"
        self._channel: Optional[grpc.aio.Channel] = None

    def _get_channel(self) -> grpc.aio.Channel:
        # Lazily create a single channel for the gateway lifetime.
        if self._channel is None:
            self._channel = grpc.aio.insecure_channel(self._address)
        return self._channel

    async def is_contact(self, owner_user_id: str, contact_user_id: str) -> bool:
        try:
            from app.generated import contacts_pb2, contacts_pb2_grpc
            stub = contacts_pb2_grpc.ContactsServiceStub(self._get_channel())
            resp = await stub.IsContact(
                contacts_pb2.IsContactRequest(
                    user_id=owner_user_id,
                    contact_user_id=contact_user_id,
                ),
                timeout=2.0,
            )
            return bool(resp.is_contact)
        except Exception as e:
            logger.warning("contacts.IsContact failed: %s", str(e))
            return False

    async def is_blocked(self, user_id: str, blocked_user_id: str) -> bool:
        """Return True if user_id has blocked blocked_user_id."""
        try:
            from app.generated import contacts_pb2, contacts_pb2_grpc
            stub = contacts_pb2_grpc.ContactsServiceStub(self._get_channel())
            resp = await stub.IsBlocked(
                contacts_pb2.IsBlockedRequest(
                    user_id=user_id,
                    blocked_user_id=blocked_user_id,
                ),
                timeout=2.0,
            )
            return bool(resp.is_blocked)
        except Exception as e:
            logger.warning("contacts.IsBlocked failed: %s", str(e))
            return False

    async def is_blocked_either_way(self, user_a: str, user_b: str) -> bool:
        """Return True if either side blocked the other."""
        if user_a == user_b:
            return False
        if await self.is_blocked(user_a, user_b):
            return True
        return await self.is_blocked(user_b, user_a)
