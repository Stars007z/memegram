"""gRPC client for messaging-service (GetConversationMembers)."""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass

import grpc.aio

from app.generated import messaging_pb2, messaging_pb2_grpc

logger = logging.getLogger(__name__)


@dataclass
class MemberInfo:
    user_id: str
    role: str


class IMessagingClient(ABC):
    @abstractmethod
    async def get_conversation_members(self, conversation_id: str) -> list[MemberInfo]: ...


class GrpcMessagingClient(IMessagingClient):

    def __init__(self, channel: grpc.aio.Channel) -> None:
        self._channel = channel

    def _stub(self) -> messaging_pb2_grpc.MessagingServiceStub:
        return messaging_pb2_grpc.MessagingServiceStub(self._channel)

    async def get_conversation_members(self, conversation_id: str) -> list[MemberInfo]:
        try:
            resp = await self._stub().GetConversationMembers(
                messaging_pb2.GetConversationMembersRequest(
                    conversation_id=conversation_id,
                ),
                timeout=5.0,
            )
            return [MemberInfo(user_id=m.user_id, role=m.role) for m in resp.members]
        except grpc.RpcError as e:
            logger.error("messaging.GetConversationMembers failed: %s", e.details())
            return []
