# Logging Architecture

## Principles

1. **Immutability (append-only)**: Applications emit logs to `stdout` only. No application code can modify or delete previously written log entries. The runtime (Docker / k8s / GCP) captures and stores them in append-only storage.
2. **Structured format**: All logs are JSON in production, enabling automated parsing, filtering, and querying.
3. **Centralized**: Every service emits logs in the same schema, collected to a single observability platform.
4. **Traceable**: Every request carries a unique `trace_id` for end-to-end correlation across services.
5. **Secure**: Sensitive data (tokens, keys, passwords, signatures) is automatically redacted before emission.

---

## Technology Stack

| Layer | Tool | Purpose |
|---|---|---|
| Application | **structlog** (Python) | Structured log emission (JSON / console) |
| Output channel | **stdout** | Universal, immutable log channel |
| Local collection | Docker **json-file** driver | Append-only capture + rotation |
| K8s collection | **Fluent Bit** DaemonSet | Node-level log aggregation & shipping |
| GCP managed storage | **Cloud Logging** | Immutable, queryable log storage |
| GCP analytics | **BigQuery** (via Log Router sink) | Long-term structured querying |
| GCP archive | **Cloud Storage** (Retention Lock) | Compliance-grade immutable archive |

---

## Immutability Guarantees

### Application Level

- Logs are written exclusively to `stdout` — no file handles, no rotation logic in the app.
- No API or code path exists to modify or delete emitted entries.
- Each entry contains a unique `trace_id` and UTC ISO 8601 timestamp.

### Docker (Local Development)

- Docker's `json-file` driver captures stdout as append-only JSON files on the host.
- Log rotation is handled by Docker daemon (`max-size`, `max-file`) — rotated files are deleted by the daemon, but individual entries within a file are never modified.
- Sufficient for development purposes.

### Kubernetes

- Container stdout is captured by the kubelet and stored on the node filesystem.
- Fluent Bit DaemonSet ships logs to external storage before node-level rotation occurs.
- Shipped logs are immutable in the destination (Cloud Logging / object storage).

### Google Cloud (Production)

- **Cloud Logging**: log entries cannot be modified or deleted through the API (native immutability).
- **Log Router sinks** export to:
  - **BigQuery** — append-only tables for structured queries.
  - **Cloud Storage** — buckets with **Bucket Lock / Retention Policy** for compliance-grade immutability.
- IAM policies restrict `logging.logs.delete` permission to a minimal set of principals.

---

## Logging Strategy

### Two layers of logging

| Layer | What | When | Where |
|---|---|---|---|
| **Access log** (interceptor) | One entry per gRPC request | Every request, on completion | `app/grpc_interceptor.py` |
| **Audit log** (service layer) | Specific business events | Registration, login, revoke, etc. | `app/services/*.py` |

### Access log — what gets logged per request

| Outcome | Level | Includes |
|---|---|---|
| Success (OK) | `INFO` | method, caller IDs, peer, status, duration |
| Client error (INVALID_ARGUMENT, NOT_FOUND, etc.) | `WARNING` | method, caller IDs, peer, status, duration, **error details**, **sanitized request_data** |
| Server error / unhandled exception | `ERROR` | method, caller IDs, peer, status, duration, **error + traceback**, **sanitized request_data** |

`request_data` is included **only on errors** — for successful requests it is omitted to reduce log volume and avoid logging unnecessary data in normal operation.

### Audit log — explicit business events

Logged from the service layer with `get_logger()`. Examples:

```
auth.register.success   — user_id, device_id, device_type
auth.login.success      — user_id, device_id
auth.logout.success     — device_id
auth.invite.created     — invite_code, expires_in_days
device.revoked          — user_id, target_device_id, reason
device.primary_transferred — old_primary, new_primary
```

These are distinct from access logs — they carry **business meaning** and are useful for security audits independent of the transport layer.

---

## Log Record Schema

### Successful request (access log)

```json
{
  "timestamp": "2025-04-16T12:30:00.123456Z",
  "level": "info",
  "logger": "grpc.access",
  "service": "auth-service",
  "version": "1.0.0",
  "env": "production",
  "trace_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "grpc_method": "/auth.AuthService/Register",
  "peer": "ipv4:172.18.0.1:54321",
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "device_id": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "grpc_status": "OK",
  "duration_ms": 45.23,
  "event": "grpc.request"
}
```

### Failed request (access log — includes request_data for debugging)

```json
{
  "timestamp": "2025-04-16T12:30:01.456789Z",
  "level": "warning",
  "logger": "grpc.access",
  "service": "auth-service",
  "version": "1.0.0",
  "env": "production",
  "trace_id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "grpc_method": "/auth.AuthService/Register",
  "peer": "ipv4:172.18.0.1:54322",
  "grpc_status": "INVALID_ARGUMENT",
  "grpc_details": "Invalid or expired invite code",
  "request_data": {
    "username": "johndoe",
    "invite_code": "EXPIRED123",
    "device_name": "iPhone 15",
    "identity_key_pub": "***",
    "credential_data": "***"
  },
  "duration_ms": 12.10,
  "event": "grpc.request"
}
```

### Audit event (service layer)

```json
{
  "timestamp": "2025-04-16T12:30:00.234567Z",
  "level": "info",
  "logger": "app.services.auth_service",
  "service": "auth-service",
  "version": "1.0.0",
  "env": "production",
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "device_id": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "device_type": "primary",
  "event": "auth.register.success"
}
```

### Required Fields (every log entry)

| Field | Type | Description |
|---|---|---|
| `timestamp` | string (ISO 8601 UTC) | When the event occurred |
| `level` | string | `debug`, `info`, `warning`, `error`, `critical` |
| `logger` | string | Logger name (module / component) |
| `service` | string | Service name (`auth-service`, `user-service`, etc.) |
| `version` | string | Service semantic version |
| `env` | string | Environment (`development`, `staging`, `production`) |
| `event` | string | Event identifier (`grpc.request`, `auth.login.success`, etc.) |

### Access Log Fields (added by gRPC interceptor)

| Field | Type | Presence | Description |
|---|---|---|---|
| `trace_id` | string (UUID v4) | always | Unique ID for the request lifecycle |
| `grpc_method` | string | always | Full gRPC method path (`/package.Service/Method`) |
| `peer` | string | always | Client network address |
| `user_id` | string | if present in request | Caller's user ID |
| `device_id` | string | if present in request | Caller's device ID |
| `grpc_status` | string | always | gRPC status code (`OK`, `INVALID_ARGUMENT`, etc.) |
| `duration_ms` | float | always | Request processing time in milliseconds |
| `grpc_details` | string | on error only | gRPC error details |
| `request_data` | object | on error only | Sanitized request parameters |

---

## Log Levels

| Level | Usage | Example |
|---|---|---|
| `DEBUG` | Detailed diagnostic info (disabled in production) | SQL queries, cache hits/misses |
| `INFO` | Normal operations | Request completed, service started, user registered |
| `WARNING` | Client errors, degraded state | Invalid request, token expired, device not found |
| `ERROR` | Internal failures, unhandled exceptions | DB connection lost, unexpected exception in handler |
| `CRITICAL` | Service cannot function | Database unreachable at startup, config missing |

---

## Sensitive Data Redaction

The following field names are automatically replaced with `***` in log output:

| Field | Reason |
|---|---|
| `access_token` | Authentication credential |
| `refresh_token` | Authentication credential |
| `signature` | Cryptographic material |
| `identity_key_pub` | Cryptographic key |
| `init_key_pub` | Cryptographic key |
| `credential_data` | Device credential |
| `password` | User secret |
| `jwt_secret` | Server secret |
| `registration_code` | One-time security code |

To add new fields to the redaction list, update `_SENSITIVE_FIELDS` in `app/grpc_interceptor.py`.

---

## Infrastructure Configuration

### Docker Compose (Local Development)

```yaml
services:
  <service-name>:
    environment:
      - LOG_LEVEL=${LOG_LEVEL:-INFO}
      - SERVICE_NAME=<service-name>
      - SERVICE_VERSION=${SERVICE_VERSION:-1.0.0}
      - ENVIRONMENT=${ENVIRONMENT:-development}
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "10"
        tag: "{{.Name}}"
```

### Kubernetes

Deploy Fluent Bit as a DaemonSet to collect container logs from `/var/log/containers/*.log`:

```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: fluent-bit
  namespace: logging
spec:
  selector:
    matchLabels:
      app: fluent-bit
  template:
    metadata:
      labels:
        app: fluent-bit
    spec:
      serviceAccountName: fluent-bit
      containers:
        - name: fluent-bit
          image: fluent/fluent-bit:3.0
          volumeMounts:
            - name: varlog
              mountPath: /var/log
              readOnly: true
            - name: config
              mountPath: /fluent-bit/etc/
          resources:
            limits:
              memory: 200Mi
              cpu: 200m
      volumes:
        - name: varlog
          hostPath:
            path: /var/log
        - name: config
          configMap:
            name: fluent-bit-config
```

Fluent Bit output configuration for Cloud Logging:

```ini
[INPUT]
    Name              tail
    Path              /var/log/containers/memegram-*.log
    Parser            docker
    Tag               memegram.*
    Refresh_Interval  5

[FILTER]
    Name   kubernetes
    Match  memegram.*

[OUTPUT]
    Name        stackdriver
    Match       memegram.*
    Resource    k8s_container
    k8s_cluster_name   memegram-cluster
    k8s_cluster_location  europe-west1
```

### Google Cloud Production Setup

1. **Cloud Logging** — enabled automatically for GKE workloads.
2. **Log Router Sink to BigQuery** — for structured analytics:
   ```
   resource.type="k8s_container"
   resource.labels.namespace_name="memegram"
   ```
3. **Log Router Sink to Cloud Storage** — for compliance archive:
   - Bucket: `memegram-audit-logs`
   - Retention Policy: 365 days (locked)
   - Storage class: Coldline
4. **IAM** — deny `logging.logs.delete` for all except break-glass principals.

---

## Implementation Guide

### Adding Logging to a New Service

1. Add dependency to `requirements.txt`:
   ```
   structlog>=24.1.0
   ```

2. Copy reference modules from `auth-service`:
   - `app/logging_config.py` — structlog + stdlib integration
   - `app/grpc_interceptor.py` — `LoggingInterceptor` class

3. Add config settings in `app/config.py`:
   ```python
   LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
   SERVICE_NAME: str = os.getenv("SERVICE_NAME", "<service-name>")
   SERVICE_VERSION: str = os.getenv("SERVICE_VERSION", "1.0.0")
   ```

4. Initialize in `app/main.py` (before any other logic):
   ```python
   from app.logging_config import setup_logging, get_logger
   from app.grpc_interceptor import LoggingInterceptor

   setup_logging()
   logger = get_logger(__name__)

   server = grpc.aio.server(interceptors=[LoggingInterceptor()])
   ```

5. Use structured logging in business logic:
   ```python
   from app.logging_config import get_logger

   logger = get_logger(__name__)

   # Audit event with contextual data
   logger.info("user.registered", user_id=str(user_id), device_type=device_type)

   # Error with exception context
   logger.error("db.connection_failed", error=str(e), retry_attempt=3)
   ```

6. Add environment variables to `docker-compose.yml` and k8s manifests:
   ```yaml
   - LOG_LEVEL=${LOG_LEVEL:-INFO}
   - SERVICE_NAME=<service-name>
   ```

### Log Output Format by Environment

| Environment | Renderer | Purpose |
|---|---|---|
| `development` | `ConsoleRenderer` | Human-readable colored output for local dev |
| `staging`, `production` | `JSONRenderer` | Machine-parseable structured JSON |

---

## Retention Policy

| Environment | Retention | Storage |
|---|---|---|
| Development | 7 days | Docker local (json-file rotation) |
| Staging | 30 days | Cloud Logging |
| Production | 365 days | Cloud Logging + Cloud Storage archive (Retention Lock) |

---

## Reference Implementation

The `auth-service` serves as the reference implementation for this logging architecture. Key files:

| File | Description |
|---|---|
| `app/logging_config.py` | structlog configuration, `setup_logging()`, `get_logger()` |
| `app/grpc_interceptor.py` | `LoggingInterceptor` — automatic request/response logging |
| `app/config.py` | `LOG_LEVEL`, `SERVICE_NAME`, `SERVICE_VERSION` settings |
| `app/main.py` | Initialization example |
