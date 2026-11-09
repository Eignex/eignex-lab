from datetime import datetime, timezone
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'aws'))
from adapter import inventory


class StartupRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.now = 1000
        self.launched = 1000
        self.ident = 'i-0123456789abcdef0'
        self.member = dict(InstanceId=self.ident, LifecycleState='InService', ProtectedFromScaleIn=False)
        self.tags = [{'Key': 'lab:deployment', 'Value': 'lab'}, {'Key': 'lab:pool', 'Value': 'general'}]
        self.group = dict(MinSize=0, MaxSize=4, DesiredCapacity=1, Tags=self.tags, Instances=[self.member])
        self.asg = Mock(); self.ec2 = Mock()
        self.asg.describe_auto_scaling_groups.return_value = {'AutoScalingGroups': [self.group]}
        self.instance = dict(InstanceId=self.ident, Tags=self.tags, State={'Name': 'running'},
                             LaunchTime=datetime.fromtimestamp(self.launched / 1000, timezone.utc))
        self.ec2.describe_instances.return_value = {'Reservations': [{'Instances': [self.instance]}]}

    def test_inventory_uses_actual_provider_launch_time_without_aging_synthetic_slots(self):
        self.group['DesiredCapacity'] = 2
        self.asg.describe_scaling_activities.return_value = {'Activities': []}
        value = inventory(self.asg, 'group', 'lab', 'general', at=self.now, ec2=self.ec2)
        self.assertEqual(self.launched, value['instances'][0]['launchedAt'])
        self.assertNotIn('launchedAt', value['instances'][1])

    def test_vanished_terminating_instance_does_not_hold_inventory_of_its_healthy_neighbor(self):
        healthy = 'i-0123456789abcdef1'
        self.group['Instances'] = [self.member | {'LifecycleState': 'Terminating'}, self.member | {'InstanceId': healthy}]
        self.ec2.describe_instances.return_value = {'Reservations': [{'Instances': [self.instance | {'InstanceId': healthy}]}]}
        self.asg.describe_scaling_activities.return_value = {'Activities': []}
        value = inventory(self.asg, 'group', 'lab', 'general', at=self.now, ec2=self.ec2)
        self.ec2.describe_instances.assert_called_once_with(Filters=[{'Name': 'instance-id', 'Values': [self.ident, healthy]}])
        self.assertEqual(self.now * 1000, value['observedAt'])
        self.assertEqual(2, len(value['instances']))
        self.assertFalse(value['instances'][0]['countedInTarget'])
        self.assertNotIn('launchedAt', value['instances'][0], 'an absent provider record must not acquire a fabricated age')
        self.assertEqual(self.launched, value['instances'][1]['launchedAt'])


if __name__ == '__main__': unittest.main()
