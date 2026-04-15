from pydantic import BaseModel


class RegisterPushTokenRequestSchema(BaseModel):
    platform: str       # ios / android
    push_token: str


class RegisterPushTokenResponseSchema(BaseModel):
    success: bool


class UnregisterPushTokenResponseSchema(BaseModel):
    success: bool


class NotificationsHealthResponseSchema(BaseModel):
    status: str
    db_status: str
    redis_status: str
    fcm_status: str
    apns_status: str
    version: str
