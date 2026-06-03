#!/usr/bin/env python3
"""Generate RawWebSocket offline parity vectors from upstream.

The vectors exercise ``proxy/raw_websocket.py`` in the checked-out
``third_party/tg-ws-proxy`` submodule without performing network I/O. Frame
bytes are produced by upstream ``RawWebSocket._build_frame`` with deterministic
``os.urandom`` patches for client masking. HTTP request and response examples
mirror the offline portions of ``RawWebSocket.connect`` and ``WsHandshakeError``.
"""

from __future__ import annotations

import base64
import importlib
import json
import random
import sys
from pathlib import Path
from typing import Any
from unittest.mock import patch

REPO_ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_ROOT = REPO_ROOT / "third_party" / "tg-ws-proxy"
OUTPUT_PATH = (
    REPO_ROOT / "app" / "src" / "test" / "resources" / "websocket_vectors.json"
)
SEED = 0x524157574542534F434B4554

sys.path.insert(0, str(UPSTREAM_ROOT))

raw_websocket = importlib.import_module("proxy.raw_websocket")  # noqa: E402
RawWebSocket = raw_websocket.RawWebSocket  # noqa: E402
WsHandshakeError = raw_websocket.WsHandshakeError  # noqa: E402


class DeterministicUrandom:
    def __init__(self, seed: int):
        self.rng = random.Random(seed)
        self.calls: list[dict[str, Any]] = []

    def __call__(self, length: int) -> bytes:
        data = bytes(self.rng.randrange(0, 256) for _ in range(length))
        self.calls.append({"length": length, "hex": data.hex()})
        return data


def _payload(label: str, length: int) -> bytes:
    base = label.encode("utf-8") + b"|"
    return bytes((base[index % len(base)] + index) & 0xFF for index in range(length))


def _parse_response_like_upstream(raw_response: bytes) -> dict[str, Any]:
    response_lines: list[str] = []
    for line in raw_response.splitlines(keepends=True):
        if line in (b"\r\n", b"\n", b""):
            break
        response_lines.append(line.decode("utf-8", errors="replace").strip())

    if not response_lines:
        err = WsHandshakeError(0, "empty response")
        return _response_error_dict(err)

    first_line = response_lines[0]
    parts = first_line.split(" ", 2)
    try:
        status_code = int(parts[1]) if len(parts) >= 2 else 0
    except ValueError:
        status_code = 0

    headers: dict[str, str] = {}
    for hl in response_lines[1:]:
        if ":" in hl:
            k, v = hl.split(":", 1)
            headers[k.strip().lower()] = v.strip()

    if status_code == 101:
        return {
            "success": True,
            "status_code": status_code,
            "status_line": first_line,
            "headers": headers,
            "location": headers.get("location"),
            "is_redirect": False,
            "error_message": None,
        }

    err = WsHandshakeError(
        status_code, first_line, headers, location=headers.get("location")
    )
    return _response_error_dict(err)


def _response_error_dict(err: Any) -> dict[str, Any]:
    return {
        "success": False,
        "status_code": err.status_code,
        "status_line": err.status_line,
        "headers": err.headers,
        "location": err.location,
        "is_redirect": err.is_redirect,
        "error_message": str(err),
    }


def _build_request_like_upstream(
    path: str, domain: str, key_bytes: bytes
) -> tuple[str, str]:
    ws_key = base64.b64encode(key_bytes).decode()
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {domain}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {ws_key}\r\n"
        f"Sec-WebSocket-Version: 13\r\n"
        f"Sec-WebSocket-Protocol: binary\r\n"
        f"\r\n"
    )
    return ws_key, req


def _frame_vector(
    name: str, opcode: int, payload: bytes, mask: bool, seed: int
) -> dict[str, Any]:
    deterministic = DeterministicUrandom(seed)
    with patch.object(raw_websocket.os, "urandom", deterministic):
        frame = RawWebSocket._build_frame(opcode, payload, mask=mask)
    mask_key_hex = deterministic.calls[0]["hex"] if mask else None
    read_opcode, read_payload = _read_frame_bytes_like_upstream(frame)
    return {
        "name": name,
        "opcode": opcode,
        "payload_hex": payload.hex(),
        "mask": mask,
        "mask_key_hex": mask_key_hex,
        "expected_frame_hex": frame.hex(),
        "expected_read": {
            "opcode": read_opcode,
            "payload_hex": read_payload.hex(),
            "masked_input": mask,
        },
    }


def _read_frame_bytes_like_upstream(frame: bytes) -> tuple[int, bytes]:
    # Synchronous copy of RawWebSocket._read_frame's byte-level behavior.
    offset = 0
    hdr = frame[offset : offset + 2]
    offset += 2
    opcode = hdr[0] & 0x0F
    length = hdr[1] & 0x7F
    if length == 126:
        length = int.from_bytes(frame[offset : offset + 2], "big")
        offset += 2
    elif length == 127:
        length = int.from_bytes(frame[offset : offset + 8], "big")
        offset += 8
    if hdr[1] & 0x80:
        mask_key = frame[offset : offset + 4]
        offset += 4
        payload = frame[offset : offset + length]
        return opcode, raw_websocket._xor_mask(payload, mask_key)
    payload = frame[offset : offset + length]
    return opcode, payload


def generate_vectors() -> dict[str, Any]:
    frame_specs = [
        (
            "binary_len_lt_126_unmasked",
            RawWebSocket.OP_BINARY,
            _payload("lt126", 12),
            False,
        ),
        (
            "binary_len_eq_126_unmasked",
            RawWebSocket.OP_BINARY,
            _payload("eq126", 126),
            False,
        ),
        (
            "binary_len_between_126_and_65535_unmasked",
            RawWebSocket.OP_BINARY,
            _payload("mid", 4096),
            False,
        ),
        (
            "binary_len_ge_65536_unmasked",
            RawWebSocket.OP_BINARY,
            _payload("large", 66000),
            False,
        ),
        ("close_frame_unmasked", RawWebSocket.OP_CLOSE, b"\x03\xe8bye", False),
        ("ping_frame_unmasked", RawWebSocket.OP_PING, b"ping-payload", False),
        ("pong_frame_unmasked", RawWebSocket.OP_PONG, b"pong-payload", False),
        (
            "binary_len_lt_126_masked",
            RawWebSocket.OP_BINARY,
            _payload("masked-small", 17),
            True,
        ),
        (
            "binary_len_eq_126_masked",
            RawWebSocket.OP_BINARY,
            _payload("masked-126", 126),
            True,
        ),
        (
            "binary_len_between_126_and_65535_masked",
            RawWebSocket.OP_BINARY,
            _payload("masked-mid", 2048),
            True,
        ),
        (
            "binary_len_ge_65536_masked",
            RawWebSocket.OP_BINARY,
            _payload("masked-large", 66000),
            True,
        ),
        ("close_frame_masked", RawWebSocket.OP_CLOSE, b"\x03\xe8", True),
        ("ping_frame_masked", RawWebSocket.OP_PING, b"ping", True),
        ("pong_frame_masked", RawWebSocket.OP_PONG, b"pong", True),
    ]
    frames = [
        _frame_vector(name, opcode, payload, mask, SEED + index)
        for index, (name, opcode, payload, mask) in enumerate(frame_specs)
    ]

    request_specs = [
        ("default_apiws", "/apiws", "example.com", bytes(range(16))),
        ("custom_path", "/custom/ws?dc=2", "ws.example.org", bytes(range(0x10, 0x20))),
    ]
    requests = []
    for name, path, domain, key_bytes in request_specs:
        sec_key, request_text = _build_request_like_upstream(path, domain, key_bytes)
        requests.append(
            {
                "name": name,
                "path": path,
                "domain": domain,
                "sec_websocket_key": sec_key,
                "expected_request_text": request_text,
            }
        )

    response_specs = [
        (
            "switching_protocols",
            b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Protocol: binary\r\n\r\n",
        ),
        (
            "redirect_301",
            b"HTTP/1.1 301 Moved Permanently\r\nLocation: https://new.example/apiws\r\nX-Test: yes\r\n\r\n",
        ),
        ("redirect_302", b"HTTP/1.1 302 Found\r\nLocation: /apiws2\r\n\r\n"),
        ("empty_response", b""),
        ("malformed_status", b"HTTP/1.1 NOPE Broken\r\nHeader: value\r\n\r\n"),
    ]
    responses = []
    for name, raw_response in response_specs:
        responses.append(
            {
                "name": name,
                "raw_response_hex": raw_response.hex(),
                "expected": _parse_response_like_upstream(raw_response),
            }
        )

    return {
        "metadata": {
            "upstream_submodule": "third_party/tg-ws-proxy",
            "upstream_file": "proxy/raw_websocket.py",
            "upstream_functions": [
                "RawWebSocket._build_frame",
                "RawWebSocket._read_frame",
                "RawWebSocket.connect",
                "WsHandshakeError",
                "_xor_mask",
            ],
            "seed": SEED,
        },
        "frames": frames,
        "requests": requests,
        "responses": responses,
    }


def main() -> None:
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    data = generate_vectors()
    OUTPUT_PATH.write_text(
        json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(f"wrote {OUTPUT_PATH.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
