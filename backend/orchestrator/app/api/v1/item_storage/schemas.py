from pydantic import BaseModel


class ItemStorageHealthResponseSchema(BaseModel):
    status: str
    db_status: str
    s3_status: str
    version: str
