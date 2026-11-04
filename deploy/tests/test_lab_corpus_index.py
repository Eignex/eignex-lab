"""Metadata misses must remain distinct from transport and old-server failures."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FAKE_CURL = r'''#!/usr/bin/env python3
import os, sys
from pathlib import Path
args = sys.argv[1:]
Path(os.environ['CALLS']).write_text(args[-1])
body = os.environ['BODY']
Path(args[args.index('-o') + 1]).write_text(body)
sys.stdout.write(os.environ['HTTP'])
raise SystemExit(int(os.environ.get('CURL_EXIT', '0')))
'''


class CorpusIndexClientTest(unittest.TestCase):
    def run_client(self, code='200', body='{"collection":"pb-comp-2024","files":[]}', transport='0', name='pb-comp-2024'):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            curl = root / 'curl'
            curl.write_text(FAKE_CURL)
            curl.chmod(0o755)
            calls = root / 'calls'
            env = os.environ | {'PATH': str(root) + os.pathsep + os.environ['PATH'],
                                'LAB_PASSWORD': 'fixture', 'LAB_URL': 'https://lab.invalid',
                                'HTTP': code, 'BODY': body, 'CURL_EXIT': transport, 'CALLS': str(calls)}
            result = subprocess.run(['bash', str(ROOT / 'deploy/lab'), 'corpus-index', name],
                                    env=env, capture_output=True, text=True)
            return result, calls.read_text() if calls.exists() else None

    def test_success_uses_only_metadata_route(self):
        result, url = self.run_client()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('pb-comp-2024', json.loads(result.stdout)['collection'])
        self.assertEqual('https://lab.invalid/corpus/indexes/pb-comp-2024', url)

    def test_only_confirmed_index_miss_exits_three(self):
        result, _ = self.run_client('404', '{"error":"no published corpus index for pb-comp-2024"}')
        self.assertEqual(3, result.returncode)
        self.assertEqual('', result.stdout)
        for code, body, transport in [('404', '', '0'), ('503', '{"error":"backend unavailable"}', '0'),
                                      ('401', '{}', '0'), ('200', '{}', '18'), ('000', '', '7')]:
            with self.subTest(code=code, transport=transport):
                result, _ = self.run_client(code, body, transport)
                self.assertEqual(1, result.returncode)
                self.assertEqual('', result.stdout)

    def test_invalid_ids_are_refused_before_http(self):
        for name in ['../secret', 'a/b', 'bad id', '']:
            result, url = self.run_client(name=name)
            self.assertEqual(2, result.returncode)
            self.assertIsNone(url)

    def test_malformed_success_is_a_failure(self):
        result, _ = self.run_client(body='not json')
        self.assertNotEqual(0, result.returncode)


if __name__ == '__main__':
    unittest.main()
