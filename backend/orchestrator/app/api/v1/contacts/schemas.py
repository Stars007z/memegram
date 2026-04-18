from typing import Optional, List
from pydantic import BaseModel, Field, ConfigDict

class UserBriefProfileSchema(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    user_id: str
    username: str
    user_public_key: str
    bio: str
    avatar_media_id: str

class ContactEntrySchema(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    contact_user_id: str
    is_favorite: bool
    created_at: int
    profile: Optional[UserBriefProfileSchema] = None

class BlockedEntrySchema(BaseModel):
    blocked_user_id: str
    blocked_at: int
    profile: Optional[UserBriefProfileSchema] = None

class AddContactRequestSchema(BaseModel):
    user_public_key: str = Field(..., min_length=1)

class RemoveContactRequestSchema(BaseModel):
    contact_user_id: str = Field(..., min_length=1)

class GetContactsQuerySchema(BaseModel):
    limit: int = Field(50, ge=1, le=200)
    offset: int = Field(0, ge=0)

class UpdateContactRequestSchema(BaseModel):
    contact_user_id: str = Field(..., min_length=1)
    is_favorite: Optional[bool] = None

class BlockUserRequestSchema(BaseModel):
    blocked_user_id: str = Field(..., min_length=1)

class UnblockUserRequestSchema(BaseModel):
    blocked_user_id: str = Field(..., min_length=1)

class GetBlockedUsersQuerySchema(BaseModel):
    limit: int = Field(50, ge=1, le=200)
    offset: int = Field(0, ge=0)

class AddContactResponseSchema(BaseModel):
    contact: ContactEntrySchema

class RemoveContactResponseSchema(BaseModel):
    success: bool

class GetContactsResponseSchema(BaseModel):
    contacts: List[ContactEntrySchema]
    total_count: int

class UpdateContactResponseSchema(BaseModel):
    contact: ContactEntrySchema

class BlockUserResponseSchema(BaseModel):
    success: bool
    created_at: int

class UnblockUserResponseSchema(BaseModel):
    success: bool

class GetBlockedUsersResponseSchema(BaseModel):
    blocked_users: List[BlockedEntrySchema]
    total_count: int

class ContactsHealthResponseSchema(BaseModel):
    status: str
    version: str
