import uuid
from abc import ABC, abstractmethod

import grpc

from app.generated import auth_pb2, auth_pb2_grpc

class IAuthClient(ABC):

    @abstractmethod
    async def get_active_device_ids(self, user_id: uuid.UUID) -> list[uuid.UUID]:
        """Return IDs of all active (non-revoked) devices for a user."""
        ...

    @abstractmethod
    async def is_device_active(self, user_id: uuid.UUID, device_id: uuid.UUID) -> bool:
        """Return whether a concrete device still belongs to the user and is active."""
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
            return [uuid.UUID(d.id) for d in response.devices if d.is_active and d.revoked_at == 0]
        except grpc.RpcError:
            return []

    async def is_device_active(self, user_id: uuid.UUID, device_id: uuid.UUID) -> bool:
        try:
            response = await self._stub.GetDevice(
                auth_pb2.GetDeviceRequest(user_id=str(user_id), device_id=str(device_id)),
                timeout=5,
            )
            return response.is_active and response.revoked_at == 0
        except grpc.RpcError:
            return False
