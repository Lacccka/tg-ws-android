import contextlib
import io
import os
import tempfile
import unittest
from pathlib import Path

from compare_diagnostics import main, parse_map, parse_report, render, warnings


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


class CompareDiagnosticsCliTest(unittest.TestCase):
    def run_main(self, *args: str) -> tuple[int, str, str]:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            try:
                status = main(list(args))
            except SystemExit as exc:
                status = int(exc.code or 0)
        return status, stdout.getvalue(), stderr.getvalue()

    def write_report(self, directory: Path, name: str, generated: str | None = None, hits: int = 0) -> Path:
        generated_line = f"Generated: {generated}\n" if generated else ""
        path = directory / name
        path.write_text(f"TG WS Android diagnostics\n{generated_line}Stats: poolHits={hits}, poolMisses=0\n", encoding="utf-8")
        return path

    def test_existing_two_file_mode_still_works(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            before = self.write_report(directory, "before.txt", hits=1)
            after = self.write_report(directory, "after.txt", hits=3)
            status, stdout, stderr = self.run_main(str(before), str(after))
        self.assertEqual(status, 0, stderr)
        self.assertIn("poolHits: 1 -> 3 (+2)", stdout)
        self.assertNotIn("Selected diagnostics:", stdout)

    def test_single_directory_argument_selects_latest_two_diagnostics_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            self.write_report(directory, "tg-ws-android-diagnostics-20260616-071100.txt", "2026-06-16 07:11:00", 1)
            before = self.write_report(directory, "tg-ws-android-diagnostics-20260616-074919.txt", "2026-06-16 07:49:19", 2)
            after = self.write_report(directory, "tg-ws-android-diagnostics-20260616-080000.txt", "2026-06-16 08:00:00", 5)
            status, stdout, stderr = self.run_main(str(directory))
        self.assertEqual(status, 0, stderr)
        self.assertIn(f"before: {before}", stdout)
        self.assertIn(f"after:  {after}", stdout)
        self.assertIn("poolHits: 2 -> 5 (+3)", stdout)

    def test_dir_flag_selects_latest_two_diagnostics_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            before = self.write_report(directory, "tg-ws-android-diagnostics-20260616-010000.txt", "2026-06-16 01:00:00", 4)
            after = self.write_report(directory, "tg-ws-android-diagnostics-20260616-020000.txt", "2026-06-16 02:00:00", 6)
            status, stdout, stderr = self.run_main("--dir", str(directory))
        self.assertEqual(status, 0, stderr)
        self.assertIn(f"before: {before}", stdout)
        self.assertIn(f"after:  {after}", stdout)

    def test_all_compares_adjacent_pairs(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            first = self.write_report(directory, "tg-ws-android-diagnostics-20260616-010000.txt", "2026-06-16 01:00:00", 1)
            second = self.write_report(directory, "tg-ws-android-diagnostics-20260616-020000.txt", "2026-06-16 02:00:00", 2)
            third = self.write_report(directory, "tg-ws-android-diagnostics-20260616-030000.txt", "2026-06-16 03:00:00", 4)
            status, stdout, stderr = self.run_main("--dir", str(directory), "--all")
        self.assertEqual(status, 0, stderr)
        self.assertIn(f"before: {first}", stdout)
        self.assertIn(f"after:  {second}", stdout)
        self.assertIn(f"before: {second}", stdout)
        self.assertIn(f"after:  {third}", stdout)
        self.assertEqual(stdout.count("Selected diagnostics:"), 2)
        self.assertIn("========================================================================", stdout)

    def test_generated_timestamp_sorting_is_preferred_over_filename_sorting(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            after = self.write_report(directory, "tg-ws-android-diagnostics-20260616-010000.txt", "2026-06-16 03:00:00", 7)
            before = self.write_report(directory, "tg-ws-android-diagnostics-20260616-030000.txt", "2026-06-16 02:00:00", 5)
            status, stdout, stderr = self.run_main(str(directory))
        self.assertEqual(status, 0, stderr)
        self.assertIn(f"before: {before}", stdout)
        self.assertIn(f"after:  {after}", stdout)

    def test_filename_timestamp_sorting_works_when_generated_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            before = self.write_report(directory, "tg-ws-android-diagnostics-20260616-010000.txt", hits=1)
            after = self.write_report(directory, "tg-ws-android-diagnostics-20260616-020000.txt", hits=2)
            status, stdout, stderr = self.run_main(str(directory))
        self.assertEqual(status, 0, stderr)
        self.assertIn(f"before: {before}", stdout)
        self.assertIn(f"after:  {after}", stdout)

    def test_fallback_to_mtime_works_without_generated_or_filename_timestamp(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            old = self.write_report(directory, "old.txt", hits=1)
            new = self.write_report(directory, "new.txt", hits=2)
            os.utime(old, (100, 100))
            os.utime(new, (200, 200))
            status, stdout, stderr = self.run_main("--dir", str(directory), "--pattern", "*.txt")
        self.assertEqual(status, 0, stderr)
        self.assertIn(f"before: {old}", stdout)
        self.assertIn(f"after:  {new}", stdout)

    def test_fewer_than_two_files_returns_non_zero_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            self.write_report(directory, "tg-ws-android-diagnostics-20260616-010000.txt")
            status, stdout, stderr = self.run_main(str(directory))
        self.assertNotEqual(status, 0)
        self.assertIn("fewer than two", stderr)

    def test_missing_path_returns_non_zero_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp) / "missing.txt"
            status, stdout, stderr = self.run_main(str(missing), str(missing))
        self.assertNotEqual(status, 0)
        self.assertIn("path does not exist", stderr)

    def test_custom_pattern_works(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            before = self.write_report(directory, "custom-a.txt", "2026-06-16 01:00:00", 2)
            after = self.write_report(directory, "custom-b.txt", "2026-06-16 02:00:00", 3)
            self.write_report(directory, "tg-ws-android-diagnostics-20260616-030000.txt", "2026-06-16 03:00:00", 9)
            status, stdout, stderr = self.run_main("--dir", str(directory), "--pattern", "custom-*.txt")
        self.assertEqual(status, 0, stderr)
        self.assertIn(f"before: {before}", stdout)
        self.assertIn(f"after:  {after}", stdout)
        self.assertIn("poolHits: 2 -> 3 (+1)", stdout)


if __name__ == "__main__":
    unittest.main()
