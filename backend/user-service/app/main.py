import asyncio
import grpc.aio
from grpc_reflection.v1alpha import reflection
from app.generated import user_pb2, user_pb2_grpc
from app.grpc_handlers.user_handler import UserHandler
from app.database.session import get_session, close_db
from app.database.redis import RedisClient
from app.config import settings

SERVICE_NAMES = (
    user_pb2.DESCRIPTOR.services_by_name["UserService"].full_name,
    reflection.SERVICE_NAME,
)


async def serve():
    server = grpc.aio.server()
    user_pb2_grpc.add_UserServiceServicer_to_server(
        UserHandler(get_session), server
    )
    reflection.enable_server_reflection(SERVICE_NAMES, server)
    server.add_insecure_port(f"[::]:{settings.GRPC_PORT}")
    print(f"User service running on port {settings.GRPC_PORT}")
    await server.start()
    try:
        await server.wait_for_termination()
    except KeyboardInterrupt:
        print("Shutting down...")
        await server.stop(0)
    finally:
        await close_db()
        await RedisClient.close()
        print("All connections closed")


if __name__ == "__main__":
    asyncio.run(serve())
