from abc import ABC, abstractmethod
import uuid

import grpc

from app.generated import auth_pb2, auth_pb2_grpc


class IAuthClient(ABC):

    @abstractmethod
    async def get_active_device_ids(self, user_id: uuid.UUID) -> list[uuid.UUID]:
        """Return IDs of all active (non-revoked) devices for a user."""
        ...


class GrpcAuthClient(IAuthClient):

    def __init__(self, channel: grpc.aio.Channel) -> None:
        self._stub = auth_pb2_grpc.AuthServiceStub(channel)

    async def get_active_device_ids(self, user_id: uuid.UUID) -> list[uuid.UUID]:
        try:
            response = await self._stub.GetDevices(
                auth_pb2.GetDevicesRequest(user_id=str(user_id)),
                timeout=5,
            )
            return [
                uuid.UUID(d.id)
                for d in response.devices
                if d.is_active and d.revoked_at == 0
            ]
        except grpc.RpcError:
            return []
