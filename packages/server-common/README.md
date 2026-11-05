# kuuma-server-common

Base for every Kuuma Python service.

```python
from kuuma_server_common.service import create_stub_service   # skeletons
from kuuma_server_common import create_app, device_auth, case_auth, get_logger, StreamPublisher, make_base
```

| Piece | Module |
| --- | --- |
| App factory: standard errors, request IDs, `/healthz`, `/readyz`, `/metrics`, personal-data guard | `app.py`, `errors.py`, `pii_guard.py` |
| OpenAPI example stubs (replace operation by operation; list replaced ones in `SKIP`) | `openapi_stub.py` |
| Device signatures (ECDSA P-256) and case-ID tokens (HMAC) | `auth.py` |
| One PostgreSQL schema per build, per-schema Alembic | `db.py` |
| Redis Streams with topic ownership; refuses phone-only events | `streams.py` |
| Content-free logging and audit | `safe_log.py`, `audit.py` |
| Test helpers: `MemoryStreams`, `signed_headers` (seed test device) | `testing.py` |

## Device signature

Headers `X-Kuuma-Device-Id`, `X-Kuuma-Timestamp` (unix seconds) and `X-Kuuma-Signature` =
base64(DER ECDSA-SHA256) over:

```
METHOD \n PATH \n TIMESTAMP \n hex(sha256(body))
```

Requests more than 5 minutes off the server clock are refused. On the phone,
`DeviceSigner.requestHeaders(...)` in core-android builds these headers.

## Personal data

Request bodies and query strings containing a phone number or email address are refused
with `422 personal_data_rejected`. Redact on the phone first (`BasicRedactor`).
