"""Companion service skeleton.

Today every operation in companion.yaml is served from its OpenAPI example. Replace
operations one at a time: add a real router and put its operationId in SKIP.
"""

from kuuma_server_common.service import create_stub_service

OPENAPI_FILES = ["companion.yaml"]
SKIP: set[str] = set()

HOOKS: dict = {}


app = create_stub_service("companion", OPENAPI_FILES, skip=SKIP, hooks=HOOKS)
