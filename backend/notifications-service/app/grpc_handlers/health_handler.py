"""gRPC handler for HealthCheck."""

from app.config import settings
from app.container import Container
from app.database.redis import check_redis_health
from app.generated import notifications_pb2


class HealthHandler:

    def __init__(self, container: Container) -> None:
        self._container = container

    async def health_check(self, request, context):
        db_status = "unknown"
        redis_status = "unknown"
        fcm_status = "unknown"
        apns_status = "unknown"

        try:
            async with self._container.request_scope() as scope:
                from sqlalchemy import text

                await scope._session.execute(text("SELECT 1"))
                db_status = "connected"
        except Exception as e:
            db_status = f"failed: {e}"

        try:
            redis_ok = await check_redis_health()
            redis_status = "connected" if redis_ok else "disconnected"
        except Exception as e:
            redis_status = f"failed: {e}"

        try:
            if settings.GOOGLE_APPLICATION_CREDENTIALS:
                fcm_status = "configured"
            else:
                fcm_status = "not_configured"
        except Exception as e:
            fcm_status = f"failed: {e}"

        try:
            if settings.APNS_KEY_PATH and settings.APNS_KEY_ID and settings.APNS_TEAM_ID:
                apns_status = "configured"
            else:
                apns_status = "not_configured"
        except Exception as e:
            apns_status = f"failed: {e}"

        overall = "ok"
        if "failed" in db_status or "failed" in redis_status:
            overall = "degraded"

        return notifications_pb2.HealthCheckResponse(
            status=overall,
            db_status=db_status,
            redis_status=redis_status,
            fcm_status=fcm_status,
            apns_status=apns_status,
            version=settings.SERVICE_VERSION,
        )
