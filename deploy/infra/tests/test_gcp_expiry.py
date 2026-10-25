import base64
import copy
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'common'))
sys.path.insert(0, str(ROOT / 'gcp'))
from capacity_grants import DOMAIN
from main import renew_binding, renew_pool, expire_pool, Cloud
spec = importlib.util.spec_from_file_location('gcp_adapter', ROOT / 'gcp/adapter.py')
adapter = importlib.util.module_from_spec(spec); spec.loader.exec_module(adapter)


class GcpCapacityTest(unittest.TestCase):
    def setUp(self):
        self.key = 'capacity-key-'*4
        self.cloud = Mock(config={'POOL':'general','MAX_NODES':'4'})
        self.cloud.read_lease.return_value = 0

    def grant(self, at=1000000):
        raw = json.dumps(dict(schemaVersion=1,pool='general',maxNodes=4,desired=2,issuedAt=at)).encode()
        return {'payload':base64.urlsafe_b64encode(raw).decode().rstrip('='),'signature':hmac.new(self.key.encode(),DOMAIN+raw,hashlib.sha256).hexdigest()}

    def test_renewal_preserves_unrelated_iam_and_concurrency_and_only_changes_lease_binding(self):
        owner = {'role':'roles/owner','members':['user:owner@example.com']}
        binding = {'role':'custom/resize','members':['serviceAccount:scaler'], 'condition':{'title':'lease','expression':'false','description':'keep'}}
        original = {'version':3,'etag':'concurrent-etag','bindings':[owner,binding]}
        policy = renew_binding(original,'custom/resize','serviceAccount:scaler','lease',2800)
        self.assertEqual(owner,policy['bindings'][0]); self.assertEqual('concurrent-etag',policy['etag'])
        self.assertIn('request.time < timestamp(',policy['bindings'][1]['condition']['expression'])
        self.assertEqual('keep',policy['bindings'][1]['condition']['description'])
        self.assertEqual('false',original['bindings'][1]['condition']['expression'])
        widened = copy.deepcopy(original); widened['bindings'].append({'role':'custom/resize','members':['serviceAccount:scaler']})
        with self.assertRaises(ValueError):
            renew_binding(widened,'custom/resize','serviceAccount:scaler','lease',2800)

    def test_fresh_signed_renewal_persists_expiry_before_advancing_iam_and_rejects_replay(self):
        renew_pool(self.grant(),self.cloud,self.key,clock=lambda:1000)
        names = [c[0] for c in self.cloud.mock_calls]
        self.assertLess(names.index('write_lease'),names.index('renew_iam'))
        self.cloud.renew_iam.assert_called_once_with(2800)
        with self.assertRaises(ValueError):
            renew_pool(self.grant(at=900000),self.cloud,self.key,clock=lambda:1000)
        self.assertEqual(1,self.cloud.renew_iam.call_count)

    def test_expiry_stops_replacement_while_a_fresh_lease_keeps_the_group_running(self):
        self.assertTrue(expire_pool(self.cloud,clock=lambda:1000)['expired'])
        self.cloud.owned.assert_called_once(); self.cloud.close.assert_called_once()
        self.cloud.close.reset_mock(); self.cloud.read_lease.return_value = 2000
        self.assertFalse(expire_pool(self.cloud,clock=lambda:1000)['expired'])
        self.cloud.close.assert_not_called()
        self.cloud.read_lease.return_value = 99999999
        self.assertTrue(expire_pool(self.cloud,clock=lambda:1000)['expired'])

    def test_inventory_counts_boots_and_terminating_workers_separately_with_quota_errors(self):
        cloud = Mock(config={'POOL':'general'},group='https://fixture/group')
        cloud.owned.return_value = {'targetSize':2}
        cloud.request.return_value = {'managedInstances':[{'instance':'https://www.googleapis.com/compute/v1/projects/p/zones/z/instances/node',
            'currentAction':'DELETING','lastAttempt':{'errors':{'errors':[{'message':'quota exhausted'}]}}}]}
        value = adapter.inventory(cloud,at=1000)
        self.assertEqual(3,len(value['instances'])); self.assertFalse(value['instances'][0]['countedInTarget'])
        self.assertEqual('projects/p/zones/z/instances/node',value['instances'][0]['id'])
        self.assertIn('quota exhausted',value['failures']['general'])

    def test_cloud_expiry_uses_resize_zero_and_refuses_wrong_group_ownership(self):
        http = Mock(); http.request.return_value.content = b'{}'
        http.request.return_value.json.return_value = {'description':'wrong'}
        cloud = Cloud(http,dict(PROJECT='p',ZONE='z',GROUP='group',BUCKET='b',POOL='general',OWNERSHIP='lab:pool'))
        with self.assertRaises(ValueError):
            cloud.owned()
        cloud.close()
        self.assertTrue(http.request.call_args.args[1].endswith('/instanceGroupManagers/group/resize'))
        self.assertEqual({'size':0},http.request.call_args.kwargs['params'])
