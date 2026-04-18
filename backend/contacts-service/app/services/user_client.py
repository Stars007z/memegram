"""gRPC-клиент для user-service."""
from __future__ import annotations

import grpc
import grpc.aio
from functools import lru_cache
from typing import Optional

from app.config import settings

# Импортируем после генерации proto
from app.generated import user_pb2, user_pb2_grpc


class UserBriefProfile:
    __slots__ = ("user_id", "username", "user_public_key", "bio", "avatar_media_id")

    def __init__(
        self,
        user_id: str,
        username: str,
        user_public_key: str = "",
        bio: str = "",
        avatar_media_id: str = "",
    ):
        self.user_id = user_id
        self.username = username
        self.user_public_key = user_public_key
        self.bio = bio
        self.avatar_media_id = avatar_media_id


_channel: Optional[grpc.aio.Channel] = None


def _get_channel() -> grpc.aio.Channel:
    global _channel
    if _channel is None:
        _channel = grpc.aio.insecure_channel(settings.USER_GRPC_ADDRESS)
    return _channel


async def close_user_channel() -> None:
    global _channel
    if _channel is not None:
        await _channel.close()
        _channel = None


class UserServiceClient:
    """Тонкая обёртка над gRPC-стабом user-service."""

    def __init__(self):
        self._stub = user_pb2_grpc.UserServiceStub(_get_channel())
        self._timeout = settings.USER_GRPC_TIMEOUT

    async def get_user_by_public_key(self, user_public_key: str, requester_user_id: str) -> Optional[str]:
        """Вернуть user_id по публичному ключу или None при NOT_FOUND."""
        try:
            resp: user_pb2.UserProfileResponse = await self._stub.GetUserByUserPublicKey(
                user_pb2.GetUserByUserPublicKeyRequest(
                    user_public_key=user_public_key,
                    requester_user_id=requester_user_id,
                ),
                timeout=self._timeout,
            )
            if resp.HasField("profile") and resp.profile.id:
                return resp.profile.id
            return None
        except grpc.aio.AioRpcError:
            return None

    async def user_exists(self, user_id: str) -> tuple[bool, bool]:
        """Вернуть (exists, is_deleted)."""
        try:
            resp: user_pb2.UserExistsResponse = await self._stub.UserExists(
                user_pb2.UserExistsRequest(user_id=user_id),
                timeout=self._timeout,
            )
            return resp.exists, resp.is_deleted
        except grpc.aio.AioRpcError:
            return False, False

    async def get_users_batch(self, user_ids: list[str]) -> dict[str, UserBriefProfile]:
        """Вернуть словарь {user_id: UserBriefProfile}.

        user-service GetUsersBatch returns proto UserBriefProfile with only
        {id, username, avatar_media_id, is_deleted}. user_public_key/bio
        are NOT available in the batch endpoint and remain empty here.
        """
        if not user_ids:
            return {}
        resp: user_pb2.GetUsersBatchResponse = await self._stub.GetUsersBatch(
            user_pb2.GetUsersBatchRequest(user_ids=user_ids),
            timeout=self._timeout,
        )
        result: dict[str, UserBriefProfile] = {}
        for p in resp.users:
            # NOTE: avatar_media_id is a plain (non-optional) string in canonical
            # proto, so we read it directly. Calling HasField on a plain scalar
            # raises ValueError in proto3, which previously silently broke avatars.
            result[p.id] = UserBriefProfile(
                user_id=p.id,
                username=p.username,
                user_public_key="",  # not provided by GetUsersBatch
                bio="",              # not provided by GetUsersBatch
                avatar_media_id=p.avatar_media_id or "",
            )
        return result


@lru_cache(maxsize=1)
def get_user_client() -> UserServiceClient:
    return UserServiceClient()
