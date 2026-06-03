#!/usr/bin/env python3
"""Generate MTProto obfuscated handshake parity vectors from upstream.

The vectors exercise ``proxy/tg_ws_proxy.py::_try_handshake`` in the checked-out
``third_party/tg-ws-proxy`` submodule and are consumed by Kotlin unit tests.
"""

from __future__ import annotations

import hashlib
import json
import random
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_ROOT = REPO_ROOT / "third_party" / "tg-ws-proxy"
OUTPUT_PATH = (
    REPO_ROOT / "app" / "src" / "test" / "resources" / "handshake_vectors.json"
)
SEED = 0x54575753
VALID_SECRET = bytes.fromhex("0123456789abcdeffedcba9876543210")
WRONG_SECRET = bytes.fromhex("00112233445566778899aabbccddeeff")

sys.path.insert(0, str(UPSTREAM_ROOT))

from proxy._aes import Cipher, algorithms, modes  # noqa: E402
from proxy.tg_ws_proxy import _try_handshake  # noqa: E402
from proxy.utils import (  # noqa: E402
    DC_IDX_POS,
    HANDSHAKE_LEN,
    IV_LEN,
    PREKEY_LEN,
    PROTO_TAG_ABRIDGED,
    PROTO_TAG_INTERMEDIATE,
    PROTO_TAG_POS,
    PROTO_TAG_SECURE,
    RESERVED_CONTINUE,
    RESERVED_FIRST_BYTES,
    RESERVED_STARTS,
    SKIP_LEN,
)


def _randbytes(rng: random.Random, length: int) -> bytes:
    return bytes(rng.randrange(0, 256) for _ in range(length))


def _random_handshake_prefix(rng: random.Random) -> bytearray:
    while True:
        candidate = bytearray(_randbytes(rng, HANDSHAKE_LEN))
        if candidate[0] in RESERVED_FIRST_BYTES:
            continue
        if bytes(candidate[:4]) in RESERVED_STARTS:
            continue
        if bytes(candidate[4:8]) == RESERVED_CONTINUE:
            continue
        return candidate


def _keystream(data: bytes, key: bytes, iv: bytes) -> bytes:
    encryptor = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
    encrypted = encryptor.update(data)
    return bytes(
        encrypted_byte ^ plain_byte
        for encrypted_byte, plain_byte in zip(encrypted, data)
    )


def build_client_handshake(
    rng: random.Random, secret: bytes, proto_tag: bytes, dc_idx: int
) -> bytes:
    """Build a client obfuscated handshake accepted by upstream _try_handshake."""
    handshake = _random_handshake_prefix(rng)
    dec_prekey = bytes(handshake[SKIP_LEN : SKIP_LEN + PREKEY_LEN])
    dec_iv = bytes(handshake[SKIP_LEN + PREKEY_LEN : SKIP_LEN + PREKEY_LEN + IV_LEN])
    dec_key = hashlib.sha256(dec_prekey + secret).digest()
    stream = _keystream(bytes(handshake), dec_key, dec_iv)

    tail_plain = (
        proto_tag + int(dc_idx).to_bytes(2, "little", signed=True) + _randbytes(rng, 2)
    )
    tail_start = PROTO_TAG_POS
    for offset, plain_byte in enumerate(tail_plain):
        handshake[tail_start + offset] = plain_byte ^ stream[tail_start + offset]
    return bytes(handshake)


def _expected(handshake: bytes, secret: bytes) -> dict[str, Any] | None:
    try:
        result = _try_handshake(handshake, secret)
    except Exception:
        return None
    if result is None:
        return None
    dc_id, is_media, proto_tag, client_dec_prekey_iv = result
    return {
        "dc_id": dc_id,
        "is_media": is_media,
        "proto_tag_hex": proto_tag.hex(),
        "client_dec_prekey_iv_hex": client_dec_prekey_iv.hex(),
    }


def _vector(name: str, secret: bytes, handshake: bytes) -> dict[str, Any]:
    return {
        "name": name,
        "secret_hex": secret.hex(),
        "handshake_hex": handshake.hex(),
        "expected": _expected(handshake, secret),
    }


def generate_vectors() -> dict[str, Any]:
    rng = random.Random(SEED)
    valid_specs = [
        ("abridged_dc2", PROTO_TAG_ABRIDGED, 2),
        ("intermediate_dc4", PROTO_TAG_INTERMEDIATE, 4),
        ("secure_dc2", PROTO_TAG_SECURE, 2),
        ("abridged_media_dc2", PROTO_TAG_ABRIDGED, -2),
        ("intermediate_media_dc4", PROTO_TAG_INTERMEDIATE, -4),
        ("secure_media_dc4", PROTO_TAG_SECURE, -4),
    ]

    vectors: list[dict[str, Any]] = []
    for name, proto_tag, dc_idx in valid_specs:
        handshake = build_client_handshake(rng, VALID_SECRET, proto_tag, dc_idx)
        vector = _vector(name, VALID_SECRET, handshake)
        if vector["expected"] is None:
            raise RuntimeError(f"upstream rejected generated valid vector {name}")
        vectors.append(vector)

    wrong_secret_handshake = build_client_handshake(
        rng, VALID_SECRET, PROTO_TAG_ABRIDGED, 2
    )
    vectors.append(
        _vector("invalid_wrong_secret", WRONG_SECRET, wrong_secret_handshake)
    )

    invalid_proto_handshake = build_client_handshake(
        rng, VALID_SECRET, b"\x00\x00\x00\x00", 2
    )
    vectors.append(_vector("invalid_proto_tag", VALID_SECRET, invalid_proto_handshake))

    malformed = build_client_handshake(rng, VALID_SECRET, PROTO_TAG_SECURE, 4)[:55]
    vectors.append(_vector("invalid_malformed_length", VALID_SECRET, malformed))

    for vector in vectors:
        if vector["name"].startswith("invalid_") and vector["expected"] is not None:
            raise RuntimeError(f"upstream accepted invalid vector {vector['name']}")

    return {
        "metadata": {
            "upstream": "third_party/tg-ws-proxy/proxy/tg_ws_proxy.py::_try_handshake",
            "utils": "third_party/tg-ws-proxy/proxy/utils.py",
            "seed": SEED,
        },
        "vectors": vectors,
    }


def main() -> int:
    data = generate_vectors()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT_PATH.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print(
        f"wrote {len(data['vectors'])} vectors to {OUTPUT_PATH.relative_to(REPO_ROOT)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
