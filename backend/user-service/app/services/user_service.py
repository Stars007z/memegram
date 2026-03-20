import uuid
from datetime import datetime
from typing import Optional
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User
from app.models.user_settings import UserSettings
from app.database.redis import check_and_set_last_active_debounce


def _now() -> datetime:
    return datetime.utcnow()


class UserService:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def create_user(self, user_id: str, username: str) -> User:
        # username is intentionally NOT unique per architecture — search is by user_public_key
        user = User(
            id=uuid.UUID(user_id),
            username=username,
            created_at=_now(),
        )
        self.session.add(user)
        await self.session.flush()

        default_settings = UserSettings(user_id=user.id)
        self.session.add(default_settings)
        await self.session.flush()
        return user

    async def get_user(self, user_id: str, requester_user_id: str) -> tuple[User, bool]:
        result = await self.session.execute(select(User).where(User.id == uuid.UUID(user_id)))
        user = result.scalar_one_or_none()
        if not user or user.is_deleted:
            raise ValueError("User not found")

        settings_result = await self.session.execute(
            select(UserSettings).where(UserSettings.user_id == user.id)
        )
        settings = settings_result.scalar_one_or_none()

        is_owner = (user_id == requester_user_id)
        if is_owner:
            return user, True
        if settings and settings.profile_visible_to == "nobody":
            return user, False
        return user, True

    async def get_user_by_public_key(
        self, user_public_key: str, requester_user_id: str
    ) -> tuple[User, bool]:
        result = await self.session.execute(
            select(User).where(
                User.user_public_key == user_public_key,
                User.is_deleted == False,
            )
        )
        user = result.scalar_one_or_none()
        if not user:
            raise ValueError("User not found")

        settings_result = await self.session.execute(
            select(UserSettings).where(UserSettings.user_id == user.id)
        )
        settings = settings_result.scalar_one_or_none()

        is_owner = (str(user.id) == requester_user_id)
        if is_owner:
            return user, True
        if settings and settings.profile_visible_to == "nobody":
            return user, False
        return user, True

    async def get_user_with_settings(self, user_id: str) -> tuple[User, UserSettings | None]:
        from sqlalchemy.orm import selectinload
        result = await self.session.execute(
            select(User)
            .options(selectinload(User.settings))
            .where(User.id == uuid.UUID(user_id))
        )
        user = result.scalar_one_or_none()
        if not user or user.is_deleted:
            raise ValueError("User not found")
        return user, user.settings

    async def get_user_by_public_key_with_settings(
        self, user_public_key: str
    ) -> tuple[User, UserSettings | None]:
        from sqlalchemy.orm import selectinload
        result = await self.session.execute(
            select(User)
            .options(selectinload(User.settings))
            .where(User.user_public_key == user_public_key, User.is_deleted == False)
        )
        user = result.scalar_one_or_none()
        if not user:
            raise ValueError("User not found")
        return user, user.settings

    async def update_user(
        self,
        user_id: str,
        bio: Optional[str] = None,
        username: Optional[str] = None,
        avatar_media_id: Optional[str] = None,
        profile_background_media_id: Optional[str] = None,
    ) -> User:
        result = await self.session.execute(
            select(User).where(User.id == uuid.UUID(user_id), User.is_deleted == False)
        )
        user = result.scalar_one_or_none()
        if not user:
            raise ValueError("User not found")

        if username is not None:
            user.username = username
        if bio is not None:
            user.bio = bio
        if avatar_media_id is not None:
            user.avatar_media_id = uuid.UUID(avatar_media_id) if avatar_media_id else None
        if profile_background_media_id is not None:
            user.profile_background_media_id = (
                uuid.UUID(profile_background_media_id) if profile_background_media_id else None
            )

        await self.session.flush()
        return user

    async def delete_user(self, user_id: str) -> datetime:
        result = await self.session.execute(
            select(User).where(User.id == uuid.UUID(user_id), User.is_deleted == False)
        )
        user = result.scalar_one_or_none()
        if not user:
            raise ValueError("User not found")

        ts = _now()
        user.is_deleted = True
        user.deleted_at = ts
        user.username = f"{user.username}_deleted_{int(ts.timestamp())}"
        await self.session.flush()
        return ts

    async def check_and_process_auto_delete(self) -> tuple[int, list[str]]:
        from sqlalchemy.sql import func
        subq = (
            select(UserSettings.user_id, UserSettings.account_auto_delete_after_days)
            .where(UserSettings.account_auto_delete_after_days.isnot(None))
            .subquery()
        )
        result = await self.session.execute(
            select(User)
            .join(subq, User.id == subq.c.user_id)
            .where(
                User.is_deleted == False,
                User.last_active.isnot(None),
                User.last_active
                < func.now()
                - func.make_interval(days=subq.c.account_auto_delete_after_days),
            )
        )
        users_to_delete = result.scalars().all()
        deleted_ids = []
        for user in users_to_delete:
            await self.delete_user(str(user.id))
            deleted_ids.append(str(user.id))
        return len(deleted_ids), deleted_ids

    async def get_user_settings(self, user_id: str) -> UserSettings:
        result = await self.session.execute(
            select(UserSettings).where(UserSettings.user_id == uuid.UUID(user_id))
        )
        settings = result.scalar_one_or_none()
        if not settings:
            raise ValueError("Settings not found")
        return settings

    async def update_user_settings(self, user_id: str, **kwargs) -> UserSettings:
        result = await self.session.execute(
            select(UserSettings).where(UserSettings.user_id == uuid.UUID(user_id))
        )
        settings = result.scalar_one_or_none()
        if not settings:
            raise ValueError("Settings not found")

        allowed_fields = {
            "theme", "language", "is_translator_active", "animations_enabled",
            "account_auto_delete_after_days", "profile_visible_to", "last_active_visible_to",
            "chat_background_media_id", "top_bar_color", "ringtone_media_id",
            "ringtone_vibration_strength", "notification_sound", "notification_vibration_strength",
        }
        uuid_fields = {"chat_background_media_id", "ringtone_media_id", "notification_sound"}

        for field, value in kwargs.items():
            if field in allowed_fields:
                if field in uuid_fields:
                    value = uuid.UUID(value) if value else None
                setattr(settings, field, value)

        settings.updated_at = _now()
        await self.session.flush()
        return settings

    async def get_users_batch(self, user_ids: list[str]) -> list[User]:
        uuids = [uuid.UUID(uid) for uid in user_ids]
        result = await self.session.execute(select(User).where(User.id.in_(uuids)))
        return result.scalars().all()

    async def user_exists(self, user_id: str) -> tuple[bool, bool]:
        result = await self.session.execute(
            select(User.is_deleted).where(User.id == uuid.UUID(user_id))
        )
        row = result.one_or_none()
        if row is None:
            return False, False
        return True, row[0]

    async def update_last_active(self, user_id: str) -> bool:
        should_update = await check_and_set_last_active_debounce(user_id)
        if not should_update:
            return True
        await self.session.execute(
            update(User)
            .where(User.id == uuid.UUID(user_id), User.is_deleted == False)
            .values(last_active=_now())
        )
        await self.session.flush()
        return True

    async def get_privacy_settings(self, user_id: str) -> UserSettings:
        result = await self.session.execute(
            select(UserSettings).where(UserSettings.user_id == uuid.UUID(user_id))
        )
        settings = result.scalar_one_or_none()
        if not settings:
            raise ValueError("Settings not found")
        return settings
