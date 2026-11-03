"""The maintained client must never publish a truncated evidence download as complete."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FAKE_CURL = r'''#!/usr/bin/env python3
import hashlib, json, os, sys
from pathlib import Path
from urllib.parse import unquote, urlparse
args = sys.argv[1:]
path = unquote(urlparse(args[-1]).path)
method = args[args.index('-X') + 1] if '-X' in args else 'GET'
with Path(os.environ['FAKE_CALLS']).open('a') as log: log.write(method + ' ' + path + '\n')
mode = os.environ['FAKE_MODE']
body, status, exit_code = b'', '200', 0
if path.endswith('/hold'):
    body = b'{"id":"ab12"}'
elif '/hold/' in path:
    pass
elif path.endswith('/files'):
    files = [{'path':'preparation/archive.zip','bytes':8},{'path':'job.log','bytes':4}]
    if mode in ('hashed', 'bad-hash'):
        files[0]['sha256'] = hashlib.sha256(b'complete').hexdigest()
    if mode in ('case-names', 'aliased-directories'):
        names = ['A.out', 'a.out'] if mode == 'case-names' else ['a/result.out', 'A/result.out']
        files = [{'path':name,'bytes':len(value),'sha256':hashlib.sha256(value).hexdigest()}
                 for name,value in zip(names, [b'first', b'other'])]
    if mode == 'literal-name':
        files = [{'path':'names/a b\t%#\n','bytes':5,'sha256':hashlib.sha256(b'first').hexdigest()}]
    body = json.dumps(files).encode()
    listings = Path(os.environ['FAKE_CALLS']).read_text().count('GET /jobs/1/files\n')
    if mode == 'inventory-unavailable' or mode == 'inventory-loading' and listings <= 2:
        status, body = '503', b'{"error":"complete file inventory is being prepared"}'
elif path.endswith('archive.zip'):
    body = b'complete'
    if mode == 'bad-hash': body = b'incorrec'
    if mode in ('short', 'transport'): body = b'par'
    if mode in ('transport', 'full-body-error'): exit_code = 16
    if mode == 'http': status, body = '404', b'not found'
elif mode in ('case-names', 'aliased-directories', 'literal-name') and '/files/' in path:
    body = b'other' if path.endswith('/a.out') or path.endswith('/A/result.out') else b'first'
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
    def fetch(self, mode, dest, root, extra_env=None):
        bin_dir = root / 'bin'
        bin_dir.mkdir(exist_ok=True)
        curl = bin_dir / 'curl'
        curl.write_text(FAKE_CURL)
        curl.chmod(0o755)
        env = os.environ | {'PATH': str(bin_dir) + os.pathsep + os.environ['PATH'],
                            'LAB_PASSWORD': 'fixture', 'LAB_URL': 'https://fixture.invalid',
                            'FAKE_MODE': mode, 'FAKE_CALLS': str(root / 'calls')}
        env.update(extra_env or {})
        return subprocess.run(['bash', str(ROOT / 'deploy/lab'), 'fetch', '1', str(dest)],
                              env=env, capture_output=True, text=True)

    def test_loading_inventory_is_retried_before_fetching_the_complete_array(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dest = root / 'download'
            result = self.fetch('inventory-loading', dest, root)
            self.assertEqual(0, result.returncode, result.stderr)
            calls = (root / 'calls').read_text()
            self.assertEqual(3, calls.count('GET /jobs/1/files\n'))
            self.assertEqual(b'complete', (dest / 'preparation/archive.zip').read_bytes())
            self.assertIn('DELETE /jobs/1/hold/ab12', calls)

    def test_unavailable_inventory_has_a_bounded_retry_and_never_claims_empty_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dest = root / 'download'
            result = self.fetch('inventory-unavailable', dest, root, {'LAB_FILE_LIST_TIMEOUT': '0'})
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((dest / 'preparation/archive.zip').exists())
            self.assertIn('complete file inventory unavailable', result.stderr)
            self.assertIn('DELETE /jobs/1/hold/ab12', (root / 'calls').read_text())

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


    def test_checksum_replaces_same_size_corrupt_resume_and_rejects_wrong_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dest = root / 'download'
            (dest / 'preparation').mkdir(parents=True)
            archive = dest / 'preparation/archive.zip'
            archive.write_bytes(b'incorrec')
            fixed = self.fetch('hashed', dest, root)
            self.assertEqual(0, fixed.returncode, fixed.stderr)
            self.assertEqual(b'complete', archive.read_bytes())
            archive.unlink()
            wrong = self.fetch('bad-hash', dest, root)
            self.assertNotEqual(0, wrong.returncode)
            self.assertFalse(archive.exists())
            missing = json.loads((dest / 'lab-fetch-missing.json').read_text())['missing'][0]
            self.assertEqual('checksum-mismatch', missing['reason'])
            self.assertEqual(hashlib.sha256(b'complete').hexdigest(), missing['expectedSha256'])
            self.assertEqual(hashlib.sha256(b'incorrec').hexdigest(), missing['receivedSha256'])
            self.assertFalse(list(dest.rglob('.lab-fetch-*')))
            self.assertIn('DELETE /jobs/1/hold/ab12', (root / 'calls').read_text())

    def test_case_variants_are_preserved_or_destination_is_rejected_before_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'probe').write_bytes(b'')
            folded = (root / 'PROBE').exists()
            dest = root / 'download'
            result = self.fetch('case-names', dest, root)
            if folded:
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((dest / 'A.out').exists())
                missing = json.loads((dest / 'lab-fetch-missing.json').read_text())['missing'][0]
                self.assertEqual('destination-case-collision', missing['reason'])
            else:
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(b'first', (dest / 'A.out').read_bytes())
                self.assertEqual(b'other', (dest / 'a.out').read_bytes())
            self.assertFalse(list(dest.glob('.lab-fetch-*')))
            self.assertIn('DELETE /jobs/1/hold/ab12', (root / 'calls').read_text())

    def test_final_audit_detects_later_write_through_an_existing_directory_alias(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dest = root / 'download'
            (dest / 'a').mkdir(parents=True)
            if (dest / 'A').exists():
                self.skipTest('case-insensitive filesystem is covered by the preflight test')
            (dest / 'A').symlink_to('a', target_is_directory=True)
            result = self.fetch('aliased-directories', dest, root)
            self.assertNotEqual(0, result.returncode)
            missing = json.loads((dest / 'lab-fetch-missing.json').read_text())['missing']
            self.assertTrue(any(row['path'] == 'a/result.out' and
                                row['reason'] == 'retained-checksum-mismatch' for row in missing))

    def test_literal_filename_survives_listing_and_component_url_encoding(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dest = root / 'download'
            result = self.fetch('literal-name', dest, root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b'first', (dest / 'names/a b\t%#\n').read_bytes())
