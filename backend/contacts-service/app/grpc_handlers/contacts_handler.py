"""gRPC-хэндлер ContactsService."""

from __future__ import annotations

from typing import Optional

import grpc

from app.generated import contacts_pb2, contacts_pb2_grpc
from app.services.contacts_service import ContactsService
from app.services.user_client import UserBriefProfile, get_user_client


def _brief_profile_to_proto(profile: Optional[UserBriefProfile]) -> contacts_pb2.UserBriefProfile:
    if not profile:
        return contacts_pb2.UserBriefProfile()
    return contacts_pb2.UserBriefProfile(
        user_id=profile.user_id,
        username=profile.username,
        user_public_key=profile.user_public_key,
        bio=profile.bio,
        avatar_media_id=profile.avatar_media_id,
    )


def _contact_dict_to_proto(d: dict) -> contacts_pb2.ContactEntry:
    return contacts_pb2.ContactEntry(
        contact_user_id=d["contact_user_id"],
        is_favorite=d["is_favorite"],
        created_at=d["created_at"],
        profile=_brief_profile_to_proto(d.get("profile")),
    )


def _blocked_dict_to_proto(d: dict) -> contacts_pb2.BlockedEntry:
    return contacts_pb2.BlockedEntry(
        blocked_user_id=d["blocked_user_id"],
        blocked_at=d["blocked_at"],
        profile=_brief_profile_to_proto(d.get("profile")),
    )


_GRPC_STATUS_MAP = {
    "NOT_FOUND": grpc.StatusCode.NOT_FOUND,
    "ALREADY_EXISTS": grpc.StatusCode.ALREADY_EXISTS,
    "INVALID_ARGUMENT": grpc.StatusCode.INVALID_ARGUMENT,
}


def _map_error(e: ValueError) -> tuple[grpc.StatusCode, str]:
    msg = str(e)
    if ":" in msg:
        code_str, detail = msg.split(":", 1)
        return _GRPC_STATUS_MAP.get(code_str, grpc.StatusCode.INTERNAL), detail
    return grpc.StatusCode.INTERNAL, msg


class ContactsHandler(contacts_pb2_grpc.ContactsServiceServicer):
    def __init__(self, get_session):
        self.get_session = get_session

    def _svc(self, session) -> ContactsService:
        return ContactsService(session, get_user_client())

    async def AddContact(self, request, context):
        if not request.user_id or not request.user_public_key:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and user_public_key are required")
            return contacts_pb2.AddContactResponse()

        async with self.get_session() as session:
            try:
                result = await self._svc(session).add_contact(request.user_id, request.user_public_key)
                return contacts_pb2.AddContactResponse(contact=_contact_dict_to_proto(result))
            except ValueError as e:
                code, detail = _map_error(e)
                context.set_code(code)
                context.set_details(detail)
                return contacts_pb2.AddContactResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.AddContactResponse()

    async def RemoveContact(self, request, context):
        if not request.user_id or not request.contact_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and contact_user_id are required")
            return contacts_pb2.RemoveContactResponse()

        async with self.get_session() as session:
            try:
                await self._svc(session).remove_contact(request.user_id, request.contact_user_id)
                return contacts_pb2.RemoveContactResponse(success=True)
            except ValueError as e:
                code, detail = _map_error(e)
                context.set_code(code)
                context.set_details(detail)
                return contacts_pb2.RemoveContactResponse(success=False)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.RemoveContactResponse(success=False)

    async def GetContacts(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return contacts_pb2.GetContactsResponse()

        limit = request.limit if request.limit > 0 else 50
        offset = request.offset if request.offset >= 0 else 0

        async with self.get_session() as session:
            try:
                result = await self._svc(session).get_contacts(request.user_id, limit, offset)
                return contacts_pb2.GetContactsResponse(
                    contacts=[_contact_dict_to_proto(c) for c in result["contacts"]],
                    total_count=result["total_count"],
                )
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.GetContactsResponse()

    async def UpdateContact(self, request, context):
        if not request.user_id or not request.contact_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and contact_user_id are required")
            return contacts_pb2.UpdateContactResponse()

        is_favorite = request.is_favorite if request.HasField("is_favorite") else None

        async with self.get_session() as session:
            try:
                result = await self._svc(session).update_contact(
                    request.user_id, request.contact_user_id, is_favorite=is_favorite
                )
                return contacts_pb2.UpdateContactResponse(contact=_contact_dict_to_proto(result))
            except ValueError as e:
                code, detail = _map_error(e)
                context.set_code(code)
                context.set_details(detail)
                return contacts_pb2.UpdateContactResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.UpdateContactResponse()

    async def BlockUser(self, request, context):
        if not request.user_id or not request.blocked_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and blocked_user_id are required")
            return contacts_pb2.BlockUserResponse()

        async with self.get_session() as session:
            try:
                result = await self._svc(session).block_user(request.user_id, request.blocked_user_id)
                return contacts_pb2.BlockUserResponse(
                    success=result["success"],
                    created_at=result["created_at"],
                )
            except ValueError as e:
                code, detail = _map_error(e)
                context.set_code(code)
                context.set_details(detail)
                return contacts_pb2.BlockUserResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.BlockUserResponse()

    async def UnblockUser(self, request, context):
        if not request.user_id or not request.blocked_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and blocked_user_id are required")
            return contacts_pb2.UnblockUserResponse()

        async with self.get_session() as session:
            try:
                await self._svc(session).unblock_user(request.user_id, request.blocked_user_id)
                return contacts_pb2.UnblockUserResponse(success=True)
            except ValueError as e:
                code, detail = _map_error(e)
                context.set_code(code)
                context.set_details(detail)
                return contacts_pb2.UnblockUserResponse(success=False)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.UnblockUserResponse(success=False)

    async def GetBlockedUsers(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return contacts_pb2.GetBlockedUsersResponse()

        limit = request.limit if request.limit > 0 else 50
        offset = request.offset if request.offset >= 0 else 0

        async with self.get_session() as session:
            try:
                result = await self._svc(session).get_blocked_users(request.user_id, limit, offset)
                return contacts_pb2.GetBlockedUsersResponse(
                    blocked_users=[_blocked_dict_to_proto(b) for b in result["blocked_users"]],
                    total_count=result["total_count"],
                )
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.GetBlockedUsersResponse()

    async def IsContact(self, request, context):
        if not request.user_id or not request.contact_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and contact_user_id are required")
            return contacts_pb2.IsContactResponse()

        async with self.get_session() as session:
            try:
                is_contact = await self._svc(session).is_contact(request.user_id, request.contact_user_id)
                return contacts_pb2.IsContactResponse(is_contact=is_contact)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.IsContactResponse(is_contact=False)

    async def IsBlocked(self, request, context):
        if not request.user_id or not request.blocked_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and blocked_user_id are required")
            return contacts_pb2.IsBlockedResponse()

        async with self.get_session() as session:
            try:
                is_blocked = await self._svc(session).is_blocked(request.user_id, request.blocked_user_id)
                return contacts_pb2.IsBlockedResponse(is_blocked=is_blocked)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.IsBlockedResponse(is_blocked=False)

    async def PurgeUser(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return contacts_pb2.PurgeUserResponse()

        async with self.get_session() as session:
            try:
                contacts_deleted, blocked_deleted = await self._svc(session).purge_user(request.user_id)
                return contacts_pb2.PurgeUserResponse(
                    contacts_deleted=contacts_deleted,
                    blocked_deleted=blocked_deleted,
                )
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return contacts_pb2.PurgeUserResponse()
