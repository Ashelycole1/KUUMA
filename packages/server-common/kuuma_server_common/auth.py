"""Authentication: device-key signatures for phones and case-ID tokens for case access.

Device signatures
-----------------
Each child device holds an ECDSA P-256 key (Android Keystore where available, software
fallback on Android 5.x). Its public key is registered at pairing. Every request carries:

    X-Kuuma-Device-Id:  the device's UUID
    X-Kuuma-Timestamp:  unix seconds
    X-Kuuma-Signature:  base64(DER ECDSA-SHA256 over the canonical string)

    canonical = METHOD "\\n" PATH "\\n" TIMESTAMP "\\n" hex(sha256(BODY))

Requests more than five minutes off the server clock are refused (replay window).

Case tokens
-----------
A case token proves the holder may act on one case. Format: base64url(payload) "." base64url(hmac)
where payload = {"c": caseId, "d": deviceId, "e": expiry}. Signed with KUUMA_CASE_TOKEN_KEY.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
from dataclasses import dataclass
from typing import Protocol

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import Depends, Header, Request

from kuuma_server_common.errors import KuumaError

MAX_SKEW_SECONDS = 300


def canonical_request(method: str, path: str, timestamp: str, body: bytes) -> bytes:
    return f"{method.upper()}\n{path}\n{timestamp}\n{hashlib.sha256(body).hexdigest()}".encode()


class DeviceKeyStore(Protocol):
    def public_key(self, device_id: str) -> ec.EllipticCurvePublicKey | None: ...


class InMemoryDeviceKeys:
    """Device keys held in memory: used by stubs, tests and the seed data."""

    def __init__(self) -> None:
        self._keys: dict[str, ec.EllipticCurvePublicKey] = {}

    def register(self, device_id: str, public_key_b64: str) -> None:
        key = serialization.load_der_public_key(base64.b64decode(public_key_b64))
        if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
            raise KuumaError(400, "bad_device_key", "Device keys must be ECDSA P-256")
        self._keys[device_id] = key

    def public_key(self, device_id: str) -> ec.EllipticCurvePublicKey | None:
        return self._keys.get(device_id)


@dataclass(frozen=True)
class DeviceIdentity:
    device_id: str


def sign_request(
    private_key: ec.EllipticCurvePrivateKey,
    method: str,
    path: str,
    body: bytes,
    device_id: str,
    now: int | None = None,
) -> dict[str, str]:
    """Client side of the scheme; used by tests, fakes and the scenario runner."""
    ts = str(now if now is not None else int(time.time()))
    sig = private_key.sign(canonical_request(method, path, ts, body), ec.ECDSA(hashes.SHA256()))
    return {
        "X-Kuuma-Device-Id": device_id,
        "X-Kuuma-Timestamp": ts,
        "X-Kuuma-Signature": base64.b64encode(sig).decode(),
    }


def device_auth(store: DeviceKeyStore):
    """FastAPI dependency factory: `Depends(device_auth(store))` returns the DeviceIdentity."""

    async def dependency(
        request: Request,
        x_kuuma_device_id: str = Header(...),
        x_kuuma_timestamp: str = Header(...),
        x_kuuma_signature: str = Header(...),
    ) -> DeviceIdentity:
        try:
            ts = int(x_kuuma_timestamp)
        except ValueError:
            raise KuumaError(401, "unauthorized", "Bad timestamp") from None
        if abs(time.time() - ts) > MAX_SKEW_SECONDS:
            raise KuumaError(401, "unauthorized", "Timestamp outside the allowed window")
        key = store.public_key(x_kuuma_device_id)
        if key is None:
            raise KuumaError(401, "unauthorized", "Unknown device")
        body = await request.body()
        try:
            key.verify(
                base64.b64decode(x_kuuma_signature),
                canonical_request(request.method, request.url.path, x_kuuma_timestamp, body),
                ec.ECDSA(hashes.SHA256()),
            )
        except (InvalidSignature, ValueError):
            raise KuumaError(401, "unauthorized", "Bad signature") from None
        return DeviceIdentity(x_kuuma_device_id)

    return dependency


# ----------------------------------------------------------------------------- case tokens


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def _unb64(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _case_key() -> bytes:
    key = os.environ.get("KUUMA_CASE_TOKEN_KEY", "")
    if not key or key == "change-me":
        if os.environ.get("KUUMA_ENV") == "production":
            raise RuntimeError("KUUMA_CASE_TOKEN_KEY must be set in production")
        key = "kuuma-local-dev-only-case-token-key"
    return key.encode()


def issue_case_token(case_id: str, device_id: str, ttl_seconds: int = 3600, now: int | None = None) -> str:
    payload = _b64(
        json.dumps(
            {"c": case_id, "d": device_id, "e": (now or int(time.time())) + ttl_seconds},
            separators=(",", ":"),
        ).encode()
    )
    mac = _b64(hmac.new(_case_key(), payload.encode(), hashlib.sha256).digest())
    return f"{payload}.{mac}"


@dataclass(frozen=True)
class CaseGrant:
    case_id: str
    device_id: str


def verify_case_token(token: str, case_id: str) -> CaseGrant:
    try:
        payload, mac = token.split(".", 1)
        expected = _b64(hmac.new(_case_key(), payload.encode(), hashlib.sha256).digest())
        if not hmac.compare_digest(mac, expected):
            raise ValueError
        data = json.loads(_unb64(payload))
    except (ValueError, KeyError):
        raise KuumaError(401, "unauthorized", "Bad case token") from None
    if data["e"] < time.time():
        raise KuumaError(401, "unauthorized", "Case token expired")
    if data["c"] != case_id:
        raise KuumaError(403, "forbidden", "Token is for another case")
    return CaseGrant(data["c"], data["d"])


async def _bearer(authorization: str = Header(...)) -> str:
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise KuumaError(401, "unauthorized", "Bearer case token required")
    return token


def case_auth():
    """FastAPI dependency: checks the bearer case token against the {caseId} path parameter."""

    async def dependency(request: Request, token: str = Depends(_bearer)) -> CaseGrant:
        case_id = request.path_params.get("caseId")
        if not case_id:
            raise KuumaError(400, "bad_request", "No caseId in path")
        return verify_case_token(token, case_id)

    return dependency
