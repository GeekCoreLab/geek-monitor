import importlib.util
from pathlib import Path
import tempfile
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


class CompletionBoundaryTests(unittest.TestCase):
    def test_monitor_deadline_accepts_a_live_runner_after_required_duration(self) -> None:
        self.assertIsNone(MONITOR.runner_deadline_problem(1, 604801.0, 604800))

    def test_monitor_deadline_rejects_a_live_runner_short_of_required_duration(self) -> None:
        self.assertIn("required duration", MONITOR.runner_deadline_problem(1, 604799.0, 604800))

    def test_runner_exit_before_monitor_deadline_is_rejected(self) -> None:
        self.assertIn("exited before monitor", MONITOR.runner_exit_before_deadline_problem(604799.0, 604800))

    def test_runner_exit_after_full_monitor_window_is_accepted(self) -> None:
        self.assertIsNone(MONITOR.runner_exit_before_deadline_problem(604800.0, 604800))

    def test_terminal_snapshot_uses_final_database_without_live_process_checks(self) -> None:
        self.assertEqual(MONITOR.cohort_runtime_problems(0, 0, 100, terminal_runner_snapshot=True), [])

    def test_regular_snapshot_still_requires_the_full_live_cohort(self) -> None:
        self.assertEqual(
            MONITOR.cohort_runtime_problems(99, 98, 100),
            ["Agent processes=99/100", "online Agents=98/100"],
        )


class ObservationContinuityTests(unittest.TestCase):
    def test_new_or_single_checkpoint_log_is_not_a_restarted_soak(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            log_file = Path(temp_dir) / "observer.jsonl"
            self.assertIsNone(MONITOR.incomplete_observation_problem(log_file))
            log_file.write_text('{"observation_kind":"checkpoint"}\n', encoding="utf-8")
            self.assertIsNone(MONITOR.incomplete_observation_problem(log_file))

    def test_incomplete_soak_is_reported_even_after_a_one_off_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            log_file = Path(temp_dir) / "observer.jsonl"
            log_file.write_text(
                '{"observation_kind":"soak","elapsed_seconds":300}\n'
                '{"observation_kind":"checkpoint"}\n',
                encoding="utf-8",
            )
            self.assertEqual(
                MONITOR.incomplete_observation_problem(log_file),
                "Previous Agent soak observation ended before completion; this run cannot count as continuous",
            )

    def test_completed_soak_can_be_followed_by_a_new_run(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            log_file = Path(temp_dir) / "observer.jsonl"
            log_file.write_text(
                '{"observation_kind":"soak","observation_completed":true}\n',
                encoding="utf-8",
            )
            self.assertIsNone(MONITOR.incomplete_observation_problem(log_file))

    def test_once_mode_does_not_report_an_interrupted_soak(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            log_file = Path(temp_dir) / "observer.jsonl"
            log_file.write_text('{"observation_kind":"soak"}\n', encoding="utf-8")
            self.assertIsNone(MONITOR.incomplete_observation_problem(log_file, once=True))

    def test_checkpoint_gap_allows_scheduling_jitter_but_reports_long_pause(self) -> None:
        gap, problem = MONITOR.checkpoint_gap_problem(100.0, 359.0, 300)
        self.assertEqual(gap, 259.0)
        self.assertIsNone(problem)

        gap, problem = MONITOR.checkpoint_gap_problem(100.0, 461.0, 300)
        self.assertEqual(gap, 361.0)
        self.assertEqual(problem, "observer checkpoint gap=361.0s exceeds 360s")


class AgentIdentityTests(unittest.TestCase):
    def test_fingerprint_is_order_independent_and_detects_process_replacement(self) -> None:
        first = {12: {"start_ticks": 100}, 21: {"start_ticks": 200}}
        reordered = {21: {"start_ticks": 200}, 12: {"start_ticks": 100}}
        replacement = {12: {"start_ticks": 101}, 21: {"start_ticks": 200}}
        self.assertEqual(MONITOR.agent_identity_fingerprint(first), MONITOR.agent_identity_fingerprint(reordered))
        self.assertNotEqual(MONITOR.agent_identity_fingerprint(first), MONITOR.agent_identity_fingerprint(replacement))


class AgentCommandDetectionTests(unittest.TestCase):
    def test_agent_detection_does_not_depend_on_binary_install_path(self) -> None:
        for binary in (
            b"/tmp/geek-monitor-agent-soak",
            b"/home/hhjmk/.local/state/geek-monitor/geek-monitor-agent-soak-v5",
        ):
            command_line = binary + b"\x00-server\x00http://127.0.0.1:18084\x00-config\x00/agent.json"
            self.assertTrue(MONITOR.is_agent_commandline(command_line))

    def test_agent_detection_requires_the_soak_server_address(self) -> None:
        command_line = b"/usr/bin/geek-monitor-agent\x00-server\x00http://127.0.0.1:18085\x00"
        self.assertFalse(MONITOR.is_agent_commandline(command_line))


if __name__ == "__main__":
    unittest.main()
