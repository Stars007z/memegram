# orchestrator/app/main.py
import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request

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
from app.api.dependencies import get_cached_user_gateway


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

@app.middleware("http")
async def update_last_active_middleware(request: Request, call_next):
    response = await call_next(request)

    session = getattr(request.state, "session", None)
    if session is not None:
        asyncio.create_task(_fire_update_last_active(session.user_id))

    return response


async def _fire_update_last_active(user_id: str) -> None:
    try:
        gateway = get_cached_user_gateway()
        await gateway.update_last_active(user_id)
    except Exception:
        pass
