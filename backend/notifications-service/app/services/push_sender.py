"""Push delivery abstraction and FCM / APNs implementations."""

from __future__ import annotations

import asyncio
import json
import random
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

from app.config import settings
from app.logging_config import get_logger

logger = get_logger(__name__)


class PushPlatform(str, Enum):
    IOS = "ios"
    ANDROID = "android"


class PushErrorType(str, Enum):
    TRANSIENT = "transient"
    PERMANENT_TOKEN = "permanent_token"
    RATE_LIMIT = "rate_limit"


@dataclass
class PushResult:
    success: bool
    error_type: PushErrorType | None = None
    error_code: str | None = None
    retry_after: float | None = None


@dataclass
class PushPayload:
    """Platform-agnostic push payload."""

    token: str
    platform: PushPlatform
    title: str
    body: str
    data: dict[str, str] = field(default_factory=dict)
    thread_id: str | None = None
    avatar_url: str | None = None


class IPushSender(ABC):
    @abstractmethod
    async def send(self, payload: PushPayload) -> PushResult: ...


class FcmSender(IPushSender):
    """Send push via Firebase Cloud Messaging v1 API using firebase-admin SDK."""

    def __init__(self) -> None:
        self._initialized = False

    def _ensure_initialized(self) -> None:
        if self._initialized:
            return
        try:
            import firebase_admin
            from firebase_admin import credentials

            cred_path = settings.GOOGLE_APPLICATION_CREDENTIALS
            if cred_path:
                cred = credentials.Certificate(cred_path)
                firebase_admin.initialize_app(cred)
            else:

                firebase_admin.initialize_app()
            self._initialized = True
            logger.info("fcm.initialized", project=settings.FCM_PROJECT_ID)
        except Exception as e:
            logger.warning("fcm.init_failed", error=str(e))

    async def send(self, payload: PushPayload) -> PushResult:
        self._ensure_initialized()
        if not self._initialized:
            return PushResult(success=False, error_type=PushErrorType.TRANSIENT, error_code="fcm_not_initialized")

        try:
            from firebase_admin import messaging as fcm_messaging

            def _s(v: Any) -> str:
                if v is None:
                    return ""
                if isinstance(v, bool):
                    return "true" if v else "false"
                return str(v)

            raw_data = {
                "event_type": _s(payload.data.get("event_type", "")),
                "conversation_id": _s(payload.data.get("conversation_id", "")),
                "conversation_type": _s(payload.data.get("conversation_type", "")),
                "conversation_name": _s(payload.data.get("conversation_name", "")),
                "sender_user_id": _s(payload.data.get("sender_user_id", "")),
                "sender_name": _s(payload.data.get("sender_name", "")),
                "msg_type": _s(payload.data.get("message_type", "")),
                "avatar_url": _s(payload.avatar_url or ""),
                "timestamp": _s(payload.data.get("timestamp", "")),
                "title": _s(payload.title),
                "body": _s(payload.body),
            }
            data_payload = {k: (v if isinstance(v, str) else str(v)) for k, v in raw_data.items()}

            message = fcm_messaging.Message(
                token=payload.token,
                data=data_payload,
                android=fcm_messaging.AndroidConfig(
                    priority="high",
                    ttl=86400,
                ),
            )

            loop = asyncio.get_event_loop()
            await loop.run_in_executor(None, fcm_messaging.send, message)
            return PushResult(success=True)

        except Exception as e:
            error_str = str(e).lower()
            logger.warning(
                "fcm.send_exception",
                error_class=type(e).__name__,
                error=str(e)[:500],
                token_prefix=payload.token[:20],
            )
            if "unregistered" in error_str or "not-registered" in error_str:
                return PushResult(success=False, error_type=PushErrorType.PERMANENT_TOKEN, error_code="UNREGISTERED")
            if "invalid" in error_str and "argument" in error_str:
                return PushResult(
                    success=False, error_type=PushErrorType.PERMANENT_TOKEN, error_code="INVALID_ARGUMENT"
                )
            if "429" in error_str or "quota" in error_str:
                return PushResult(
                    success=False, error_type=PushErrorType.RATE_LIMIT, error_code="RATE_LIMITED", retry_after=60
                )
            return PushResult(success=False, error_type=PushErrorType.TRANSIENT, error_code=str(e)[:100])


class ApnsSender(IPushSender):
    """Send push via APNs HTTP/2 using aioapns."""

    def __init__(self) -> None:
        self._client: Any = None

    async def _ensure_client(self) -> Any:
        if self._client is not None:
            return self._client
        try:
            from aioapns import APNs, NotificationRequest

            self._client = APNs(
                key=settings.APNS_KEY_PATH,
                key_id=settings.APNS_KEY_ID,
                team_id=settings.APNS_TEAM_ID,
                topic=settings.APNS_BUNDLE_ID,
                use_sandbox=settings.APNS_USE_SANDBOX,
            )
            logger.info("apns.initialized", bundle=settings.APNS_BUNDLE_ID, sandbox=settings.APNS_USE_SANDBOX)
        except Exception as e:
            logger.warning("apns.init_failed", error=str(e))
        return self._client

    async def send(self, payload: PushPayload) -> PushResult:
        client = await self._ensure_client()
        if client is None:
            return PushResult(success=False, error_type=PushErrorType.TRANSIENT, error_code="apns_not_initialized")

        try:
            from aioapns import NotificationRequest

            apns_payload: dict[str, Any] = {
                "aps": {
                    "alert": {
                        "title": payload.title,
                        "body": payload.body,
                    },
                    "mutable-content": 1,
                    "sound": "default",
                },
            }
            if payload.thread_id:
                apns_payload["aps"]["thread-id"] = payload.thread_id

            for k, v in payload.data.items():
                apns_payload[k] = v
            if payload.avatar_url:
                apns_payload["avatar_url"] = payload.avatar_url

            request = NotificationRequest(
                device_token=payload.token,
                message=apns_payload,
                push_type="alert",
                priority=10,
            )

            response = await client.send_notification(request)
            if response.is_successful:
                return PushResult(success=True)

            reason = response.description or "unknown"
            reason_lower = reason.lower()
            if "baddevicetoken" in reason_lower or "unregistered" in reason_lower:
                return PushResult(success=False, error_type=PushErrorType.PERMANENT_TOKEN, error_code=reason)
            if "toomanyrequest" in reason_lower:
                return PushResult(success=False, error_type=PushErrorType.RATE_LIMIT, error_code=reason, retry_after=60)
            return PushResult(success=False, error_type=PushErrorType.TRANSIENT, error_code=reason)

        except Exception as e:
            return PushResult(success=False, error_type=PushErrorType.TRANSIENT, error_code=str(e)[:100])


async def send_with_retry(
    sender: IPushSender,
    payload: PushPayload,
    max_attempts: int = settings.MAX_RETRY_ATTEMPTS,
    base_delay: float = settings.RETRY_BASE_DELAY_SEC,
    jitter_pct: int = settings.RETRY_JITTER_PERCENT,
) -> PushResult:
    """Send a push notification with exponential backoff retry for transient errors."""
    for attempt in range(1, max_attempts + 1):
        result = await sender.send(payload)

        if result.success:
            return result

        if result.error_type == PushErrorType.PERMANENT_TOKEN:
            return result

        if result.error_type == PushErrorType.RATE_LIMIT and result.retry_after:
            await asyncio.sleep(result.retry_after)
            continue

        if attempt < max_attempts:
            delay = base_delay * (2 ** (attempt - 1))
            delay_max = min(delay, 16)
            jitter = delay_max * (jitter_pct / 100) * (2 * random.random() - 1)
            await asyncio.sleep(max(0, delay_max + jitter))

    return PushResult(
        success=False,
        error_type=PushErrorType.TRANSIENT,
        error_code="max_retries_exhausted",
    )
