"""Бизнес-логика contacts-service."""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Optional

from sqlalchemy.ext.asyncio import AsyncSession

from app.logging_config import get_logger
from app.models.blocked_user import BlockedUser
from app.models.contact import Contact
from app.repositories.blocked_user_repo import BlockedUserRepository
from app.repositories.contact_repo import ContactRepository
from app.services.user_client import UserBriefProfile, UserServiceClient

logger = get_logger(__name__)


def _now() -> datetime:
    return datetime.utcnow()


class ContactsService:
    def __init__(self, session: AsyncSession, user_client: UserServiceClient):
        self.session = session
        self.user_client = user_client
        self.contact_repo = ContactRepository(session)
        self.blocked_repo = BlockedUserRepository(session)

    async def add_contact(self, user_id: str, user_public_key: str) -> dict:
        uid = uuid.UUID(user_id)

        contact_user_id_str = await self.user_client.get_user_by_public_key(user_public_key, requester_user_id=user_id)
        if not contact_user_id_str:
            raise ValueError("NOT_FOUND:User with given public key not found")

        cuid = uuid.UUID(contact_user_id_str)

        if uid == cuid:
            raise ValueError("INVALID_ARGUMENT:Cannot add yourself as contact")

        exists, is_deleted = await self.user_client.user_exists(contact_user_id_str)
        if not exists or is_deleted:
            raise ValueError("NOT_FOUND:User not found")

        if await self.blocked_repo.exists(uid, cuid):
            raise ValueError("NOT_FOUND:User not found")

        if await self.blocked_repo.exists(cuid, uid):
            raise ValueError("NOT_FOUND:User not found")

        if await self.contact_repo.exists(uid, cuid):
            raise ValueError("ALREADY_EXISTS:Contact already exists")

        now = _now()
        contact = await self.contact_repo.create(
            {
                "id": uuid.uuid4(),
                "user_id": uid,
                "contact_user_id": cuid,
                "created_at": now,
                "is_favorite": False,
            }
        )

        profiles = await self.user_client.get_users_batch([contact_user_id_str])
        profile = profiles.get(contact_user_id_str)

        logger.info(
            "contact.added",
            user_id=user_id,
            contact_user_id=contact_user_id_str,
        )

        return _contact_to_dict(contact, profile)

    async def remove_contact(self, user_id: str, contact_user_id: str) -> bool:
        uid = uuid.UUID(user_id)
        cuid = uuid.UUID(contact_user_id)

        contact = await self.contact_repo.get_by_pair(uid, cuid)
        if not contact:
            raise ValueError("NOT_FOUND:Contact not found")

        await self.contact_repo.delete(contact)

        logger.info(
            "contact.removed",
            user_id=user_id,
            contact_user_id=contact_user_id,
        )

        return True

    async def get_contacts(self, user_id: str, limit: int, offset: int) -> dict:
        uid = uuid.UUID(user_id)
        contacts = await self.contact_repo.get_paginated(uid, limit, offset)
        total = await self.contact_repo.count_by_user(uid)

        contact_ids = [str(c.contact_user_id) for c in contacts]
        profiles = await self.user_client.get_users_batch(contact_ids)

        return {
            "contacts": [_contact_to_dict(c, profiles.get(str(c.contact_user_id))) for c in contacts],
            "total_count": total,
        }

    async def update_contact(
        self,
        user_id: str,
        contact_user_id: str,
        is_favorite: Optional[bool] = None,
    ) -> dict:
        uid = uuid.UUID(user_id)
        cuid = uuid.UUID(contact_user_id)

        contact = await self.contact_repo.get_by_pair(uid, cuid)
        if not contact:
            raise ValueError("NOT_FOUND:Contact not found")

        updates = {}
        if is_favorite is not None:
            updates["is_favorite"] = is_favorite

        if updates:
            await self.contact_repo.update(contact, updates)

        profiles = await self.user_client.get_users_batch([contact_user_id])
        profile = profiles.get(contact_user_id)
        return _contact_to_dict(contact, profile)

    async def block_user(self, user_id: str, blocked_user_id: str) -> dict:
        uid = uuid.UUID(user_id)
        buid = uuid.UUID(blocked_user_id)

        if uid == buid:
            raise ValueError("INVALID_ARGUMENT:Cannot block yourself")

        existing = await self.blocked_repo.get_by_pair(uid, buid)
        if existing:
            raise ValueError("ALREADY_EXISTS:User already blocked")

        now = _now()

        blocked = await self.blocked_repo.create(
            {
                "id": uuid.uuid4(),
                "user_id": uid,
                "blocked_user_id": buid,
                "created_at": now,
            }
        )

        await self.contact_repo.delete_mutual(uid, buid)

        logger.info(
            "user.blocked",
            user_id=user_id,
            blocked_user_id=blocked_user_id,
        )

        return {
            "success": True,
            "created_at": int(blocked.created_at.timestamp()),
        }

    async def unblock_user(self, user_id: str, blocked_user_id: str) -> bool:
        uid = uuid.UUID(user_id)
        buid = uuid.UUID(blocked_user_id)

        blocked = await self.blocked_repo.get_by_pair(uid, buid)
        if not blocked:
            raise ValueError("NOT_FOUND:Block record not found")

        await self.blocked_repo.delete(blocked)

        logger.info(
            "user.unblocked",
            user_id=user_id,
            blocked_user_id=blocked_user_id,
        )

        return True

    async def get_blocked_users(self, user_id: str, limit: int, offset: int) -> dict:
        uid = uuid.UUID(user_id)
        blocked_list = await self.blocked_repo.get_paginated(uid, limit, offset)
        total = await self.blocked_repo.count_by_user(uid)

        blocked_ids = [str(b.blocked_user_id) for b in blocked_list]
        profiles = await self.user_client.get_users_batch(blocked_ids)

        return {
            "blocked_users": [_blocked_to_dict(b, profiles.get(str(b.blocked_user_id))) for b in blocked_list],
            "total_count": total,
        }

    async def is_contact(self, user_id: str, contact_user_id: str) -> bool:
        return await self.contact_repo.exists(uuid.UUID(user_id), uuid.UUID(contact_user_id))

    async def is_blocked(self, user_id: str, blocked_user_id: str) -> bool:
        return await self.blocked_repo.exists(uuid.UUID(user_id), uuid.UUID(blocked_user_id))


def _contact_to_dict(contact: Contact, profile: Optional[UserBriefProfile]) -> dict:
    return {
        "contact_user_id": str(contact.contact_user_id),
        "is_favorite": contact.is_favorite,
        "created_at": int(contact.created_at.timestamp()),
        "profile": profile,
    }


def _blocked_to_dict(blocked: BlockedUser, profile: Optional[UserBriefProfile]) -> dict:
    return {
        "blocked_user_id": str(blocked.blocked_user_id),
        "blocked_at": int(blocked.created_at.timestamp()),
        "profile": profile,
    }
