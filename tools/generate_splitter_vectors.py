#!/usr/bin/env python3
"""Generate MsgSplitter parity vectors from upstream.

The vectors exercise ``proxy/bridge.py::MsgSplitter`` in the checked-out
``third_party/tg-ws-proxy`` submodule. Kotlin unit tests consume the JSON to
verify byte-for-byte parity, including chunk boundaries that affect the
stateful AES-CTR stream and buffered packet parser.
"""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_ROOT = REPO_ROOT / "third_party" / "tg-ws-proxy"
OUTPUT_PATH = REPO_ROOT / "app" / "src" / "test" / "resources" / "splitter_vectors.json"
SEED = 0x53504C4954544552

sys.path.insert(0, str(UPSTREAM_ROOT))

from proxy._aes import Cipher, algorithms, modes  # noqa: E402
from proxy.bridge import MsgSplitter  # noqa: E402
from proxy.utils import (  # noqa: E402
    PROTO_ABRIDGED_INT,
    PROTO_INTERMEDIATE_INT,
    PROTO_PADDED_INTERMEDIATE_INT,
    ZERO_64,
)


def _randbytes(rng: random.Random, length: int) -> bytes:
    return bytes(rng.randrange(0, 256) for _ in range(length))


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


def _abridged_packet(payload: bytes, *, extended: bool = False) -> bytes:
    if len(payload) % 4:
        raise ValueError("abridged payload length must be divisible by 4")
    units = len(payload) // 4
    if not extended and units < 0x7F:
        return bytes([units]) + payload
    if units >= 1 << 24:
        raise ValueError("abridged payload too large")
    return b"\x7f" + units.to_bytes(3, "little") + payload


def _intermediate_packet(payload: bytes, *, padded_flag: bool = False) -> bytes:
    length = len(payload)
    if padded_flag:
        length |= 0x80000000
    return length.to_bytes(4, "little") + payload


def _relay_init(rng: random.Random, proto_int: int) -> bytes:
    # MsgSplitter only depends on relay_init[8:40] and relay_init[40:56]. Keep
    # the rest deterministic and place the proto where relay-init normally does
    # so the vectors remain easy to inspect.
    relay = bytearray(_randbytes(rng, 64))
    relay[56:60] = proto_int.to_bytes(4, "little", signed=False)
    return bytes(relay)


def _encrypt_for_splitter(relay_init: bytes, plain: bytes) -> bytes:
    stream = Cipher(algorithms.AES(relay_init[8:40]), modes.CTR(relay_init[40:56])).encryptor()
    stream.update(ZERO_64)
    return stream.update(plain)


def _run_upstream(relay_init: bytes, proto_int: int, chunks: list[bytes]) -> tuple[list[list[bytes]], list[bytes]]:
    splitter = MsgSplitter(relay_init, proto_int)
    per_chunk = [splitter.split(chunk) for chunk in chunks]
    flush_parts = splitter.flush()
    return per_chunk, flush_parts


def _payload(label: str, length: int) -> bytes:
    base = (label.encode("utf-8") + b"|")
    return bytes((base[index % len(base)] + index) & 0xFF for index in range(length))


def _vector(
    rng: random.Random,
    name: str,
    proto_int: int,
    packets: list[bytes],
    chunk_sizes: list[int],
    truncate_ciphertext_to: int | None = None,
) -> dict[str, Any]:
    relay_init = _relay_init(rng, proto_int)
    plaintext = b"".join(packets)
    ciphertext = _encrypt_for_splitter(relay_init, plaintext)
    if truncate_ciphertext_to is not None:
        ciphertext = ciphertext[:truncate_ciphertext_to]
    chunks = _chunks(ciphertext, chunk_sizes)
    expected_per_chunk, expected_flush = _run_upstream(relay_init, proto_int, chunks)
    return {
        "name": name,
        "relay_init_hex": relay_init.hex(),
        "proto_int": proto_int,
        "chunks_hex": [chunk.hex() for chunk in chunks],
        "expected_parts_per_chunk_hex": [[part.hex() for part in parts] for parts in expected_per_chunk],
        "expected_flush_parts_hex": [part.hex() for part in expected_flush],
    }


def generate_vectors() -> dict[str, Any]:
    rng = random.Random(SEED)
    vectors: list[dict[str, Any]] = []

    abridged_a = _abridged_packet(_payload("abridged-a", 12))
    abridged_b = _abridged_packet(_payload("abridged-b", 8))
    abridged_c = _abridged_packet(_payload("abridged-c", 16))
    abridged_ext = _abridged_packet(_payload("abridged-extended", 128 * 4), extended=True)

    vectors.append(_vector(rng, "abridged_single_complete_packet", PROTO_ABRIDGED_INT, [abridged_a], [len(abridged_a)]))
    vectors.append(_vector(rng, "abridged_multiple_packets_one_chunk", PROTO_ABRIDGED_INT, [abridged_a, abridged_b, abridged_ext], [len(abridged_a) + len(abridged_b) + len(abridged_ext)]))
    vectors.append(_vector(rng, "abridged_packet_split_across_chunks", PROTO_ABRIDGED_INT, [abridged_c], [1, 3, 5]))
    vectors.append(
        _vector(
            rng,
            "abridged_flush_trailing_incomplete_packet",
            PROTO_ABRIDGED_INT,
            [abridged_ext],
            [2, 11],
            truncate_ciphertext_to=13,
        )
    )

    intermediate_a = _intermediate_packet(_payload("intermediate-a", 11))
    intermediate_b = _intermediate_packet(_payload("intermediate-b", 19))
    intermediate_c = _intermediate_packet(_payload("intermediate-c", 7))

    vectors.append(_vector(rng, "intermediate_single_complete_packet", PROTO_INTERMEDIATE_INT, [intermediate_a], [len(intermediate_a)]))
    vectors.append(_vector(rng, "intermediate_multiple_packets_one_chunk", PROTO_INTERMEDIATE_INT, [intermediate_a, intermediate_b], [len(intermediate_a) + len(intermediate_b)]))
    vectors.append(_vector(rng, "intermediate_packet_split_across_chunks", PROTO_INTERMEDIATE_INT, [intermediate_c], [2, 4, 3]))

    secure_a = _intermediate_packet(_payload("secure-a", 23), padded_flag=True)
    secure_b = _intermediate_packet(_payload("secure-b", 5))
    vectors.append(_vector(rng, "padded_intermediate_secure_packets", PROTO_PADDED_INTERMEDIATE_INT, [secure_a, secure_b], [3, 6, 99]))

    unknown_a = _intermediate_packet(_payload("unknown-a", 9))
    unknown_b = _intermediate_packet(_payload("unknown-b", 6))
    vectors.append(_vector(rng, "unknown_proto_disabled_fallback", 0x12345678, [unknown_a, unknown_b], [5, 7, 99]))

    return {
        "metadata": {
            "upstream": "third_party/tg-ws-proxy/proxy/bridge.py::MsgSplitter",
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
    print(f"wrote {len(data['vectors'])} vectors to {OUTPUT_PATH.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
