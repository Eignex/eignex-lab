import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "aws"))
import adapter


class AwsAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "key").write_text("fixture-key-" * 4)
        self.config = {"deployment": "lab", "inventoryFile": str(self.root / "inventory.json"),
                       "capacityTokenFile": str(self.root / "key"), "coordinator": "http://coordinator",
                       "pools": [{"pool": "avx2", "group": "group-avx2", "renewFunction": "renew-avx2"},
                                 {"pool": "neon", "group": "group-neon", "renewFunction": "renew-neon"}]}
        self.functions = Mock()
        self.functions.invoke.return_value = {"Payload": Mock()}

    def observation(self, asg, group, deployment, pool):
        return {"observedAt": 123, "pools": [pool], "instances": [{"id": group, "pool": pool}], "failures": {}}

    @patch.object(adapter.urllib.request, "urlopen")
    @patch.object(adapter, "inventory")
    def test_all_pools_are_published_before_their_independent_renewals(self, inventory, urlopen):
        inventory.side_effect = self.observation
        def grant(request, timeout):
            self.assertEqual(["avx2", "neon"], json.loads((self.root / "inventory.json").read_text())["pools"])
            return io.BytesIO(b'{"payload":"fixture","signature":"fixture"}')
        urlopen.side_effect = grant
        adapter.once(self.config, Mock(), self.functions)
        value = json.loads((self.root / "inventory.json").read_text())
        self.assertEqual(["avx2", "neon"], [item["pool"] for item in value["instances"]])
        self.assertCountEqual(["renew-avx2", "renew-neon"], [call.kwargs["FunctionName"] for call in self.functions.invoke.call_args_list])

    @patch.object(adapter.urllib.request, "urlopen")
    @patch.object(adapter, "inventory")
    def test_slow_pool_requests_overlap_instead_of_aging_the_entire_fleet(self, inventory, urlopen):
        barrier = threading.Barrier(2)
        def observe(*args):
            barrier.wait(timeout=2)
            return self.observation(*args)
        inventory.side_effect = observe
        urlopen.side_effect = lambda *args, **kwargs: io.BytesIO(b'{}')
        adapter.once(self.config, Mock(), self.functions)
        self.assertEqual(2, self.functions.invoke.call_count)

    @patch.object(adapter, "inventory")
    def test_missing_pool_does_not_publish_a_partial_fresh_inventory(self, inventory):
        def observe(asg, group, deployment, pool):
            if pool == "neon":
                raise ValueError("missing group")
            return self.observation(asg, group, deployment, pool)
        inventory.side_effect = observe
        with self.assertRaises(ValueError):
            adapter.once(self.config, Mock(), self.functions)
        self.assertFalse((self.root / "inventory.json").exists())
        self.functions.invoke.assert_not_called()

    @patch.object(adapter.urllib.request, "urlopen")
    @patch.object(adapter, "inventory")
    def test_one_failed_renewal_does_not_starve_other_pools(self, inventory, urlopen):
        inventory.side_effect = self.observation
        urlopen.side_effect = lambda *args, **kwargs: io.BytesIO(b'{}')
        self.functions.invoke.side_effect = lambda **kwargs: ({"FunctionError": "failed"} if kwargs["FunctionName"] == "renew-avx2" else {}) | {"Payload": Mock()}
        with self.assertRaisesRegex(RuntimeError, "avx2"):
            adapter.once(self.config, Mock(), self.functions)
        self.assertEqual(2, self.functions.invoke.call_count)

    @patch.object(adapter.urllib.request, "urlopen")
    @patch.object(adapter, "inventory")
    def test_renewal_reports_actionable_codes_without_exposing_response_secrets(self, inventory, urlopen):
        inventory.side_effect = self.observation
        def grant(request, timeout):
            if request.full_url.endswith("avx2"):
                raise adapter.urllib.error.HTTPError(request.full_url, 503, "secret-grant", {}, None)
            return io.BytesIO(b'{}')
        urlopen.side_effect = grant
        payload = io.BytesIO(b'{"errorType":"ValueError","errorMessage":"secret-signature"}')
        self.functions.invoke.return_value = {"FunctionError": "Unhandled", "Payload": payload}
        with self.assertRaises(adapter.RenewalError) as raised:
            adapter.once(self.config, Mock(), self.functions)
        self.assertIn("avx2=HTTP_503", str(raised.exception))
        self.assertIn("neon=ValueError", str(raised.exception))
        self.assertNotIn("secret", str(raised.exception))
        self.assertTrue(payload.closed)

    @patch.object(adapter.urllib.request, "urlopen")
    @patch.object(adapter, "inventory")
    def test_sdk_error_code_survives_without_logging_its_message(self, inventory, urlopen):
        inventory.side_effect = self.observation
        urlopen.side_effect = lambda *args, **kwargs: io.BytesIO(b'{}')
        error = RuntimeError("secret-request")
        error.response = {"Error": {"Code": "TooManyRequestsException", "Message": "secret-request"}}
        self.functions.invoke.side_effect = error
        with self.assertRaises(adapter.RenewalError) as raised:
            adapter.once(self.config, Mock(), self.functions)
        self.assertIn("avx2=TooManyRequestsException", str(raised.exception))
        self.assertNotIn("secret", str(raised.exception))
