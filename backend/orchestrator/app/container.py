from app.config import Settings
from app.core.interfaces.auth_gateway import IAuthGateway
from app.core.interfaces.user_gateway import IUserGateway
from app.core.interfaces.contacts_gateway import IContactsGateway
from app.core.interfaces.messaging_gateway import IMessagingGateway
from app.core.interfaces.media_gateway import IMediaGateway
from app.core.interfaces.item_storage_gateway import IItemStorageGateway
from app.core.interfaces.notifications_gateway import INotificationsGateway
from app.infrastructure.grpc.client import GrpcChannelManager
from app.infrastructure.grpc.auth_gateway import GrpcAuthGateway
from app.infrastructure.grpc.user_gateway import GrpcUserGateway
from app.infrastructure.grpc.contacts_gateway import GrpcContactsGateway
from app.infrastructure.grpc.messaging_gateway import GrpcMessagingGateway
from app.infrastructure.grpc.media_gateway import GrpcMediaGateway
from app.infrastructure.grpc.item_storage_gateway import GrpcItemStorageGateway
from app.infrastructure.grpc.notifications_gateway import GrpcNotificationsGateway

class Container:
    """IoC container — single source of truth for all gateway instances."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.channels = GrpcChannelManager(settings)

        self._auth_gw = GrpcAuthGateway(self.channels, settings)
        self._user_gw = GrpcUserGateway(self.channels, settings)
        self._contacts_gw = GrpcContactsGateway(self.channels, settings)
        self._messaging_gw = GrpcMessagingGateway(self.channels, settings)
        self._media_gw = GrpcMediaGateway(self.channels, settings)
        self._item_storage_gw = GrpcItemStorageGateway(self.channels, settings)
        self._notifications_gw = GrpcNotificationsGateway(self.channels, settings)

    @property
    def auth_gateway(self) -> IAuthGateway:
        return self._auth_gw

    @property
    def user_gateway(self) -> IUserGateway:
        return self._user_gw

    @property
    def contacts_gateway(self) -> IContactsGateway:
        return self._contacts_gw

    @property
    def messaging_gateway(self) -> IMessagingGateway:
        return self._messaging_gw

    @property
    def media_gateway(self) -> IMediaGateway:
        return self._media_gw

    @property
    def item_storage_gateway(self) -> IItemStorageGateway:
        return self._item_storage_gw

    @property
    def notifications_gateway(self) -> INotificationsGateway:
        return self._notifications_gw

    async def close(self) -> None:
        await self.channels.close()
