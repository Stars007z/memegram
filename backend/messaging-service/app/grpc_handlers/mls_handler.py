import uuid

import grpc

from app.container import Container
from app.generated import messaging_pb2
from app.grpc_handlers.conversation_handler import _set_error_from_value_error


class MlsHandler:

    def __init__(self, container: Container) -> None:
        self._container = container

    async def upload_key_packages(self, request, context):
        if not request.user_id or not request.device_id or not request.key_packages:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, device_id, and key_packages are required")
            return messaging_pb2.UploadKeyPackagesResponse()

        async with self._container.request_scope() as scope:
            try:
                count = await scope.mls_service.upload_key_packages(
                    user_id=uuid.UUID(request.user_id),
                    device_id=uuid.UUID(request.device_id),
                    key_packages=list(request.key_packages),
                )
                return messaging_pb2.UploadKeyPackagesResponse(uploaded_count=count)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.UploadKeyPackagesResponse()

    async def get_key_package(self, request, context):
        if not request.target_user_id or not request.target_device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("target_user_id and target_device_id are required")
            return messaging_pb2.GetKeyPackageResponse()

        async with self._container.request_scope() as scope:
            try:
                result = await scope.mls_service.get_key_package(
                    target_user_id=uuid.UUID(request.target_user_id),
                    target_device_id=uuid.UUID(request.target_device_id),
                )
                return messaging_pb2.GetKeyPackageResponse(
                    key_package_data=result.key_package_data,
                    key_package_ref=result.key_package_ref,
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.GetKeyPackageResponse()

    async def get_key_packages_count(self, request, context):
        if not request.user_id or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and device_id are required")
            return messaging_pb2.GetKeyPackagesCountResponse()

        async with self._container.request_scope() as scope:
            try:
                count = await scope.mls_service.get_key_packages_count(
                    user_id=uuid.UUID(request.user_id),
                    device_id=uuid.UUID(request.device_id),
                )
                return messaging_pb2.GetKeyPackagesCountResponse(available_count=count)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.GetKeyPackagesCountResponse()

    async def commit_group_change(self, request, context):
        if not request.user_id or not request.conversation_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and conversation_id are required")
            return messaging_pb2.CommitGroupChangeResponse()

        async with self._container.request_scope() as scope:
            try:
                welcomes = None
                if request.welcome_messages:
                    welcomes = [
                        (uuid.UUID(w.device_id), w.welcome_data)
                        for w in request.welcome_messages
                    ]
                removed = None
                if request.removed_device_ids:
                    removed = [uuid.UUID(d) for d in request.removed_device_ids]

                result = await scope.mls_service.commit_group_change(
                    user_id=uuid.UUID(request.user_id),
                    device_id=uuid.UUID(request.device_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    commit_data=request.commit_data,
                    new_epoch=request.new_epoch,
                    welcome_messages=welcomes,
                    ratchet_tree=request.ratchet_tree if request.ratchet_tree else None,
                    removed_device_ids=removed,
                )
                return messaging_pb2.CommitGroupChangeResponse(
                    new_epoch=result.new_epoch,
                    committed_at=int(result.committed_at),
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.CommitGroupChangeResponse()

    async def get_pending_welcomes(self, request, context):
        if not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("device_id is required")
            return messaging_pb2.GetPendingWelcomesResponse()

        async with self._container.request_scope() as scope:
            try:
                items = await scope.mls_service.get_pending_welcomes(
                    device_id=uuid.UUID(request.device_id),
                )
                return messaging_pb2.GetPendingWelcomesResponse(
                    items=[
                        messaging_pb2.GetPendingWelcomesResponse.WelcomeEntry(
                            id=str(w.id),
                            conversation_id=str(w.conversation_id),
                            welcome_data=w.welcome_data,
                            created_at=int(w.created_at),
                        )
                        for w in items
                    ]
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.GetPendingWelcomesResponse()

    async def ack_welcome(self, request, context):
        if not request.device_id or not request.welcome_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("device_id and welcome_id are required")
            return messaging_pb2.AckWelcomeResponse()

        async with self._container.request_scope() as scope:
            try:
                success = await scope.mls_service.ack_welcome(
                    device_id=uuid.UUID(request.device_id),
                    welcome_id=uuid.UUID(request.welcome_id),
                )
                return messaging_pb2.AckWelcomeResponse(success=success)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.AckWelcomeResponse()

    async def get_pending_commits(self, request, context):
        if not request.conversation_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("conversation_id is required")
            return messaging_pb2.GetPendingCommitsResponse()

        async with self._container.request_scope() as scope:
            try:
                commits = await scope.mls_service.get_pending_commits(
                    conversation_id=uuid.UUID(request.conversation_id),
                    since_epoch=request.since_epoch,
                )
                return messaging_pb2.GetPendingCommitsResponse(
                    commits=[
                        messaging_pb2.GetPendingCommitsResponse.CommitEntry(
                            epoch=c.epoch,
                            commit_data=c.commit_data,
                            created_at=int(c.created_at),
                        )
                        for c in commits
                    ]
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.GetPendingCommitsResponse()

    async def notify_device_revoked(self, request, context):
        if not request.user_id or not request.revoked_device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and revoked_device_id are required")
            return messaging_pb2.NotifyDeviceRevokedResponse()

        async with self._container.request_scope() as scope:
            try:
                count = await scope.mls_service.notify_device_revoked(
                    user_id=uuid.UUID(request.user_id),
                    revoked_device_id=uuid.UUID(request.revoked_device_id),
                )
                return messaging_pb2.NotifyDeviceRevokedResponse(
                    notified_conversations_count=count,
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.NotifyDeviceRevokedResponse()
