import asyncio

import grpc.aio
from grpc_reflection.v1alpha import reflection

from app.config import settings
from app.logging_config import setup_logging, get_logger
from app.grpc_interceptor import LoggingInterceptor
from app.container import Container
from app.generated import media_pb2, media_pb2_grpc
from app.grpc_handlers.media_handler import MediaServiceHandler
from app.infrastructure.s3_client import S3Client

setup_logging()
logger = get_logger(__name__)

SERVICE_NAMES = (
    media_pb2.DESCRIPTOR.services_by_name["MediaService"].full_name,
    reflection.SERVICE_NAME,
)


async def serve() -> None:
    s3_client = S3Client()
    container = Container(s3=s3_client)

    server = grpc.aio.server(interceptors=[LoggingInterceptor()])
    media_pb2_grpc.add_MediaServiceServicer_to_server(
        MediaServiceHandler(container), server,
    )

    reflection.enable_server_reflection(SERVICE_NAMES, server)
    server.add_insecure_port(f"[::]:{settings.GRPC_PORT}")

    logger.info("service.started", port=settings.GRPC_PORT)
    await server.start()

    try:
        await server.wait_for_termination()
    except KeyboardInterrupt:
        logger.info("service.shutting_down")
        await server.stop(0)

        from app.database.session import close_db
        await close_db()
        logger.info("service.stopped")


if __name__ == "__main__":
    asyncio.run(serve())
