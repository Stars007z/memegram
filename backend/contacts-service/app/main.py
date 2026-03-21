import asyncio
import grpc.aio
from grpc_reflection.v1alpha import reflection

from app.generated import contacts_pb2, contacts_pb2_grpc
from app.grpc_handlers.contacts_handler import ContactsHandler
from app.database.session import get_session, close_db
from app.config import settings

SERVICE_NAMES = (
    contacts_pb2.DESCRIPTOR.services_by_name["ContactsService"].full_name,
    reflection.SERVICE_NAME,
)


async def serve() -> None:
    server = grpc.aio.server()

    contacts_pb2_grpc.add_ContactsServiceServicer_to_server(
        ContactsHandler(get_session), server
    )

    reflection.enable_server_reflection(SERVICE_NAMES, server)
    server.add_insecure_port(f"[::]:{settings.GRPC_PORT}")

    print(f"Contacts service running on port {settings.GRPC_PORT}")
    await server.start()

    try:
        await server.wait_for_termination()
    except KeyboardInterrupt:
        print("\nShutting down...")
        await server.stop(0)
        await close_db()
        print("All connections closed")


if __name__ == "__main__":
    asyncio.run(serve())
