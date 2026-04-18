"""
Centralized structured logging configuration.

All logs are emitted as JSON (production) or colored console (dev) to stdout.
Container runtimes (Docker, k8s) capture stdout without mutation — append-only by design.

Usage:
    from app.logging_config import setup_logging, get_logger

    setup_logging()                       # call once at startup
    logger = get_logger(__name__)         # per-module logger
    logger.info("something.happened", key="value")
"""

import logging
import sys

import structlog

from app.config import settings

def setup_logging() -> None:
    """Configure structlog + stdlib logging for the service.

    Must be called before any logger is used (top of main.py).
    """
    log_level = getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO)

    shared_processors: list[structlog.types.Processor] = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_logger_name,
        structlog.stdlib.add_log_level,
        structlog.stdlib.ExtraAdder(),
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.UnicodeDecoder(),
    ]

    if settings.is_production:
        renderer: structlog.types.Processor = structlog.processors.JSONRenderer()
    else:
        renderer = structlog.dev.ConsoleRenderer()

    structlog.configure(
        processors=[
            *shared_processors,
            structlog.stdlib.ProcessorFormatter.wrap_for_formatter,
        ],
        logger_factory=structlog.stdlib.LoggerFactory(),
        wrapper_class=structlog.stdlib.BoundLogger,
        cache_logger_on_first_use=True,
    )

    formatter = structlog.stdlib.ProcessorFormatter(
        processors=[
            structlog.stdlib.ProcessorFormatter.remove_processors_meta,
            renderer,
        ],
    )

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(formatter)

    root_logger = logging.getLogger()
    root_logger.handlers.clear()
    root_logger.addHandler(handler)
    root_logger.setLevel(log_level)

    logging.getLogger("asyncpg").setLevel(logging.WARNING)
    logging.getLogger("grpc").setLevel(logging.WARNING)
    logging.getLogger("sqlalchemy.engine").setLevel(logging.WARNING)
    logging.getLogger("sqlalchemy.pool").setLevel(logging.WARNING)
    logging.getLogger("aioboto3").setLevel(logging.WARNING)
    logging.getLogger("botocore").setLevel(logging.WARNING)
    logging.getLogger("s3transfer").setLevel(logging.WARNING)

def get_logger(name: str | None = None) -> structlog.stdlib.BoundLogger:
    """Return a structlog logger pre-bound with service-level context.

    Every log entry from this logger will include service, version, and env fields.
    """
    log: structlog.stdlib.BoundLogger = structlog.get_logger(name)
    return log.bind(
        service=settings.SERVICE_NAME,
        version=settings.SERVICE_VERSION,
        env=settings.ENVIRONMENT,
    )
