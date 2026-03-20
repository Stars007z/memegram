import grpc
import grpc.aio
from app.config import settings

_channel: grpc.aio.Channel | None = None

_user_channel: grpc.aio.Channel | None = None

def _create_user_channel() -> grpc.aio.Channel:
    return grpc.aio.insecure_channel(
        settings.USER_GRPC_ADDRESS,
        options=[
            ("grpc.initial_reconnect_backoff_ms", 500),
            ("grpc.max_reconnect_backoff_ms", 5000),
        ],
    )

async def get_user_grpc_channel() -> grpc.aio.Channel:
    global _user_channel
    if _user_channel is None:
        _user_channel = _create_user_channel()
        return _user_channel
    state = _user_channel.get_state(try_to_connect=False)
    if state == grpc.ChannelConnectivity.SHUTDOWN:
        _user_channel = _create_user_channel()
    return _user_channel

async def close_grpc_channel():
    global channel, _user_channel
    if channel:
        await channel.close()
    if _user_channel:
        await _user_channel.close()


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
            ("grpc.keepalive_time_ms", 300_000),
            ("grpc.keepalive_timeout_ms", 10_000),
            ("grpc.keepalive_permit_without_calls", True),
        ],
    )

