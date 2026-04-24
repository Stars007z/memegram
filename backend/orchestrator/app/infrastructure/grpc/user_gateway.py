import grpc

from app.config import Settings
from app.core.interfaces.user_gateway import (
    AutoDeleteResult,
    CreateUserResult,
    IUserGateway,
    UpdateUserRequest,
    UpdateUserSettingsRequest,
    UserProfileResult,
    UserSettingsResult,
)
from app.infrastructure.grpc.client import GrpcChannelManager
from app.infrastructure.grpc.errors import grpc_error_to_exception
from app.infrastructure.grpc.generated import user_pb2, user_pb2_grpc

_SERVICE = "User service"


def _profile_from_response(profile) -> UserProfileResult:
    last_active = 0
    try:
        if profile.HasField("last_active"):
            last_active = profile.last_active
    except Exception:
        last_active = profile.last_active or 0
    return UserProfileResult(
        id=profile.id,
        username=profile.username,
        user_public_key=profile.user_public_key,
        bio=profile.bio,
        last_active=last_active,
        is_deleted=profile.is_deleted,
        avatar_media_id=profile.avatar_media_id or None,
        profile_background_media_id=profile.profile_background_media_id or None,
    )


def _settings_from_response(s) -> UserSettingsResult:
    def _opt_int(field: str) -> int:
        try:
            return getattr(s, field) if s.HasField(field) else 0
        except Exception:
            return getattr(s, field, 0) or 0

    def _opt_str(field: str) -> str:
        try:
            return getattr(s, field) if s.HasField(field) else ""
        except Exception:
            return getattr(s, field, "") or ""

    def _opt_media_id(field: str) -> str | None:
        val = _opt_str(field)
        return val if val else None

    return UserSettingsResult(
        id=s.user_id,
        user_id=s.user_id,
        theme=s.theme,
        language=s.language,
        is_translator_active=s.is_translator_active,
        animations_enabled=s.animations_enabled,
        account_auto_delete_after_days=_opt_int("account_auto_delete_after_days"),
        profile_visible_to=s.profile_visible_to,
        last_active_visible_to=s.last_active_visible_to,
        top_bar_color=_opt_str("top_bar_color"),
        chat_background_media_id=_opt_media_id("chat_background_media_id"),
        top_bar_media_id=_opt_media_id("top_bar_media_id"),
        my_bubble_media_id=_opt_media_id("my_bubble_media_id"),
        their_bubble_media_id=_opt_media_id("their_bubble_media_id"),
    )


class GrpcUserGateway(IUserGateway):

    def __init__(self, channels: GrpcChannelManager, settings: Settings):
        self._channels = channels
        self._settings = settings

    def _stub(self) -> user_pb2_grpc.UserServiceStub:
        return user_pb2_grpc.UserServiceStub(self._channels.get("user"))

    async def create_user(self, user_id: str, username: str) -> CreateUserResult:
        metadata = [("x-user-id", user_id)]
        try:
            response = await self._stub().CreateUser(
                user_pb2.CreateUserRequest(username=username),
                metadata=metadata,
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return CreateUserResult(id=response.profile.id, username=response.profile.username)

    async def get_user(self, user_id: str, requester_user_id: str) -> UserProfileResult:
        try:
            response = await self._stub().GetUser(
                user_pb2.GetUserRequest(
                    user_id=user_id,
                    requester_user_id=requester_user_id,
                ),
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return _profile_from_response(response.profile)

    async def get_user_by_public_key(
        self,
        user_public_key: str,
        requester_user_id: str,
    ) -> UserProfileResult:
        try:
            response = await self._stub().GetUserByUserPublicKey(
                user_pb2.GetUserByUserPublicKeyRequest(
                    user_public_key=user_public_key,
                    requester_user_id=requester_user_id,
                ),
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return _profile_from_response(response.profile)

    async def update_user(self, request: UpdateUserRequest) -> UserProfileResult:
        pb_request = user_pb2.UpdateUserRequest(user_id=request.user_id)
        if request.bio is not None:
            pb_request.bio = request.bio
        if request.username is not None:
            pb_request.username = request.username
        if request.avatar_media_id is not None:
            pb_request.avatar_media_id = request.avatar_media_id
        if request.profile_background_media_id is not None:
            pb_request.profile_background_media_id = request.profile_background_media_id
        try:
            response = await self._stub().UpdateUser(
                pb_request,
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return _profile_from_response(response.profile)

    async def delete_user(self, user_id: str) -> bool:
        try:
            response = await self._stub().DeleteUser(
                user_pb2.DeleteUserRequest(user_id=user_id),
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return response.success

    async def get_user_settings(self, user_id: str) -> UserSettingsResult:
        try:
            response = await self._stub().GetUserSettings(
                user_pb2.GetUserSettingsRequest(user_id=user_id),
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return _settings_from_response(response.settings)

    async def update_user_settings(
        self,
        request: UpdateUserSettingsRequest,
    ) -> UserSettingsResult:
        pb_request = user_pb2.UpdateUserSettingsRequest(user_id=request.user_id)
        optional_fields = [
            "theme",
            "language",
            "is_translator_active",
            "animations_enabled",
            "account_auto_delete_after_days",
            "profile_visible_to",
            "last_active_visible_to",
            "chat_background_media_id",
            "top_bar_color",
            "top_bar_media_id",
            "my_bubble_media_id",
            "their_bubble_media_id",
        ]
        for f in optional_fields:
            val = getattr(request, f, None)
            if val is not None:
                setattr(pb_request, f, val)
        try:
            response = await self._stub().UpdateUserSettings(
                pb_request,
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return _settings_from_response(response.settings)

    async def health_check(self) -> dict:
        try:
            response = await self._stub().HealthCheck(
                user_pb2.HealthCheckRequest(),
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
            return {
                "status": response.status,
                "db_status": response.db_status,
                "version": response.version,
            }
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)

    async def update_last_active(self, user_id: str) -> bool:
        try:
            response = await self._stub().UpdateLastActive(
                user_pb2.UpdateLastActiveRequest(user_id=user_id),
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
            return response.success
        except grpc.RpcError:
            return False

    async def check_and_process_auto_delete(self) -> AutoDeleteResult:
        try:
            response = await self._stub().CheckAndProcessAutoDelete(
                user_pb2.CheckAndProcessAutoDeleteRequest(),
                timeout=self._settings.USER_GRPC_TIMEOUT,
            )
            return AutoDeleteResult(
                deleted_count=response.deleted_count,
                user_ids=list(response.user_ids),
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
