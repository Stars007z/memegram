import grpc
import os


class ContactsGateway:
    """
    Стаб для вызова contacts-service.
    Подключается к CONTACTS_GRPC_HOST:CONTACTS_GRPC_PORT.
    Пока contacts-service не поднят — возвращает False безопасно.
    """

    def __init__(self):
        host = os.getenv("CONTACTS_GRPC_HOST", "contacts-service")
        port = os.getenv("CONTACTS_GRPC_PORT", "50053")
        self._address = f"{host}:{port}"

    async def is_contact(self, owner_user_id: str, contact_user_id: str) -> bool:
        try:
            # from app.generated import contacts_pb2, contacts_pb2_grpc
            # channel = grpc.aio.insecure_channel(self._address)
            # stub = contacts_pb2_grpc.ContactsServiceStub(channel)
            # resp = await stub.IsContact(contacts_pb2.IsContactRequest(
            #     owner_user_id=owner_user_id,
            #     contact_user_id=contact_user_id,
            # ), timeout=2.0)
            # return resp.is_contact
            return False
        except Exception:
            return False
