from fastapi import APIRouter, Depends, Query

from app.api.dependencies import get_current_session, get_contacts_gateway
from app.api.v1.contacts.schemas import (
    AddContactRequestSchema,
    AddContactResponseSchema,
    RemoveContactRequestSchema,
    RemoveContactResponseSchema,
    GetContactsResponseSchema,
    UpdateContactRequestSchema,
    UpdateContactResponseSchema,
    BlockUserRequestSchema,
    BlockUserResponseSchema,
    UnblockUserRequestSchema,
    UnblockUserResponseSchema,
    GetBlockedUsersResponseSchema,
    ContactsHealthResponseSchema,
    UserBriefProfileSchema,
    ContactEntrySchema,
)
from app.core.interfaces.contacts_gateway import IContactsGateway
from app.core.session_context import SessionContext

router = APIRouter(prefix="/contacts", tags=["contacts"])

@router.get("/health", response_model=ContactsHealthResponseSchema)
async def contacts_health(
    gateway: IContactsGateway = Depends(get_contacts_gateway),
) -> ContactsHealthResponseSchema:
    result = await gateway.health_check()
    return ContactsHealthResponseSchema(status=result.status, version=result.version)

@router.post("", response_model=AddContactResponseSchema, status_code=201)
async def add_contact(
    body: AddContactRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gateway: IContactsGateway = Depends(get_contacts_gateway),
) -> AddContactResponseSchema:
    result = await gateway.add_contact(
        user_id=session.user_id,
        user_public_key=body.user_public_key,
    )
    return AddContactResponseSchema(contact=result.contact.__dict__)

@router.delete("/{contact_user_id}", response_model=RemoveContactResponseSchema)
async def remove_contact(
    contact_user_id: str,
    session: SessionContext = Depends(get_current_session),
    gateway: IContactsGateway = Depends(get_contacts_gateway),
) -> RemoveContactResponseSchema:
    result = await gateway.remove_contact(
        user_id=session.user_id,
        contact_user_id=contact_user_id,
    )
    return RemoveContactResponseSchema(success=result.success)

@router.get("", response_model=GetContactsResponseSchema)
async def get_contacts(
    session: SessionContext = Depends(get_current_session),
    gateway: IContactsGateway = Depends(get_contacts_gateway),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
) -> GetContactsResponseSchema:
    result = await gateway.get_contacts(
        user_id=session.user_id, limit=limit, offset=offset,
    )
    return GetContactsResponseSchema(
        contacts=[
            ContactEntrySchema(
                contact_user_id=c.contact_user_id,
                is_favorite=c.is_favorite,
                created_at=c.created_at,
                profile=UserBriefProfileSchema(
                    user_id=c.profile.user_id,
                    username=c.profile.username,
                    user_public_key=c.profile.user_public_key,
                    bio=c.profile.bio,
                    avatar_media_id=c.profile.avatar_media_id,
                ) if c.profile else None,
            )
            for c in result.contacts
        ],
        total_count=result.total_count,
    )

@router.patch("/{contact_user_id}", response_model=UpdateContactResponseSchema)
async def update_contact(
    contact_user_id: str,
    body: UpdateContactRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gateway: IContactsGateway = Depends(get_contacts_gateway),
) -> UpdateContactResponseSchema:
    result = await gateway.update_contact(
        user_id=session.user_id,
        contact_user_id=contact_user_id,
        is_favorite=body.is_favorite,
    )
    return UpdateContactResponseSchema(contact=result.contact.__dict__)

@router.post("/blocked", response_model=BlockUserResponseSchema, status_code=201)
async def block_user(
    body: BlockUserRequestSchema,
    session: SessionContext = Depends(get_current_session),
    gateway: IContactsGateway = Depends(get_contacts_gateway),
) -> BlockUserResponseSchema:
    result = await gateway.block_user(
        user_id=session.user_id,
        blocked_user_id=body.blocked_user_id,
    )
    return BlockUserResponseSchema(success=result.success, created_at=result.created_at)

@router.delete("/blocked/{blocked_user_id}", response_model=UnblockUserResponseSchema)
async def unblock_user(
    blocked_user_id: str,
    session: SessionContext = Depends(get_current_session),
    gateway: IContactsGateway = Depends(get_contacts_gateway),
) -> UnblockUserResponseSchema:
    result = await gateway.unblock_user(
        user_id=session.user_id,
        blocked_user_id=blocked_user_id,
    )
    return UnblockUserResponseSchema(success=result.success)

@router.get("/blocked", response_model=GetBlockedUsersResponseSchema)
async def get_blocked_users(
    session: SessionContext = Depends(get_current_session),
    gateway: IContactsGateway = Depends(get_contacts_gateway),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
) -> GetBlockedUsersResponseSchema:
    result = await gateway.get_blocked_users(
        user_id=session.user_id,
        limit=limit,
        offset=offset,
    )
    return GetBlockedUsersResponseSchema(
        blocked_users=[b.__dict__ for b in result.blocked_users],
        total_count=result.total_count,
    )
