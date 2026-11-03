import base64
from datetime import datetime, timezone
import hashlib
import hmac
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'common'))
sys.path.insert(0, str(ROOT / 'aws'))
from capacity_grants import DOMAIN, STARTUP_GRACE_MS, verify
from functions import renew_pool
from adapter import inventory


class StartupRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.key = 'fixture-key-' * 4
        self.now = 1000
        self.launched = 1000
        self.ident = 'i-0123456789abcdef0'
        self.action = dict(id=self.ident, launchedAt=self.launched)
        self.member = dict(InstanceId=self.ident, LifecycleState='InService', ProtectedFromScaleIn=False)
        self.tags = [{'Key': 'lab:deployment', 'Value': 'lab'}, {'Key': 'lab:pool', 'Value': 'general'}]
        self.group = dict(MinSize=0, MaxSize=4, DesiredCapacity=1, Tags=self.tags, Instances=[self.member])
        self.asg = Mock(); self.ec2 = Mock(); self.ddb = Mock()
        self.asg.describe_auto_scaling_groups.return_value = {'AutoScalingGroups': [self.group]}
        self.instance = dict(InstanceId=self.ident, Tags=self.tags, State={'Name': 'running'},
                             LaunchTime=datetime.fromtimestamp(self.launched / 1000, timezone.utc))
        self.ec2.describe_instances.return_value = {'Reservations': [{'Instances': [self.instance]}]}

    def grant(self, **changes):
        value = dict(schemaVersion=2, pool='general', maxNodes=4, desired=1,
                     issuedAt=self.now * 1000, startupRetirements=[self.action]) | changes
        raw = json.dumps(value).encode()
        return dict(payload=base64.urlsafe_b64encode(raw).decode().rstrip('='),
                    signature=hmac.new(self.key.encode(), DOMAIN + raw, hashlib.sha256).hexdigest())

    def renew(self, grant=None, clock=None):
        return renew_pool(grant or self.grant(), self.asg, self.ddb, self.key, 'leases', 'group', 'lab', 'general', 4,
                          clock=clock or (lambda: self.now), ec2=self.ec2)

    def test_recovery_uses_normal_exact_member_replacement_and_preserves_target_and_limits(self):
        result = self.renew()
        self.assertEqual([self.ident], result['startupRetired'])
        self.asg.terminate_instance_in_auto_scaling_group.assert_called_once_with(InstanceId=self.ident, ShouldDecrementDesiredCapacity=False)
        self.asg.update_auto_scaling_group.assert_not_called()
        self.assertEqual(1, self.group['DesiredCapacity'])
        self.assertEqual(4, self.group['MaxSize'])

    def test_already_removed_or_terminating_members_are_idempotent_without_ec2_or_termination(self):
        for members in ([], [self.member | {'LifecycleState': 'Terminating:Wait'}]):
            with self.subTest(members=members):
                self.group['Instances'] = members
                self.renew()
        self.ec2.describe_instances.assert_not_called()
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

    def test_forged_signature_or_unowned_group_cannot_reach_destructive_action(self):
        for grant, tags in ((self.grant() | {'signature': '0' * 64}, self.tags),
                            (self.grant(), [{'Key': 'lab:deployment', 'Value': 'other'}])):
            with self.subTest(tags=tags):
                self.group['Tags'] = tags
                with self.assertRaises(ValueError): self.renew(grant)
        self.ec2.describe_instances.assert_not_called()
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

    def test_protected_or_standby_members_are_not_replaced(self):
        for member in (self.member | {'ProtectedFromScaleIn': True}, self.member | {'LifecycleState': 'Standby'},
                       {key: value for key, value in self.member.items() if key != 'ProtectedFromScaleIn'}):
            self.group['Instances'] = [member]
            with self.assertRaises(ValueError): self.renew()
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

    def test_provider_ownership_state_and_exact_launch_identity_are_required(self):
        for changed in (self.instance | {'Tags': []}, self.instance | {'InstanceId': 'i-another'},
                        self.instance | {'State': {'Name': 'stopped'}},
                        self.instance | {'LaunchTime': datetime.fromtimestamp(2, timezone.utc)}):
            with self.subTest(changed=list(changed)):
                self.ec2.describe_instances.return_value = {'Reservations': [{'Instances': [changed]}]}
                with self.assertRaises(ValueError): self.renew()
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

    def test_final_membership_and_protection_are_rechecked_after_provider_read(self):
        def protect(**_):
            self.member['ProtectedFromScaleIn'] = True
            return {'Reservations': [{'Instances': [self.instance]}]}
        self.ec2.describe_instances.side_effect = protect
        with self.assertRaises(ValueError): self.renew()
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

    def test_grant_expiring_during_provider_reads_cannot_replace_an_instance(self):
        now = [self.now]
        def delayed(**_):
            now[0] += 31
            return {'Reservations': [{'Instances': [self.instance]}]}
        self.ec2.describe_instances.side_effect = delayed
        with self.assertRaisesRegex(ValueError, 'expired'):
            self.renew(clock=lambda: now[0])
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

    def test_concurrent_replacement_validation_error_only_succeeds_if_member_is_now_gone(self):
        error = RuntimeError('bounded provider error')
        error.response = {'Error': {'Code': 'ValidationError'}}
        def replaced(**_):
            self.member['LifecycleState'] = 'Terminating'
            raise error
        self.asg.terminate_instance_in_auto_scaling_group.side_effect = replaced
        self.renew()
        self.member['LifecycleState'] = 'InService'
        self.asg.terminate_instance_in_auto_scaling_group.side_effect = error
        with self.assertRaises(RuntimeError): self.renew()

    def test_action_schema_malformed_launch_times_and_unexpired_grace_fail_closed(self):
        for changed in (dict(schemaVersion=1), dict(startupRetirements=[self.action, self.action]),
                        dict(startupRetirements=[self.action | {'launchedAt': True}]),
                        dict(startupRetirements=[self.action | {'launchedAt': self.now * 1000 - STARTUP_GRACE_MS + 1}]),
                        dict(startupRetirements=[self.action | {'extra': 'ignored'}])):
            with self.subTest(changed=changed):
                with self.assertRaises(ValueError): verify(self.grant(**changed), self.key, 'general', 4, self.now * 1000)
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

    def test_ordinary_schema_one_renewals_remain_compatible(self):
        grant = self.grant(schemaVersion=1, startupRetirements=[])
        self.renew(grant)
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

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

    def test_missing_retirement_record_is_idempotent_only_after_fresh_asg_absence_or_termination(self):
        error = RuntimeError('provider record is gone')
        error.response = {'Error': {'Code': 'InvalidInstanceID.NotFound'}}
        for members in ([], [self.member | {'LifecycleState': 'Terminating'}]):
            self.group['Instances'] = [self.member]
            def disappeared(next_members=members, **_):
                self.group['Instances'] = next_members
                raise error
            self.ec2.describe_instances.side_effect = disappeared
            self.renew()
        self.group['Instances'] = [self.member]
        self.ec2.describe_instances.side_effect = error
        with self.assertRaises(RuntimeError): self.renew()
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

    def test_missing_record_does_not_hide_changed_ownership_or_unrelated_provider_errors(self):
        missing = RuntimeError('provider record is gone')
        missing.response = {'Error': {'Code': 'InvalidInstanceID.NotFound'}}
        def unowned(**_):
            self.group['Tags'] = []
            raise missing
        self.ec2.describe_instances.side_effect = unowned
        with self.assertRaisesRegex(ValueError, 'ownership'): self.renew()
        self.group['Tags'] = self.tags
        denied = RuntimeError('provider denied')
        denied.response = {'Error': {'Code': 'UnauthorizedOperation'}}
        self.ec2.describe_instances.side_effect = denied
        with self.assertRaises(RuntimeError): self.renew()
        self.asg.terminate_instance_in_auto_scaling_group.assert_not_called()

    def test_lifetime_protection_is_armed_before_secret_retrieval_on_both_providers(self):
        for provider in ('aws', 'gcp'):
            text = (ROOT / provider / 'user-data.sh.tftpl').read_text()
            self.assertLess(text.index('systemctl enable --now lab-lifetime.timer'), text.index('mkdir -p'))
            self.assertLess(text.index('systemctl enable --now lab-lifetime.timer'), text.index('exec '))


if __name__ == '__main__': unittest.main()
