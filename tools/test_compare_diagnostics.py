import unittest

from compare_diagnostics import parse_map, parse_report, render, warnings


BASE = """TG WS Android diagnostics
Generated: 2026-06-16 10:00:00
Build:
  gitCommitSha: abc
Device:
  model: Pixel
  batteryOptimizationStatus: unrestricted
Network:
  normalizedNetworkType: WIFI
Status: running
Proxy link current: true
WakeLock held: false
Previous run was unexpected: false
Process death diagnostics:
  previousRunLikelyKilledBySigkill: false
  previousRunLikelyKilledByCleaner: false
Client experience diagnostics:
  likelyReconnectBurst: false
Stats: poolHits=10, poolMisses=2, poolRefillErrors=0, cf429Count=0, effectiveRouteMode=direct, directHealthState=healthy, directPoolHitsByKey=DC2.normal=3;DC2.media=1, directPoolMissesByKey=DC2.normal=1
"""


class CompareDiagnosticsTest(unittest.TestCase):
    def test_parses_basic_scalar_fields(self):
        report = parse_report(BASE)
        self.assertEqual(report.fields["Generated"], "2026-06-16 10:00:00")
        self.assertEqual(report.fields["gitCommitSha"], "abc")
        self.assertEqual(report.fields["Status"], "running")

    def test_parses_long_stats_line(self):
        report = parse_report(BASE)
        self.assertEqual(report.fields["poolHits"], "10")
        self.assertEqual(report.fields["effectiveRouteMode"], "direct")
        self.assertEqual(report.fields["directHealthState"], "healthy")

    def test_parses_map_like_values(self):
        self.assertEqual(parse_map("{DC1=normal, DC2=degraded}"), {"DC1": "normal", "DC2": "degraded"})
        self.assertEqual(parse_map("DC2.normal=12;DC2.media=3"), {"DC2.normal": "12", "DC2.media": "3"})
        self.assertEqual(parse_map("none"), {})

    def test_computes_numeric_deltas(self):
        before = parse_report("Stats: poolHits=10, poolMisses=2, poolRefillErrors=0")
        after = parse_report("Stats: poolHits=15, poolMisses=4, poolRefillErrors=1")
        output = render(before, after)
        self.assertIn("poolHits: 10 -> 15 (+5)", output)
        self.assertIn("poolHitDelta: 5", output)
        self.assertIn("poolRefillErrorDelta: 1", output)

    def test_handles_missing_fields_from_older_reports(self):
        output = render(parse_report("Status: running"), parse_report("Status: running"))
        self.assertIn("directPoolReadyByKey", output)
        self.assertIn("missing", output)

    def test_warns_for_high_pool_miss_rate(self):
        before = parse_report("Stats: poolHits=10, poolMisses=10")
        after = parse_report("Stats: poolHits=11, poolMisses=20")
        self.assertTrue(any("pool miss rate" in warning for warning in warnings(before, after)))

    def test_warns_for_cf429_delta(self):
        before = parse_report("Stats: cf429Count=0")
        after = parse_report("Stats: cf429Count=2")
        self.assertIn("cf429Count increased", warnings(before, after))

    def test_produces_per_key_direct_pool_deltas(self):
        before = parse_report("""Direct pool readiness:
  readyByKey: DC2.normal=1
  hitsByKey: DC2.normal=3
  missesByKey: DC2.normal=1
""")
        after = parse_report("""Stats: directPoolReadyByKey=DC2.normal=2, directPoolHitsByKey=DC2.normal=5, directPoolMissesByKey=DC2.normal=4, directPoolRefillErrorsByKey=DC2.normal=1, directPoolStaleByKey=DC2.normal=0""")
        output = render(before, after)
        self.assertIn("DC2.normal:", output)
        self.assertIn("ready: 1 -> 2 (+1)", output)
        self.assertIn("hits: 3 -> 5 (+2)", output)
        self.assertIn("misses: 1 -> 4 (+3)", output)


if __name__ == "__main__":
    unittest.main()
