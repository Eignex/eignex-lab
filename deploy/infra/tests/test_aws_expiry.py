import base64
import hashlib
import hmac
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "aws"))
from capacity_grants import verify, DOMAIN
from functions import expire_pool, renew_pool
from adapter import inventory


class AwsCapacityTest(unittest.TestCase):
    def setUp(self):
        self.asg = Mock(); self.ec2 = Mock(); self.ddb = Mock(); self.key = "capacity-key-" * 4
        self.group = {"MinSize": 0, "MaxSize": 4, "DesiredCapacity": 2, "Instances": [{"InstanceId": "i-1", "LifecycleState": "InService", "ProtectedFromScaleIn": True}],
                      "Tags": [{"Key": "lab:deployment", "Value": "lab"}, {"Key": "lab:pool", "Value": "general"}]}
        self.asg.describe_auto_scaling_groups.return_value = {"AutoScalingGroups": [self.group]}
        self.ec2.get_paginator.return_value.paginate.return_value = [{"Reservations": [{"Instances": [{"InstanceId": "i-1"}]}]}]
        self.ddb.get_item.return_value = {}

    def grant(self, issued=1000000, pool="general", desired=2):
        raw = json.dumps(dict(schemaVersion=1, pool=pool, maxNodes=4, desired=desired, issuedAt=issued, nonce="test")).encode()
        return dict(payload=base64.urlsafe_b64encode(raw).decode().rstrip("="), signature=hmac.new(self.key.encode(), DOMAIN+raw, hashlib.sha256).hexdigest())

    def test_expiry_clamps_min_desired_max_before_removing_remaining_workers(self):
        calls = Mock(); calls.attach_mock(self.asg, "asg"); calls.attach_mock(self.ec2, "ec2")
        result = expire_pool(self.asg, self.ec2, self.ddb, "leases", "group", "lab", "general", clock=lambda: 1000)
        self.assertTrue(result["expired"])
        self.asg.update_auto_scaling_group.assert_called_once_with(AutoScalingGroupName="group", MinSize=0, DesiredCapacity=0, MaxSize=0)
        self.asg.set_instance_protection.assert_called_once()
        operations = [call[0] for call in calls.mock_calls]
        self.assertLess(operations.index("asg.update_auto_scaling_group"), operations.index("ec2.terminate_instances"))

    def test_fresh_lease_does_not_terminate_and_invalid_far_future_lease_fails_closed(self):
        self.ddb.get_item.return_value = {"Item": {"lease_until": {"N": "2000"}}}
        self.assertFalse(expire_pool(self.asg, self.ec2, self.ddb, "leases", "group", "lab", "general", clock=lambda: 1000)["expired"])
        self.asg.update_auto_scaling_group.assert_not_called()
        self.ddb.get_item.return_value = {"Item": {"lease_until": {"N": "99999999999"}}}
        self.assertTrue(expire_pool(self.asg, self.ec2, self.ddb, "leases", "group", "lab", "general", clock=lambda: 1000)["expired"])

    def test_signed_fresh_grant_persists_lease_before_reopening_a_closed_group(self):
        self.group["MaxSize"] = 0
        calls = Mock(); calls.attach_mock(self.asg, "asg"); calls.attach_mock(self.ddb, "ddb")
        renew_pool(self.grant(), self.asg, self.ddb, self.key, "leases", "group", "lab", "general", 4, clock=lambda: 1000)
        self.assertLess([c[0] for c in calls.mock_calls].index("ddb.put_item"), [c[0] for c in calls.mock_calls].index("asg.update_auto_scaling_group"))
        self.assertEqual("2800", self.ddb.put_item.call_args.kwargs["Item"]["lease_until"]["N"])
        for grant in (self.grant(issued=900000), self.grant(pool="other"), self.grant(desired=5)):
            with self.assertRaises(ValueError):
                renew_pool(grant, self.asg, self.ddb, self.key, "leases", "group", "lab", "general", 4, clock=lambda: 1000)

    def test_inventory_accounts_for_missing_boots_and_does_not_reopen_terminating_targets(self):
        self.asg.describe_scaling_activities.return_value = {"Activities": []}
        value = inventory(self.asg, "group", "lab", "general", at=1000)
        self.assertEqual(2, len(value["instances"]))
        self.group["Instances"][0]["LifecycleState"] = "Terminating"
        self.group["DesiredCapacity"] = 0
        value = inventory(self.asg, "group", "lab", "general", at=1000)
        self.assertFalse(value["instances"][0]["countedInTarget"])
        self.group["Tags"] = []
        with self.assertRaises(ValueError):
            expire_pool(self.asg, self.ec2, self.ddb, "leases", "group", "lab", "general", clock=lambda: 1000)


if __name__ == "__main__":
    unittest.main()
