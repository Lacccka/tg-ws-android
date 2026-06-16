#!/usr/bin/env python3
"""Build a compact daily-report summary from telemetry events.

The parser accepts both legacy flat ``diagnostics_snapshot.payload`` fields and
new nested ``payload.counters`` / ``payload.flags`` objects.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Iterable, Mapping
from pathlib import Path
from typing import Any

COUNTER_ALIASES = {
    "direct_timeouts": "direct_timeout",
    "pool_misses": "pool_miss",
    "cf_queue_failures": "cf_queue_failure",
}
FLAG_ALIASES = {
    "likely_reconnect_burst": "reconnect_burst_detected",
}
LEGACY_COUNTER_KEYS = set(COUNTER_ALIASES) | {
    "direct_timeout",
    "pool_miss",
    "cf_queue_failure",
    "cf429_count",
    "cf503_count",
    "cf_timeout_count",
    "cf_unknown_host_count",
}
LEGACY_FLAG_KEYS = set(FLAG_ALIASES) | {
    "foreground_service_active",
    "wake_lock_active_at_last_marker",
    "reconnect_burst_detected",
    "likely_telegram_disabled_proxy",
}


def _as_positive_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value > 0 else None
    return None


def _add_counter(summary: dict[str, dict[str, int]], key: str, value: Any) -> None:
    amount = _as_positive_int(value)
    if amount is not None:
        counters = summary["problem_counters"]
        counters[key] = counters.get(key, 0) + amount


def _add_flag(summary: dict[str, dict[str, int]], key: str, value: Any) -> None:
    if value is True:
        flags = summary["problem_flags"]
        flags[key] = flags.get(key, 0) + 1


def summarize_event(summary: dict[str, dict[str, int]], event: Mapping[str, Any]) -> None:
    """Merge one telemetry event into ``summary``.

    Only ``diagnostics_snapshot`` events are inspected. Legacy flat keys remain
    supported, while nested counters/flags are now read from the Android payload.
    """
    if event.get("name") != "diagnostics_snapshot":
        return
    payload = event.get("payload")
    if not isinstance(payload, Mapping):
        return

    counters = payload.get("counters")
    if isinstance(counters, Mapping):
        for key, value in counters.items():
            _add_counter(summary, str(key), value)

    flags = payload.get("flags")
    if isinstance(flags, Mapping):
        for key, value in flags.items():
            _add_flag(summary, str(key), value)

    for legacy_key in LEGACY_COUNTER_KEYS:
        if legacy_key in payload:
            _add_counter(summary, COUNTER_ALIASES.get(legacy_key, legacy_key), payload[legacy_key])
    for legacy_key in LEGACY_FLAG_KEYS:
        if legacy_key in payload:
            _add_flag(summary, FLAG_ALIASES.get(legacy_key, legacy_key), payload[legacy_key])


def summarize_events(events: Iterable[Mapping[str, Any]]) -> dict[str, dict[str, int]]:
    summary: dict[str, dict[str, int]] = {"problem_counters": {}, "problem_flags": {}}
    for event in events:
        summarize_event(summary, event)
    return summary


def _load_events(text: str) -> list[Mapping[str, Any]]:
    data = json.loads(text)
    if isinstance(data, Mapping):
        if isinstance(data.get("events"), list):
            return [event for event in data["events"] if isinstance(event, Mapping)]
        return [data]
    if isinstance(data, list):
        return [event for event in data if isinstance(event, Mapping)]
    raise ValueError("input must be an event object, an events array, or an object with events")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", nargs="?", type=Path, help="JSON file to parse; stdin is used when omitted")
    args = parser.parse_args(argv)
    text = args.path.read_text(encoding="utf-8") if args.path else sys.stdin.read()
    print(json.dumps(summarize_events(_load_events(text)), ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
