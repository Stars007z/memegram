import grpc
from pathlib import Path
from app.config import settings
from app.core.interfaces.user_gateway import (
    IUserGateway, UserProfileResult, UserSettingsResult,
    UpdateUserRequest, UpdateUserSettingsRequest, CreateUserResult, AutoDeleteResult
)
from app.exceptions import GatewayError, NotFoundError, ValidationError, PermissionDeniedError
from app.infrastructure.grpc.client import get_user_grpc_channel
from app.infrastructure.grpc.generated import user_pb2, user_pb2_grpc

STATIC_DIR = Path(__file__).parent.parent.parent / "api" / "static"


# Заглушки читаются один раз при старте
DEFAULT_AVATAR_BYTES = (STATIC_DIR / "default_avatar.jpg").read_bytes()
DEFAULT_PROFILE_BG_BYTES = (STATIC_DIR / "default_profile_bg.jpg").read_bytes()
DEFAULT_CHAT_BG_BYTES = (STATIC_DIR / "default_chat_bg.jpg").read_bytes()
DEFAULT_RINGTONE_BYTES = (STATIC_DIR / "default_ringtone.mp3").read_bytes()
DEFAULT_NOTIFICATION_BYTES = (STATIC_DIR / "default_notification.mp3").read_bytes()


def _grpc_error_to_exception(e: grpc.RpcError) -> Exception:
    code = e.code()
    details = e.details() or "Unknown gRPC error"
    if code == grpc.StatusCode.INVALID_ARGUMENT:
        return ValidationError(details)
    if code == grpc.StatusCode.NOT_FOUND:
        return NotFoundError(details)
    if code == grpc.StatusCode.PERMISSION_DENIED:
        return PermissionDeniedError(details)
    if code == grpc.StatusCode.UNAVAILABLE:
        return GatewayError("User service is unavailable", code=503)
    return GatewayError(f"User service error: {details}", code=502)


def _profile_from_response(r) -> UserProfileResult:
    return UserProfileResult(
        id=r.id,
        username=r.username,
        # TODO: item-storage — запросить байты аватара по r.avatar_media_id
        avatar_data=DEFAULT_AVATAR_BYTES,
        # TODO: item-storage — запросить байты фона профиля по r.profile_background_media_id
        profile_background_data=DEFAULT_PROFILE_BG_BYTES,
        user_public_key=r.user_public_key,
        bio=r.bio,
        last_active=r.last_active,
        is_deleted=r.is_deleted,
    )


def _settings_from_response(s) -> UserSettingsResult:
    return UserSettingsResult(
        id=s.id,
        user_id=s.user_id,
        theme=s.theme,
        language=s.language,
        is_translator_active=s.is_translator_active,
        animations_enabled=s.animations_enabled,
        account_auto_delete_after_days=s.account_auto_delete_after_days,
        profile_visible_to=s.profile_visible_to,
        last_active_visible_to=s.last_active_visible_to,
        # TODO: item-storage — запросить байты фона чата по s.chat_background_media_id
        chat_background_data=DEFAULT_CHAT_BG_BYTES,
        top_bar_color=s.top_bar_color,
        # TODO: item-storage — запросить байты рингтона по s.ringtone_media_id
        ringtone_data=DEFAULT_RINGTONE_BYTES,
        ringtone_vibration_strength=s.ringtone_vibration_strength,
        # TODO: item-storage — запросить байты звука уведомлений по s.notification_sound
        notification_sound_data=DEFAULT_NOTIFICATION_BYTES,
        notification_vibration_strength=s.notification_vibration_strength,
    )


class GrpcUserGateway(IUserGateway):
    async def _get_stub(self) -> user_pb2_grpc.UserServiceStub:
        channel = await get_user_grpc_channel()
        return user_pb2_grpc.UserServiceStub(channel)

    async def get_user(self, user_id: str, requester_user_id: str) -> UserProfileResult:
        stub = await self._get_stub()
        try:
            response = await stub.GetUser(
                user_pb2.GetUserRequest(user_id=user_id, requester_user_id=requester_user_id),
                timeout=settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return _profile_from_response(response.profile)

    async def get_user_by_public_key(self, user_public_key: str, requester_user_id: str) -> UserProfileResult:
        stub = await self._get_stub()
        try:
            response = await stub.GetUserByUserPublicKey(
                user_pb2.GetUserByUserPublicKeyRequest(
                    user_public_key=user_public_key,
                    requester_user_id=requester_user_id,
                ),
                timeout=settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return _profile_from_response(response.profile)

    async def update_user(self, request: UpdateUserRequest) -> UserProfileResult:
        stub = await self._get_stub()
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
            response = await stub.UpdateUser(pb_request, timeout=settings.USER_GRPC_TIMEOUT)
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return _profile_from_response(response.profile)

    async def delete_user(self, user_id: str) -> bool:
        stub = await self._get_stub()
        try:
            response = await stub.DeleteUser(
                user_pb2.DeleteUserRequest(user_id=user_id),
                timeout=settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return response.success

    async def get_user_settings(self, user_id: str) -> UserSettingsResult:
        stub = await self._get_stub()
        try:
            response = await stub.GetUserSettings(
                user_pb2.GetUserSettingsRequest(user_id=user_id),
                timeout=settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return _settings_from_response(response.settings)

    async def update_user_settings(self, request: UpdateUserSettingsRequest) -> UserSettingsResult:
        stub = await self._get_stub()
        pb_request = user_pb2.UpdateUserSettingsRequest(user_id=request.user_id)
        optional_fields = [
            "theme", "language", "is_translator_active", "animations_enabled",
            "account_auto_delete_after_days", "profile_visible_to", "last_active_visible_to",
            "chat_background_media_id", "top_bar_color", "ringtone_media_id",
            "ringtone_vibration_strength", "notification_sound", "notification_vibration_strength",
        ]
        for f in optional_fields:
            val = getattr(request, f, None)
            if val is not None:
                setattr(pb_request, f, val)
        try:
            response = await stub.UpdateUserSettings(pb_request, timeout=settings.USER_GRPC_TIMEOUT)
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return _settings_from_response(response.settings)

    async def health_check(self) -> dict:
        stub = await self._get_stub()
        try:
            response = await stub.HealthCheck(
                user_pb2.HealthCheckRequest(),
                timeout=settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return {"status": response.status, "db_status": response.db_status, "version": response.version}

    async def create_user(self, userid: str, username: str) -> CreateUserResult:
        stub = await self.get_stub()
        metadata = [("x-user-id", userid)]
        try:
            response = await stub.CreateUser(
                user_pb2.CreateUserRequest(username=username),
                metadata=metadata,
                timeout=settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return CreateUserResult(
            id=response.profile.id,
            username=response.profile.username,
        )

    async def update_last_active(self, user_id: str) -> bool:
        stub = await self.get_stub()
        try:
            response = await stub.UpdateLastActive(
                user_pb2.UpdateLastActiveRequest(user_id=user_id),
                timeout=settings.USER_GRPC_TIMEOUT,
            )
            return response.success
        except grpc.RpcError:
            return False

    async def check_and_process_auto_delete(self) -> "AutoDeleteResult":
        stub = await self.get_stub()
        try:
            response = await stub.CheckAndProcessAutoDelete(
                user_pb2.CheckAndProcessAutoDeleteRequest(),
                timeout=settings.USER_GRPC_TIMEOUT,
            )
            return AutoDeleteResult(
                deleted_count=response.deleted_count,
                user_ids=list(response.user_ids),
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
