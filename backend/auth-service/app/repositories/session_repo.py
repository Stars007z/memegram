from sqlalchemy.ext.asyncio import AsyncSession
from app.models.session import Session
from app.repositories.base import BaseRepository


class SessionRepository(BaseRepository[Session]):
    def __init__(self, session: AsyncSession):
        super().__init__(Session, session)

    async def get_by_access_token(self, access_token: str) -> Session | None:
        return await self.get_by_field("access_token", access_token)

    async def get_by_refresh_token(self, refresh_token: str) -> Session | None:
        return await self.get_by_field("refresh_token", refresh_token)
