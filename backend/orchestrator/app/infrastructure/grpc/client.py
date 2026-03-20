import grpc
import grpc.aio
from app.config import settings

_auth_channel: grpc.aio.Channel = None
_user_channel: grpc.aio.Channel = None
_contacts_channel: grpc.aio.Channel = None

_CHANNEL_OPTIONS = [
    ("grpc.initial_reconnect_backoff_ms", 500),
    ("grpc.max_reconnect_backoff_ms", 5000),
    ("grpc.keepalive_time_ms", 300_000),
    ("grpc.keepalive_timeout_ms", 10_000),
    ("grpc.keepalive_permit_without_calls", True),
]


def _create_channel(address: str) -> grpc.aio.Channel:
    return grpc.aio.insecure_channel(address, options=_CHANNEL_OPTIONS)


async def get_grpc_channel() -> grpc.aio.Channel:
    global _auth_channel
    if _auth_channel is None:
        _auth_channel = _create_channel(settings.AUTH_GRPC_ADDRESS)
        return _auth_channel
    if _auth_channel.get_state(try_to_connect=False) == grpc.ChannelConnectivity.SHUTDOWN:
        _auth_channel = _create_channel(settings.AUTH_GRPC_ADDRESS)
    return _auth_channel


async def get_user_grpc_channel() -> grpc.aio.Channel:
    global _user_channel
    if _user_channel is None:
        _user_channel = _create_channel(settings.USER_GRPC_ADDRESS)
        return _user_channel
    if _user_channel.get_state(try_to_connect=False) == grpc.ChannelConnectivity.SHUTDOWN:
        _user_channel = _create_channel(settings.USER_GRPC_ADDRESS)
    return _user_channel


async def get_contacts_grpc_channel() -> grpc.aio.Channel:
    global _contacts_channel
    if _contacts_channel is None:
        _contacts_channel = _create_channel(settings.CONTACTS_GRPC_ADDRESS)
        return _contacts_channel
    if _contacts_channel.get_state(try_to_connect=False) == grpc.ChannelConnectivity.SHUTDOWN:
        _contacts_channel = _create_channel(settings.CONTACTS_GRPC_ADDRESS)
    return _contacts_channel


async def close_grpc_channels() -> None:
    global _auth_channel, _user_channel, _contacts_channel
    for ch in (_auth_channel, _user_channel, _contacts_channel):
        if ch is not None:
            await ch.close()
    _auth_channel = None
    _user_channel = None
    _contacts_channel = None
