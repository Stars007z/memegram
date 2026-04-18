import asyncio

import grpc.aio
from grpc_reflection.v1alpha import reflection

from app.config import settings
from app.container import Container
from app.database.redis import RedisClient
from app.generated import messaging_pb2, messaging_pb2_grpc
from app.grpc_handlers.messaging_handler import MessagingHandler
from app.grpc_interceptor import LoggingInterceptor
from app.infrastructure.auth_client import GrpcAuthClient
from app.infrastructure.contacts_client import GrpcContactsClient
from app.infrastructure.media_client import GrpcMediaClient
from app.logging_config import setup_logging, get_logger

setup_logging()
logger = get_logger(__name__)

SERVICE_NAMES = (
    messaging_pb2.DESCRIPTOR.services_by_name["MessagingService"].full_name,
    reflection.SERVICE_NAME,
)

async def _build_container() -> Container:
    redis = await RedisClient.get_instance()

    auth_channel = grpc.aio.insecure_channel(settings.auth_grpc_address)
    auth_client = GrpcAuthClient(auth_channel)

    contacts_channel = grpc.aio.insecure_channel(settings.contacts_grpc_address)
    contacts_client = GrpcContactsClient(contacts_channel)

    media_channel = grpc.aio.insecure_channel(settings.media_grpc_address)
    media_client = GrpcMediaClient(media_channel)

    return Container(
        redis=redis,
        auth_client=auth_client,
        contacts_client=contacts_client,
        media_client=media_client,
    )

async def serve() -> None:
    container = await _build_container()

    server = grpc.aio.server(interceptors=[LoggingInterceptor()])
    messaging_pb2_grpc.add_MessagingServiceServicer_to_server(
        MessagingHandler(container), server,
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
        await RedisClient.close()
        logger.info("service.stopped", message="All connections closed")

if __name__ == "__main__":
    asyncio.run(serve())
