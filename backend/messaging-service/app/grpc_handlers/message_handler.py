import uuid

import grpc

from app.container import Container
from app.generated import messaging_pb2
from app.grpc_handlers.conversation_handler import _set_error_from_value_error


class MessageHandler:

    def __init__(self, container: Container) -> None:
        self._container = container

    async def send_message(self, request, context):
        if not request.sender_user_id or not request.conversation_id or not request.client_message_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("sender_user_id, conversation_id, and client_message_id are required")
            return messaging_pb2.SendMessageResponse()

        async with self._container.request_scope() as scope:
            try:
                result = await scope.message_service.send_message(
                    sender_user_id=uuid.UUID(request.sender_user_id),
                    sender_device_id=uuid.UUID(request.sender_device_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    mls_ciphertext=request.mls_ciphertext,
                    type=request.type or "text",
                    client_message_id=uuid.UUID(request.client_message_id),
                    media_id=uuid.UUID(request.media_id) if request.media_id else None,
                    reply_to_message_id=uuid.UUID(request.reply_to_message_id) if request.reply_to_message_id else None,
                )
                return messaging_pb2.SendMessageResponse(
                    message_id=str(result.message_id),
                    created_at=int(result.created_at),
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.SendMessageResponse()

    async def get_messages(self, request, context):
        if not request.user_id or not request.conversation_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and conversation_id are required")
            return messaging_pb2.GetMessagesResponse()

        async with self._container.request_scope() as scope:
            try:
                result = await scope.message_service.get_messages(
                    user_id=uuid.UUID(request.user_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    limit=request.limit or 50,
                    before_message_id=uuid.UUID(request.before_message_id) if request.before_message_id else None,
                )
                return messaging_pb2.GetMessagesResponse(
                    messages=[_msg_to_proto(m) for m in result.messages],
                    has_more=result.has_more,
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.GetMessagesResponse()

    async def edit_message(self, request, context):
        if not request.user_id or not request.message_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and message_id are required")
            return messaging_pb2.EditMessageResponse()

        async with self._container.request_scope() as scope:
            try:
                result = await scope.message_service.edit_message(
                    user_id=uuid.UUID(request.user_id),
                    message_id=uuid.UUID(request.message_id),
                    new_mls_ciphertext=request.new_mls_ciphertext,
                )
                return messaging_pb2.EditMessageResponse(message=_msg_to_proto(result))
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.EditMessageResponse()

    async def delete_message(self, request, context):
        if not request.user_id or not request.message_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and message_id are required")
            return messaging_pb2.DeleteMessageResponse()

        async with self._container.request_scope() as scope:
            try:
                success = await scope.message_service.delete_message(
                    user_id=uuid.UUID(request.user_id),
                    message_id=uuid.UUID(request.message_id),
                    delete_for_everyone=request.delete_for_everyone,
                )
                return messaging_pb2.DeleteMessageResponse(success=success)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.DeleteMessageResponse()

    async def mark_as_read(self, request, context):
        if not request.user_id or not request.conversation_id or not request.last_read_message_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, conversation_id, and last_read_message_id are required")
            return messaging_pb2.MarkAsReadResponse()

        async with self._container.request_scope() as scope:
            try:
                count = await scope.message_service.mark_as_read(
                    user_id=uuid.UUID(request.user_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    last_read_message_id=uuid.UUID(request.last_read_message_id),
                )
                return messaging_pb2.MarkAsReadResponse(unread_count=count)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.MarkAsReadResponse()


def _msg_to_proto(m) -> messaging_pb2.MessageEntry:
    return messaging_pb2.MessageEntry(
        id=str(m.id),
        sender_user_id=str(m.sender_user_id),
        sender_device_id=str(m.sender_device_id),
        type=m.type,
        mls_ciphertext=m.mls_ciphertext,
        media_id=str(m.media_id) if m.media_id else "",
        reply_to_message_id=str(m.reply_to_message_id) if m.reply_to_message_id else "",
        mls_epoch=m.mls_epoch or 0,
        created_at=int(m.created_at),
        edited_at=int(m.edited_at) if m.edited_at else 0,
        deleted_at=int(m.deleted_at) if m.deleted_at else 0,
    )
