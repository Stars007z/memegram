import uuid

import grpc

from app.container import Container
from app.database.redis import check_redis_health
from app.generated import messaging_pb2


class HealthHandler:

    def __init__(self, container: Container) -> None:
        self._container = container

    async def health_check(self, request, context):
        db_status = "unknown"
        redis_status = "unknown"
        media_status = "unknown"

        try:
            async with self._container.request_scope() as scope:
                from sqlalchemy import text

                await scope._session.execute(text("SELECT 1"))
                db_status = "connected"
        except Exception as e:
            db_status = f"failed: {e}"

        try:
            redis_ok = await check_redis_health()
            redis_status = "connected" if redis_ok else "disconnected"
        except Exception as e:
            redis_status = f"failed: {e}"

        try:
            ok = await self._container._media.health_check()
            media_status = "connected" if ok else "disconnected"
        except Exception as e:
            media_status = f"failed: {e}"

        overall = "ok"
        if "failed" in db_status or "failed" in redis_status:
            overall = "degraded"

        return messaging_pb2.HealthCheckResponse(
            status=overall,
            db_status=db_status,
            redis_status=redis_status,
            media_service_status=media_status,
            version="1.0.0",
        )

    async def get_conversation_members(self, request, context):
        if not request.conversation_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("conversation_id is required")
            return messaging_pb2.GetConversationMembersResponse()

        async with self._container.request_scope() as scope:
            from app.repositories.member_repo import MemberRepository

            member_repo = MemberRepository(scope._session)
            members = await member_repo.get_active_members(
                uuid.UUID(request.conversation_id),
            )
            return messaging_pb2.GetConversationMembersResponse(
                members=[
                    messaging_pb2.GetConversationMembersResponse.MemberInfo(
                        user_id=str(m.user_id),
                        role=m.role,
                    )
                    for m in members
                ]
            )
