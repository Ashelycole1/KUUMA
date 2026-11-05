"""AI service skeleton.

Today every operation in ai.yaml is served from its OpenAPI example. Replace
operations one at a time: add a real router and put its operationId in SKIP.
"""

from kuuma_server_common.service import create_stub_service

OPENAPI_FILES = ["ai.yaml"]
SKIP: set[str] = set()

HOOKS: dict = {}


app = create_stub_service("ai", OPENAPI_FILES, skip=SKIP, hooks=HOOKS)
