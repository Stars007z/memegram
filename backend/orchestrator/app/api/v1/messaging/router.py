import asyncio
import json

from fastapi import APIRouter, Depends, Query, Response
from starlette.responses import StreamingResponse

from app.api.dependencies import get_contacts_gateway, get_current_session, get_messaging_gateway, get_user_gateway
from app.api.v1.messaging.schemas import (
    AckWelcomeResponseSchema,
    CommitEntrySchema,
    CommitGroupChangeRequestSchema,
    CommitGroupChangeResponseSchema,
    ConfirmMediaUploadResponseSchema,
    ConversationMemberSchema,
    ConversationResponseSchema,
    ConversationSummarySchema,
    CreateDirectConversationRequestSchema,
    CreateGroupConversationRequestSchema,
    DeleteKeyPackagesResponseSchema,
    DeleteMessageRequestSchema,
    DeleteMessageResponseSchema,
    EditMessageRequestSchema,
    GetConversationsResponseSchema,
    GetKeyPackagesForUserResponseSchema,
    GetMediaDownloadUrlResponseSchema,
    GetMessagesResponseSchema,
    GetPendingCommitsResponseSchema,
    GetPendingWelcomesResponseSchema,
    InitiateMediaUploadRequestSchema,
    InitiateMediaUploadResponseSchema,
    KeyPackagesCountResponseSchema,
    KickMemberResponseSchema,
    LeaveConversationRequestSchema,
    LeaveConversationResponseSchema,
    MarkAsReadRequestSchema,
    MarkAsReadResponseSchema,
    MessageEntrySchema,
    MessagingHealthResponseSchema,
    MlsGroupInfoSchema,
    SendMessageRequestSchema,
    SendMessageResponseSchema,
    SetTypingRequestSchema,
    SuccessResponseSchema,
    UpdateGroupAvatarRequestSchema,
    UpdateGroupNameRequestSchema,
    UpdateMemberRoleRequestSchema,
    UpdateMemberRoleResponseSchema,
    UploadKeyPackagesRequestSchema,
    UploadKeyPackagesResponseSchema,
    UserDeviceKeyPackageSchema,
    WelcomeEntrySchema,
    b64_to_bytes,
)
from app.core.interfaces.contacts_gateway import IContactsGateway
from app.core.interfaces.messaging_gateway import DeviceWelcome, IMessagingGateway, MemberWithWelcomes
from app.core.interfaces.user_gateway import IUserGateway
from app.core.session_context import SessionContext
from app.exceptions import NotFoundError, PermissionDeniedError
from app.logging_config import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/messaging", tags=["messaging"])


def _conv_to_schema(r, requester_user_id: str | None = None):
    mls = None
    if r.mls_group:
        mls = MlsGroupInfoSchema(
            current_epoch=r.mls_group.current_epoch,
            cipher_suite=r.mls_group.cipher_suite,
        )
    # For 1:1 chats, surface the peer's last_read_message_id at the top level
    # so the client can render the "read" double-tick on outgoing messages
    # even if the live SSE `message_read` event was missed (chat closed,
    # app backgrounded, etc.). Computed only when a requester is supplied
    # AND the conversation is direct AND a peer's row carries a non-empty id.
    peer_last_read: str | None = None
    if requester_user_id and r.type == "direct":
        for m in r.members:
            if m.user_id != requester_user_id and getattr(m, "last_read_message_id", ""):
                peer_last_read = m.last_read_message_id
                break
    return ConversationResponseSchema(
        id=r.id,
        type=r.type,
        name=r.name,
        members=[ConversationMemberSchema(user_id=m.user_id, role=m.role, joined_at=m.joined_at) for m in r.members],
        mls_group=mls,
        created_at=r.created_at,
        avatar_media_id=r.avatar_media_id,
        peer_last_read_message_id=peer_last_read,
    )


async def _augment_conv_with_blocks(
    schema: ConversationResponseSchema,
    requester_user_id: str,
    contacts_gw: IContactsGateway,
) -> ConversationResponseSchema:
    """For direct conversations, fill is_blocked_by_peer / is_peer_blocked
    by querying contacts-service. No-op for groups or self-DMs."""
    if schema.type != "direct":
        return schema
    peer_id = next(
        (m.user_id for m in schema.members if m.user_id != requester_user_id),
        None,
    )
    if not peer_id:
        return schema
    try:
        a, b = await asyncio.gather(
            contacts_gw.is_blocked(user_id=requester_user_id, blocked_user_id=peer_id),
            contacts_gw.is_blocked(user_id=peer_id, blocked_user_id=requester_user_id),
            return_exceptions=True,
        )
        if not isinstance(a, Exception):
            schema.is_peer_blocked = a.is_blocked
        else:
            logger.warning("conv.block_lookup_failed", side="peer", error=str(a))
        if not isinstance(b, Exception):
            schema.is_blocked_by_peer = b.is_blocked
        else:
            logger.warning("conv.block_lookup_failed", side="by_peer", error=str(b))
    except Exception as e:
        logger.warning("conv.block_flags_failed", error=str(e))
    return schema


def _msg_to_schema(m):
    return MessageEntrySchema(
        id=m.id,
        sender_user_id=m.sender_user_id,
        sender_device_id=m.sender_device_id,
        type=m.type,
        mls_ciphertext=m.mls_ciphertext,
        media_id=m.media_id,
        reply_to_message_id=m.reply_to_message_id,
        mls_epoch=m.mls_epoch,
        created_at=m.created_at,
        edited_at=m.edited_at,
        deleted_at=m.deleted_at,
    )


@router.post("/key-packages", response_model=UploadKeyPackagesResponseSchema, status_code=201)
async def upload_key_packages(
    body: UploadKeyPackagesRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    count = await gw.upload_key_packages(
        user_id=session.user_id,
        device_id=session.device_id,
        key_packages=[b64_to_bytes(kp) for kp in body.key_packages],
        signature_key=b64_to_bytes(body.signature_key) if body.signature_key else None,
    )
    return UploadKeyPackagesResponseSchema(uploaded_count=count)


@router.delete("/key-packages", response_model=DeleteKeyPackagesResponseSchema)
async def delete_my_key_packages(
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    """
    Purge all unconsumed key packages for the current session's device.
    Called by the client when its local MLS key-store is wiped
    (logout, account reset). Old KPs must not be handed out to peers
    because their private halves no longer exist on this device.
    """
    deleted = await gw.delete_key_packages_for_device(
        user_id=session.user_id,
        device_id=session.device_id,
    )
    return DeleteKeyPackagesResponseSchema(deleted_count=deleted)


@router.get(
    "/key-packages/by-user/{target_user_id}",
    response_model=GetKeyPackagesForUserResponseSchema,
)
async def get_key_packages_for_user(
    target_user_id: str,
    response: Response,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):

    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    results = await gw.get_key_packages_for_user(target_user_id)
    return GetKeyPackagesForUserResponseSchema(
        key_packages=[
            UserDeviceKeyPackageSchema(
                device_id=r.device_id,
                key_package_data=r.key_package_data,
                key_package_ref=r.key_package_ref,
            )
            for r in results
        ],
    )


@router.get("/key-packages/count", response_model=KeyPackagesCountResponseSchema)
async def get_key_packages_count(
    response: Response,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    count = await gw.get_key_packages_count(session.user_id, session.device_id)
    return KeyPackagesCountResponseSchema(available_count=count)


@router.post("/conversations/direct", response_model=ConversationResponseSchema, status_code=201)
async def create_direct_conversation(
    body: CreateDirectConversationRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
    contacts_gw: IContactsGateway = Depends(get_contacts_gateway),
    user_gw: IUserGateway = Depends(get_user_gateway),
):
    exists, is_deleted = await user_gw.user_exists(body.recipient_user_id)
    if not exists:
        raise NotFoundError("User not found")
    if is_deleted:
        raise PermissionDeniedError("Recipient account is deleted")

    result = await gw.create_direct_conversation(
        initiator_user_id=session.user_id,
        initiator_device_id=session.device_id,
        recipient_user_id=body.recipient_user_id,
        welcome_messages=[
            DeviceWelcome(device_id=w.device_id, welcome_data=b64_to_bytes(w.welcome_data))
            for w in body.welcome_messages
        ],
    )
    return await _augment_conv_with_blocks(_conv_to_schema(result, session.user_id), session.user_id, contacts_gw)


@router.post("/conversations/group", response_model=ConversationResponseSchema, status_code=201)
async def create_group_conversation(
    body: CreateGroupConversationRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    members = [
        MemberWithWelcomes(
            user_id=m.user_id,
            welcomes=[
                DeviceWelcome(device_id=w.device_id, welcome_data=b64_to_bytes(w.welcome_data)) for w in m.welcomes
            ],
        )
        for m in body.members
    ]
    result = await gw.create_group_conversation(
        creator_user_id=session.user_id,
        creator_device_id=session.device_id,
        name=body.name,
        members=members,
    )
    return _conv_to_schema(result)


@router.get("/conversations", response_model=GetConversationsResponseSchema)
async def get_conversations(
    response: Response,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
    limit: int = Query(20, ge=1, le=100),
    cursor: str = Query(""),
):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    result = await gw.get_conversations(session.user_id, limit, cursor)
    return GetConversationsResponseSchema(
        items=[
            ConversationSummarySchema(
                id=i.id,
                type=i.type,
                name=i.name,
                last_message_type=i.last_message_type,
                unread_count=i.unread_count,
                last_activity_at=i.last_activity_at,
                avatar_media_id=i.avatar_media_id,
            )
            for i in result.items
        ],
        next_cursor=result.next_cursor,
    )


@router.get("/conversations/{conversation_id}", response_model=ConversationResponseSchema)
async def get_conversation(
    conversation_id: str,
    response: Response,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
    contacts_gw: IContactsGateway = Depends(get_contacts_gateway),
):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    result = await gw.get_conversation(session.user_id, conversation_id)
    return await _augment_conv_with_blocks(_conv_to_schema(result, session.user_id), session.user_id, contacts_gw)


@router.post(
    "/conversations/{conversation_id}/leave",
    response_model=LeaveConversationResponseSchema,
)
async def leave_conversation(
    conversation_id: str,
    body: LeaveConversationRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.leave_conversation(
        user_id=session.user_id,
        device_id=session.device_id,
        conversation_id=conversation_id,
        commit_data=b64_to_bytes(body.commit_data),
    )
    return LeaveConversationResponseSchema(success=success)


@router.post(
    "/conversations/{conversation_id}/members/{target_user_id}/kick",
    response_model=KickMemberResponseSchema,
)
async def kick_member(
    conversation_id: str,
    target_user_id: str,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.kick_member(
        user_id=session.user_id,
        conversation_id=conversation_id,
        target_user_id=target_user_id,
    )
    return KickMemberResponseSchema(success=success)


@router.patch(
    "/conversations/{conversation_id}/members/{target_user_id}/role",
    response_model=UpdateMemberRoleResponseSchema,
)
async def update_member_role(
    conversation_id: str,
    target_user_id: str,
    body: UpdateMemberRoleRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.update_member_role(
        user_id=session.user_id,
        conversation_id=conversation_id,
        target_user_id=target_user_id,
        new_role=body.new_role,
    )
    return UpdateMemberRoleResponseSchema(success=success)


@router.patch(
    "/conversations/{conversation_id}/avatar",
    response_model=SuccessResponseSchema,
)
async def update_group_avatar(
    conversation_id: str,
    body: UpdateGroupAvatarRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.update_group_avatar(
        user_id=session.user_id,
        conversation_id=conversation_id,
        avatar_media_id=body.avatar_media_id,
    )
    return SuccessResponseSchema(success=success)


@router.patch(
    "/conversations/{conversation_id}/name",
    response_model=SuccessResponseSchema,
)
async def update_group_name(
    conversation_id: str,
    body: UpdateGroupNameRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.update_group_name(
        user_id=session.user_id,
        conversation_id=conversation_id,
        name=body.name,
    )
    return SuccessResponseSchema(success=success)


@router.delete(
    "/conversations/{conversation_id}",
    response_model=SuccessResponseSchema,
)
async def delete_conversation(
    conversation_id: str,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.delete_conversation(
        user_id=session.user_id,
        conversation_id=conversation_id,
    )
    return SuccessResponseSchema(success=success)


@router.post(
    "/conversations/{conversation_id}/messages",
    response_model=SendMessageResponseSchema,
    status_code=201,
)
async def send_message(
    conversation_id: str,
    body: SendMessageRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
    user_gw: IUserGateway = Depends(get_user_gateway),
):
    conv = await gw.get_conversation(session.user_id, conversation_id)
    if conv.type == "direct":
        peer_id = next((m.user_id for m in conv.members if m.user_id != session.user_id), None)
        if peer_id is None:
            raise PermissionDeniedError("Recipient is not available")
        exists, is_deleted = await user_gw.user_exists(peer_id)
        if not exists or is_deleted:
            raise PermissionDeniedError("Recipient account is deleted")

    result = await gw.send_message(
        sender_user_id=session.user_id,
        sender_device_id=session.device_id,
        conversation_id=conversation_id,
        mls_ciphertext=b64_to_bytes(body.mls_ciphertext),
        type=body.type,
        media_id=body.media_id or "",
        reply_to_message_id=body.reply_to_message_id or "",
        client_message_id=body.client_message_id,
    )
    return SendMessageResponseSchema(message_id=result.message_id, created_at=result.created_at)


@router.get(
    "/conversations/{conversation_id}/messages",
    response_model=GetMessagesResponseSchema,
)
async def get_messages(
    conversation_id: str,
    response: Response,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
    before_message_id: str = Query(""),
    limit: int = Query(50, ge=1, le=100),
):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    result = await gw.get_messages(
        user_id=session.user_id,
        conversation_id=conversation_id,
        before_message_id=before_message_id,
        limit=limit,
    )
    return GetMessagesResponseSchema(
        messages=[_msg_to_schema(m) for m in result.messages],
        has_more=result.has_more,
    )


@router.patch("/messages/{message_id}", response_model=MessageEntrySchema)
async def edit_message(
    message_id: str,
    body: EditMessageRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    result = await gw.edit_message(
        user_id=session.user_id,
        device_id=session.device_id,
        message_id=message_id,
        new_mls_ciphertext=b64_to_bytes(body.new_mls_ciphertext),
    )
    return _msg_to_schema(result)


@router.delete("/messages/{message_id}", response_model=DeleteMessageResponseSchema)
async def delete_message(
    message_id: str,
    body: DeleteMessageRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.delete_message(
        user_id=session.user_id,
        message_id=message_id,
        delete_for_everyone=body.delete_for_everyone,
    )
    return DeleteMessageResponseSchema(success=success)


@router.post(
    "/conversations/{conversation_id}/read",
    response_model=MarkAsReadResponseSchema,
)
async def mark_as_read(
    conversation_id: str,
    body: MarkAsReadRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    unread = await gw.mark_as_read(
        user_id=session.user_id,
        device_id=session.device_id,
        conversation_id=conversation_id,
        last_read_message_id=body.last_read_message_id,
    )
    return MarkAsReadResponseSchema(unread_count=unread)


@router.post(
    "/conversations/{conversation_id}/mls/commit",
    response_model=CommitGroupChangeResponseSchema,
)
async def commit_group_change(
    conversation_id: str,
    body: CommitGroupChangeRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    welcomes = [
        DeviceWelcome(device_id=w.device_id, welcome_data=b64_to_bytes(w.welcome_data)) for w in body.welcome_messages
    ]
    result = await gw.commit_group_change(
        user_id=session.user_id,
        device_id=session.device_id,
        conversation_id=conversation_id,
        commit_data=b64_to_bytes(body.commit_data),
        new_epoch=body.new_epoch,
        welcome_messages=welcomes,
        ratchet_tree=b64_to_bytes(body.ratchet_tree) if body.ratchet_tree else b"",
        removed_device_ids=body.removed_device_ids,
        added_user_ids=body.added_user_ids,
    )
    return CommitGroupChangeResponseSchema(
        new_epoch=result.new_epoch,
        committed_at=result.committed_at,
    )


@router.get("/welcomes", response_model=GetPendingWelcomesResponseSchema)
async def get_pending_welcomes(
    response: Response,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    items = await gw.get_pending_welcomes(session.device_id)
    return GetPendingWelcomesResponseSchema(
        items=[
            WelcomeEntrySchema(
                id=w.id,
                conversation_id=w.conversation_id,
                welcome_data=w.welcome_data,
                created_at=w.created_at,
            )
            for w in items
        ],
    )


@router.post("/welcomes/{welcome_id}/ack", response_model=AckWelcomeResponseSchema)
async def ack_welcome(
    welcome_id: str,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.ack_welcome(session.device_id, welcome_id)
    return AckWelcomeResponseSchema(success=success)


@router.get(
    "/conversations/{conversation_id}/mls/commits",
    response_model=GetPendingCommitsResponseSchema,
)
async def get_pending_commits(
    conversation_id: str,
    response: Response,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
    since_epoch: int = Query(0, ge=0),
):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    commits = await gw.get_pending_commits(
        session.device_id,
        conversation_id,
        since_epoch,
    )
    return GetPendingCommitsResponseSchema(
        commits=[
            CommitEntrySchema(
                epoch=c.epoch,
                commit_data=c.commit_data,
                created_at=c.created_at,
            )
            for c in commits
        ],
    )


@router.post("/media/upload", response_model=InitiateMediaUploadResponseSchema, status_code=201)
async def initiate_media_upload(
    body: InitiateMediaUploadRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    result = await gw.initiate_media_upload(
        user_id=session.user_id,
        conversation_id=body.conversation_id,
        mime_type=body.mime_type,
        encrypted_size=body.encrypted_size,
        encryption_metadata=b64_to_bytes(body.encryption_metadata),
    )
    return InitiateMediaUploadResponseSchema(
        media_id=result.media_id,
        upload_url=result.upload_url,
        expires_in=result.expires_in,
    )


@router.post("/media/{media_id}/confirm", response_model=ConfirmMediaUploadResponseSchema)
async def confirm_media_upload(
    media_id: str,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.confirm_media_upload(session.user_id, media_id)
    return ConfirmMediaUploadResponseSchema(success=success)


@router.get("/media/{media_id}/download", response_model=GetMediaDownloadUrlResponseSchema)
async def get_media_download_url(
    media_id: str,
    response: Response,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    result = await gw.get_media_download_url(session.user_id, media_id)
    return GetMediaDownloadUrlResponseSchema(
        download_url=result.download_url,
        expires_in=result.expires_in,
        encryption_metadata=result.encryption_metadata,
    )


@router.post("/typing", response_model=SuccessResponseSchema)
async def set_typing(
    body: SetTypingRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.set_typing(
        user_id=session.user_id,
        device_id=session.device_id,
        conversation_id=body.conversation_id,
        is_typing=body.is_typing,
    )
    return SuccessResponseSchema(success=success)


@router.post("/online", response_model=SuccessResponseSchema)
async def set_online(
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    success = await gw.set_online(session.user_id, session.device_id)
    return SuccessResponseSchema(success=success)


@router.get("/events")
async def subscribe_events(
    conversation_ids: str = Query(..., description="Comma-separated conversation IDs"),
    session: SessionContext = Depends(get_current_session),
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    ids = [cid.strip() for cid in conversation_ids.split(",") if cid.strip()]

    async def event_stream():
        async for event in gw.subscribe_to_conversations(
            user_id=session.user_id,
            device_id=session.device_id,
            conversation_ids=ids,
        ):
            yield f"data: {json.dumps(event)}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.get("/health", response_model=MessagingHealthResponseSchema)
async def messaging_health(
    gw: IMessagingGateway = Depends(get_messaging_gateway),
):
    result = await gw.health_check()
    return MessagingHealthResponseSchema(**result.__dict__)
