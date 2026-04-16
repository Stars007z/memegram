import asyncio

import grpc.aio
from grpc_reflection.v1alpha import reflection

from app.logging_config import setup_logging, get_logger
from app.grpc_interceptor import LoggingInterceptor
from app.config import settings
from app.container import Container
from app.database.redis import MessagingRedisClient, RedisClient
from app.generated import notifications_pb2, notifications_pb2_grpc
from app.grpc_handlers.notifications_handler import NotificationsHandler
from app.infrastructure.item_storage_client import GrpcItemStorageClient
from app.infrastructure.messaging_client import GrpcMessagingClient
from app.infrastructure.user_client import GrpcUserClient

setup_logging()
logger = get_logger(__name__)

SERVICE_NAMES = (
    notifications_pb2.DESCRIPTOR.services_by_name["NotificationsService"].full_name,
    reflection.SERVICE_NAME,
)


async def _build_container() -> Container:
    own_redis = await RedisClient.get_instance()
    messaging_redis = await MessagingRedisClient.get_instance()

    messaging_channel = grpc.aio.insecure_channel(settings.messaging_grpc_address)
    messaging_client = GrpcMessagingClient(messaging_channel)

    user_channel = grpc.aio.insecure_channel(settings.user_grpc_address)
    user_client = GrpcUserClient(user_channel)

    item_storage_channel = grpc.aio.insecure_channel(settings.item_storage_grpc_address)
    item_storage_client = GrpcItemStorageClient(item_storage_channel)

    return Container(
        own_redis=own_redis,
        messaging_redis=messaging_redis,
        messaging_client=messaging_client,
        user_client=user_client,
        item_storage_client=item_storage_client,
    )


async def serve() -> None:
    container = await _build_container()

    # Start gRPC server with logging interceptor
    server = grpc.aio.server(interceptors=[LoggingInterceptor()])
    notifications_pb2_grpc.add_NotificationsServiceServicer_to_server(
        NotificationsHandler(container), server,
    )
    reflection.enable_server_reflection(SERVICE_NAMES, server)
    server.add_insecure_port(f"[::]:{settings.GRPC_PORT}")

    logger.info("service.started", port=settings.GRPC_PORT)
    await server.start()

    # Start Redis Streams event consumer as a background task
    consumer_task = asyncio.create_task(container.event_consumer.start())
    logger.info("event_consumer.started")

    try:
        await server.wait_for_termination()
    except KeyboardInterrupt:
        logger.info("service.shutting_down")
    finally:
        await container.event_consumer.stop()
        consumer_task.cancel()
        await server.stop(0)

        from app.database.session import close_db
        await close_db()
        await RedisClient.close()
        await MessagingRedisClient.close()
        logger.info("service.stopped")


if __name__ == "__main__":
    asyncio.run(serve())
