#!/usr/bin/env python3
"""Compare two TG WS Android exported diagnostics reports."""
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

MISSING = "missing"

METADATA_FIELDS = [
    "Generated", "gitCommitSha", "model", "batteryOptimizationStatus",
    "normalizedNetworkType", "Network", "configuredRouteMode", "effectiveRouteMode",
    "serviceUptimeMs", "proxyUptimeMs",
]
STATUS_FIELDS = [
    "Status", "Proxy link current", "WakeLock held", "Previous run was unexpected",
    "previousRunLikelyKilledBySigkill", "previousRunLikelyKilledByCleaner",
    "directHealthState", "effectiveRouteMode", "lastRouteUsed",
]
CLIENT_FIELDS = [
    "likelyReconnectBurst", "likelyTelegramDisabledProxy", "recentAcceptedHandshakes",
    "recentClientClosedSessions", "recentVeryShortClientClosedSessions",
    "recentShortRemoteEofSessions", "recentConnectionResetSessions", "recentDirectTimeouts",
    "recentPoolMisses", "recentPoolRefillErrors", "recentPoolStale",
    "timeToFirstSuccessfulRouteAfterIdleMs",
]
WAKE_FIELDS = [
    "wakeBurstPrewarmTriggers", "wakeBurstPrewarmAttempts", "wakeBurstPrewarmSuccesses",
    "wakeBurstPrewarmFailures", "wakeBurstPrewarmSkippedNoDirectRedirect",
    "wakeBurstPrewarmSkippedCooldown", "lastWakeBurstPrewarmDc", "lastWakeBurstPrewarmError",
]
IDLE_FIELDS = [
    "idlePoolMaintenanceRuns", "idlePoolMaintenanceSkippedNetwork",
    "idlePoolMaintenanceSkippedRoute", "idlePoolMaintenanceSkippedDirectHealth",
    "idlePoolMaintenanceSkippedActiveSessions", "idlePoolMaintenanceAttempts",
    "idlePoolMaintenanceSuccesses", "idlePoolMaintenanceFailures", "lastIdlePoolMaintenanceError",
]
POOL_FIELDS = ["poolHits", "poolMisses", "poolRefillErrors", "poolStale", "directAttempts", "directTimeouts", "wsErrors"]
CF_FIELDS = [
    "cfConnections", "cfErrors", "cf429Count", "cf503Count", "cfUnknownHostCount",
    "cfTimeoutCount", "cfConnectQueueTimeouts", "cfQueueControlledFailures",
    "cf429BackoffCount", "cfPressureLevelByDc", "cfPressureReasonByDc", "cfBestDomainByDc",
]
DIRECT_KEY_MAPS = {
    "ready": "directPoolReadyByKey",
    "hits": "directPoolHitsByKey",
    "misses": "directPoolMissesByKey",
    "refillErrors": "directPoolRefillErrorsByKey",
    "stale": "directPoolStaleByKey",
}
SECTION_KEY_ALIASES = {
    "readyByKey": "directPoolReadyByKey",
    "hitsByKey": "directPoolHitsByKey",
    "missesByKey": "directPoolMissesByKey",
    "refillErrorsByKey": "directPoolRefillErrorsByKey",
    "staleByKey": "directPoolStaleByKey",
}
STATS_ALIASES = {
    "clientExperienceLikelyReconnectBurst": "likelyReconnectBurst",
    "clientExperienceLikelyTelegramDisabledProxy": "likelyTelegramDisabledProxy",
    "clientExperienceRecentAcceptedHandshakes": "recentAcceptedHandshakes",
    "clientExperienceRecentClientClosedSessions": "recentClientClosedSessions",
    "clientExperienceRecentVeryShortClientClosedSessions": "recentVeryShortClientClosedSessions",
    "clientExperienceRecentShortRemoteEofSessions": "recentShortRemoteEofSessions",
    "clientExperienceRecentConnectionResetSessions": "recentConnectionResetSessions",
    "clientExperienceRecentDirectTimeouts": "recentDirectTimeouts",
    "clientExperienceRecentPoolMisses": "recentPoolMisses",
    "clientExperienceRecentPoolRefillErrors": "recentPoolRefillErrors",
    "clientExperienceRecentPoolStale": "recentPoolStale",
    "clientExperienceTimeToFirstSuccessfulRouteAfterIdleMs": "timeToFirstSuccessfulRouteAfterIdleMs",
}
LINE_ALIASES = {
    "Configured route mode": "configuredRouteMode",
    "Effective route mode": "effectiveRouteMode",
    "Proxy link current": "Proxy link current",
    "WakeLock held": "WakeLock held",
    "Previous run was unexpected": "Previous run was unexpected",
}

@dataclass
class Report:
    fields: dict[str, str]
    maps: dict[str, dict[str, str]]


def split_pairs(text: str) -> list[str]:
    parts, buf, depth = [], [], 0
    for ch in text:
        if ch in "{[": depth += 1
        elif ch in "}]" and depth > 0: depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(buf).strip()); buf = []
        else:
            buf.append(ch)
    if buf: parts.append("".join(buf).strip())
    return parts


def parse_key_values(text: str) -> dict[str, str]:
    out = {}
    for part in split_pairs(text):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def parse_map(value: str) -> dict[str, str]:
    value = value.strip()
    if not value or value.lower() in {"none", "unknown", MISSING}: return {}
    if value.startswith("{") and value.endswith("}"): value = value[1:-1]
    sep = ";" if ";" in value else ","
    out = {}
    for item in [p.strip() for p in value.split(sep) if p.strip()]:
        if "=" in item:
            k, v = item.split("=", 1); out[k.strip()] = v.strip()
    return out


def parse_report(text: str) -> Report:
    fields: dict[str, str] = {}
    in_direct = False
    for raw in text.splitlines():
        line = raw.rstrip()
        stripped = line.strip()
        if not stripped: continue
        if stripped == "Direct pool readiness:":
            in_direct = True; continue
        if not raw.startswith(" ") and stripped.endswith(":"):
            in_direct = False
        if stripped.startswith("Stats: "):
            stats = parse_key_values(stripped[7:])
            for k, v in stats.items():
                fields[k] = v
                if k in STATS_ALIASES: fields[STATS_ALIASES[k]] = v
            continue
        if ":" in stripped:
            k, v = stripped.split(":", 1)
            key = LINE_ALIASES.get(k.strip(), k.strip())
            if in_direct and key in SECTION_KEY_ALIASES: key = SECTION_KEY_ALIASES[key]
            fields[key] = v.strip()
    maps = {name: parse_map(fields.get(name, "")) for name in set(DIRECT_KEY_MAPS.values()) | {"cfPressureLevelByDc", "cfPressureReasonByDc", "cfBestDomainByDc"}}
    return Report(fields, maps)


def num(value: str | None) -> int | None:
    if value is None: return None
    return int(value) if re.fullmatch(r"-?\d+", value.strip()) else None


def val(report: Report, field: str) -> str:
    return report.fields.get(field, MISSING)


def delta(before: Report, after: Report, field: str) -> str:
    b, a = val(before, field), val(after, field)
    nb, na = num(b), num(a)
    suffix = f" ({na - nb:+d})" if nb is not None and na is not None else ""
    return f"{field}: {b} -> {a}{suffix}"


def section(title: str, lines: Iterable[str]) -> list[str]:
    body = list(lines)
    return [f"\n## {title}", *body]


def compare_maps(before: Report, after: Report) -> list[str]:
    keys = sorted(set().union(*(before.maps[m].keys() | after.maps[m].keys() for m in DIRECT_KEY_MAPS.values())))
    if not keys: return [f"{name}: missing" for name in DIRECT_KEY_MAPS.values()]
    lines = []
    for key in keys:
        lines.append(f"{key}:")
        for label, map_name in DIRECT_KEY_MAPS.items():
            b, a = before.maps[map_name].get(key, MISSING), after.maps[map_name].get(key, MISSING)
            nb, na = num(b), num(a)
            suffix = f" ({na - nb:+d})" if nb is not None and na is not None else ""
            lines.append(f"  {label}: {b} -> {a}{suffix}")
    return lines


def warnings(before: Report, after: Report) -> list[str]:
    out = []
    hit_delta = (num(val(after,"poolHits")) or 0) - (num(val(before,"poolHits")) or 0)
    miss_delta = (num(val(after,"poolMisses")) or 0) - (num(val(before,"poolMisses")) or 0)
    refill_delta = (num(val(after,"poolRefillErrors")) or 0) - (num(val(before,"poolRefillErrors")) or 0)
    cf429_delta = (num(val(after,"cf429Count")) or 0) - (num(val(before,"cf429Count")) or 0)
    cfq_delta = (num(val(after,"cfQueueControlledFailures")) or 0) - (num(val(before,"cfQueueControlledFailures")) or 0)
    denom = hit_delta + miss_delta
    if val(after,"likelyReconnectBurst").lower() == "true": out.append("likelyReconnectBurst is true after")
    if val(after,"likelyTelegramDisabledProxy").lower() == "true": out.append("likelyTelegramDisabledProxy is true after")
    if miss_delta > hit_delta: out.append("pool miss delta is greater than hit delta")
    if denom > 0 and miss_delta / denom >= 0.5: out.append(f"pool miss rate delta is high ({miss_delta / denom:.1%})")
    if refill_delta > 0: out.append("pool refillErrors increased")
    if cf429_delta > 0: out.append("cf429Count increased")
    if cfq_delta > 0: out.append("cfQueueControlledFailures increased")
    if val(after,"previousRunLikelyKilledBySigkill").lower() == "true": out.append("previous run likely killed by SIGKILL")
    if val(after,"previousRunLikelyKilledByCleaner").lower() == "true": out.append("previous run likely killed by cleaner")
    if val(before,"effectiveRouteMode") != val(after,"effectiveRouteMode"): out.append("effectiveRouteMode changed")
    dh = val(after,"directHealthState").lower()
    if dh not in {MISSING, "healthy"}: out.append(f"directHealthState is not healthy ({val(after,'directHealthState')})")
    return out or ["none"]


def render(before: Report, after: Report) -> str:
    lines = ["TG WS Android diagnostics delta"]
    for title, fields in [("Metadata", METADATA_FIELDS), ("High-level status", STATUS_FIELDS), ("Client experience delta", CLIENT_FIELDS), ("Wake-burst prewarm delta", WAKE_FIELDS), ("Idle pool maintenance delta", IDLE_FIELDS), ("Aggregate direct pool delta", POOL_FIELDS), ("CF health delta", CF_FIELDS)]:
        lines += section(title, (delta(before, after, f) for f in fields))
        if title == "Aggregate direct pool delta":
            hd = (num(val(after,"poolHits")) or 0) - (num(val(before,"poolHits")) or 0)
            md = (num(val(after,"poolMisses")) or 0) - (num(val(before,"poolMisses")) or 0)
            rd = (num(val(after,"poolRefillErrors")) or 0) - (num(val(before,"poolRefillErrors")) or 0)
            den = hd + md
            lines += [f"poolHitDelta: {hd}", f"poolMissDelta: {md}", f"poolRefillErrorDelta: {rd}"]
            lines.append(f"poolHitRateDelta: {hd / den:.3f}" if den > 0 else "poolHitRateDelta: missing")
            lines.append(f"poolMissRateDelta: {md / den:.3f}" if den > 0 else "poolMissRateDelta: missing")
    lines += section("Per-key direct pool diagnostics delta", compare_maps(before, after))
    lines += section("Warnings / interpretation", (f"- {w}" for w in warnings(before, after)))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    args = parser.parse_args(argv)
    print(render(parse_report(args.before.read_text(encoding="utf-8", errors="replace")), parse_report(args.after.read_text(encoding="utf-8", errors="replace"))))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
