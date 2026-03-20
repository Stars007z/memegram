from fastapi import APIRouter
from app.api.v1.auth.router import router as auth_router
from app.api.v1.user.router import router as user_router
from app.api.v1.admin.router import router as admin_router

v1_router = APIRouter(prefix="/api/v1")
v1_router.include_router(auth_router)
v1_router.include_router(user_router)
v1_router.include_router(admin_router)