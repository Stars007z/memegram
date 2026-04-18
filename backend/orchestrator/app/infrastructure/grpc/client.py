import grpc
import grpc.aio

from app.config import Settings

_CHANNEL_OPTIONS = [
    ("grpc.initial_reconnect_backoff_ms", 500),
    ("grpc.max_reconnect_backoff_ms", 5000),
    ("grpc.keepalive_time_ms", 300_000),
    ("grpc.keepalive_timeout_ms", 10_000),
    ("grpc.keepalive_permit_without_calls", True),
]


class GrpcChannelManager:
    """Manages lazy-created gRPC channels keyed by service name."""

    def __init__(self, settings: Settings):
        self._addresses: dict[str, str] = {
            "auth": settings.AUTH_GRPC_ADDRESS,
            "user": settings.USER_GRPC_ADDRESS,
            "contacts": settings.CONTACTS_GRPC_ADDRESS,
            "messaging": settings.MESSAGING_GRPC_ADDRESS,
            "media": settings.MEDIA_GRPC_ADDRESS,
            "item_storage": settings.ITEM_STORAGE_GRPC_ADDRESS,
            "notifications": settings.NOTIFICATIONS_GRPC_ADDRESS,
        }
        self._channels: dict[str, grpc.aio.Channel] = {}

    def get(self, service: str) -> grpc.aio.Channel:
        channel = self._channels.get(service)
        if channel is None or channel.get_state(try_to_connect=False) == grpc.ChannelConnectivity.SHUTDOWN:
            channel = grpc.aio.insecure_channel(self._addresses[service], options=_CHANNEL_OPTIONS)
            self._channels[service] = channel
        return channel

    async def close(self) -> None:
        for ch in self._channels.values():
            await ch.close()
        self._channels.clear()
