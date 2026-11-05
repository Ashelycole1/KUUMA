"""Guardian service skeleton.

Today every operation in guardian.yaml is served from its OpenAPI example. Replace
operations one at a time: add a real router and put its operationId in SKIP.
"""

import uuid

from kuuma_server_common.seed import seed
from kuuma_server_common.service import create_stub_service, envelope

OPENAPI_FILES = ["guardian.yaml"]
SKIP: set[str] = set()


def _alert_sent(app, body):
    # Sample wiring: publish guardian.alert.sent when an alert is raised.
    app.state.bus.publish(
        envelope(
            "guardian.alert.sent",
            {"alertId": str(uuid.uuid4()), "caseId": body["caseId"], "severity": body["severity"]},
            device_id=seed()["child_device"]["device_id"],
            case_id=body["caseId"],
        )
    )


HOOKS = {"createAlert": _alert_sent}


app = create_stub_service("guardian", OPENAPI_FILES, skip=SKIP, hooks=HOOKS)
