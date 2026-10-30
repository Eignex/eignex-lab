"""Short-lived, pool-scoped coordinator authorization; no provider logic."""
import base64
import hashlib
import hmac
import json
import time

# The wire format shared with the coordinator (CapacityWire): deploy/capacity-wire.json holds the same values.
DOMAIN = b"eignex-capacity-v1\n"
SCHEMA_VERSION = 1
FRESH_MS = 30000
CLOCK_SKEW_MS = 5000
LEASE_SECONDS = 1800


def verify(grant, key, pool, max_nodes, now_ms=None):
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    if not isinstance(grant, dict) or not {"payload", "signature"}.issubset(grant):
        raise ValueError("invalid capacity grant")
    payload = grant["payload"]
    signature = grant["signature"]
    if not isinstance(payload, str) or len(payload) > 8192 or not isinstance(signature, str):
        raise ValueError("invalid capacity grant")
    raw = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
    expected = hmac.new(key.strip().encode(), DOMAIN + raw, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature):
        raise ValueError("invalid capacity signature")
    value = json.loads(raw)
    if value.get("schemaVersion") != SCHEMA_VERSION or value.get("pool") != pool or value.get("maxNodes") != max_nodes:
        raise ValueError("capacity grant target mismatch")
    if type(value.get("issuedAt")) is not int or not -CLOCK_SKEW_MS <= now_ms - value["issuedAt"] <= FRESH_MS:
        raise ValueError("capacity grant expired")
    if type(value.get("desired")) is not int or not 0 <= value["desired"] <= max_nodes:
        raise ValueError("capacity grant exceeds finite limit")
    return value
