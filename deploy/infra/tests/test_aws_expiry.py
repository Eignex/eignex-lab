from pathlib import Path
import sys
import unittest
from unittest.mock import Mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "aws"))
from adapter import inventory


class AwsCapacityTest(unittest.TestCase):
    def setUp(self):
        self.asg = Mock()
        self.group = {"MinSize": 0, "MaxSize": 4, "DesiredCapacity": 2, "Instances": [{"InstanceId": "i-1", "LifecycleState": "InService", "ProtectedFromScaleIn": True}],
                      "Tags": [{"Key": "lab:deployment", "Value": "lab"}, {"Key": "lab:pool", "Value": "general"}]}
        self.asg.describe_auto_scaling_groups.return_value = {"AutoScalingGroups": [self.group]}

    def test_inventory_accounts_for_missing_boots_and_does_not_reopen_terminating_targets(self):
        self.asg.describe_scaling_activities.return_value = {"Activities": []}
        value = inventory(self.asg, "group", "lab", "general", at=1000)
        self.assertEqual(2, len(value["instances"]))
        self.group["Instances"][0]["LifecycleState"] = "Terminating"
        self.group["DesiredCapacity"] = 0
        value = inventory(self.asg, "group", "lab", "general", at=1000)
        self.assertFalse(value["instances"][0]["countedInTarget"])


if __name__ == "__main__":
    unittest.main()
