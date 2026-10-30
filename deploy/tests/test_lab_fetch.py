"""The maintained client must never publish a truncated evidence download as complete."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FAKE_CURL = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
from urllib.parse import urlparse
args = sys.argv[1:]
path = urlparse(args[-1]).path
method = args[args.index('-X') + 1] if '-X' in args else 'GET'
with Path(os.environ['FAKE_CALLS']).open('a') as log: log.write(method + ' ' + path + '\n')
mode = os.environ['FAKE_MODE']
body, status, exit_code = b'', '200', 0
if path.endswith('/hold'):
    body = b'{"id":"ab12"}'
elif '/hold/' in path:
    pass
elif path.endswith('/files'):
    body = json.dumps([{'path':'preparation/archive.zip','bytes':8},{'path':'job.log','bytes':4}]).encode()
elif path.endswith('archive.zip'):
    body = b'complete'
    if mode in ('short', 'transport'): body = b'par'
    if mode in ('transport', 'full-body-error'): exit_code = 16
    if mode == 'http': status, body = '404', b'not found'
elif path.endswith('job.log'):
    body = b'log\n'
elif path.endswith('cases.jsonl'):
    body = b'{}\n'
    if mode == 'cases-error': body, exit_code = b'{', 16
else:
    raise SystemExit('unexpected test URL')
if '-o' in args:
    output = args[args.index('-o') + 1]
    if output != '/dev/null': Path(output).write_bytes(body)
else:
    sys.stdout.buffer.write(body)
if '-w' in args: sys.stdout.write(status)
raise SystemExit(exit_code)
'''


class FetchTest(unittest.TestCase):
    def fetch(self, mode, dest, root):
        bin_dir = root / 'bin'
        bin_dir.mkdir(exist_ok=True)
        curl = bin_dir / 'curl'
        curl.write_text(FAKE_CURL)
        curl.chmod(0o755)
        env = os.environ | {'PATH': str(bin_dir) + os.pathsep + os.environ['PATH'],
                            'LAB_PASSWORD': 'fixture', 'LAB_URL': 'https://fixture.invalid',
                            'FAKE_MODE': mode, 'FAKE_CALLS': str(root / 'calls')}
        return subprocess.run(['bash', str(ROOT / 'deploy/lab'), 'fetch', '1', str(dest)],
                              env=env, capture_output=True, text=True)

    def test_transport_failure_and_short_http_200_body_are_incomplete_and_resumable(self):
        for mode in ('transport', 'short', 'full-body-error', 'http'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                dest = root / 'download'
                result = self.fetch(mode, dest, root)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertFalse((dest / 'preparation/archive.zip').exists())
                self.assertFalse((dest / 'preparation/archive.zip.part').exists())
                self.assertEqual(b'log\n', (dest / 'job.log').read_bytes())
                receipt_text = (dest / 'lab-fetch-missing.json').read_text()
                self.assertTrue(receipt_text, result.stderr)
                receipt = json.loads(receipt_text)
                missing = receipt['missing'][0]
                self.assertEqual('preparation/archive.zip', missing['path'])
                self.assertEqual(8, missing['bytes'])
                self.assertEqual(16 if mode in ('transport', 'full-body-error') else 0, missing['curlExit'])
                self.assertEqual(3 if mode in ('short', 'transport') else 8 if mode == 'full-body-error' else 9,
                                 missing['receivedBytes'])
                self.assertIn('DELETE /jobs/1/hold/ab12', (root / 'calls').read_text())
                fixed = self.fetch('success', dest, root)
                self.assertEqual(0, fixed.returncode, fixed.stderr)
                self.assertEqual(b'complete', (dest / 'preparation/archive.zip').read_bytes())
                self.assertFalse((dest / 'lab-fetch-missing.json').exists())

    def test_failed_case_export_also_leaves_an_incomplete_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dest = root / 'download'
            result = self.fetch('cases-error', dest, root)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((dest / 'lab-cases.jsonl').exists())
            self.assertFalse((dest / 'lab-cases.jsonl.part').exists())
            receipt_text = (dest / 'lab-fetch-missing.json').read_text()
            self.assertTrue(receipt_text, result.stderr)
            receipt = json.loads(receipt_text)
            self.assertEqual('lab-cases.jsonl', receipt['missing'][0]['path'])
            self.assertIsNone(receipt['missing'][0]['bytes'])
            self.assertEqual(16, receipt['missing'][0]['curlExit'])
            self.assertIn('DELETE /jobs/1/hold/ab12', (root / 'calls').read_text())
