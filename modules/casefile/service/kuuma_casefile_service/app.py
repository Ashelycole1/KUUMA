"""Casefile service skeleton.

Today every operation in cases.yaml, verify.yaml is served from its OpenAPI example. Replace
operations one at a time: add a real router and put its operationId in SKIP.
"""

from kuuma_server_common.seed import seed
from kuuma_server_common.service import create_stub_service, envelope

OPENAPI_FILES = ["cases.yaml", "verify.yaml"]
SKIP: set[str] = set()


def _share_completed(app, body):
    # Sample wiring: a real Casefile publishes this once the parent approved and the link exists.
    case_id = seed()["sample_case"]["case_id"]
    app.state.bus.publish(
        envelope(
            "casefile.share.completed",
            {
                "requestId": body["requestId"],
                "caseId": case_id,
                "recipientType": body["recipientType"],
                "expiresAt": "2026-12-05T09:30:00Z",
            },
            device_id=seed()["child_device"]["device_id"],
            case_id=case_id,
        )
    )


HOOKS = {"createShare": _share_completed}


app = create_stub_service("casefile", OPENAPI_FILES, skip=SKIP, hooks=HOOKS)
