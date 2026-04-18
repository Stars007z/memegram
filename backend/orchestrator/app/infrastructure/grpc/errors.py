import grpc

from app.exceptions import (
    GatewayError,
    NotFoundError,
    ValidationError,
    PermissionDeniedError,
)

def grpc_error_to_exception(e: grpc.RpcError, service_name: str = "Service") -> Exception:
    code = e.code()
    details = e.details() or "Unknown gRPC error"

    if code == grpc.StatusCode.INVALID_ARGUMENT:
        return ValidationError(details)
    if code == grpc.StatusCode.NOT_FOUND:
        return NotFoundError(details)
    if code == grpc.StatusCode.PERMISSION_DENIED:
        return PermissionDeniedError(details)
    if code == grpc.StatusCode.ALREADY_EXISTS:
        return ValidationError(f"Already exists: {details}")
    if code == grpc.StatusCode.ABORTED:
        return ValidationError(f"Conflict: {details}")
    if code == grpc.StatusCode.UNAVAILABLE:
        return GatewayError(f"{service_name} is unavailable", code=503)
    return GatewayError(f"{service_name} error: {details}", code=502)
