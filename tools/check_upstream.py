#!/usr/bin/env python3
"""Verify critical tg-ws-proxy upstream files against recorded SHA-256 hashes."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = REPO_ROOT / "tools" / "upstream_manifest.json"


@dataclass(frozen=True)
class ManifestEntry:
    path: str
    sha256: str


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_manifest(path: Path) -> tuple[Path, list[ManifestEntry]]:
    with path.open("r", encoding="utf-8") as handle:
        raw: dict[str, Any] = json.load(handle)

    upstream_root = REPO_ROOT / raw.get("upstream_root", "third_party/tg-ws-proxy")
    entries = [
        ManifestEntry(path=item["path"], sha256=item["sha256"])
        for item in raw.get("files", [])
    ]
    return upstream_root, entries


def check_manifest(manifest_path: Path) -> int:
    upstream_root, entries = _load_manifest(manifest_path)
    if not entries:
        print(f"error: manifest has no files: {manifest_path}", file=sys.stderr)
        return 2

    failures = 0
    for entry in entries:
        upstream_file = upstream_root / entry.path
        if not upstream_file.is_file():
            print(f"MISSING {entry.path}")
            failures += 1
            continue

        actual = _sha256(upstream_file)
        if actual == entry.sha256:
            print(f"OK      {entry.path} {actual}")
        else:
            print(f"CHANGED {entry.path}")
            print(f"        expected {entry.sha256}")
            print(f"        actual   {actual}")
            failures += 1

    if failures:
        print(f"\n{failures} upstream file(s) differ from {manifest_path}.")
        return 1

    print(f"\nAll {len(entries)} upstream file hash(es) match {manifest_path}.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=DEFAULT_MANIFEST,
        help="Path to the upstream manifest JSON file.",
    )
    args = parser.parse_args()
    return check_manifest(args.manifest)


if __name__ == "__main__":
    raise SystemExit(main())
