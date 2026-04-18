import asyncio
import grpc.aio
from grpc_reflection.v1alpha import reflection
from app.generated import item_storage_pb2, item_storage_pb2_grpc
from app.grpc_handlers.item_storage_handler import ItemStorageHandler
from app.database.session import get_session, close_db
from app.config import settings
from app.logging_config import setup_logging, get_logger
from app.grpc_interceptor import LoggingInterceptor

setup_logging()
logger = get_logger(__name__)

SERVICE_NAMES = (
    item_storage_pb2.DESCRIPTOR.services_by_name["ItemStorageService"].full_name,
    reflection.SERVICE_NAME,
)

async def serve():
    server = grpc.aio.server(interceptors=[LoggingInterceptor()])
    item_storage_pb2_grpc.add_ItemStorageServiceServicer_to_server(
        ItemStorageHandler(get_session), server
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
    finally:
        await close_db()
        logger.info("service.stopped")

if __name__ == "__main__":
    asyncio.run(serve())
