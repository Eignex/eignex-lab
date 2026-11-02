"""The resource probe may await only the same allocation's precise log-readiness error."""
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import Mock, call, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from nomad.sandbox import Sandbox


class SandboxLogReadinessTest(unittest.TestCase):
    allocation = "00000000-0000-4000-8000-000000000001"

    def unavailable(self, allocation=None, code=1, detail=None):
        message = detail or ("Failed to read stdout file: error reading file: Unexpected response code: 404 "
                             f"(state for allocation {allocation or self.allocation} not found on client)")
        return subprocess.CalledProcessError(code, ["nomad", "alloc", "logs", self.allocation, "worker"], stderr=message + "\n")

    def clock(self):
        self.at = 0.0
        def sleep(delay):
            self.assertGreater(delay, 0)
            self.at += delay
        monotonic = patch("nomad.sandbox.time.monotonic", side_effect=lambda: self.at)
        sleeping = patch("nomad.sandbox.time.sleep", side_effect=sleep)
        monotonic.start(); self.addCleanup(monotonic.stop)
        self.sleep = sleeping.start(); self.addCleanup(sleeping.stop)

    def test_known_readiness_error_retries_only_the_original_allocation_and_returns_unchanged_logs(self):
        self.clock()
        sandbox = Sandbox("/unused", 14646)
        report = '{"cpuMax":"50000 100000","childExits":[0,0,0]}\n'
        sandbox.cli = Mock(side_effect=[self.unavailable(), self.unavailable(), report])
        self.assertEqual(report, sandbox.allocation_logs(self.allocation))
        self.assertEqual([call("alloc", "logs", self.allocation, "worker", timeout=5)] * 3, sandbox.cli.call_args_list)
        self.assertEqual([call(0.25), call(0.25)], self.sleep.call_args_list)

    def test_permanent_readiness_failure_has_one_total_deadline_and_a_bounded_timeout_per_attempt(self):
        self.clock()
        sandbox = Sandbox("/unused", 14646)
        failure = self.unavailable()
        sandbox.cli = Mock(side_effect=failure)
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            sandbox.allocation_logs(self.allocation)
        self.assertIs(failure, raised.exception)
        self.assertEqual(10, self.at)
        self.assertEqual(40, sandbox.cli.call_count)
        for invocation in sandbox.cli.call_args_list:
            self.assertEqual(("alloc", "logs", self.allocation, "worker"), invocation.args)
            self.assertGreater(invocation.kwargs["timeout"], 0)
            self.assertLessEqual(invocation.kwargs["timeout"], min(5, 10))
        self.assertEqual(0.25, sandbox.cli.call_args_list[-1].kwargs["timeout"])

    def test_other_failures_are_not_retried_or_replaced(self):
        self.clock()
        failures = [self.unavailable(allocation="another-allocation"), self.unavailable(code=2),
                    self.unavailable(detail="Unexpected response code: 403 (Permission denied)"),
                    self.unavailable(detail="Unexpected response code: 500 (internal error)"),
                    self.unavailable(detail="Unexpected response code: 404 (log file not found)"),
                    subprocess.TimeoutExpired(["nomad", "alloc", "logs"], 5)]
        for failure in failures:
            with self.subTest(failure=type(failure).__name__, detail=str(failure)):
                sandbox = Sandbox("/unused", 14646); sandbox.cli = Mock(side_effect=failure)
                with self.assertRaises(type(failure)) as raised:
                    sandbox.allocation_logs(self.allocation)
                self.assertIs(failure, raised.exception); self.assertEqual(1, sandbox.cli.call_count)
        self.sleep.assert_not_called()

    def test_successful_malformed_output_is_returned_for_the_existing_resource_assertions_to_reject(self):
        self.clock()
        sandbox = Sandbox("/unused", 14646); sandbox.cli = Mock(return_value="not a probe report")
        self.assertEqual("not a probe report", sandbox.allocation_logs(self.allocation))
        self.assertEqual(1, sandbox.cli.call_count); self.sleep.assert_not_called()

    def test_cli_forwards_the_timeout_to_the_same_command(self):
        sandbox = Sandbox("/unused", 14646); sandbox.env = Mock(return_value={"NOMAD_ADDR": "fixture"})
        with patch("nomad.sandbox.run", return_value="probe") as invoke:
            self.assertEqual("probe", sandbox.cli("alloc", "logs", self.allocation, "worker", timeout=0.25))
        invoke.assert_called_once_with([sandbox.nomad, "alloc", "logs", self.allocation, "worker"],
                                       env={"NOMAD_ADDR": "fixture"}, timeout=0.25)
