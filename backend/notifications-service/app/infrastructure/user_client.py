"""gRPC client for user-service (GetUsersBatch)."""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass

import grpc.aio

from app.generated import user_pb2, user_pb2_grpc

logger = logging.getLogger(__name__)


@dataclass
class UserInfo:
    user_id: str
    display_name: str
    username: str
    avatar_media_id: str


class IUserClient(ABC):
    @abstractmethod
    async def get_users_batch(self, user_ids: list[str]) -> list[UserInfo]: ...


class GrpcUserClient(IUserClient):

    def __init__(self, channel: grpc.aio.Channel) -> None:
        self._channel = channel

    def _stub(self) -> user_pb2_grpc.UserServiceStub:
        return user_pb2_grpc.UserServiceStub(self._channel)

    async def get_users_batch(self, user_ids: list[str]) -> list[UserInfo]:
        try:
            resp = await self._stub().GetUsersBatch(
                user_pb2.GetUsersBatchRequest(user_ids=user_ids),
                timeout=5.0,
            )
            return [
                UserInfo(
                    user_id=u.user_id,
                    display_name=u.display_name,
                    username=u.username,
                    avatar_media_id=u.avatar_media_id,
                )
                for u in resp.users
            ]
        except grpc.RpcError as e:
            logger.error("user.GetUsersBatch failed: %s", e.details())
            return []
