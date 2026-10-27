import importlib.util
from pathlib import Path
import unittest


def module(name):
    path = Path(__file__).resolve().parents[1] / 'aws' / (name + '.py')
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class InputStoreTest(unittest.TestCase):
    def test_regional_access_requires_the_specific_vpc_endpoint(self):
        policy = module('input_store').statements('input-bucket', 'vpce-test')
        for row in policy:
            self.assertEqual('vpce-test', row['Condition']['StringEquals']['aws:SourceVpce'])
        self.assertTrue(all(row['Action'] != 's3:PutObject' for row in policy))
        writes = module('input_store').native_policy('input-bucket')['Statement'][-1]
        self.assertEqual('*', writes['Condition']['StringEquals']['s3:if-none-match'])

    def test_native_signer_can_only_publish_immutable_content_hashes(self):
        signer = module('input_signer')
        sha = 'a' * 64
        operation, params, headers = signer.signature_request(dict(method='PUT', key='input-blobs/' + sha, sha256=sha), 'cache')
        self.assertEqual('put_object', operation)
        self.assertEqual('*', params['IfNoneMatch'])
        self.assertEqual(sha, headers['x-amz-meta-sha256'])
        _, pack, _ = signer.signature_request(dict(method='PUT', key='corpus-packs/fixture.tar', sha256=sha), 'cache')
        self.assertEqual('*', pack['IfNoneMatch'])
        for key in ('corpus/model', '../private', 'other/key', 'input-blobs/bad'):
            with self.assertRaises(ValueError):
                signer.signature_request(dict(method='PUT', key=key, sha256=sha), 'cache')

    def test_native_signer_cannot_sign_other_buckets_or_non_input_keys(self):
        signer = module('input_signer')
        for key in ('corpus/../../secret', 'corpus//model', 'builds/private', 'corpus\\private'):
            with self.assertRaises(ValueError):
                signer.signature_request(dict(method='GET', key=key), 'cache')
