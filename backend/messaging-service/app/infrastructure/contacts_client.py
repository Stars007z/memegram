import uuid
from abc import ABC, abstractmethod

import grpc

from app.generated import contacts_pb2, contacts_pb2_grpc


class IContactsClient(ABC):

    @abstractmethod
    async def is_blocked(self, user_id: uuid.UUID, blocked_user_id: uuid.UUID) -> bool: ...


class GrpcContactsClient(IContactsClient):

    def __init__(self, channel: grpc.aio.Channel):
        self._stub = contacts_pb2_grpc.ContactsServiceStub(channel)

    async def is_blocked(self, user_id: uuid.UUID, blocked_user_id: uuid.UUID) -> bool:
        try:
            response = await self._stub.IsBlocked(
                contacts_pb2.IsBlockedRequest(
                    user_id=str(user_id),
                    blocked_user_id=str(blocked_user_id),
                ),
                timeout=5,
            )
            return response.is_blocked
        except grpc.RpcError:
            return False
