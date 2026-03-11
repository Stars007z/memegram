import uuid
from typing import TypeVar, Type, Optional, Generic, Dict, Any
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from app.database.base import Base

ModelType = TypeVar("ModelType", bound=Base)

class BaseRepository(Generic[ModelType]):
    def __init__(self, model: Type[ModelType], session: AsyncSession):
        self.model = model
        self.session = session

    async def get_by_id(self, id: uuid.UUID) -> Optional[ModelType]:
        result = await self.session.get(self.model, id)
        return result

    async def get_by_field(self, field: str, value: Any) -> Optional[ModelType]:
        query = select(self.model).where(getattr(self.model, field) == value)
        result = await self.session.execute(query)
        return result.scalar_one_or_none()

    async def create(self, attributes: Dict[str, Any]) -> ModelType:
        entity = self.model(**attributes)
        self.session.add(entity)
        await self.session.flush()
        return entity

    async def update(self, entity: ModelType, attributes: Dict[str, Any]) -> ModelType:
        for attr, value in attributes.items():
            if hasattr(entity, attr):
                setattr(entity, attr, value)
        return entity

    async def delete(self, entity: ModelType) -> None:
        await self.session.delete(entity)

    async def get_all(self, limit: int = 100, offset: int = 0) -> list[ModelType]:
        query = select(self.model).limit(limit).offset(offset)
        result = await self.session.execute(query)
        return result.scalars().all()

    async def count(self) -> int:
        from sqlalchemy import func
        query = select(func.count()).select_from(self.model)
        result = await self.session.execute(query)
        return result.scalar()