import io
import json
from pathlib import Path
import sys
import tempfile
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
        self.assertEqual(["renew-avx2", "renew-neon"], [call.kwargs["FunctionName"] for call in self.functions.invoke.call_args_list])

    @patch.object(adapter, "inventory")
    def test_missing_pool_does_not_publish_a_partial_fresh_inventory(self, inventory):
        inventory.side_effect = [self.observation(None, "group-avx2", "lab", "avx2"), ValueError("missing group")]
        with self.assertRaises(ValueError):
            adapter.once(self.config, Mock(), self.functions)
        self.assertFalse((self.root / "inventory.json").exists())
        self.functions.invoke.assert_not_called()

    @patch.object(adapter.urllib.request, "urlopen")
    @patch.object(adapter, "inventory")
    def test_one_failed_renewal_does_not_starve_other_pools(self, inventory, urlopen):
        inventory.side_effect = self.observation
        urlopen.side_effect = lambda *args, **kwargs: io.BytesIO(b'{}')
        self.functions.invoke.side_effect = [{"FunctionError": "failed", "Payload": Mock()}, {"Payload": Mock()}]
        with self.assertRaisesRegex(RuntimeError, "avx2"):
            adapter.once(self.config, Mock(), self.functions)
        self.assertEqual(2, self.functions.invoke.call_count)
