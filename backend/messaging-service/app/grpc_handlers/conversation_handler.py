import uuid

import grpc

from app.container import Container
from app.generated import messaging_pb2


class ConversationHandler:

    def __init__(self, container: Container) -> None:
        self._container = container

    async def create_direct(self, request, context):
        if not request.initiator_user_id or not request.recipient_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("initiator_user_id and recipient_user_id are required")
            return messaging_pb2.ConversationResponse()

        async with self._container.request_scope() as scope:
            try:
                welcomes = [
                    (uuid.UUID(w.device_id), w.welcome_data)
                    for w in request.welcome_messages
                ]
                result = await scope.conversation_service.create_direct(
                    initiator_user_id=uuid.UUID(request.initiator_user_id),
                    initiator_device_id=uuid.UUID(request.initiator_device_id),
                    recipient_user_id=uuid.UUID(request.recipient_user_id),
                    welcome_messages=welcomes,
                )
                return self._to_response(result)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.ConversationResponse()

    async def create_group(self, request, context):
        if not request.creator_user_id or not request.name:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("creator_user_id and name are required")
            return messaging_pb2.ConversationResponse()

        async with self._container.request_scope() as scope:
            try:
                members = [
                    (
                        uuid.UUID(m.user_id),
                        [(uuid.UUID(w.device_id), w.welcome_data) for w in m.welcomes],
                    )
                    for m in request.members
                ]
                result = await scope.conversation_service.create_group(
                    creator_user_id=uuid.UUID(request.creator_user_id),
                    creator_device_id=uuid.UUID(request.creator_device_id),
                    name=request.name,
                    members=members,
                )
                return self._to_response(result)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.ConversationResponse()

    async def get_conversations(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return messaging_pb2.GetConversationsResponse()

        async with self._container.request_scope() as scope:
            try:
                result = await scope.conversation_service.get_conversations(
                    user_id=uuid.UUID(request.user_id),
                    limit=request.limit or 20,
                    cursor=request.cursor or None,
                )
                return messaging_pb2.GetConversationsResponse(
                    items=[
                        messaging_pb2.ConversationSummary(
                            id=str(s.id),
                            type=s.type,
                            name=s.name or "",
                            last_message_type=s.last_message_type or "",
                            unread_count=s.unread_count,
                            last_activity_at=int(s.last_activity_at),
                            avatar_media_id=str(s.avatar_media_id) if s.avatar_media_id else "",
                        )
                        for s in result.items
                    ],
                    next_cursor=result.next_cursor or "",
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.GetConversationsResponse()

    async def get_conversation(self, request, context):
        if not request.user_id or not request.conversation_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and conversation_id are required")
            return messaging_pb2.ConversationResponse()

        async with self._container.request_scope() as scope:
            try:
                result = await scope.conversation_service.get_conversation(
                    user_id=uuid.UUID(request.user_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                )
                return self._to_response(result)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.ConversationResponse()

    async def leave_conversation(self, request, context):
        if not request.user_id or not request.conversation_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and conversation_id are required")
            return messaging_pb2.LeaveConversationResponse()

        async with self._container.request_scope() as scope:
            try:
                success = await scope.conversation_service.leave_conversation(
                    user_id=uuid.UUID(request.user_id),
                    device_id=uuid.UUID(request.device_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    commit_data=request.commit_data,
                )
                return messaging_pb2.LeaveConversationResponse(success=success)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.LeaveConversationResponse()

    async def kick_member(self, request, context):
        if not request.user_id or not request.conversation_id or not request.target_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, conversation_id, and target_user_id are required")
            return messaging_pb2.KickMemberResponse()

        async with self._container.request_scope() as scope:
            try:
                success = await scope.conversation_service.kick_member(
                    caller_user_id=uuid.UUID(request.user_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    target_user_id=uuid.UUID(request.target_user_id),
                )
                return messaging_pb2.KickMemberResponse(success=success)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.KickMemberResponse()

    async def update_member_role(self, request, context):
        if not request.user_id or not request.conversation_id or not request.target_user_id or not request.new_role:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, conversation_id, target_user_id, and new_role are required")
            return messaging_pb2.UpdateMemberRoleResponse()

        async with self._container.request_scope() as scope:
            try:
                success = await scope.conversation_service.update_member_role(
                    caller_user_id=uuid.UUID(request.user_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    target_user_id=uuid.UUID(request.target_user_id),
                    new_role=request.new_role,
                )
                return messaging_pb2.UpdateMemberRoleResponse(success=success)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.UpdateMemberRoleResponse()

    async def update_group_name(self, request, context):
        if not request.user_id or not request.conversation_id or not request.name:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, conversation_id, and name are required")
            return messaging_pb2.UpdateGroupNameResponse()

        async with self._container.request_scope() as scope:
            try:
                success = await scope.conversation_service.update_group_name(
                    caller_user_id=uuid.UUID(request.user_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    name=request.name,
                )
                return messaging_pb2.UpdateGroupNameResponse(success=success)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.UpdateGroupNameResponse()

    async def update_group_avatar(self, request, context):
        if not request.user_id or not request.conversation_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and conversation_id are required")
            return messaging_pb2.UpdateGroupAvatarResponse()

        async with self._container.request_scope() as scope:
            try:
                avatar_id = uuid.UUID(request.avatar_media_id) if request.avatar_media_id else None
                success = await scope.conversation_service.update_group_avatar(
                    caller_user_id=uuid.UUID(request.user_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    avatar_media_id=avatar_id,
                )
                return messaging_pb2.UpdateGroupAvatarResponse(success=success)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.UpdateGroupAvatarResponse()

    @staticmethod
    def _to_response(result) -> messaging_pb2.ConversationResponse:
        return messaging_pb2.ConversationResponse(
            id=str(result.id),
            type=result.type,
            name=result.name or "",
            members=[
                messaging_pb2.ConversationMember(
                    user_id=str(m.user_id),
                    role=m.role,
                    joined_at=int(m.joined_at),
                )
                for m in result.members
            ],
            mls_group=messaging_pb2.MlsGroupInfo(
                current_epoch=result.mls_group.current_epoch,
                cipher_suite=result.mls_group.cipher_suite,
            ) if result.mls_group else None,
            created_at=int(result.created_at),
            avatar_media_id=str(result.avatar_media_id) if result.avatar_media_id else "",
        )


def _set_error_from_value_error(context, e: ValueError) -> None:
    msg = str(e)
    if msg.startswith("ALREADY_EXISTS:"):
        context.set_code(grpc.StatusCode.ALREADY_EXISTS)
    elif msg.startswith("NOT_FOUND:"):
        context.set_code(grpc.StatusCode.NOT_FOUND)
    elif msg.startswith("PERMISSION_DENIED:"):
        context.set_code(grpc.StatusCode.PERMISSION_DENIED)
    elif msg.startswith("ABORTED:"):
        context.set_code(grpc.StatusCode.ABORTED)
    elif msg.startswith("FAILED_PRECONDITION:"):
        context.set_code(grpc.StatusCode.FAILED_PRECONDITION)
    elif msg.startswith("INVALID_ARGUMENT:"):
        context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
    else:
        context.set_code(grpc.StatusCode.INTERNAL)
    context.set_details(msg)
