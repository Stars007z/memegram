import asyncio
import grpc.aio
from grpc_reflection.v1alpha import reflection

from app.logging_config import setup_logging, get_logger
from app.generated import auth_pb2, auth_pb2_grpc
from app.grpc_handlers.auth_handler import AuthHandler
from app.grpc_interceptor import LoggingInterceptor
from app.database.session import get_session
from app.config import settings
from app.database.redis import RedisClient

# Initialize structured logging before anything else
setup_logging()
logger = get_logger(__name__)

SERVICE_NAMES = (
    auth_pb2.DESCRIPTOR.services_by_name['AuthService'].full_name,
    reflection.SERVICE_NAME,
)

async def serve():
    server = grpc.aio.server(interceptors=[LoggingInterceptor()])

    auth_pb2_grpc.add_AuthServiceServicer_to_server(
        AuthHandler(get_session), server
    )

    reflection.enable_server_reflection(SERVICE_NAMES, server)
    server.add_insecure_port(f'[::]:{settings.GRPC_PORT}')

    logger.info("service.started", port=settings.GRPC_PORT)

    await server.start()

    try:
        await server.wait_for_termination()
    except KeyboardInterrupt:
        logger.info("service.shutting_down")
        await server.stop(0)

        from app.database.session import close_db
        from app.database.redis import RedisClient

        await close_db()
        await RedisClient.close()
        logger.info("service.stopped", message="All connections closed")


if __name__ == '__main__':
    asyncio.run(serve())
