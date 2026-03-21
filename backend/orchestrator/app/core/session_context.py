from dataclasses import dataclass

@dataclass
class SessionContext:
    user_id: str
    device_id: str
    device_type: str
    expires_at: int
