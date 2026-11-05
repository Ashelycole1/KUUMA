"""Object storage and the timestamp authority, behind small interfaces so tests run without MinIO."""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import os
from typing import Protocol

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

BUCKET = "kuuma-bundles"


class ObjectStore(Protocol):
    def upload_url(self, key: str, expires: dt.timedelta) -> str: ...
    def download_url(self, key: str, expires: dt.timedelta) -> str: ...
    def exists(self, key: str) -> bool: ...


class MinioStore:
    """MinIO locally (S3 later). URLs are pre-signed, so bundles never pass through this service."""

    def __init__(self) -> None:
        from minio import Minio

        public = os.environ.get("MINIO_PUBLIC_ENDPOINT", "localhost:9000")
        internal = os.environ.get("MINIO_ENDPOINT", public)
        user, secret = os.environ.get("MINIO_ROOT_USER", ""), os.environ.get("MINIO_ROOT_PASSWORD", "")
        # Signs URLs for the address phones and recipients use; region set so signing needs no network.
        self.public = Minio(public, access_key=user, secret_key=secret, secure=False, region="us-east-1")
        self.internal = Minio(internal, access_key=user, secret_key=secret, secure=False, region="us-east-1")

    def upload_url(self, key: str, expires: dt.timedelta) -> str:
        return self.public.presigned_put_object(BUCKET, key, expires=expires)

    def download_url(self, key: str, expires: dt.timedelta) -> str:
        return self.public.presigned_get_object(BUCKET, key, expires=expires)

    def exists(self, key: str) -> bool:
        try:
            self.internal.stat_object(BUCKET, key)
            return True
        except Exception:  # noqa: BLE001 - any S3 error means "not there (yet)"
            return False


class MemoryStore:
    """For tests: 'uploads' are simulated with put()."""

    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}

    def upload_url(self, key: str, expires: dt.timedelta) -> str:
        return f"http://minio.test/{BUCKET}/{key}?upload"

    def download_url(self, key: str, expires: dt.timedelta) -> str:
        return f"http://minio.test/{BUCKET}/{key}?download"

    def exists(self, key: str) -> bool:
        return key in self.objects

    def put(self, key: str, data: bytes) -> None:
        self.objects[key] = data


class MockTimestampAuthority:
    """
    LOCAL ONLY: signs `hash|timestamp` with ECDSA P-256. Not RFC 3161, not trusted by anyone
    else. Replace with a real RFC 3161 authority before the pilot (Casefile brief).
    """

    name = "kuuma-local-mock-tsa"

    def __init__(self, seed: str | None = None) -> None:
        seed = seed or os.environ.get("KUUMA_TSA_SEED", "kuuma-local-mock-tsa")
        # Deterministic key from the seed, so timestamps still verify after a restart.
        n = int.from_bytes(hashlib.sha256(seed.encode()).digest(), "big") % (2**256 - 2**224) + 1
        self.key = ec.derive_private_key(n, ec.SECP256R1())

    def stamp(self, hash_hex: str, now: dt.datetime) -> tuple[str, str]:
        ts = now.astimezone(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
        sig = self.key.sign(f"{hash_hex}|{ts}".encode(), ec.ECDSA(hashes.SHA256()))
        return ts, base64.b64encode(sig).decode()

    def verify(self, hash_hex: str, ts: str, signature: str) -> bool:
        try:
            self.key.public_key().verify(
                base64.b64decode(signature), f"{hash_hex}|{ts}".encode(), ec.ECDSA(hashes.SHA256())
            )
            return True
        except Exception:  # noqa: BLE001
            return False
