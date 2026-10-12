import datetime as dt
import unittest

import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify-agent-cadence.py")
SPEC = importlib.util.spec_from_file_location("verify_agent_cadence", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
CADENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CADENCE)


START = "2026-10-12T04:56:03+00:00"
END = "2026-10-13T04:56:03+00:00"
CHECKED = "2026-10-13T05:00:00+00:00"


def report_for(*agents):
    return {
        "window_start": START,
        "window_end": END,
        "checked_at": CHECKED,
        "expected_agent_count": len(agents),
        "active_agent_count": len(agents),
        "agents": list(agents),
    }


def agent(**overrides):
    result = {
        "id": "agent-1",
        "online": True,
        "trusted_batch_count": 2880,
        "untrusted_batch_count": 0,
        "first_sampled_at": START,
        "last_sampled_at": END,
        "first_received_at": START,
        "last_received_at": END,
        "start_edge_gap_seconds": 30.0,
        "end_edge_gap_seconds": 30.0,
        "interval_count": 2879,
        "sampled_gap_min_seconds": 29.9,
        "sampled_gap_p50_seconds": 30.0,
        "sampled_gap_p95_seconds": 30.1,
        "sampled_gap_max_seconds": 30.2,
        "sampled_gap_out_of_range_count": 0,
        "received_gap_min_seconds": 29.9,
        "received_gap_p50_seconds": 30.0,
        "received_gap_p95_seconds": 30.1,
        "received_gap_max_seconds": 30.2,
        "received_gap_out_of_range_count": 0,
    }
    result.update(overrides)
    return result


class CadenceReportTests(unittest.TestCase):
    def test_full_window_and_cadence_pass(self):
        self.assertEqual(
            CADENCE.validate_report(report_for(agent()), expected_agents=1, duration_seconds=86_400),
            [],
        )

    def test_incomplete_window_is_not_accepted(self):
        report = report_for(agent())
        report["checked_at"] = "2026-10-13T04:55:59+00:00"
        self.assertTrue(any("window is incomplete" in problem for problem in CADENCE.validate_report(
            report, expected_agents=1, duration_seconds=86_400,
        )))

    def test_missing_agent_or_untrusted_batch_fails(self):
        report = report_for(agent(online=False, trusted_batch_count=0, untrusted_batch_count=1))
        problems = CADENCE.validate_report(report, expected_agents=2, duration_seconds=86_400)
        self.assertTrue(any("cohort mismatch" in problem for problem in problems))
        self.assertTrue(any("not currently online" in problem for problem in problems))
        self.assertTrue(any("no trusted batches" in problem for problem in problems))
        self.assertTrue(any("untrusted batches" in problem for problem in problems))

    def test_out_of_band_cadence_and_long_window_edge_fail(self):
        report = report_for(agent(
            sampled_gap_out_of_range_count=1,
            received_gap_out_of_range_count=2,
            end_edge_gap_seconds=61.0,
        ))
        problems = CADENCE.validate_report(report, expected_agents=1, duration_seconds=86_400)
        self.assertTrue(any("sampled intervals outside 25–35" in problem for problem in problems))
        self.assertTrue(any("received intervals outside 25–35" in problem for problem in problems))
        self.assertTrue(any("end_edge_gap_seconds" in problem for problem in problems))

    def test_timezone_is_required(self):
        with self.assertRaises(ValueError):
            CADENCE.parse_timestamp("2026-10-12T04:56:03")


if __name__ == "__main__":
    unittest.main()
