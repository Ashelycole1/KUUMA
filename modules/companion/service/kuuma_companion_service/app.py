"""Companion service.

Real operations are mounted here and listed in SKIP; anything left in companion.yaml is still
served from its OpenAPI example.
"""

from __future__ import annotations

import os

from fastapi import APIRouter, Response

from kuuma_companion_service import packs
from kuuma_server_common.service import create_stub_service

OPENAPI_FILES = ["companion.yaml"]
SKIP: set[str] = {"latestContentPack"}

HOOKS: dict = {}

# Where the archives are published (MinIO in the local stack). The archive name is appended.
DEFAULT_PACK_BASE_URL = "http://localhost:9000/kuuma-artifacts/content-packs"

router = APIRouter(prefix="/v1/companion")


@router.get("/content-packs/latest")
def latest_content_pack(response: Response) -> dict:
    """Manifest of the newest signed content pack; the phone downloads it in the background."""
    pack = packs.latest()
    response.headers["ETag"] = f'"{pack.sha256}"'
    response.headers["Cache-Control"] = "public, max-age=3600"
    return pack.manifest(os.environ.get("COMPANION_PACK_BASE_URL", DEFAULT_PACK_BASE_URL))


app = create_stub_service("companion", OPENAPI_FILES, skip=SKIP, hooks=HOOKS)
app.include_router(router)
