import uuid

import grpc

from app.container import Container
from app.generated import messaging_pb2

class PresenceHandler:

    def __init__(self, container: Container) -> None:
        self._container = container

    async def set_typing(self, request, context):
        if not request.user_id or not request.conversation_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and conversation_id are required")
            return messaging_pb2.SetTypingResponse()

        try:
            success = await self._container.presence_service.set_typing(
                user_id=uuid.UUID(request.user_id),
                device_id=uuid.UUID(request.device_id),
                conversation_id=uuid.UUID(request.conversation_id),
                is_typing=request.is_typing,
            )
            return messaging_pb2.SetTypingResponse(success=success)
        except Exception as e:
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return messaging_pb2.SetTypingResponse()

    async def set_online(self, request, context):
        if not request.user_id or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and device_id are required")
            return messaging_pb2.SetOnlineResponse()

        try:
            success = await self._container.presence_service.set_online(
                user_id=uuid.UUID(request.user_id),
                device_id=uuid.UUID(request.device_id),
            )
            return messaging_pb2.SetOnlineResponse(success=success)
        except Exception as e:
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return messaging_pb2.SetOnlineResponse()

    async def subscribe(self, request, context):
        if not request.user_id or not request.conversation_ids:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and conversation_ids are required")
            return

        conversation_ids = [uuid.UUID(cid) for cid in request.conversation_ids]

        async for event in self._container.stream_service.subscribe(
            user_id=uuid.UUID(request.user_id),
            device_id=uuid.UUID(request.device_id),
            conversation_ids=conversation_ids,
        ):
            proto_event = _event_to_proto(event)
            if proto_event:
                yield proto_event

def _event_to_proto(event: dict) -> messaging_pb2.ConversationEvent | None:
    conv_id = event.get("conversation_id", "")
    event_type = event.get("event_type")

    if event_type == "new_message":
        msg = event["message"]
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            new_message=messaging_pb2.MessageEntry(
                id=msg["id"],
                sender_user_id=msg["sender_user_id"],
                sender_device_id=msg["sender_device_id"],
                type=msg["type"],
                mls_ciphertext=bytes.fromhex(msg["mls_ciphertext"]),
                media_id=msg.get("media_id", ""),
                reply_to_message_id=msg.get("reply_to_message_id", ""),
                mls_epoch=msg.get("mls_epoch", 0),
                created_at=int(msg["created_at"]),
                edited_at=int(msg.get("edited_at", 0)),
                deleted_at=int(msg.get("deleted_at", 0)),
            ),
        )

    if event_type == "message_edited":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            message_edited=messaging_pb2.MessageEdited(
                message_id=event["message_id"],
                new_mls_ciphertext=bytes.fromhex(event["new_mls_ciphertext"]),
                edited_at=int(event["edited_at"]),
            ),
        )

    if event_type == "message_deleted":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            message_deleted=messaging_pb2.MessageDeleted(
                message_id=event["message_id"],
            ),
        )

    if event_type == "typing":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            typing=messaging_pb2.TypingEvent(
                user_id=event["user_id"],
                is_typing=event["is_typing"],
            ),
        )

    if event_type == "member_joined":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            member_joined=messaging_pb2.MemberEvent(user_id=event["user_id"]),
        )

    if event_type == "member_left":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            member_left=messaging_pb2.MemberEvent(user_id=event["user_id"]),
        )

    if event_type == "member_kicked":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            member_kicked=messaging_pb2.MemberKickedEvent(
                user_id=event["user_id"],
                kicked_by=event.get("kicked_by", ""),
            ),
        )

    if event_type == "role_changed":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            role_changed=messaging_pb2.RoleChangedEvent(
                user_id=event["user_id"],
                new_role=event.get("new_role", ""),
            ),
        )

    if event_type == "epoch_changed":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            epoch_changed=messaging_pb2.EpochChanged(
                new_epoch=event["new_epoch"],
            ),
        )

    if event_type == "device_revoked":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            device_revoked=messaging_pb2.DeviceRevoked(
                user_id=event["user_id"],
                revoked_device_id=event["revoked_device_id"],
                conversation_ids=event.get("conversation_ids", []),
            ),
        )

    if event_type == "conversation_deleted":
        return messaging_pb2.ConversationEvent(
            conversation_id=conv_id,
            conversation_deleted=messaging_pb2.ConversationDeletedEvent(
                deleted_by=event.get("deleted_by", ""),
            ),
        )

    return None
