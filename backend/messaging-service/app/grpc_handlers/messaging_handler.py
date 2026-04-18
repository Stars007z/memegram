"""
Main gRPC servicer — thin delegate to domain-specific sub-handlers.
Each sub-handler owns a domain slice (conversations, messages, mls, media, presence, health).
"""

from app.container import Container
from app.generated import messaging_pb2_grpc
from app.grpc_handlers.conversation_handler import ConversationHandler
from app.grpc_handlers.health_handler import HealthHandler
from app.grpc_handlers.media_handler import MediaHandler
from app.grpc_handlers.message_handler import MessageHandler
from app.grpc_handlers.mls_handler import MlsHandler
from app.grpc_handlers.presence_handler import PresenceHandler


class MessagingHandler(messaging_pb2_grpc.MessagingServiceServicer):

    def __init__(self, container: Container) -> None:
        self._conversations = ConversationHandler(container)
        self._messages = MessageHandler(container)
        self._mls = MlsHandler(container)
        self._media = MediaHandler(container)
        self._presence = PresenceHandler(container)
        self._health = HealthHandler(container)

    async def UploadKeyPackages(self, request, context):
        return await self._mls.upload_key_packages(request, context)

    async def GetKeyPackage(self, request, context):
        return await self._mls.get_key_package(request, context)

    async def GetKeyPackagesCount(self, request, context):
        return await self._mls.get_key_packages_count(request, context)

    async def GetKeyPackagesForUser(self, request, context):
        return await self._mls.get_key_packages_for_user(request, context)

    async def DeleteKeyPackagesForDevice(self, request, context):
        return await self._mls.delete_key_packages_for_device(request, context)

    async def CreateDirectConversation(self, request, context):
        return await self._conversations.create_direct(request, context)

    async def CreateGroupConversation(self, request, context):
        return await self._conversations.create_group(request, context)

    async def GetConversations(self, request, context):
        return await self._conversations.get_conversations(request, context)

    async def GetConversation(self, request, context):
        return await self._conversations.get_conversation(request, context)

    async def LeaveConversation(self, request, context):
        return await self._conversations.leave_conversation(request, context)

    async def KickMember(self, request, context):
        return await self._conversations.kick_member(request, context)

    async def UpdateMemberRole(self, request, context):
        return await self._conversations.update_member_role(request, context)

    async def UpdateGroupAvatar(self, request, context):
        return await self._conversations.update_group_avatar(request, context)

    async def UpdateGroupName(self, request, context):
        return await self._conversations.update_group_name(request, context)

    async def DeleteConversation(self, request, context):
        return await self._conversations.delete_conversation(request, context)

    async def SendMessage(self, request, context):
        return await self._messages.send_message(request, context)

    async def GetMessages(self, request, context):
        return await self._messages.get_messages(request, context)

    async def EditMessage(self, request, context):
        return await self._messages.edit_message(request, context)

    async def DeleteMessage(self, request, context):
        return await self._messages.delete_message(request, context)

    async def MarkAsRead(self, request, context):
        return await self._messages.mark_as_read(request, context)

    async def SubscribeToConversation(self, request, context):
        async for event in self._presence.subscribe(request, context):
            yield event

    async def CommitGroupChange(self, request, context):
        return await self._mls.commit_group_change(request, context)

    async def GetPendingWelcomes(self, request, context):
        return await self._mls.get_pending_welcomes(request, context)

    async def AckWelcome(self, request, context):
        return await self._mls.ack_welcome(request, context)

    async def GetPendingCommits(self, request, context):
        return await self._mls.get_pending_commits(request, context)

    async def NotifyDeviceRevoked(self, request, context):
        return await self._mls.notify_device_revoked(request, context)

    async def InitiateMediaUpload(self, request, context):
        return await self._media.initiate_media_upload(request, context)

    async def ConfirmMediaUpload(self, request, context):
        return await self._media.confirm_media_upload(request, context)

    async def GetMediaDownloadUrl(self, request, context):
        return await self._media.get_media_download_url(request, context)

    async def SetTyping(self, request, context):
        return await self._presence.set_typing(request, context)

    async def SetOnline(self, request, context):
        return await self._presence.set_online(request, context)

    async def GetConversationMembers(self, request, context):
        return await self._health.get_conversation_members(request, context)

    async def HealthCheck(self, request, context):
        return await self._health.health_check(request, context)
