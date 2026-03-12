import grpc
from app.config import settings

_channel: grpc.aio.Channel | None = None


async def get_grpc_channel() -> grpc.aio.Channel:
    global _channel
    if _channel is None:
        _channel = grpc.aio.insecure_channel(settings.AUTH_GRPC_ADDRESS)
    return _channel


async def close_grpc_channel() -> None:
    global _channel
    if _channel is not None:
        await _channel.close()
        _channel = None