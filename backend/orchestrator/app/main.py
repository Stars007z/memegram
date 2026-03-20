import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.config import settings
from app.api.v1.router import v1_router
from app.exceptions import GatewayError, NotFoundError, ValidationError, PermissionDeniedError
from app.exceptions.handlers import (
    gateway_error_handler,
    not_found_handler,
    validation_error_handler,
    permission_denied_handler,
)
from app.infrastructure.grpc.client import close_grpc_channels
from app.api.dependencies import get_cached_user_gateway

logger = logging.getLogger(__name__)


async def _auto_delete_task() -> None:
    """Daily cron: call user-service.CheckAndProcessAutoDelete at configured UTC time."""
    while True:
        from datetime import datetime, timezone, timedelta
        now = datetime.now(timezone.utc)
        target = now.replace(
            hour=settings.AUTO_DELETE_CRON_HOUR,
            minute=settings.AUTO_DELETE_CRON_MINUTE,
            second=0,
            microsecond=0,
        )
        if target <= now:
            target += timedelta(days=1)
        wait_secs = (target - now).total_seconds()
        logger.info("Next auto-delete run in %.0f seconds (at %s UTC)", wait_secs, target.isoformat())
        await asyncio.sleep(wait_secs)
        try:
            gateway = get_cached_user_gateway()
            result = await gateway.check_and_process_auto_delete()
            logger.info("Auto-delete completed: deleted %d users", result.deleted_count)
        except Exception as exc:
            logger.error("Auto-delete failed: %s", exc)


@asynccontextmanager
async def lifespan(app: FastAPI):
    task = asyncio.create_task(_auto_delete_task())
    try:
        yield
    finally:
        task.cancel()
        await close_grpc_channels()


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


@app.get("/health", include_in_schema=False)
async def root_health():
    """Root health endpoint — used by Docker HEALTHCHECK and load balancers."""
    return JSONResponse({"status": "ok", "version": settings.APP_VERSION})


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
