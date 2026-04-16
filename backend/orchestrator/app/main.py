import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.logging_config import setup_logging, get_logger
from app.config import settings
from app.container import Container
from app.api.v1.router import v1_router
from app.exceptions import GatewayError, NotFoundError, ValidationError, PermissionDeniedError
from app.exceptions.handlers import (
    gateway_error_handler,
    not_found_handler,
    validation_error_handler,
    permission_denied_handler,
)
from app.middleware.logging_middleware import LoggingMiddleware

setup_logging()
logger = get_logger(__name__)


async def _auto_delete_task(container: Container) -> None:
    """Daily cron: call user-service.CheckAndProcessAutoDelete at configured UTC time."""
    while True:
        from datetime import datetime, timezone, timedelta

        now = datetime.now(timezone.utc)
        target = now.replace(
            hour=container.settings.AUTO_DELETE_CRON_HOUR,
            minute=container.settings.AUTO_DELETE_CRON_MINUTE,
            second=0,
            microsecond=0,
        )
        if target <= now:
            target += timedelta(days=1)
        wait_secs = (target - now).total_seconds()
        logger.info(
            "auto_delete.scheduled",
            wait_seconds=round(wait_secs),
            next_run=target.isoformat(),
        )
        await asyncio.sleep(wait_secs)
        try:
            result = await container.user_gateway.check_and_process_auto_delete()
            logger.info(
                "auto_delete.completed",
                deleted_count=result.deleted_count,
            )
        except Exception as exc:
            logger.error(
                "auto_delete.failed",
                error=str(exc),
                error_type=type(exc).__name__,
            )


@asynccontextmanager
async def lifespan(app: FastAPI):
    container = Container(settings)
    app.state.container = container
    task = asyncio.create_task(_auto_delete_task(container))
    logger.info("service.started", port=8000)
    try:
        yield
    finally:
        logger.info("service.shutting_down")
        task.cancel()
        await container.close()
        logger.info("service.stopped")


app = FastAPI(
    title=settings.APP_TITLE,
    version=settings.APP_VERSION,
    debug=settings.DEBUG,
    lifespan=lifespan,
)

app.add_middleware(LoggingMiddleware)

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
        container: Container = request.app.state.container
        asyncio.create_task(_fire_update_last_active(container, session.user_id))
        asyncio.create_task(
            _fire_set_online(container, session.user_id, session.device_id),
        )
    return response


async def _fire_update_last_active(container: Container, user_id: str) -> None:
    try:
        await container.user_gateway.update_last_active(user_id)
    except Exception:
        pass


async def _fire_set_online(container: Container, user_id: str, device_id: str) -> None:
    try:
        await container.messaging_gateway.set_online(user_id, device_id)
    except Exception:
        pass
