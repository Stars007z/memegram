import grpc
import grpc.aio
from app.config import settings

_channel: grpc.aio.Channel | None = None


async def get_grpc_channel() -> grpc.aio.Channel:
    global _channel

    if _channel is None:
        _channel = _create_channel()
        return _channel

    state = _channel.get_state(try_to_connect=False)
    if state == grpc.ChannelConnectivity.SHUTDOWN:
        _channel = _create_channel()
    return _channel


def _create_channel() -> grpc.aio.Channel:
    return grpc.aio.insecure_channel(
        settings.AUTH_GRPC_ADDRESS,
        options=[
            ("grpc.initial_reconnect_backoff_ms", 500),
            ("grpc.max_reconnect_backoff_ms", 5000),
            # Keepalive: пингуем сервер каждые 30с, чтобы не протух idle-коннект
            ("grpc.keepalive_time_ms", 30_000),
            ("grpc.keepalive_timeout_ms", 10_000),
            ("grpc.keepalive_permit_without_calls", True),
        ],
    )


async def close_grpc_channel() -> None:
    global _channel
    if _channel is not None:
        await _channel.close()
        _channel = None