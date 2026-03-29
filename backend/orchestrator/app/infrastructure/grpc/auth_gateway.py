import grpc
from typing import Optional

from app.config import Settings
from app.core.interfaces.auth_gateway import (
    IAuthGateway,
    RegisterRequest,
    AuthResult,
    LoginInitResult,
    LoginCompleteRequest,
    LogoutResult,
    HealthResult,
    CreateInviteResult,
    ValidateTokenResult,
    DeviceInfoResult,
    InitDeviceAdditionResult,
    SubmitDeviceDataResult,
    DeviceAdditionStatusResult,
    PendingRegistrationResult,
    ConfirmDeviceAdditionResult,
    RevokeDeviceResult,
    UpdateDeviceKeysResult,
    RenameDeviceResult,
    VerifyDeviceResult,
    TransferPrimaryResult,
    BulkRevokeDevicesResult,
    DeviceStatsResult,
    DeviceTypeCountResult,
)
from app.infrastructure.grpc.errors import grpc_error_to_exception
from app.infrastructure.grpc.client import GrpcChannelManager
from app.infrastructure.grpc.generated import auth_pb2, auth_pb2_grpc

_SERVICE = "Auth service"


class GrpcAuthGateway(IAuthGateway):

    def __init__(self, channels: GrpcChannelManager, settings: Settings):
        self._channels = channels
        self._settings = settings

    def _stub(self) -> auth_pb2_grpc.AuthServiceStub:
        return auth_pb2_grpc.AuthServiceStub(self._channels.get("auth"))

    # ── Auth methods ──────────────────────────────────────────────────

    async def register(self, request: RegisterRequest) -> AuthResult:
        try:
            response = await self._stub().Register(
                auth_pb2.RegisterRequest(
                    username=request.username,
                    invite_code=request.invite_code,
                    device_id=request.device_id,
                    device_name=request.device_name,
                    identity_key_pub=request.identity_key_pub,
                    init_key_pub=request.init_key_pub,
                    credential_data=request.credential_data,
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return AuthResult(
            user_id=response.user_id,
            device_id=response.device_id,
            is_primary=response.is_primary,
            access_token=response.access_token,
            refresh_token=response.refresh_token,
            expires_at=response.expires_at,
        )

    async def login_init(self, device_id: str) -> LoginInitResult:
        try:
            response = await self._stub().LoginInit(
                auth_pb2.LoginInitRequest(device_id=device_id),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return LoginInitResult(
            challenge=response.challenge,
            expires_at=response.expires_at,
            device_id=response.device_id,
        )

    async def login_complete(self, request: LoginCompleteRequest) -> AuthResult:
        try:
            response = await self._stub().LoginComplete(
                auth_pb2.LoginCompleteRequest(
                    device_id=request.device_id,
                    challenge=request.challenge,
                    signature=request.signature,
                    device_name=request.device_name or "",
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return AuthResult(
            user_id=response.user_id,
            device_id=response.device_id,
            is_primary=response.is_primary,
            access_token=response.access_token,
            refresh_token=response.refresh_token,
            expires_at=response.expires_at,
        )

    async def logout(self, access_token: str) -> LogoutResult:
        try:
            response = await self._stub().Logout(
                auth_pb2.LogoutRequest(access_token=access_token),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return LogoutResult(success=response.success, message=response.message)

    async def health_check(self) -> HealthResult:
        try:
            response = await self._stub().HealthCheck(
                auth_pb2.HealthCheckRequest(),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return HealthResult(
            status=response.status,
            db_status=response.db_status,
            redis_status=response.redis_status,
            version=response.version,
        )

    async def create_invite(
        self,
        expires_in_days: int,
        created_by_device_id: Optional[str] = None,
    ) -> CreateInviteResult:
        pb_request = auth_pb2.CreateInviteRequest(expires_in_days=expires_in_days)
        if created_by_device_id:
            pb_request.created_by_device_id = created_by_device_id
        try:
            response = await self._stub().CreateInvite(
                pb_request, timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return CreateInviteResult(
            code=response.code,
            created_at=response.created_at,
            expires_at=response.expires_at,
            is_used=response.is_used,
            message=response.message,
        )

    async def validate_token(self, access_token: str) -> ValidateTokenResult:
        try:
            response = await self._stub().ValidateToken(
                auth_pb2.ValidateTokenRequest(access_token=access_token),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return ValidateTokenResult(
            valid=response.valid,
            user_id=response.user_id,
            device_id=response.device_id,
            device_type=response.device_type,
            expires_at=response.expires_at,
        )

    # ── Device management methods ─────────────────────────────────────

    async def init_device_addition(self, user_id: str, device_id: str) -> InitDeviceAdditionResult:
        try:
            r = await self._stub().InitDeviceAddition(
                auth_pb2.InitDeviceAdditionRequest(user_id=user_id, device_id=device_id),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return InitDeviceAdditionResult(
            registration_id=r.registration_id,
            expires_at=r.expires_at,
            registration_code=r.registration_code,
        )

    async def submit_device_data(
        self, registration_id: str, registration_code: str,
        device_id: str, device_name: str, device_type: str,
        identity_key_pub: bytes, init_key_pub: bytes, credential_data: bytes,
    ) -> SubmitDeviceDataResult:
        try:
            r = await self._stub().SubmitDeviceData(
                auth_pb2.SubmitDeviceDataRequest(
                    registration_id=registration_id,
                    registration_code=registration_code,
                    device_id=device_id,
                    device_name=device_name,
                    device_type=device_type,
                    identity_key_pub=identity_key_pub,
                    init_key_pub=init_key_pub,
                    credential_data=credential_data,
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return SubmitDeviceDataResult(status=r.status, expires_at=r.expires_at)

    async def get_device_addition_status(self, registration_id: str) -> DeviceAdditionStatusResult:
        try:
            r = await self._stub().GetDeviceAdditionStatus(
                auth_pb2.GetDeviceAdditionStatusRequest(registration_id=registration_id),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        device = self._pb_to_device_info(r.device) if r.HasField("device") else None
        return DeviceAdditionStatusResult(
            status=r.status,
            expires_at=r.expires_at,
            device=device,
            access_token=r.access_token,
            refresh_token=r.refresh_token,
            token_expires_at=r.token_expires_at,
        )

    async def get_pending_device_additions(
        self, user_id: str, device_id: str,
    ) -> list[PendingRegistrationResult]:
        try:
            r = await self._stub().GetPendingDeviceAdditions(
                auth_pb2.GetPendingDeviceAdditionsRequest(user_id=user_id, device_id=device_id),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return [
            PendingRegistrationResult(
                registration_id=item.registration_id,
                registration_code=item.registration_code,
                expires_at=item.expires_at,
                status=item.status,
                device_id=item.device_id,
                device_name=item.device_name,
                device_type=item.device_type,
                created_at=item.created_at,
            )
            for item in r.registrations
        ]

    async def confirm_device_addition(
        self, user_id: str, device_id: str, registration_id: str,
        confirm: bool, new_device_name: str,
    ) -> ConfirmDeviceAdditionResult:
        try:
            r = await self._stub().ConfirmDeviceAddition(
                auth_pb2.ConfirmDeviceAdditionRequest(
                    user_id=user_id,
                    device_id=device_id,
                    registration_id=registration_id,
                    confirm=confirm,
                    new_device_name=new_device_name or "",
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return ConfirmDeviceAdditionResult(
            new_device_id=r.new_device_id,
            user_id=r.user_id,
            status=r.status,
            message=r.message,
            access_token=r.access_token,
            refresh_token=r.refresh_token,
            expires_at=r.expires_at,
        )

    async def get_devices(self, user_id: str) -> list[DeviceInfoResult]:
        try:
            r = await self._stub().GetDevices(
                auth_pb2.GetDevicesRequest(user_id=user_id),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return [self._pb_to_device_info(d) for d in r.devices]

    async def get_device(self, user_id: str, device_id: str) -> DeviceInfoResult:
        try:
            r = await self._stub().GetDevice(
                auth_pb2.GetDeviceRequest(user_id=user_id, device_id=device_id),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return self._pb_to_device_info(r)

    async def revoke_device(
        self, user_id: str, requesting_device_id: str,
        target_device_id: str, reason: str,
    ) -> RevokeDeviceResult:
        try:
            r = await self._stub().RevokeDevice(
                auth_pb2.RevokeDeviceRequest(
                    user_id=user_id,
                    requesting_device_id=requesting_device_id,
                    target_device_id=target_device_id,
                    reason=reason,
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return RevokeDeviceResult(
            success=r.success, message=r.message,
            revoked_device_id=r.revoked_device_id, revoked_at=r.revoked_at,
        )

    async def update_device_keys(
        self, user_id: str, device_id: str,
        identity_key_pub: bytes, init_key_pub: bytes, credential_data: bytes,
    ) -> UpdateDeviceKeysResult:
        try:
            r = await self._stub().UpdateDeviceKeys(
                auth_pb2.UpdateDeviceKeysRequest(
                    user_id=user_id, device_id=device_id,
                    identity_key_pub=identity_key_pub,
                    init_key_pub=init_key_pub,
                    credential_data=credential_data,
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return UpdateDeviceKeysResult(
            success=r.success, message=r.message, updated_at=r.updated_at,
        )

    async def rename_device(
        self, user_id: str, requesting_device_id: str,
        target_device_id: str, new_name: str,
    ) -> RenameDeviceResult:
        try:
            r = await self._stub().RenameDevice(
                auth_pb2.RenameDeviceRequest(
                    user_id=user_id,
                    requesting_device_id=requesting_device_id,
                    target_device_id=target_device_id,
                    new_name=new_name,
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return RenameDeviceResult(
            success=r.success, new_name=r.new_name, message=r.message,
        )

    async def verify_device(self, device_id: str, signature: bytes) -> VerifyDeviceResult:
        try:
            r = await self._stub().VerifyDevice(
                auth_pb2.VerifyDeviceRequest(device_id=device_id, signature=signature),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return VerifyDeviceResult(valid=r.valid, message=r.message)

    async def transfer_primary(
        self, user_id: str, requesting_device_id: str, target_device_id: str,
    ) -> TransferPrimaryResult:
        try:
            r = await self._stub().TransferPrimary(
                auth_pb2.TransferPrimaryRequest(
                    user_id=user_id,
                    requesting_device_id=requesting_device_id,
                    target_device_id=target_device_id,
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return TransferPrimaryResult(
            success=r.success,
            new_primary_device_id=r.new_primary_device_id,
            message=r.message,
        )

    async def bulk_revoke_devices(
        self, user_id: str, requesting_device_id: str,
        target_device_ids: list[str], reason: str,
    ) -> BulkRevokeDevicesResult:
        try:
            r = await self._stub().BulkRevokeDevices(
                auth_pb2.BulkRevokeDevicesRequest(
                    user_id=user_id,
                    requesting_device_id=requesting_device_id,
                    target_device_ids=target_device_ids,
                    reason=reason,
                ),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return BulkRevokeDevicesResult(
            success=r.success,
            revoked_count=r.revoked_count,
            revoked_device_ids=list(r.revoked_device_ids),
        )

    async def get_device_stats(self, user_id: str) -> DeviceStatsResult:
        try:
            r = await self._stub().GetDeviceStats(
                auth_pb2.GetDeviceStatsRequest(user_id=user_id),
                timeout=self._settings.AUTH_GRPC_TIMEOUT,
            )
        except grpc.RpcError as e:
            raise grpc_error_to_exception(e, _SERVICE)
        return DeviceStatsResult(
            total_count=r.total_count,
            active_count=r.active_count,
            primary_count=r.primary_count,
            type_stats=[
                DeviceTypeCountResult(device_type=ts.device_type, count=ts.count)
                for ts in r.type_stats
            ],
            last_activity_at=r.last_activity_at,
        )

    # ── Helpers ────────────────────────────────────────────────────────

    @staticmethod
    def _pb_to_device_info(pb) -> DeviceInfoResult:
        return DeviceInfoResult(
            id=pb.id,
            user_id=pb.user_id,
            client_device_id=pb.client_device_id,
            device_name=pb.device_name,
            device_type=pb.device_type,
            is_active=pb.is_active,
            created_at=pb.created_at,
            last_seen=pb.last_seen,
            identity_key_pub=pb.identity_key_pub,
            init_key_pub=pb.init_key_pub,
            revoked_at=pb.revoked_at,
        )
