"""
gRPC server interceptor for structured request logging.

One log entry per request (on completion) with:
- trace_id  — unique UUID for correlation
- caller    — user_id, device_id extracted from request fields
- result    — gRPC status code, duration in ms
- request_data — sanitized parameters, included ONLY on errors for debugging

Streaming RPCs are passed through without modification.
"""

import time
import uuid

import grpc
import grpc.aio
from google.protobuf.json_format import MessageToDict

from app.logging_config import get_logger

logger = get_logger("grpc.access")

_CALLER_FIELDS = frozenset(
    {
        "user_id",
        "device_id",
        "sender_user_id",
        "sender_device_id",
        "initiator_user_id",
        "initiator_device_id",
        "caller_user_id",
        "target_user_id",
        "recipient_user_id",
    }
)

_SENSITIVE_FIELDS = frozenset(
    {
        "access_token",
        "refresh_token",
        "signature",
        "identity_key_pub",
        "init_key_pub",
        "credential_data",
        "jwt_secret",
        "password",
        "registration_code",
        "mls_ciphertext",
        "commit_data",
        "welcome_data",
        "key_package_data",
        "ratchet_tree",
        "encryption_metadata",
    }
)


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

        if not handler.unary_unary:
            return handler

        original = handler.unary_unary
        method = handler_call_details.method

        async def _logged_handler(request, context):
            trace_id = str(uuid.uuid4())
            start = time.monotonic()

            caller = _extract_caller_info(request)
            peer = context.peer()

            log = logger.bind(
                trace_id=trace_id,
                grpc_method=method,
                peer=peer,
                **caller,
            )

            try:
                response = await original(request, context)
            except Exception as exc:

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

                request_data = _sanitize_request(request)
                log.warning(
                    "grpc.request",
                    grpc_status=status,
                    grpc_details=context.details(),
                    request_data=request_data,
                    duration_ms=duration_ms,
                )
            else:

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
