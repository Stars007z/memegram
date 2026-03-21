import grpc

from app.config import settings
from app.core.interfaces.contacts_gateway import (
    IContactsGateway,
    UserBriefProfile,
    ContactEntry,
    BlockedEntry,
    AddContactResult,
    RemoveContactResult,
    GetContactsResult,
    UpdateContactResult,
    BlockUserResult,
    UnblockUserResult,
    GetBlockedUsersResult,
    ContactsHealthResult,
)
from app.exceptions import GatewayError, NotFoundError, ValidationError
from app.infrastructure.grpc.client import get_contacts_grpc_channel
from app.infrastructure.grpc.generated import contacts_pb2, contacts_pb2_grpc
from typing import Optional


def _grpc_error_to_exception(e: grpc.RpcError) -> Exception:
    code = e.code()
    details = e.details() or "Unknown gRPC error"
    if code == grpc.StatusCode.INVALID_ARGUMENT:
        return ValidationError(details)
    if code == grpc.StatusCode.NOT_FOUND:
        return NotFoundError(details)
    if code == grpc.StatusCode.ALREADY_EXISTS:
        return ValidationError(f"ALREADY_EXISTS: {details}")
    if code == grpc.StatusCode.UNAVAILABLE:
        return GatewayError("Contacts service is unavailable", code=503)
    return GatewayError(f"Contacts service error: {details}", code=502)


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
    async def _stub(self) -> contacts_pb2_grpc.ContactsServiceStub:
        channel = await get_contacts_grpc_channel()
        return contacts_pb2_grpc.ContactsServiceStub(channel)

    async def add_contact(self, user_id: str, user_public_key: str) -> AddContactResult:
        stub = await self._stub()
        try:
            resp = await stub.AddContact(
                contacts_pb2.AddContactRequest(
                    user_id=user_id,
                    user_public_key=user_public_key,
                ),
                timeout=settings.CONTACTS_GRPC_TIMEOUT,
            )
            return AddContactResult(contact=_contact_proto_to_dc(resp.contact))
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

    async def remove_contact(self, user_id: str, contact_user_id: str) -> RemoveContactResult:
        stub = await self._stub()
        try:
            resp = await stub.RemoveContact(
                contacts_pb2.RemoveContactRequest(
                    user_id=user_id,
                    contact_user_id=contact_user_id,
                ),
                timeout=settings.CONTACTS_GRPC_TIMEOUT,
            )
            return RemoveContactResult(success=resp.success)
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

    async def get_contacts(self, user_id: str, limit: int, offset: int) -> GetContactsResult:
        stub = await self._stub()
        try:
            resp = await stub.GetContacts(
                contacts_pb2.GetContactsRequest(
                    user_id=user_id,
                    limit=limit,
                    offset=offset,
                ),
                timeout=settings.CONTACTS_GRPC_TIMEOUT,
            )
            return GetContactsResult(
                contacts=[_contact_proto_to_dc(c) for c in resp.contacts],
                total_count=resp.total_count,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

    async def update_contact(
        self,
        user_id: str,
        contact_user_id: str,
        is_favorite: Optional[bool],
    ) -> UpdateContactResult:
        stub = await self._stub()
        req = contacts_pb2.UpdateContactRequest(
            user_id=user_id,
            contact_user_id=contact_user_id,
        )
        if is_favorite is not None:
            req.is_favorite = is_favorite
        try:
            resp = await stub.UpdateContact(req, timeout=settings.CONTACTS_GRPC_TIMEOUT)
            return UpdateContactResult(contact=_contact_proto_to_dc(resp.contact))
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

    async def block_user(self, user_id: str, blocked_user_id: str) -> BlockUserResult:
        stub = await self._stub()
        try:
            resp = await stub.BlockUser(
                contacts_pb2.BlockUserRequest(
                    user_id=user_id,
                    blocked_user_id=blocked_user_id,
                ),
                timeout=settings.CONTACTS_GRPC_TIMEOUT,
            )
            return BlockUserResult(success=resp.success, created_at=resp.created_at)
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

    async def unblock_user(self, user_id: str, blocked_user_id: str) -> UnblockUserResult:
        stub = await self._stub()
        try:
            resp = await stub.UnblockUser(
                contacts_pb2.UnblockUserRequest(
                    user_id=user_id,
                    blocked_user_id=blocked_user_id,
                ),
                timeout=settings.CONTACTS_GRPC_TIMEOUT,
            )
            return UnblockUserResult(success=resp.success)
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

    async def get_blocked_users(
        self, user_id: str, limit: int, offset: int
    ) -> GetBlockedUsersResult:
        stub = await self._stub()
        try:
            resp = await stub.GetBlockedUsers(
                contacts_pb2.GetBlockedUsersRequest(
                    user_id=user_id,
                    limit=limit,
                    offset=offset,
                ),
                timeout=settings.CONTACTS_GRPC_TIMEOUT,
            )
            return GetBlockedUsersResult(
                blocked_users=[_blocked_proto_to_dc(b) for b in resp.blocked_users],
                total_count=resp.total_count,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

    async def health_check(self) -> ContactsHealthResult:
        """
        contacts-service has no HealthCheck RPC.
        We probe connectivity by checking the gRPC channel state.
        """
        try:
            channel = await get_contacts_grpc_channel()
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
