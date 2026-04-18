"""
DI container — wires all dependencies together.

Container holds singleton (app-lifetime) resources: Redis, gRPC channels.
RequestScope is created per-request and lazily builds services that share a DB session.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncGenerator

import redis.asyncio as aioredis
from sqlalchemy.ext.asyncio import AsyncSession

from app.database.session import get_session
from app.infrastructure.auth_client import IAuthClient
from app.infrastructure.contacts_client import IContactsClient
from app.infrastructure.media_client import IMediaClient

from app.services.interfaces import (
    IConversationService,
    IMediaService,
    IMessageService,
    IMlsService,
    IPresenceService,
    IStreamService,
)


class RequestScope:
    """Per-request scope: owns a DB session, lazily builds services."""

    def __init__(
        self,
        session: AsyncSession,
        redis: aioredis.Redis,
        auth: IAuthClient,
        contacts: IContactsClient,
        media: IMediaClient,
        stream: IStreamService,
    ) -> None:
        self._session = session
        self._redis = redis
        self._auth = auth
        self._contacts = contacts
        self._media = media
        self._stream = stream
        self._cache: dict[str, object] = {}

    @property
    def conversation_service(self) -> IConversationService:
        if "conversation" not in self._cache:
            from app.services.conversation_service import ConversationServiceImpl
            from app.repositories.conversation_repo import ConversationRepository
            from app.repositories.member_repo import MemberRepository
            from app.repositories.mls_group_repo import MlsGroupRepository
            from app.repositories.mls_welcome_repo import MlsWelcomeRepository
            from app.repositories.message_repo import MessageRepository
            from app.repositories.mls_commit_repo import MlsCommitRepository

            self._cache["conversation"] = ConversationServiceImpl(
                conversation_repo=ConversationRepository(self._session),
                member_repo=MemberRepository(self._session),
                mls_group_repo=MlsGroupRepository(self._session),
                mls_welcome_repo=MlsWelcomeRepository(self._session),
                commit_repo=MlsCommitRepository(self._session),
                message_repo=MessageRepository(self._session),
                contacts_client=self._contacts,
                redis=self._redis,
                stream_service=self._stream,
            )
        return self._cache["conversation"]  # type: ignore[return-value]

    @property
    def message_service(self) -> IMessageService:
        if "message" not in self._cache:
            from app.services.message_service import MessageServiceImpl
            from app.repositories.message_repo import MessageRepository
            from app.repositories.member_repo import MemberRepository
            from app.repositories.conversation_repo import ConversationRepository

            self._cache["message"] = MessageServiceImpl(
                message_repo=MessageRepository(self._session),
                member_repo=MemberRepository(self._session),
                conversation_repo=ConversationRepository(self._session),
                redis=self._redis,
                stream_service=self._stream,
                media_service=self.media_service,
                contacts_client=self._contacts,
            )
        return self._cache["message"]  # type: ignore[return-value]

    @property
    def mls_service(self) -> IMlsService:
        if "mls" not in self._cache:
            from app.services.mls_service import MlsServiceImpl
            from app.repositories.mls_key_package_repo import MlsKeyPackageRepository
            from app.repositories.mls_group_repo import MlsGroupRepository
            from app.repositories.mls_welcome_repo import MlsWelcomeRepository
            from app.repositories.mls_commit_repo import MlsCommitRepository
            from app.repositories.member_repo import MemberRepository

            self._cache["mls"] = MlsServiceImpl(
                key_package_repo=MlsKeyPackageRepository(self._session),
                mls_group_repo=MlsGroupRepository(self._session),
                welcome_repo=MlsWelcomeRepository(self._session),
                commit_repo=MlsCommitRepository(self._session),
                member_repo=MemberRepository(self._session),
                auth_client=self._auth,
                redis=self._redis,
                stream_service=self._stream,
            )
        return self._cache["mls"]  # type: ignore[return-value]

    @property
    def media_service(self) -> IMediaService:
        if "media" not in self._cache:
            from app.services.media_service import MediaServiceImpl
            from app.repositories.media_attachment_repo import MediaAttachmentRepository
            from app.repositories.member_repo import MemberRepository

            self._cache["media"] = MediaServiceImpl(
                attachment_repo=MediaAttachmentRepository(self._session),
                member_repo=MemberRepository(self._session),
                media_client=self._media,
            )
        return self._cache["media"]  # type: ignore[return-value]


class Container:
    """Application-level DI container (singleton for the process lifetime)."""

    def __init__(
        self,
        redis: aioredis.Redis,
        auth_client: IAuthClient,
        contacts_client: IContactsClient,
        media_client: IMediaClient,
    ) -> None:
        self._redis = redis
        self._auth = auth_client
        self._contacts = contacts_client
        self._media = media_client
        self._stream: IStreamService | None = None

    @property
    def stream_service(self) -> IStreamService:
        if self._stream is None:
            from app.services.stream_service import StreamServiceImpl
            self._stream = StreamServiceImpl(self._redis)
        return self._stream

    @property
    def presence_service(self) -> IPresenceService:
        from app.services.presence_service import PresenceServiceImpl
        return PresenceServiceImpl(self._redis, self.stream_service)

    @asynccontextmanager
    async def request_scope(self) -> AsyncGenerator[RequestScope, None]:
        """Creates a DB-session-scoped container for one gRPC call."""
        async with get_session() as session:
            yield RequestScope(
                session=session,
                redis=self._redis,
                auth=self._auth,
                contacts=self._contacts,
                media=self._media,
                stream=self.stream_service,
            )
