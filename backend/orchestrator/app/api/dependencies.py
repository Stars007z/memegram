from functools import lru_cache
from app.core.interfaces.auth_gateway import IAuthGateway
from app.infrastructure.grpc.auth_gateway import GrpcAuthGateway
from app.core.use_cases.auth.register import RegisterUseCase
from app.core.use_cases.auth.login_init import LoginInitUseCase
from app.core.use_cases.auth.login_complete import LoginCompleteUseCase
from app.core.use_cases.auth.logout import LogoutUseCase
from app.core.use_cases.auth.create_invite import CreateInviteUseCase


@lru_cache
def _get_cached_gateway() -> IAuthGateway:
    return GrpcAuthGateway()


def get_auth_gateway() -> IAuthGateway:
    return _get_cached_gateway()


def get_register_use_case() -> RegisterUseCase:
    return RegisterUseCase(get_auth_gateway())


def get_login_init_use_case() -> LoginInitUseCase:
    return LoginInitUseCase(get_auth_gateway())


def get_login_complete_use_case() -> LoginCompleteUseCase:
    return LoginCompleteUseCase(get_auth_gateway())


def get_logout_use_case() -> LogoutUseCase:
    return LogoutUseCase(get_auth_gateway())


def get_create_invite_use_case() -> CreateInviteUseCase:
    return CreateInviteUseCase(get_auth_gateway())