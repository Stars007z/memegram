"""
gRPC server interceptor for structured request logging.

One log entry per request (on completion) with:
- trace_id  — unique UUID for correlation
- caller    — user_id extracted from request fields
- result    — gRPC status code, duration in ms
- request_data — sanitized parameters, included ONLY on errors for debugging

All user-service RPCs are unary-unary, so only that handler type is wrapped.
Streaming RPCs are passed through without modification.
"""

import time
import uuid

import grpc
import grpc.aio
from google.protobuf.json_format import MessageToDict

from app.logging_config import get_logger


logger = get_logger("grpc.access")

# ── Field classification ──────────────────────────────────────────────

# Fields that identify the caller — extracted into top-level log keys
_CALLER_FIELDS = frozenset({
    "user_id",
    "requester_user_id",
})

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
    "user_public_key",
})


# ── Helpers ───────────────────────────────────────────────────────────

def _extract_caller_info(request) -> dict[str, str]:
    """Pull known ID fields from the protobuf message into a flat dict."""
    info: dict[str, str] = {}
    try:
        for field in request.DESCRIPTOR.fields:
            if field.name in _CALLER_FIELDS:
                value = getattr(request, field.name, "")
                if value:
                    info[field.name] = str(value)
    except Exception:
        pass
    return info


def _sanitize_request(request) -> dict:
    """Convert protobuf message to dict, replacing sensitive values with '***'."""
    try:
        data = MessageToDict(request, preserving_proto_field_name=True)
    except Exception:
        return {}

    sanitized: dict = {}
    for key, value in data.items():
        if key in _SENSITIVE_FIELDS:
            sanitized[key] = "***"
        else:
            sanitized[key] = value
    return sanitized


# ── Interceptor ───────────────────────────────────────────────────────

class LoggingInterceptor(grpc.aio.ServerInterceptor):
    """Intercepts every unary-unary RPC and emits one structured log entry per request.

    Log levels by outcome:
      - INFO    — request completed successfully (OK)
      - WARNING — request failed with a client error (INVALID_ARGUMENT, NOT_FOUND, etc.)
      - ERROR   — unhandled exception (includes sanitized request_data for debugging)
    """

    async def intercept_service(self, continuation, handler_call_details):
        handler = await continuation(handler_call_details)
        if handler is None:
            return handler

        # Only wrap unary-unary handlers (all RPCs in user-service)
        if not handler.unary_unary:
            return handler

        original = handler.unary_unary
        method = handler_call_details.method  # e.g. "/user.UserService/CreateUser"

        async def _logged_handler(request, context):
            trace_id = str(uuid.uuid4())
            start = time.monotonic()

            caller = _extract_caller_info(request)
            peer = context.peer()  # e.g. "ipv4:172.18.0.1:54321"

            log = logger.bind(
                trace_id=trace_id,
                grpc_method=method,
                peer=peer,
                **caller,
            )

            try:
                response = await original(request, context)
            except Exception as exc:
                # Unhandled exception — include request_data for debugging
                duration_ms = round((time.monotonic() - start) * 1000, 2)
                request_data = _sanitize_request(request)
                log.error(
                    "grpc.request",
                    grpc_status="INTERNAL",
                    error=str(exc),
                    error_type=type(exc).__name__,
                    request_data=request_data,
                    duration_ms=duration_ms,
                )
                raise

            duration_ms = round((time.monotonic() - start) * 1000, 2)
            grpc_code = context.code()
            status = grpc_code.name if grpc_code else "OK"

            if grpc_code and grpc_code != grpc.StatusCode.OK:
                # Client/business error — include request_data and details for debugging
                request_data = _sanitize_request(request)
                log.warning(
                    "grpc.request",
                    grpc_status=status,
                    grpc_details=context.details(),
                    request_data=request_data,
                    duration_ms=duration_ms,
                )
            else:
                # Success — only caller IDs, method, and duration
                log.info(
                    "grpc.request",
                    grpc_status=status,
                    duration_ms=duration_ms,
                )

            return response

        return grpc.unary_unary_rpc_method_handler(
            _logged_handler,
            request_deserializer=handler.request_deserializer,
            response_serializer=handler.response_serializer,
        )
