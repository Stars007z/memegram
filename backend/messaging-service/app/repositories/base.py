import uuid
from typing import Any, Generic, Optional, Type, TypeVar

from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from app.database.base import Base

ModelType = TypeVar("ModelType", bound=Base)


class BaseRepository(Generic[ModelType]):
    def __init__(self, model: Type[ModelType], session: AsyncSession):
        self.model = model
        self.session = session

    async def get_by_id(self, id: uuid.UUID) -> Optional[ModelType]:
        return await self.session.get(self.model, id)

    async def get_by_field(self, field: str, value: Any) -> Optional[ModelType]:
        query = select(self.model).where(getattr(self.model, field) == value)
        result = await self.session.execute(query)
        return result.scalar_one_or_none()

    async def create(self, attributes: dict[str, Any]) -> ModelType:
        entity = self.model(**attributes)
        self.session.add(entity)
        await self.session.flush()
        return entity

    async def create_many(self, items: list[dict[str, Any]]) -> list[ModelType]:
        entities = [self.model(**attrs) for attrs in items]
        self.session.add_all(entities)
        await self.session.flush()
        return entities

    async def update(self, entity: ModelType, attributes: dict[str, Any]) -> ModelType:
        for attr, value in attributes.items():
            if hasattr(entity, attr):
                setattr(entity, attr, value)
        await self.session.flush()
        return entity

    async def delete(self, entity: ModelType) -> None:
        await self.session.delete(entity)

    async def count(self, **filters: Any) -> int:
        query = select(func.count()).select_from(self.model)
        for field, value in filters.items():
            query = query.where(getattr(self.model, field) == value)
        result = await self.session.execute(query)
        return result.scalar()
