from grpc.aio import Metadata

from app.core.session_context import SessionContext


def build_session_metadata(session: SessionContext) -> list[tuple[str, str]]:
    return [
        ("x-user-id", session.userid),
        ("x-device-id", session.deviceid),
        ("x-device-type", session.devicetype),
    ]
