#!/usr/bin/env python3
"""Generate relay-init and crypto-context parity vectors from upstream.

The vectors exercise ``proxy/tg_ws_proxy.py::_generate_relay_init`` and
``proxy/tg_ws_proxy.py::_build_crypto_ctx`` in the checked-out
``third_party/tg-ws-proxy`` submodule. Kotlin unit tests consume the JSON to
verify byte-for-byte parity without deriving expected values from Kotlin code.
"""

from __future__ import annotations

import json
import random
import sys
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable, Iterator

REPO_ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_ROOT = REPO_ROOT / "third_party" / "tg-ws-proxy"
OUTPUT_PATH = REPO_ROOT / "app" / "src" / "test" / "resources" / "crypto_vectors.json"
SEED = 0x43525950544F3257
SECRET = bytes.fromhex("0123456789abcdeffedcba9876543210")
CHUNK_SIZES = [1, 2, 5, 13, 8, 21, 34]

sys.path.insert(0, str(UPSTREAM_ROOT))

from proxy._aes import Cipher, algorithms, modes  # noqa: E402
from proxy.tg_ws_proxy import _build_crypto_ctx, _generate_relay_init  # noqa: E402
import proxy.tg_ws_proxy as tg_ws_proxy  # noqa: E402
from proxy.utils import (  # noqa: E402
    IV_LEN,
    KEY_LEN,
    PREKEY_LEN,
    PROTO_TAG_ABRIDGED,
    PROTO_TAG_INTERMEDIATE,
    PROTO_TAG_SECURE,
    SKIP_LEN,
    ZERO_64,
)


def _randbytes(rng: random.Random, length: int) -> bytes:
    return bytes(rng.randrange(0, 256) for _ in range(length))


class RecordingUrandom:
    def __init__(self, rng: random.Random) -> None:
        self._rng = rng
        self.calls: list[bytes] = []

    def __call__(self, length: int) -> bytes:
        data = _randbytes(self._rng, length)
        self.calls.append(data)
        return data


@contextmanager
def _patched_urandom(fake: Callable[[int], bytes]) -> Iterator[None]:
    original = tg_ws_proxy.os.urandom
    tg_ws_proxy.os.urandom = fake
    try:
        yield
    finally:
        tg_ws_proxy.os.urandom = original


def _cipher(key: bytes, iv: bytes):
    return Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()


def _chunks(data: bytes, sizes: list[int]) -> list[bytes]:
    chunks: list[bytes] = []
    offset = 0
    for size in sizes:
        if offset >= len(data):
            break
        chunks.append(data[offset : offset + size])
        offset += size
    if offset < len(data):
        chunks.append(data[offset:])
    return chunks


def _update_in_chunks(stream, data: bytes, sizes: list[int]) -> bytes:
    out = bytearray()
    for chunk in _chunks(data, sizes):
        out.extend(stream.update(chunk))
    return bytes(out)


def _client_payload(name: str) -> bytes:
    return (
        f"client->telegram parity payload for {name}; ".encode("utf-8")
        + bytes(range(64))
    )[:96]


def _telegram_payload(name: str) -> bytes:
    return (
        f"telegram->client parity payload for {name}; ".encode("utf-8")
        + bytes(range(255, 159, -1))
    )[:96]


def _client_ciphertext(
    client_dec_prekey_iv: bytes, secret: bytes, plain: bytes
) -> bytes:
    prekey = client_dec_prekey_iv[:PREKEY_LEN]
    iv = client_dec_prekey_iv[PREKEY_LEN:]
    import hashlib

    stream = _cipher(hashlib.sha256(prekey + secret).digest(), iv)
    stream.update(ZERO_64)
    return stream.update(plain)


def _telegram_ciphertext(relay_init: bytes, plain: bytes) -> bytes:
    relay_dec_prekey_iv = relay_init[SKIP_LEN : SKIP_LEN + KEY_LEN + IV_LEN][::-1]
    stream = _cipher(relay_dec_prekey_iv[:KEY_LEN], relay_dec_prekey_iv[KEY_LEN:])
    return stream.update(plain)


def _vector(
    rng: random.Random, name: str, proto_tag: bytes, dc_idx: int
) -> dict[str, Any]:
    client_dec_prekey_iv = _randbytes(rng, PREKEY_LEN + IV_LEN)

    recorder = RecordingUrandom(rng)
    with _patched_urandom(recorder):
        relay_init = _generate_relay_init(proto_tag, dc_idx)

    if len(recorder.calls) < 2:
        raise RuntimeError(
            f"relay init for {name} did not request expected random bytes"
        )

    ctx = _build_crypto_ctx(client_dec_prekey_iv, SECRET, relay_init)

    plain_from_client = _client_payload(name)
    sample_client_ciphertext = _client_ciphertext(
        client_dec_prekey_iv, SECRET, plain_from_client
    )
    expected_plain_from_client = _update_in_chunks(
        ctx.clt_dec, sample_client_ciphertext, CHUNK_SIZES
    )
    expected_telegram_ciphertext = _update_in_chunks(
        ctx.tg_enc, expected_plain_from_client, CHUNK_SIZES
    )

    plain_from_telegram = _telegram_payload(name)
    sample_telegram_ciphertext = _telegram_ciphertext(relay_init, plain_from_telegram)
    expected_plain_from_telegram = _update_in_chunks(
        ctx.tg_dec, sample_telegram_ciphertext, CHUNK_SIZES
    )
    expected_client_ciphertext = _update_in_chunks(
        ctx.clt_enc, expected_plain_from_telegram, CHUNK_SIZES
    )

    if expected_plain_from_client != plain_from_client:
        raise RuntimeError(f"client decrypt mismatch while generating {name}")
    if expected_plain_from_telegram != plain_from_telegram:
        raise RuntimeError(f"telegram decrypt mismatch while generating {name}")

    return {
        "name": name,
        "secret_hex": SECRET.hex(),
        "proto_tag_hex": proto_tag.hex(),
        "dc_idx": dc_idx,
        "client_dec_prekey_iv_hex": client_dec_prekey_iv.hex(),
        "relay_init_hex": relay_init.hex(),
        "sample_client_ciphertext_hex": sample_client_ciphertext.hex(),
        "expected_plain_from_client_hex": expected_plain_from_client.hex(),
        "expected_telegram_ciphertext_hex": expected_telegram_ciphertext.hex(),
        "sample_telegram_ciphertext_hex": sample_telegram_ciphertext.hex(),
        "expected_plain_from_telegram_hex": expected_plain_from_telegram.hex(),
        "expected_client_ciphertext_hex": expected_client_ciphertext.hex(),
        "chunk_sizes": CHUNK_SIZES,
        "relay_random_calls_hex": [call.hex() for call in recorder.calls],
    }


def generate_vectors() -> dict[str, Any]:
    rng = random.Random(SEED)
    specs = [
        ("abridged_dc2", PROTO_TAG_ABRIDGED, 2),
        ("intermediate_dc2", PROTO_TAG_INTERMEDIATE, 2),
        ("secure_dc2", PROTO_TAG_SECURE, 2),
        ("abridged_dc4", PROTO_TAG_ABRIDGED, 4),
        ("intermediate_dc4", PROTO_TAG_INTERMEDIATE, 4),
        ("secure_dc4", PROTO_TAG_SECURE, 4),
        ("abridged_media_dc2", PROTO_TAG_ABRIDGED, -2),
        ("intermediate_media_dc4", PROTO_TAG_INTERMEDIATE, -4),
        ("secure_media_dc4", PROTO_TAG_SECURE, -4),
    ]
    return {
        "metadata": {
            "upstream_relay_init": "third_party/tg-ws-proxy/proxy/tg_ws_proxy.py::_generate_relay_init",
            "upstream_crypto_ctx": "third_party/tg-ws-proxy/proxy/tg_ws_proxy.py::_build_crypto_ctx",
            "upstream_bridge_ctx": "third_party/tg-ws-proxy/proxy/bridge.py::CryptoCtx",
            "utils": "third_party/tg-ws-proxy/proxy/utils.py",
            "seed": SEED,
        },
        "vectors": [
            _vector(rng, name, proto_tag, dc_idx) for name, proto_tag, dc_idx in specs
        ],
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
