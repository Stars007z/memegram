from typing import Optional

import grpc

from app.config import Settings
from app.core.interfaces.contacts_gateway import (
    AddContactResult,
    BlockedEntry,
    BlockUserResult,
    ContactEntry,
    ContactsHealthResult,
    GetBlockedUsersResult,
    GetContactsResult,
    IContactsGateway,
    IsBlockedResult,
    RemoveContactResult,
    UnblockUserResult,
    UpdateContactResult,
    UserBriefProfile,
)
from app.infrastructure.grpc.client import GrpcChannelManager
from app.infrastructure.grpc.errors import grpc_error_to_exception
from app.infrastructure.grpc.generated import contacts_pb2, contacts_pb2_grpc

_SERVICE = "Contacts service"


def _brief_proto_to_dc(p) -> Optional[UserBriefProfile]:
    if not p:
        return None
    return UserBriefProfile(
        user_id=p.user_id,
        username=p.username,
        user_public_key=p.user_public_key,
        bio=p.bio,
        avatar_media_id=p.avatar_media_id,
    )


def _contact_proto_to_dc(c) -> ContactEntry:
    return ContactEntry(
        contact_user_id=c.contact_user_id,
        is_favorite=c.is_favorite,
        created_at=c.created_at,
        profile=_brief_proto_to_dc(c.profile) if c.HasField("profile") else None,
    )


def _blocked_proto_to_dc(b) -> BlockedEntry:
    return BlockedEntry(
        blocked_user_id=b.blocked_user_id,
        blocked_at=b.blocked_at,
        profile=_brief_proto_to_dc(b.profile) if b.HasField("profile") else None,
    )


class GrpcContactsGateway(IContactsGateway):

    def __init__(self, channels: GrpcChannelManager, settings: Settings):
        self._channels = channels
        self._settings = settings

    def _stub(self) -> contacts_pb2_grpc.ContactsServiceStub:
        return contacts_pb2_grpc.ContactsServiceStub(self._channels.get("contacts"))

    async def add_contact(self, user_id: str, user_public_key: str) -> AddContactResult:
        try:
            resp = await self._stub().AddContact(
                contacts_pb2.AddContactRequest(
                    user_id=user_id,
                    user_public_key=user_public_key,
                ),
                timeout=self._settings.CONTACTS_GRPC_TIMEOUT,
            )
            return AddContactResult(contact=_contact_proto_to_dc(resp.contact))
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)

    async def remove_contact(self, user_id: str, contact_user_id: str) -> RemoveContactResult:
        try:
            resp = await self._stub().RemoveContact(
                contacts_pb2.RemoveContactRequest(
                    user_id=user_id,
                    contact_user_id=contact_user_id,
                ),
                timeout=self._settings.CONTACTS_GRPC_TIMEOUT,
            )
            return RemoveContactResult(success=resp.success)
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)

    async def get_contacts(self, user_id: str, limit: int, offset: int) -> GetContactsResult:
        try:
            resp = await self._stub().GetContacts(
                contacts_pb2.GetContactsRequest(
                    user_id=user_id,
                    limit=limit,
                    offset=offset,
                ),
                timeout=self._settings.CONTACTS_GRPC_TIMEOUT,
            )
            return GetContactsResult(
                contacts=[_contact_proto_to_dc(c) for c in resp.contacts],
                total_count=resp.total_count,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)

    async def update_contact(
        self,
        user_id: str,
        contact_user_id: str,
        is_favorite: Optional[bool],
    ) -> UpdateContactResult:
        req = contacts_pb2.UpdateContactRequest(
            user_id=user_id,
            contact_user_id=contact_user_id,
        )
        if is_favorite is not None:
            req.is_favorite = is_favorite
        try:
            resp = await self._stub().UpdateContact(
                req,
                timeout=self._settings.CONTACTS_GRPC_TIMEOUT,
            )
            return UpdateContactResult(contact=_contact_proto_to_dc(resp.contact))
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)

    async def block_user(self, user_id: str, blocked_user_id: str) -> BlockUserResult:
        try:
            resp = await self._stub().BlockUser(
                contacts_pb2.BlockUserRequest(
                    user_id=user_id,
                    blocked_user_id=blocked_user_id,
                ),
                timeout=self._settings.CONTACTS_GRPC_TIMEOUT,
            )
            return BlockUserResult(success=resp.success, created_at=resp.created_at)
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)

    async def unblock_user(self, user_id: str, blocked_user_id: str) -> UnblockUserResult:
        try:
            resp = await self._stub().UnblockUser(
                contacts_pb2.UnblockUserRequest(
                    user_id=user_id,
                    blocked_user_id=blocked_user_id,
                ),
                timeout=self._settings.CONTACTS_GRPC_TIMEOUT,
            )
            return UnblockUserResult(success=resp.success)
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)

    async def get_blocked_users(
        self,
        user_id: str,
        limit: int,
        offset: int,
    ) -> GetBlockedUsersResult:
        try:
            resp = await self._stub().GetBlockedUsers(
                contacts_pb2.GetBlockedUsersRequest(
                    user_id=user_id,
                    limit=limit,
                    offset=offset,
                ),
                timeout=self._settings.CONTACTS_GRPC_TIMEOUT,
            )
            return GetBlockedUsersResult(
                blocked_users=[_blocked_proto_to_dc(b) for b in resp.blocked_users],
                total_count=resp.total_count,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)

    async def is_blocked(self, user_id: str, blocked_user_id: str) -> IsBlockedResult:
        try:
            resp = await self._stub().IsBlocked(
                contacts_pb2.IsBlockedRequest(
                    user_id=user_id,
                    blocked_user_id=blocked_user_id,
                ),
                timeout=self._settings.CONTACTS_GRPC_TIMEOUT,
            )
            return IsBlockedResult(is_blocked=resp.is_blocked)
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)

    async def health_check(self) -> ContactsHealthResult:
        try:
            channel = self._channels.get("contacts")
            state = channel.get_state(try_to_connect=True)
            if state in (
                grpc.ChannelConnectivity.READY,
                grpc.ChannelConnectivity.IDLE,
                grpc.ChannelConnectivity.CONNECTING,
            ):
                return ContactsHealthResult(status="ok", version="1.0.0")
            return ContactsHealthResult(status="degraded", version="1.0.0")
        except Exception:
            return ContactsHealthResult(status="degraded", version="1.0.0")
