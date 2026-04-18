import grpc
from app.generated import auth_pb2, auth_pb2_grpc
from app.services.auth_service import AuthService
from app.services.device_service import DeviceService
from app.database.redis import check_redis_health

class AuthHandler(auth_pb2_grpc.AuthServiceServicer):
    def __init__(self, get_session):
        self.get_session = get_session

    async def Register(self, request, context):
        if not request.invite_code or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("Missing required fields")
            return auth_pb2.AuthResponse()

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.register(
                    username=request.username,
                    invite_code=request.invite_code,
                    device_id=request.device_id,
                    device_name=request.device_name,
                    identity_key_pub=request.identity_key_pub,
                    init_key_pub=request.init_key_pub,
                    credential_data=request.credential_data,
                )
                return auth_pb2.AuthResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.AuthResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.AuthResponse()

    async def LoginInit(self, request, context):
        if not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("device_id is required")
            return auth_pb2.LoginInitResponse()

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.login_init(device_id=request.device_id)
                return auth_pb2.LoginInitResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.LoginInitResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.LoginInitResponse()

    async def LoginComplete(self, request, context):
        if not request.device_id or not request.challenge or not request.signature:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("Missing required fields")
            return auth_pb2.AuthResponse()

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.login_complete(
                    device_id=request.device_id,
                    challenge=request.challenge,
                    signature=request.signature,
                    device_name=request.device_name if request.device_name else None,
                )
                return auth_pb2.AuthResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.AuthResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.AuthResponse()

    async def RefreshToken(self, request, context):
        if not request.refresh_token:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("refresh_token is required")
            return auth_pb2.AuthResponse()

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.refresh_token(refresh_token=request.refresh_token)
                return auth_pb2.AuthResponse(**result)
            except ValueError as e:
                msg = str(e)
                if "expired" in msg.lower():
                    context.set_code(grpc.StatusCode.UNAUTHENTICATED)
                elif "revoked" in msg.lower() or "not found" in msg.lower():
                    context.set_code(grpc.StatusCode.UNAUTHENTICATED)
                else:
                    context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(msg)
                return auth_pb2.AuthResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.AuthResponse()

    async def Logout(self, request, context):
        if not request.access_token:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("access_token is required")
            return auth_pb2.LogoutResponse(success=False, message="Missing access token")

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.logout(access_token=request.access_token)
                return auth_pb2.LogoutResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.LogoutResponse(success=False, message=str(e))
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.LogoutResponse(success=False, message=str(e))

    async def HealthCheck(self, request, context):
        db_status = "unknown"
        redis_status = "unknown"

        try:
            async with self.get_session() as session:
                from sqlalchemy import text
                await session.execute(text("SELECT 1"))
                db_status = "connected"
        except Exception as e:
            db_status = f"failed: {str(e)}"

        try:
            redis_ok = await check_redis_health()
            redis_status = "connected" if redis_ok else "disconnected"
        except Exception as e:
            redis_status = f"failed: {str(e)}"

        return auth_pb2.HealthCheckResponse(
            status="ok" if db_status == "connected" and redis_status == "connected" else "degraded",
            db_status=db_status,
            redis_status=redis_status,
            version="1.0.0",
        )

    async def CreateInvite(self, request, context):
        if not request.expires_in_days:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("expires_in_days is required")
            return auth_pb2.CreateInviteResponse()

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.create_invite(
                    expires_in_days=request.expires_in_days,
                    created_by_device_id=request.created_by_device_id
                    if request.created_by_device_id
                    else None,
                )
                return auth_pb2.CreateInviteResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.CreateInviteResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {str(e)}")
                return auth_pb2.CreateInviteResponse()

    async def ValidateToken(self, request, context):
        if not request.access_token:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("access_token is required")
            return auth_pb2.ValidateTokenResponse(valid=False)

        async with self.get_session() as session:
            service = AuthService(session)
            try:
                result = await service.validate_token(access_token=request.access_token)
                return auth_pb2.ValidateTokenResponse(**result)
            except Exception:
                return auth_pb2.ValidateTokenResponse(valid=False)

    async def InitDeviceAddition(self, request, context):
        if not request.user_id or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and device_id are required")
            return auth_pb2.InitDeviceAdditionResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.init_device_addition(
                    user_id=request.user_id,
                    device_id=request.device_id,
                )
                return auth_pb2.InitDeviceAdditionResponse(**result)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.InitDeviceAdditionResponse()
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.InitDeviceAdditionResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.InitDeviceAdditionResponse()

    async def SubmitDeviceData(self, request, context):
        if not request.registration_id or not request.registration_code:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("registration_id and registration_code are required")
            return auth_pb2.SubmitDeviceDataResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.submit_device_data(
                    registration_id=request.registration_id,
                    registration_code=request.registration_code,
                    device_id=request.device_id,
                    device_name=request.device_name,
                    device_type=request.device_type,
                    identity_key_pub=request.identity_key_pub,
                    init_key_pub=request.init_key_pub,
                    credential_data=request.credential_data,
                )
                return auth_pb2.SubmitDeviceDataResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.SubmitDeviceDataResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.SubmitDeviceDataResponse()

    async def GetDeviceAdditionStatus(self, request, context):
        if not request.registration_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("registration_id is required")
            return auth_pb2.GetDeviceAdditionStatusResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.get_device_addition_status(
                    registration_id=request.registration_id,
                )
                resp = auth_pb2.GetDeviceAdditionStatusResponse(
                    status=result["status"],
                    expires_at=result["expires_at"],
                    access_token=result.get("access_token", ""),
                    refresh_token=result.get("refresh_token", ""),
                    token_expires_at=result.get("token_expires_at", 0),
                )
                if result.get("device"):
                    resp.device.CopyFrom(self._device_dict_to_pb(result["device"]))
                return resp
            except ValueError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return auth_pb2.GetDeviceAdditionStatusResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.GetDeviceAdditionStatusResponse()

    async def GetPendingDeviceAdditions(self, request, context):
        if not request.user_id or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and device_id are required")
            return auth_pb2.GetPendingDeviceAdditionsResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                items = await svc.get_pending_device_additions(
                    user_id=request.user_id,
                    device_id=request.device_id,
                )
                return auth_pb2.GetPendingDeviceAdditionsResponse(
                    registrations=[
                        auth_pb2.PendingRegistrationInfo(**item) for item in items
                    ]
                )
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.GetPendingDeviceAdditionsResponse()
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.GetPendingDeviceAdditionsResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.GetPendingDeviceAdditionsResponse()

    async def ConfirmDeviceAddition(self, request, context):
        if not request.user_id or not request.device_id or not request.registration_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, device_id and registration_id are required")
            return auth_pb2.ConfirmDeviceAdditionResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.confirm_device_addition(
                    user_id=request.user_id,
                    device_id=request.device_id,
                    registration_id=request.registration_id,
                    confirm=request.confirm,
                    new_device_name=request.new_device_name or "",
                )
                return auth_pb2.ConfirmDeviceAdditionResponse(**result)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.ConfirmDeviceAdditionResponse()
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.ConfirmDeviceAdditionResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.ConfirmDeviceAdditionResponse()

    async def GetDevices(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return auth_pb2.GetDevicesResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                devices = await svc.get_devices(user_id=request.user_id)
                return auth_pb2.GetDevicesResponse(
                    devices=[self._device_dict_to_pb(d) for d in devices]
                )
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.GetDevicesResponse()

    async def GetDevice(self, request, context):
        if not request.user_id or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and device_id are required")
            return auth_pb2.DeviceInfo()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                device = await svc.get_device(
                    user_id=request.user_id,
                    device_id=request.device_id,
                )
                return self._device_dict_to_pb(device)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.DeviceInfo()
            except ValueError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return auth_pb2.DeviceInfo()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.DeviceInfo()

    async def RevokeDevice(self, request, context):
        if not request.user_id or not request.requesting_device_id or not request.target_device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, requesting_device_id, and target_device_id are required")
            return auth_pb2.RevokeDeviceResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.revoke_device(
                    user_id=request.user_id,
                    requesting_device_id=request.requesting_device_id,
                    target_device_id=request.target_device_id,
                    reason=request.reason or "No reason provided",
                )
                return auth_pb2.RevokeDeviceResponse(**result)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.RevokeDeviceResponse()
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.RevokeDeviceResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.RevokeDeviceResponse()

    async def UpdateDeviceKeys(self, request, context):
        if not request.user_id or not request.device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and device_id are required")
            return auth_pb2.UpdateDeviceKeysResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.update_device_keys(
                    user_id=request.user_id,
                    device_id=request.device_id,
                    identity_key_pub=request.identity_key_pub,
                    init_key_pub=request.init_key_pub,
                    credential_data=request.credential_data,
                )
                return auth_pb2.UpdateDeviceKeysResponse(**result)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.UpdateDeviceKeysResponse()
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.UpdateDeviceKeysResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.UpdateDeviceKeysResponse()

    async def RenameDevice(self, request, context):
        if not request.user_id or not request.requesting_device_id or not request.target_device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, requesting_device_id, and target_device_id are required")
            return auth_pb2.RenameDeviceResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.rename_device(
                    user_id=request.user_id,
                    requesting_device_id=request.requesting_device_id,
                    target_device_id=request.target_device_id,
                    new_name=request.new_name,
                )
                return auth_pb2.RenameDeviceResponse(**result)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.RenameDeviceResponse()
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.RenameDeviceResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.RenameDeviceResponse()

    async def VerifyDevice(self, request, context):
        if not request.device_id or not request.signature:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("device_id and signature are required")
            return auth_pb2.VerifyDeviceResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.verify_device(
                    device_id=request.device_id,
                    signature=request.signature,
                )
                return auth_pb2.VerifyDeviceResponse(**result)
            except ValueError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return auth_pb2.VerifyDeviceResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.VerifyDeviceResponse()

    async def TransferPrimary(self, request, context):
        if not request.user_id or not request.requesting_device_id or not request.target_device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, requesting_device_id, and target_device_id are required")
            return auth_pb2.TransferPrimaryResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.transfer_primary(
                    user_id=request.user_id,
                    requesting_device_id=request.requesting_device_id,
                    target_device_id=request.target_device_id,
                )
                return auth_pb2.TransferPrimaryResponse(**result)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.TransferPrimaryResponse()
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.TransferPrimaryResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.TransferPrimaryResponse()

    async def BulkRevokeDevices(self, request, context):
        if not request.user_id or not request.requesting_device_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and requesting_device_id are required")
            return auth_pb2.BulkRevokeDevicesResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                result = await svc.bulk_revoke_devices(
                    user_id=request.user_id,
                    requesting_device_id=request.requesting_device_id,
                    target_device_ids=list(request.target_device_ids),
                    reason=request.reason or "No reason provided",
                )
                return auth_pb2.BulkRevokeDevicesResponse(**result)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return auth_pb2.BulkRevokeDevicesResponse()
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return auth_pb2.BulkRevokeDevicesResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.BulkRevokeDevicesResponse()

    async def GetDeviceStats(self, request, context):
        if not request.user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required")
            return auth_pb2.GetDeviceStatsResponse()

        async with self.get_session() as session:
            svc = DeviceService(session)
            try:
                stats = await svc.get_device_stats(user_id=request.user_id)
                return auth_pb2.GetDeviceStatsResponse(
                    total_count=stats["total_count"],
                    active_count=stats["active_count"],
                    primary_count=stats["primary_count"],
                    type_stats=[
                        auth_pb2.DeviceTypeCount(device_type=dt, count=cnt)
                        for dt, cnt in stats["type_stats"].items()
                    ],
                    last_activity_at=stats["last_activity_at"],
                )
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return auth_pb2.GetDeviceStatsResponse()

    @staticmethod
    def _device_dict_to_pb(d: dict) -> auth_pb2.DeviceInfo:
        return auth_pb2.DeviceInfo(
            id=d["id"],
            user_id=d["user_id"],
            client_device_id=d.get("client_device_id", ""),
            device_name=d.get("device_name", ""),
            device_type=d.get("device_type", ""),
            is_active=d.get("is_active", False),
            created_at=d.get("created_at", 0),
            last_seen=d.get("last_seen", 0),
            identity_key_pub=d.get("identity_key_pub", b""),
            init_key_pub=d.get("init_key_pub", b""),
            revoked_at=d.get("revoked_at", 0),
        )
