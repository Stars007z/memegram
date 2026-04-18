from app.core.session_context import SessionContext

def build_session_metadata(session: SessionContext) -> list[tuple[str, str]]:
    return [
        ("x-user-id", session.user_id),
        ("x-device-id", session.device_id),
        ("x-device-type", session.device_type),
    ]
