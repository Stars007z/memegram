from contextlib import asynccontextmanager
from fastapi import FastAPI

from app.config import settings
from app.api.v1.router import v1_router
from app.exceptions import GatewayError, NotFoundError, ValidationError, PermissionDeniedError
from app.exceptions.handlers import (
    gateway_error_handler,
    not_found_handler,
    validation_error_handler,
    permission_denied_handler,
)
from app.infrastructure.grpc.client import close_grpc_channel


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    await close_grpc_channel()


app = FastAPI(
    title=settings.APP_TITLE,
    version=settings.APP_VERSION,
    debug=settings.DEBUG,
    lifespan=lifespan,
)

app.include_router(v1_router)

app.add_exception_handler(GatewayError, gateway_error_handler)
app.add_exception_handler(NotFoundError, not_found_handler)
app.add_exception_handler(ValidationError, validation_error_handler)
app.add_exception_handler(PermissionDeniedError, permission_denied_handler)