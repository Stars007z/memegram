from fastapi import APIRouter
from app.api.v1.auth.router import router as auth_router
from app.api.v1.user.router import router as user_router
from app.api.v1.contacts.router import router as contacts_router
from app.api.v1.admin.router import router as admin_router
from app.api.v1.messaging.router import router as messaging_router
from app.api.v1.media.router import router as media_router
from app.api.v1.devices.router import router as devices_router
from app.api.v1.item_storage.router import router as item_storage_router
from app.api.v1.notifications.router import router as notifications_router

v1_router = APIRouter(prefix="/api/v1")
v1_router.include_router(auth_router)
v1_router.include_router(user_router)
v1_router.include_router(contacts_router)
v1_router.include_router(admin_router)
v1_router.include_router(messaging_router)
v1_router.include_router(media_router)
v1_router.include_router(devices_router)
v1_router.include_router(item_storage_router)
v1_router.include_router(notifications_router)
