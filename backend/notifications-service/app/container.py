"""DI container for notifications-service."""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncGenerator

import redis.asyncio as aioredis
from sqlalchemy.ext.asyncio import AsyncSession

from app.database.session import get_session
from app.infrastructure.contacts_client import IContactsClient
from app.infrastructure.item_storage_client import IItemStorageClient
from app.infrastructure.messaging_client import IMessagingClient
from app.infrastructure.user_client import IUserClient
from app.services.event_consumer import EventConsumer


class RequestScope:
    """Per-gRPC-call scope: owns a DB session."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session


class Container:
    """Application-level DI container."""

    def __init__(
        self,
        own_redis: aioredis.Redis,
        messaging_redis: aioredis.Redis,
        messaging_client: IMessagingClient,
        user_client: IUserClient,
        item_storage_client: IItemStorageClient,
        contacts_client: IContactsClient,
    ) -> None:
        self._own_redis = own_redis
        self._messaging_redis = messaging_redis
        self._messaging_client = messaging_client
        self._user_client = user_client
        self._item_storage_client = item_storage_client
        self._contacts_client = contacts_client
        self._consumer: EventConsumer | None = None

    @property
    def event_consumer(self) -> EventConsumer:
        if self._consumer is None:
            self._consumer = EventConsumer(
                messaging_redis=self._messaging_redis,
                own_redis=self._own_redis,
                messaging_client=self._messaging_client,
                user_client=self._user_client,
                item_storage_client=self._item_storage_client,
                contacts_client=self._contacts_client,
            )
        return self._consumer

    @asynccontextmanager
    async def request_scope(self) -> AsyncGenerator[RequestScope, None]:
        async with get_session() as session:
            yield RequestScope(session=session)
