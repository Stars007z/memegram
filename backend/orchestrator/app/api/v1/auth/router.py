from fastapi import APIRouter, Depends, HTTPException
import dataclasses

from app.api.dependencies import (
    get_register_use_case,
    get_login_init_use_case,
    get_login_complete_use_case,
    get_logout_use_case,
    get_create_invite_use_case,
    get_auth_gateway,
    get_current_session,
    get_user_gateway,
    require_device_type,
)
from app.api.v1.auth.schemas import (
    RegisterRequestSchema,
    LoginInitRequestSchema,
    LoginCompleteRequestSchema,
    LogoutRequestSchema,
    CreateInviteRequestSchema,
    AuthResponseSchema,
    LoginInitResponseSchema,
    LogoutResponseSchema,
    HealthResponseSchema,
    CreateInviteResponseSchema,
)
from app.core.use_cases.auth.register import RegisterUseCase
from app.core.use_cases.auth.login_init import LoginInitUseCase
from app.core.use_cases.auth.login_complete import LoginCompleteUseCase
from app.core.use_cases.auth.logout import LogoutUseCase
from app.core.use_cases.auth.create_invite import CreateInviteUseCase
from app.core.interfaces.auth_gateway import IAuthGateway, RegisterRequest
from app.core.interfaces.user_gateway import IUserGateway
from app.core.session_context import SessionContext
from app.exceptions import NotFoundError

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/register", response_model=AuthResponseSchema, status_code=201)
async def register(
    body: RegisterRequestSchema,
    usecase: RegisterUseCase = Depends(get_register_use_case),
) -> AuthResponseSchema:
    request = RegisterRequest(
        username=body.username,
        invite_code=body.invite_code,
        device_id=body.device_id,
        device_name=body.device_name,
        identity_key_pub=body.identity_key_pub_bytes,
        init_key_pub=body.init_key_pub_bytes,
        credential_data=body.credential_data_bytes,
    )
    result = await usecase.execute(request)
    return AuthResponseSchema(**dataclasses.asdict(result))



@router.post("/login-init", response_model=LoginInitResponseSchema)
async def login_init(
    body: LoginInitRequestSchema,
    use_case: LoginInitUseCase = Depends(get_login_init_use_case),
) -> LoginInitResponseSchema:
    result = await use_case.execute(device_id=body.device_id)
    return LoginInitResponseSchema(**result.__dict__)


@router.post("/login-complete", response_model=AuthResponseSchema)
async def login_complete(
    body: LoginCompleteRequestSchema,
    use_case: LoginCompleteUseCase = Depends(get_login_complete_use_case),
    user_gateway: IUserGateway = Depends(get_user_gateway),
) -> AuthResponseSchema:
    result = await use_case.execute(
        device_id=body.device_id,
        challenge=body.challenge,
        signature=body.signature_bytes,
        device_name=body.device_name,
    )
    try:
        await user_gateway.get_user(
            user_id=result.user_id,
            requester_user_id=result.user_id,
        )
    except NotFoundError:
        raise HTTPException(status_code=401, detail="Account has been deleted")
    return AuthResponseSchema(**result.__dict__)


@router.post("/logout", response_model=LogoutResponseSchema)
async def logout(
    body: LogoutRequestSchema,
    use_case: LogoutUseCase = Depends(get_logout_use_case),
) -> LogoutResponseSchema:
    result = await use_case.execute(access_token=body.access_token)
    return LogoutResponseSchema(**result.__dict__)


@router.get("/health", response_model=HealthResponseSchema)
async def health_check(
    gateway: IAuthGateway = Depends(get_auth_gateway),
) -> HealthResponseSchema:
    result = await gateway.health_check()
    return HealthResponseSchema(**result.__dict__)


@router.post("/invite", response_model=CreateInviteResponseSchema, status_code=201)
async def create_invite(
    body: CreateInviteRequestSchema,
    session: SessionContext = Depends(require_device_type("admin")),
    use_case: CreateInviteUseCase = Depends(get_create_invite_use_case),
) -> CreateInviteResponseSchema:
    """Only admin devices can create invites. created_by_device_id is set to the caller's device."""
    result = await use_case.execute(
        expires_in_days=body.expires_in_days,
        created_by_device_id=session.device_id,
    )
    return CreateInviteResponseSchema(**result.__dict__)
