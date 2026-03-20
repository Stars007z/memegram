import uuid
from typing import TypeVar, Type, Optional, Generic, Dict, Any
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, func
from app.database.base import Base

ModelType = TypeVar("ModelType", bound=Base)


class BaseRepository(Generic[ModelType]):
    def __init__(self, model: Type[ModelType], session: AsyncSession):
        self.model = model
        self.session = session

    async def get_by_id(self, id: uuid.UUID) -> Optional[ModelType]:
        return await self.session.get(self.model, id)

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
