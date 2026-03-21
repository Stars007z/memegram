import grpc
from typing import Optional

from app.config import settings
from app.core.interfaces.user_gateway import (
    IUserGateway,
    UserProfileResult,
    UserSettingsResult,
    UpdateUserRequest,
    UpdateUserSettingsRequest,
    CreateUserResult,
    AutoDeleteResult,
)
from app.exceptions import GatewayError, NotFoundError, ValidationError, PermissionDeniedError
from app.infrastructure.grpc.client import get_user_grpc_channel
from app.infrastructure.grpc.generated import user_pb2, user_pb2_grpc


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
        avatar_data=None,
        profile_background_data=None,
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
        ringtone_vibration_strength=_opt_int("ringtone_vibration_strength"),
        notification_vibration_strength=_opt_int("notification_vibration_strength"),
        chat_background_data=None,
        ringtone_data=None,
        notification_sound_data=None,
    )


class GrpcUserGateway(IUserGateway):

    async def _get_stub(self) -> user_pb2_grpc.UserServiceStub:
        channel = await get_user_grpc_channel()
        return user_pb2_grpc.UserServiceStub(channel)

    # ── Public methods ──────────────────────────────────────────────────

    async def create_user(self, user_id: str, username: str) -> CreateUserResult:
        stub = await self._get_stub()
        metadata = [("x-user-id", user_id)]
        try:
            response = await stub.CreateUser(
                user_pb2.CreateUserRequest(username=username),
                metadata=metadata,
                timeout=settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return CreateUserResult(id=response.profile.id, username=response.profile.username)

    async def get_user(self, user_id: str, requester_user_id: str) -> UserProfileResult:
        stub = await self._get_stub()
        try:
            response = await stub.GetUser(
                user_pb2.GetUserRequest(
                    user_id=user_id,
                    requester_user_id=requester_user_id,
                ),
                timeout=settings.USER_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)
        return _profile_from_response(response.profile)

    async def get_user_by_public_key(
        self, user_public_key: str, requester_user_id: str
    ) -> UserProfileResult:
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
        for field in optional_fields:
            val = getattr(request, field, None)
            if val is not None:
                setattr(pb_request, field, val)
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
            return {
                "status": response.status,
                "db_status": response.db_status,
                "version": response.version,
            }
        except grpc.RpcError as e:
            raise _grpc_error_to_exception(e)

    async def update_last_active(self, user_id: str) -> bool:
        stub = await self._get_stub()
        try:
            response = await stub.UpdateLastActive(
                user_pb2.UpdateLastActiveRequest(user_id=user_id),
                timeout=settings.USER_GRPC_TIMEOUT,
            )
            return response.success
        except grpc.RpcError:
            return False

    async def check_and_process_auto_delete(self) -> AutoDeleteResult:
        stub = await self._get_stub()   # ← FIX: was self.get_stub() (missing underscore)
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
