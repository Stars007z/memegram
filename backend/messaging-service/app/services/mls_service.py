import hashlib
import uuid
from datetime import datetime
from typing import Optional

import redis.asyncio as aioredis
from sqlalchemy.exc import IntegrityError

from app.repositories.member_repo import MemberRepository
from app.repositories.mls_commit_repo import MlsCommitRepository
from app.repositories.mls_group_repo import MlsGroupRepository
from app.repositories.mls_key_package_repo import MlsKeyPackageRepository
from app.repositories.mls_welcome_repo import MlsWelcomeRepository
from app.infrastructure.auth_client import IAuthClient
from app.logging_config import get_logger
from app.services.interfaces.mls_service import (
    CommitEntryResult,
    CommitResult,
    IMlsService,
    KeyPackageResult,
    UserDeviceKeyPackageResult,
    WelcomeEntryResult,
)
from app.services.interfaces.stream_service import IStreamService


DEFAULT_CIPHER_SUITE = 1  # MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519

logger = get_logger(__name__)


class MlsServiceImpl(IMlsService):

    def __init__(
        self,
        key_package_repo: MlsKeyPackageRepository,
        mls_group_repo: MlsGroupRepository,
        welcome_repo: MlsWelcomeRepository,
        commit_repo: MlsCommitRepository,
        member_repo: MemberRepository,
        auth_client: IAuthClient,
        redis: aioredis.Redis,
        stream_service: IStreamService,
    ) -> None:
        self._key_packages = key_package_repo
        self._mls_groups = mls_group_repo
        self._welcomes = welcome_repo
        self._commits = commit_repo
        self._members = member_repo
        self._auth = auth_client
        self._redis = redis
        self._stream = stream_service

    # ── Key Packages ────────────────────────────────

    async def upload_key_packages(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        key_packages: list[bytes],
    ) -> int:
        # Self-healing: when the client re-uploads a batch, its local MLS
        # key-store has been regenerated (e.g. logout→login, fresh install,
        # add-device). Any previously uploaded, still-unconsumed KPs belong
        # to private keys that no longer exist on the client — serving them
        # to peers would cause "No matching key package" on processWelcome.
        # We wipe them before inserting the new batch so only fresh KPs
        # backed by live private material remain.
        purged = await self._key_packages.delete_by_device(
            user_id=user_id, device_id=device_id, only_unconsumed=True,
        )
        if purged:
            logger.info(
                "mls.key_packages.purged_on_upload",
                user_id=str(user_id), device_id=str(device_id), purged=purged,
            )

        items = []
        for kp_data in key_packages:
            kp_ref = hashlib.sha256(kp_data).digest()
            items.append({
                "user_id": user_id,
                "device_id": device_id,
                "key_package_data": kp_data,
                "key_package_ref": kp_ref,
                "cipher_suite": DEFAULT_CIPHER_SUITE,
            })

        created = await self._key_packages.create_many(items)
        logger.info(
            "mls.key_packages.uploaded",
            user_id=str(user_id),
            device_id=str(device_id),
            count=len(created),
        )
        return len(created)

    async def delete_key_packages_for_device(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
    ) -> int:
        deleted = await self._key_packages.delete_by_device(
            user_id=user_id, device_id=device_id, only_unconsumed=True,
        )
        logger.info(
            "mls.key_packages.deleted_for_device",
            user_id=str(user_id), device_id=str(device_id), deleted=deleted,
        )
        return deleted

    async def get_key_package(
        self,
        target_user_id: uuid.UUID,
        target_device_id: uuid.UUID,
    ) -> KeyPackageResult:
        package = await self._key_packages.consume_one(target_user_id, target_device_id)
        if not package:
            raise ValueError("NOT_FOUND: No available key packages for this device")
        return KeyPackageResult(
            key_package_data=package.key_package_data,
            key_package_ref=package.key_package_ref,
        )

    async def get_key_packages_count(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
    ) -> int:
        return await self._key_packages.count_available(user_id, device_id)

    async def get_key_packages_for_user(
        self,
        target_user_id: uuid.UUID,
    ) -> list[UserDeviceKeyPackageResult]:
        device_ids = await self._auth.get_active_device_ids(target_user_id)
        if not device_ids:
            raise ValueError("NOT_FOUND: No active devices found for user")

        results: list[UserDeviceKeyPackageResult] = []
        for device_id in device_ids:
            package = await self._key_packages.consume_one(target_user_id, device_id)
            if package:
                results.append(UserDeviceKeyPackageResult(
                    device_id=device_id,
                    key_package_data=package.key_package_data,
                    key_package_ref=package.key_package_ref,
                ))

        if not results:
            raise ValueError(
                "NOT_FOUND: No available key packages for any device of this user"
            )
        return results

    # ── Group Management ────────────────────────────

    async def commit_group_change(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        conversation_id: uuid.UUID,
        commit_data: bytes,
        new_epoch: int,
        welcome_messages: Optional[list[tuple[uuid.UUID, bytes]]] = None,
        ratchet_tree: Optional[bytes] = None,
        removed_device_ids: Optional[list[uuid.UUID]] = None,
        added_user_ids: Optional[list[uuid.UUID]] = None,
    ) -> CommitResult:
        mls_group = await self._mls_groups.get_by_conversation_id(conversation_id)
        if not mls_group:
            raise ValueError("NOT_FOUND: MLS group not found")

        # Role check: only admins/owners may add or remove members
        if added_user_ids or removed_device_ids:
            has_admin = await self._members.has_role(
                conversation_id, user_id, ["owner", "admin"],
            )
            if not has_admin:
                raise ValueError(
                    "PERMISSION_DENIED: Only admins can add or remove members"
                )

        if mls_group.current_epoch + 1 != new_epoch:
            raise ValueError("ABORTED: Epoch conflict — expected "
                             f"{mls_group.current_epoch + 1}, got {new_epoch}")

        try:
            async with self._commits.session.begin_nested():
                await self._commits.create({
                    "conversation_id": conversation_id,
                    "sender_device_id": device_id,
                    "epoch": new_epoch,
                    "commit_data": commit_data,
                })
        except IntegrityError:
            # Another client already committed at this epoch (race condition).
            # The unique constraint on (conversation_id, epoch) prevents duplicates.
            # The savepoint (begin_nested) ensures the session stays clean.
            raise ValueError("ABORTED: Epoch conflict — another commit already "
                             f"exists at epoch {new_epoch}")

        if welcome_messages:
            for wm_device_id, welcome_data in welcome_messages:
                await self._welcomes.create({
                    "recipient_device_id": wm_device_id,
                    "conversation_id": conversation_id,
                    "welcome_data": welcome_data,
                })

        update_data: dict = {"current_epoch": new_epoch}
        if ratchet_tree is not None:
            update_data["ratchet_tree"] = ratchet_tree
        await self._mls_groups.update(mls_group, update_data)

        if added_user_ids:
            for new_uid in added_user_ids:
                existing_member = await self._members.get_member(
                    conversation_id, new_uid,
                )
                if existing_member:
                    if existing_member.left_at is not None:
                        # Re-add: member previously left — reset instead of INSERT
                        await self._members.update(existing_member, {
                            "left_at": None,
                            "joined_at": datetime.utcnow(),
                            "role": "member",
                        })
                        await self._stream.publish_event(conversation_id, {
                            "event_type": "member_joined",
                            "user_id": str(new_uid),
                        })
                    # else: already active member — skip
                else:
                    await self._members.create({
                        "conversation_id": conversation_id,
                        "user_id": new_uid,
                        "role": "member",
                    })
                    await self._stream.publish_event(conversation_id, {
                        "event_type": "member_joined",
                        "user_id": str(new_uid),
                    })

        now = datetime.utcnow()

        await self._stream.publish_event(conversation_id, {
            "event_type": "epoch_changed",
            "new_epoch": new_epoch,
        })

        logger.info(
            "mls.group.commit",
            conversation_id=str(conversation_id),
            device_id=str(device_id),
            new_epoch=new_epoch,
            added_count=len(added_user_ids) if added_user_ids else 0,
            removed_count=len(removed_device_ids) if removed_device_ids else 0,
        )

        return CommitResult(new_epoch=new_epoch, committed_at=now.timestamp())

    # ── Welcomes & Commits ──────────────────────────

    async def get_pending_welcomes(
        self, device_id: uuid.UUID,
    ) -> list[WelcomeEntryResult]:
        rows = await self._welcomes.get_pending_for_device(device_id)
        return [
            WelcomeEntryResult(
                id=w.id,
                conversation_id=w.conversation_id,
                welcome_data=w.welcome_data,
                created_at=w.created_at.timestamp(),
            )
            for w in rows
        ]

    async def ack_welcome(
        self, device_id: uuid.UUID, welcome_id: uuid.UUID,
    ) -> bool:
        welcome = await self._welcomes.get_by_id(welcome_id)
        if not welcome or welcome.recipient_device_id != device_id:
            raise ValueError("NOT_FOUND: Welcome message not found")
        await self._welcomes.update(welcome, {"delivered_at": datetime.utcnow()})
        return True

    async def get_pending_commits(
        self, conversation_id: uuid.UUID, since_epoch: int,
    ) -> list[CommitEntryResult]:
        rows = await self._commits.get_since_epoch(conversation_id, since_epoch)
        return [
            CommitEntryResult(
                epoch=c.epoch,
                commit_data=c.commit_data,
                created_at=c.created_at.timestamp(),
            )
            for c in rows
        ]

    # ── Device Revoked ──────────────────────────────

    async def notify_device_revoked(
        self, user_id: uuid.UUID, revoked_device_id: uuid.UUID,
    ) -> int:
        conv_ids = await self._members.get_user_conversation_ids(user_id)
        if not conv_ids:
            return 0

        for cid in conv_ids:
            await self._stream.publish_event(cid, {
                "event_type": "device_revoked",
                "user_id": str(user_id),
                "revoked_device_id": str(revoked_device_id),
                "conversation_ids": [str(c) for c in conv_ids],
            })

        logger.info(
            "mls.device_revoked.notified",
            user_id=str(user_id),
            revoked_device_id=str(revoked_device_id),
            conversation_count=len(conv_ids),
        )

        return len(conv_ids)
