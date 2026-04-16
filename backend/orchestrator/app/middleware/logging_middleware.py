"""
HTTP middleware for structured request logging.

One log entry per request (on completion) with:
- trace_id  — unique UUID for correlation
- caller    — user_id, device_id extracted from session context
- result    — HTTP status code, duration in ms
- request body — sanitized, included ONLY on errors for debugging

Analogous to the gRPC LoggingInterceptor in auth-service,
adapted for FastAPI / Starlette HTTP requests.
"""

import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from app.logging_config import get_logger


logger = get_logger("http.access")

# ── Field classification ──────────────────────────────────────────────

# Fields whose values must be redacted in logs
_SENSITIVE_FIELDS = frozenset({
    "access_token",
    "refresh_token",
    "signature",
    "identity_key_pub",
    "init_key_pub",
    "credential_data",
    "jwt_secret",
    "password",
    "registration_code",
    "challenge",
    "push_token",
    "mls_ciphertext",
    "welcome_data",
    "commit_data",
    "ratchet_tree",
    "encryption_metadata",
    "key_packages",
    "download_url",
    "upload_url",
})

# Paths excluded from access logging (noisy / health checks)
_SKIP_PATHS = frozenset({
    "/health",
    "/api/v1/auth/health",
    "/api/v1/user/health",
    "/api/v1/contacts/health",
    "/api/v1/item-storage/health",
    "/api/v1/notifications/health",
    "/docs",
    "/openapi.json",
    "/redoc",
})


# ── Helpers ───────────────────────────────────────────────────────────

def _sanitize_body(body: dict) -> dict:
    """Replace sensitive values with '***'."""
    sanitized: dict = {}
    for key, value in body.items():
        if key in _SENSITIVE_FIELDS:
            sanitized[key] = "***"
        elif isinstance(value, dict):
            sanitized[key] = _sanitize_body(value)
        else:
            sanitized[key] = value
    return sanitized


def _extract_caller(request: Request) -> dict[str, str]:
    """Extract caller identity from the session context set by auth dependency."""
    info: dict[str, str] = {}
    session = getattr(getattr(request, "state", None), "session", None)
    if session is not None:
        if session.user_id:
            info["user_id"] = session.user_id
        if session.device_id:
            info["device_id"] = session.device_id
    return info


# ── Middleware ────────────────────────────────────────────────────────

class LoggingMiddleware(BaseHTTPMiddleware):
    """Emits one structured log entry per HTTP request.

    Log levels by outcome:
      - INFO    — 2xx response
      - WARNING — 4xx response (client error)
      - ERROR   — 5xx response or unhandled exception
    """

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint,
    ) -> Response:
        if request.url.path in _SKIP_PATHS:
            return await call_next(request)

        trace_id = str(uuid.uuid4())
        start = time.monotonic()

        # Cache request body for error logging (read once, replay later)
        request_body: dict | None = None
        if request.method in {"POST", "PUT", "PATCH", "DELETE"}:
            try:
                request_body = await request.json()
            except Exception:
                request_body = None

        log = logger.bind(
            trace_id=trace_id,
            http_method=request.method,
            http_path=request.url.path,
            peer=request.client.host if request.client else "unknown",
        )

        try:
            response = await call_next(request)
        except Exception as exc:
            duration_ms = round((time.monotonic() - start) * 1000, 2)
            caller = _extract_caller(request)
            extra: dict = {**caller, "duration_ms": duration_ms}
            if request_body:
                extra["request_data"] = _sanitize_body(request_body)
            log.error(
                "http.request",
                http_status=500,
                error=str(exc),
                error_type=type(exc).__name__,
                **extra,
            )
            raise

        duration_ms = round((time.monotonic() - start) * 1000, 2)
        caller = _extract_caller(request)
        status = response.status_code

        if status >= 500:
            extra = {**caller, "duration_ms": duration_ms}
            if request_body:
                extra["request_data"] = _sanitize_body(request_body)
            log.error(
                "http.request",
                http_status=status,
                **extra,
            )
        elif status >= 400:
            extra = {**caller, "duration_ms": duration_ms}
            if request_body:
                extra["request_data"] = _sanitize_body(request_body)
            log.warning(
                "http.request",
                http_status=status,
                **extra,
            )
        else:
            log.info(
                "http.request",
                http_status=status,
                duration_ms=duration_ms,
                **caller,
            )

        return response
