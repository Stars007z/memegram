import grpc
from app.generated import user_pb2, user_pb2_grpc
from app.services.user_service import UserService
from app.database.redis import check_redis_health


def _build_minimal_profile(user) -> user_pb2.UserProfile:
    return user_pb2.UserProfile(
        id=str(user.id),
        username=user.username,
        is_deleted=user.is_deleted,
    )


def _build_full_profile(user, settings, is_owner: bool) -> user_pb2.UserProfile:
    proto = user_pb2.UserProfile(
        id=str(user.id),
        username=user.username,
        user_public_key=user.user_public_key or "",
        bio=user.bio or "",
        is_deleted=user.is_deleted,
    )
    if user.avatar_media_id:
        proto.avatar_media_id = str(user.avatar_media_id)
    if user.profile_background_media_id:
        proto.profile_background_media_id = str(user.profile_background_media_id)

    if user.last_active:
        if is_owner:
            proto.last_active = int(user.last_active.timestamp())
        elif settings:
            vis = settings.last_active_visible_to
            if vis == "everybody":
                proto.last_active = int(user.last_active.timestamp())
    return proto


def _settings_to_proto(s) -> user_pb2.UserSettings:
    return user_pb2.UserSettings(
        id=str(s.id),
        user_id=str(s.user_id),
        theme=s.theme,
        language=s.language,
        is_translator_active=s.is_translator_active,
        animations_enabled=s.animations_enabled,
        account_auto_delete_after_days=s.account_auto_delete_after_days or 0,
        profile_visible_to=s.profile_visible_to,
        last_active_visible_to=s.last_active_visible_to,
        chat_background_media_id=str(s.chat_background_media_id) if s.chat_background_media_id else "",
        top_bar_color=s.top_bar_color or "",
        ringtone_media_id=str(s.ringtone_media_id) if s.ringtone_media_id else "",
        ringtone_vibration_strength=s.ringtone_vibration_strength or 0,
        notification_sound=str(s.notification_sound) if s.notification_sound else "",
        notification_vibration_strength=s.notification_vibration_strength or 0,
    )


class UserHandler(user_pb2_grpc.UserServiceServicer):
    def __init__(self, get_session):
        self.get_session = get_session

    async def _is_in_contacts(self, owner_user_id: str, requester_user_id: str) -> bool:
        """Graceful fallback: returns False if contacts-service is unavailable."""
        try:
            from app.infrastructure.contacts_gateway import ContactsGateway
            gw = ContactsGateway()
            return await gw.is_contact(
                owner_user_id=owner_user_id,
                contact_user_id=requester_user_id,
            )
        except Exception:
            return False

    async def _apply_privacy(
        self,
        user,
        settings,
        requester_user_id: str,
    ) -> user_pb2.UserProfile:
        is_owner = str(user.id) == requester_user_id

        if is_owner:
            return _build_full_profile(user, settings, is_owner=True)

        if not settings:
            return _build_full_profile(user, settings, is_owner=False)

        pv = settings.profile_visible_to
        if pv == "nobody":
            return _build_minimal_profile(user)

        if pv == "contacts":
            in_contacts = await self._is_in_contacts(str(user.id), requester_user_id)
            if not in_contacts:
                return _build_minimal_profile(user)

        proto = _build_full_profile(user, settings, is_owner=False)

        # Override last_active based on privacy
        lv = settings.last_active_visible_to
        if user.last_active:
            if lv == "everybody":
                proto.last_active = int(user.last_active.timestamp())
            elif lv == "contacts":
                in_contacts = await self._is_in_contacts(str(user.id), requester_user_id)
                proto.last_active = int(user.last_active.timestamp()) if in_contacts else 0
            else:
                proto.last_active = 0
        return proto

    async def CreateUser(self, request, context):
        user_id = dict(context.invocation_metadata()).get("x-user-id")
        if not request.username or not user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("username and x-user-id metadata are required")
            return user_pb2.UserProfileResponse()

        async with self.get_session() as session:
            service = UserService(session)
            try:
                user = await service.create_user(user_id=user_id, username=request.username)
                proto = _build_full_profile(user, settings=None, is_owner=True)
                return user_pb2.UserProfileResponse(profile=proto)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.UserProfileResponse()

    async def GetUser(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return user_pb2.UserProfileResponse()

        async with self.get_session() as session:
            service = UserService(session)
            try:
                user, settings = await service.get_user_with_settings(request.user_id)
                proto = await self._apply_privacy(user, settings, request.requester_user_id)
                return user_pb2.UserProfileResponse(profile=proto)
            except ValueError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return user_pb2.UserProfileResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.UserProfileResponse()

    async def GetUserByUserPublicKey(self, request, context):
        if not request.user_public_key:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_public_key is required")
            return user_pb2.UserProfileResponse()

        async with self.get_session() as session:
            service = UserService(session)
            try:
                user, settings = await service.get_user_by_public_key_with_settings(
                    request.user_public_key
                )
                proto = await self._apply_privacy(user, settings, request.requester_user_id)
                return user_pb2.UserProfileResponse(profile=proto)
            except ValueError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return user_pb2.UserProfileResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.UserProfileResponse()

    async def UpdateUser(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return user_pb2.UserProfileResponse()

        async with self.get_session() as session:
            service = UserService(session)
            try:
                kwargs = {}
                if request.HasField("bio"):                         kwargs["bio"] = request.bio
                if request.HasField("username"):                    kwargs["username"] = request.username
                if request.HasField("avatar_media_id"):             kwargs["avatar_media_id"] = request.avatar_media_id
                if request.HasField("profile_background_media_id"): kwargs["profile_background_media_id"] = request.profile_background_media_id
                user = await service.update_user(request.user_id, **kwargs)
                proto = _build_full_profile(user, settings=None, is_owner=True)
                return user_pb2.UserProfileResponse(profile=proto)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return user_pb2.UserProfileResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.UserProfileResponse()

    async def DeleteUser(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return user_pb2.DeleteUserResponse()

        async with self.get_session() as session:
            service = UserService(session)
            try:
                deleted_at = await service.delete_user(request.user_id)
                return user_pb2.DeleteUserResponse(
                    success=True, deleted_at=int(deleted_at.timestamp())
                )
            except ValueError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return user_pb2.DeleteUserResponse(success=False)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.DeleteUserResponse(success=False)

    async def CheckAndProcessAutoDelete(self, request, context):
        async with self.get_session() as session:
            service = UserService(session)
            try:
                count, ids = await service.check_and_process_auto_delete()
                return user_pb2.CheckAndProcessAutoDeleteResponse(
                    deleted_count=count, user_ids=ids
                )
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.CheckAndProcessAutoDeleteResponse()

    async def GetUserSettings(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return user_pb2.UserSettingsResponse()

        async with self.get_session() as session:
            service = UserService(session)
            try:
                settings = await service.get_user_settings(request.user_id)
                return user_pb2.UserSettingsResponse(settings=_settings_to_proto(settings))
            except ValueError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return user_pb2.UserSettingsResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.UserSettingsResponse()

    async def UpdateUserSettings(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return user_pb2.UserSettingsResponse()

        async with self.get_session() as session:
            service = UserService(session)
            try:
                # All settings fields are marked `optional` in proto3, so HasField works
                # for ALL of them — including numeric (int32). This avoids the "!= 0" bug
                # that would prevent setting a field to zero explicitly.
                optional_fields = [
                    "theme", "language", "is_translator_active", "animations_enabled",
                    "account_auto_delete_after_days", "profile_visible_to",
                    "last_active_visible_to", "chat_background_media_id", "top_bar_color",
                    "ringtone_media_id", "ringtone_vibration_strength",
                    "notification_sound", "notification_vibration_strength",
                ]
                kwargs = {
                    f: getattr(request, f)
                    for f in optional_fields
                    if request.HasField(f)
                }
                settings = await service.update_user_settings(request.user_id, **kwargs)
                return user_pb2.UserSettingsResponse(settings=_settings_to_proto(settings))
            except ValueError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return user_pb2.UserSettingsResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.UserSettingsResponse()

    async def GetUsersBatch(self, request, context):
        """Internal: returns brief profiles WITHOUT privacy filtering."""
        async with self.get_session() as session:
            service = UserService(session)
            try:
                users = await service.get_users_batch(list(request.user_ids))
                # Architecture: { id, username, avatar_media_id, is_deleted }
                # contacts-service user_client also reads user_public_key + bio
                brief_list = [
                    user_pb2.UserProfile(
                        id=str(u.id),
                        username=u.username,
                        user_public_key=u.user_public_key or "",
                        bio=u.bio or "",
                        avatar_media_id=str(u.avatar_media_id) if u.avatar_media_id else "",
                        is_deleted=u.is_deleted,
                    )
                    for u in users
                ]
                return user_pb2.GetUsersBatchResponse(users=brief_list)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.GetUsersBatchResponse()

    async def UserExists(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return user_pb2.UserExistsResponse()

        async with self.get_session() as session:
            service = UserService(session)
            try:
                exists, is_deleted = await service.user_exists(request.user_id)
                return user_pb2.UserExistsResponse(exists=exists, is_deleted=is_deleted)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.UserExistsResponse()

    async def UpdateLastActive(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return user_pb2.UpdateLastActiveResponse(success=False)

        async with self.get_session() as session:
            service = UserService(session)
            try:
                success = await service.update_last_active(request.user_id)
                return user_pb2.UpdateLastActiveResponse(success=success)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.UpdateLastActiveResponse(success=False)

    async def GetPrivacySettings(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return user_pb2.GetPrivacySettingsResponse()

        async with self.get_session() as session:
            service = UserService(session)
            try:
                settings = await service.get_privacy_settings(request.user_id)
                return user_pb2.GetPrivacySettingsResponse(
                    profile_visible_to=settings.profile_visible_to,
                    last_active_visible_to=settings.last_active_visible_to,
                )
            except ValueError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return user_pb2.GetPrivacySettingsResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return user_pb2.GetPrivacySettingsResponse()

    async def HealthCheck(self, request, context):
        db_status = "unknown"
        redis_ok = False

        try:
            async with self.get_session() as session:
                from sqlalchemy import text
                await session.execute(text("SELECT 1"))
                db_status = "connected"
        except Exception as e:
            db_status = f"failed: {str(e)}"

        try:
            redis_ok = await check_redis_health()
        except Exception:
            pass

        redis_status = "connected" if redis_ok else "disconnected"
        return user_pb2.HealthCheckResponse(
            status="ok" if db_status == "connected" and redis_ok else "degraded",
            db_status=db_status,
            redis_status=redis_status,
            version="1.0.0",
        )
