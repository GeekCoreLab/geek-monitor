import importlib.util
from pathlib import Path
import unittest


MONITOR_PATH = Path(__file__).with_name("monitor-agent-soak.py")
SPEC = importlib.util.spec_from_file_location("monitor_agent_soak", MONITOR_PATH)
assert SPEC is not None and SPEC.loader is not None
MONITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITOR)


class SuspendGapTests(unittest.TestCase):
    def test_active_time_without_suspend_has_no_gap(self) -> None:
        self.assertEqual(MONITOR.suspend_gap_seconds(100.0, 400.0, 500.0, 800.0), 0.0)

    def test_boottime_excess_records_system_suspend(self) -> None:
        self.assertEqual(MONITOR.suspend_gap_seconds(100.0, 400.0, 500.0, 1813.0), 1013.0)


class RunnerDetectionTests(unittest.TestCase):
    def test_single_checkpoint_fails_when_runner_is_missing(self) -> None:
        self.assertEqual(
            MONITOR.missing_runner_problem(runner_count=0, runner_seen=False, once=True),
            "Agent soak runner process was not detected",
        )

    def test_long_monitor_fails_before_runner_is_first_seen(self) -> None:
        self.assertEqual(
            MONITOR.missing_runner_problem(runner_count=0, runner_seen=False, once=False),
            "Agent soak runner process was not detected",
        )

    def test_monitor_checks_duration_after_a_seen_runner_exits(self) -> None:
        self.assertIsNone(MONITOR.missing_runner_problem(runner_count=0, runner_seen=True, once=False))

    def test_attached_cohort_mode_is_explicitly_allowed_without_runner(self) -> None:
        self.assertIsNone(MONITOR.missing_runner_problem(
            runner_count=0, runner_seen=False, once=False, observe_existing_agent_cohort=True,
        ))


class AgentIdentityTests(unittest.TestCase):
    def test_fingerprint_is_order_independent_and_detects_process_replacement(self) -> None:
        first = {12: {"start_ticks": 100}, 21: {"start_ticks": 200}}
        reordered = {21: {"start_ticks": 200}, 12: {"start_ticks": 100}}
        replacement = {12: {"start_ticks": 101}, 21: {"start_ticks": 200}}
        self.assertEqual(MONITOR.agent_identity_fingerprint(first), MONITOR.agent_identity_fingerprint(reordered))
        self.assertNotEqual(MONITOR.agent_identity_fingerprint(first), MONITOR.agent_identity_fingerprint(replacement))


if __name__ == "__main__":
    unittest.main()
