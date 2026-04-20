"""
Async S3 client built on top of aioboto3.

Wraps presigned URL generation, head_object, delete_object, and delete_objects.
Uses contextmanager-based sessions because aioboto3 resources are not long-lived.
"""

from __future__ import annotations

from types import TracebackType

import aioboto3
from botocore.config import Config as BotoConfig
from botocore.exceptions import ClientError

from app.config import settings


class S3Client:
    def __init__(self) -> None:
        self._session = aioboto3.Session(
            aws_access_key_id=settings.AWS_ACCESS_KEY_ID,
            aws_secret_access_key=settings.AWS_SECRET_ACCESS_KEY,
            region_name=settings.AWS_REGION,
        )
        self._extra: dict[str, str] = {}
        if settings.S3_ENDPOINT_URL:
            self._extra["endpoint_url"] = settings.S3_ENDPOINT_URL
        # Separate endpoint for presigned URL generation (must be reachable from clients).
        # Falls back to S3_ENDPOINT_URL if not set.
        self._presign_extra: dict[str, str] = {}
        public_ep = settings.S3_PUBLIC_ENDPOINT or settings.S3_ENDPOINT_URL
        if public_ep:
            self._presign_extra["endpoint_url"] = public_ep
        self._boto_config = BotoConfig(signature_version="s3v4")

    def _client_ctx(self):
        return self._session.client("s3", config=self._boto_config, **self._extra)

    def _presign_client_ctx(self):
        """Client used only for generating presigned URLs with the public endpoint."""
        return self._session.client("s3", config=self._boto_config, **self._presign_extra)

    async def generate_presigned_upload_url(
        self,
        bucket: str,
        key: str,
        content_type: str,
        content_length: int,
        expires_in: int,
    ) -> str:
        async with self._presign_client_ctx() as client:
            url = await client.generate_presigned_url(
                "put_object",
                Params={
                    "Bucket": bucket,
                    "Key": key,
                    "ContentType": content_type,
                    "ContentLength": content_length,
                },
                ExpiresIn=expires_in,
            )
        return url

    async def generate_presigned_download_url(
        self,
        bucket: str,
        key: str,
        expires_in: int,
    ) -> str:
        async with self._presign_client_ctx() as client:
            url = await client.generate_presigned_url(
                "get_object",
                Params={"Bucket": bucket, "Key": key},
                ExpiresIn=expires_in,
            )
        return url

    async def head_object(self, bucket: str, key: str) -> dict | None:
        """Returns metadata dict or None if object does not exist."""
        async with self._client_ctx() as client:
            try:
                response = await client.head_object(Bucket=bucket, Key=key)
                return {
                    "content_length": response["ContentLength"],
                    "content_type": response.get("ContentType", ""),
                }
            except ClientError as e:
                if e.response["Error"]["Code"] == "404":
                    return None
                raise

    async def delete_object(self, bucket: str, key: str) -> bool:
        async with self._client_ctx() as client:
            await client.delete_object(Bucket=bucket, Key=key)
        return True

    async def delete_objects(
        self,
        bucket: str,
        keys: list[str],
    ) -> tuple[int, list[dict]]:
        """
        Batch delete up to 1000 keys.
        Returns (deleted_count, errors_list).
        """
        if not keys:
            return 0, []
        async with self._client_ctx() as client:
            response = await client.delete_objects(
                Bucket=bucket,
                Delete={"Objects": [{"Key": k} for k in keys], "Quiet": False},
            )
        deleted = len(response.get("Deleted", []))
        errors = [{"key": e["Key"], "code": e["Code"], "message": e["Message"]} for e in response.get("Errors", [])]
        return deleted, errors

    async def head_bucket(self, bucket: str) -> bool:
        """Returns True if the bucket is accessible."""
        async with self._client_ctx() as client:
            try:
                await client.head_bucket(Bucket=bucket)
                return True
            except ClientError:
                return False
