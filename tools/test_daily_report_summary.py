import unittest

from daily_report_summary import summarize_events


class DailyReportSummaryTest(unittest.TestCase):
    def test_nested_diagnostics_snapshot_counters_and_flags_are_summarized(self):
        summary = summarize_events([
            {
                "name": "diagnostics_snapshot",
                "payload": {
                    "counters": {
                        "direct_timeout": 2,
                        "pool_miss": 5,
                    },
                    "flags": {
                        "foreground_service_active": True,
                        "wake_lock_active_at_last_marker": True,
                    },
                },
            }
        ])

        self.assertEqual(summary["problem_counters"]["direct_timeout"], 2)
        self.assertEqual(summary["problem_counters"]["pool_miss"], 5)
        self.assertEqual(summary["problem_flags"]["foreground_service_active"], 1)
        self.assertEqual(summary["problem_flags"]["wake_lock_active_at_last_marker"], 1)

    def test_legacy_flat_manual_payload_still_works(self):
        summary = summarize_events([
            {
                "name": "diagnostics_snapshot",
                "payload": {
                    "direct_timeouts": 3,
                    "pool_misses": 4,
                    "likely_reconnect_burst": True,
                },
            }
        ])

        self.assertEqual(summary["problem_counters"]["direct_timeout"], 3)
        self.assertEqual(summary["problem_counters"]["pool_miss"], 4)
        self.assertEqual(summary["problem_flags"]["reconnect_burst_detected"], 1)

    def test_ignores_zero_negative_bool_counters_and_false_flags(self):
        summary = summarize_events([
            {
                "name": "diagnostics_snapshot",
                "payload": {
                    "counters": {"direct_timeout": 0, "pool_miss": -1, "cf_queue_failure": True},
                    "flags": {"foreground_service_active": False},
                },
            }
        ])

        self.assertEqual(summary, {"problem_counters": {}, "problem_flags": {}})


if __name__ == "__main__":
    unittest.main()
