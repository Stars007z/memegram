"""
DI container — wires all dependencies together.

Container holds singleton (app-lifetime) resources: S3 client.
RequestScope is created per-request and lazily builds services that share a DB session.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession

from app.database.session import get_session
from app.infrastructure.s3_client import S3Client
from app.services.interfaces import IMediaObjectService


class RequestScope:
    """Per-request scope: owns a DB session, lazily builds services."""

    def __init__(self, session: AsyncSession, s3: S3Client) -> None:
        self._session = session
        self._s3 = s3
        self._cache: dict[str, object] = {}

    @property
    def media_object_service(self) -> IMediaObjectService:
        if "media_object" not in self._cache:
            from app.services.media_object_service import MediaObjectServiceImpl
            from app.repositories.media_object_repo import MediaObjectRepository

            self._cache["media_object"] = MediaObjectServiceImpl(
                repo=MediaObjectRepository(self._session),
                s3=self._s3,
            )
        return self._cache["media_object"]  # type: ignore[return-value]


class Container:
    """Application-level DI container (singleton for the process lifetime)."""

    def __init__(self, s3: S3Client) -> None:
        self._s3 = s3

    @asynccontextmanager
    async def request_scope(self) -> AsyncGenerator[RequestScope, None]:
        async with get_session() as session:
            yield RequestScope(session=session, s3=self._s3)
