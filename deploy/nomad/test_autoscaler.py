import json
from pathlib import Path
import tempfile
import unittest
from autoscaler import render


class AutoscalerTest(unittest.TestCase):
    def test_empty_only_scale_in_and_fail_closed_metrics_are_forced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); key = root/'key'; key.write_text('private-token-'*4)
            settings = dict(capacityTokenFile=str(key), nomadTokenFile=str(key), coordinator='http://127.0.0.1:8091',
                nomad={'address':'https://nomad:4646'}, targets=[dict(pool='general', driver='aws-asg', options={'aws_asg_name':'group', 'node_selector_strategy':'least_busy'})])
            files = render(settings, {'pools':[{'id':'general','nodeClass':'general','maxNodes':4}]}, root)
            policy = files[Path('policies/general.json')]['scaling']['general']
            self.assertEqual(0, policy['min']); self.assertEqual(4, policy['max'])
            self.assertEqual('fail', policy['policy']['on_check_error'])
            self.assertEqual('empty', policy['policy']['target']['pool-general']['node_selector_strategy'])
            self.assertEqual('instant', policy['policy']['check']['fresh-demand']['query_window'])
            self.assertNotIn('private-token', json.dumps(policy))
            with self.assertRaises(ValueError):
                render(settings | {'targets':[]}, {'pools':[{'id':'general'}]}, root)
