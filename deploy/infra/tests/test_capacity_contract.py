"""The capacity wire format, the pool contract, the shared inventory builder, and the examples agreeing on their pools."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'deploy/infra/common'))
import capacity_contract  # noqa: E402
import capacity_grants  # noqa: E402
import inventory  # noqa: E402


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


class WireTest(unittest.TestCase):
    """Every Python copy of the wire format is the one deploy/capacity-wire.json holds (CapacityWireTest holds Kotlin to it)."""
    wire = json.loads((ROOT / 'deploy/capacity-wire.json').read_text())

    def test_grants_bootstrap_and_autoscaler_speak_the_shared_wire_format(self):
        self.assertEqual(self.wire['grantDomain'].encode(), capacity_grants.DOMAIN)
        self.assertEqual(self.wire['grantSchemaVersion'], capacity_grants.SCHEMA_VERSION)
        self.assertEqual(self.wire['grantFreshMs'], capacity_grants.FRESH_MS)
        self.assertEqual(self.wire['grantClockSkewMs'], capacity_grants.CLOCK_SKEW_MS)
        bootstrap = module('host_bootstrap', ROOT / 'deploy/infra/common/bootstrap.py')
        self.assertEqual(self.wire['capacityIdMeta'], bootstrap.CAPACITY_ID_META)
        autoscaler = module('autoscaler', ROOT / 'deploy/nomad/autoscaler.py')
        self.assertEqual(self.wire['desiredMetric'], autoscaler.DESIRED_METRIC)


class ContractTest(unittest.TestCase):
    def contract(self, pools):
        path = Path(tempfile.mkdtemp()) / 'contract.json'
        path.write_text(json.dumps({'schemaVersion': 1, 'pools': pools}))
        return path

    def test_a_pool_is_checked_against_the_lab_contract(self):
        path = self.contract([{'id': 'general-spot', 'nodeClass': 'linux', 'maxNodes': 4, 'vcpus': 16, 'memoryMiB': 65536, 'market': 'spot'}])
        self.assertEqual(4, capacity_contract.check(path, 'general-spot', maxNodes=4)['maxNodes'])
        with self.assertRaisesRegex(ValueError, 'maxNodes 8 here, 4 there'):
            capacity_contract.check(path, 'general-spot', maxNodes=8)
        with self.assertRaisesRegex(ValueError, 'not in the contract'):
            capacity_contract.check(path, 'other')
        self.assertIsNone(capacity_contract.check(None, 'anything'))


class InventoryTest(unittest.TestCase):
    def test_booting_nodes_fill_the_target_and_draining_ones_do_not_count(self):
        value = inventory.observation('p', [('i-1', False), ('i-2', True)], 3, None, at=1)
        self.assertEqual(['i-1', 'i-2', 'booting-0', 'booting-1'], [item['id'] for item in value['instances']])
        self.assertEqual({}, value['failures'])
        self.assertEqual({'p': 'quota'}, inventory.observation('p', [], 0, 'quota', at=1)['failures'])

    def test_merged_pools_stay_distinct_and_as_old_as_the_oldest(self):
        merged = inventory.merge([inventory.observation('a', [], 1, at=2), inventory.observation('b', [], 0, at=1)])
        self.assertEqual(1000, merged['observedAt'])
        with self.assertRaisesRegex(ValueError, 'pool twice'):
            inventory.merge([inventory.observation('a', [], 0, at=1), inventory.observation('a', [], 0, at=1)])


class ExamplesTest(unittest.TestCase):
    """The examples one pool's setup is copied from name that pool alike; the fleet's pools are the architectures'."""

    def load(self, path):
        return json.loads((ROOT / path).read_text())

    def test_examples_agree_on_their_pools(self):
        single = {p['id'] for p in self.load('deploy/nomad/capacity.example.json')['pools']}
        self.assertEqual(single, {t['pool'] for t in self.load('deploy/nomad/autoscaler.example.json')['targets']})
        self.assertEqual(single, {t['pool'] for t in self.load('deploy/nomad/autoscaler-gcp.example.json')['targets']})
        self.assertEqual(single, {self.load('deploy/infra/aws/adapter.example.json')['pool']})
        self.assertEqual(single, {self.load('deploy/infra/gcp/adapter.example.json')['POOL']})
        architectures = {p['id'] for p in self.load('deploy/nomad/capacity-architectures.example.json')['pools']}
        fleet = {p['pool'] for p in self.load('deploy/infra/aws/adapter-fleet.example.json')['pools']}
        self.assertTrue(fleet <= architectures, fleet - architectures)


if __name__ == '__main__':
    unittest.main()
