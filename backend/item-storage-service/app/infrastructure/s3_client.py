import aioboto3
from contextlib import asynccontextmanager
from typing import AsyncGenerator, Any
from app.config import settings


_session = aioboto3.Session(
    aws_access_key_id=settings.AWS_ACCESS_KEY_ID,
    aws_secret_access_key=settings.AWS_SECRET_ACCESS_KEY,
    region_name=settings.AWS_REGION,
)


def _sse_params() -> dict[str, str]:
    if settings.S3_SSE_TYPE == "aws:kms":
        params: dict[str, str] = {"ServerSideEncryption": "aws:kms"}
        if settings.KMS_KEY_ID:
            params["SSEKMSKeyId"] = settings.KMS_KEY_ID
        return params
    return {"ServerSideEncryption": "AES256"}


@asynccontextmanager
async def get_s3_client() -> AsyncGenerator[Any, None]:
    async with _session.client(
        "s3",
        endpoint_url=settings.s3_endpoint,
    ) as client:
        yield client


async def generate_presigned_upload_url(
    bucket: str, key: str, mime_type: str, ttl: int,
) -> str:
    sse = _sse_params()
    params: dict[str, Any] = {
        "Bucket": bucket,
        "Key": key,
        "ContentType": mime_type,
    }
    params.update(sse)

    async with get_s3_client() as client:
        url: str = await client.generate_presigned_url(
            "put_object",
            Params=params,
            ExpiresIn=ttl,
        )
    return url


async def generate_presigned_download_url(
    bucket: str, key: str, ttl: int,
) -> str:
    async with get_s3_client() as client:
        url: str = await client.generate_presigned_url(
            "get_object",
            Params={"Bucket": bucket, "Key": key},
            ExpiresIn=ttl,
        )
    return url


async def head_object(bucket: str, key: str) -> dict:
    async with get_s3_client() as client:
        return await client.head_object(Bucket=bucket, Key=key)


async def delete_object(bucket: str, key: str) -> None:
    async with get_s3_client() as client:
        await client.delete_object(Bucket=bucket, Key=key)


async def delete_objects(bucket: str, keys: list[str]) -> None:
    async with get_s3_client() as client:
        for i in range(0, len(keys), 1000):
            batch = keys[i : i + 1000]
            await client.delete_objects(
                Bucket=bucket,
                Delete={"Objects": [{"Key": k} for k in batch]},
            )


async def head_bucket(bucket: str) -> bool:
    try:
        async with get_s3_client() as client:
            await client.head_bucket(Bucket=bucket)
        return True
    except Exception:
        return False
