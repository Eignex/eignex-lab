import importlib.util
from http.server import BaseHTTPRequestHandler
from pathlib import Path
import socket
import unittest


def module(name):
    path = Path(__file__).resolve().parents[1] / 'aws' / (name + '.py')
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class InputStoreTest(unittest.TestCase):
    def test_native_signer_queues_a_worker_burst_before_handlers_accept(self):
        signer = module('input_signer')
        # Delay accept entirely: the kernel must retain all eight connections, independent
        # of handler scheduling or AWS latency. The old five-entry listener fails this burst.
        with signer.InputSigningServer(('127.0.0.1', 0), BaseHTTPRequestHandler) as server:
            clients = []
            try:
                for _ in range(8):
                    clients.append(socket.create_connection(server.server_address, timeout=1))
            finally:
                for client in clients:
                    client.close()

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

    def test_build_archives_are_signed_by_content_hash_and_expire(self):
        signer, store = module('input_signer'), module('input_store')
        sha = 'b' * 64
        operation, params, headers = signer.signature_request(dict(method='PUT', key='build-blobs/' + sha, sha256=sha), 'cache')
        self.assertEqual(('put_object', '*', sha), (operation, params['IfNoneMatch'], headers['x-amz-meta-sha256']))
        self.assertEqual('head_object', signer.signature_request(dict(method='HEAD', key='build-blobs/' + sha), 'cache')[0])
        for key, digest in (('build-blobs/' + sha, 'c' * 64), ('build-blobs/bad', sha), ('build-blobs/' + sha + '/x', sha)):
            with self.assertRaises(ValueError):
                signer.signature_request(dict(method='PUT', key=key, sha256=digest), 'cache')
        reads = store.statements('input-bucket', 'vpce-test')[0]['Resource']
        self.assertIn('arn:aws:s3:::input-bucket/build-blobs/*', reads)
        kept = dict(ID='other', Status='Enabled', Filter=dict(Prefix='logs/'), Expiration=dict(Days=3))
        rules = store.lifecycle([kept, dict(ID=store.BUILD_ARCHIVE_RULE, Status='Disabled')])
        self.assertEqual([kept], rules[:-1])
        self.assertEqual(('build-blobs/', 30, 'Enabled'), (rules[-1]['Filter']['Prefix'], rules[-1]['Expiration']['Days'], rules[-1]['Status']))
