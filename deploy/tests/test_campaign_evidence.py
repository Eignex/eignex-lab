import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('evidence', Path(__file__).parents[1] / 'campaign-evidence.py')
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class CampaignEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'input'
        self.root.mkdir()
        self.output = Path(self.temp.name) / 'output'
        (self.root / 'record.json').write_text('{"sourceSha256":"retained"}\n')
        self.catalog = {'campaign': 'active-test', 'purpose': 'Verify retention',
                        'references': ['https://github.com/Eignex/klause/issues/2403'],
                        'limitations': ['Storage checks do not validate solver claims.'],
                        'roles': {role: ['record.json'] for role in evidence.ROLES}}
        self.write_catalog()

    def write_catalog(self):
        (self.root / 'catalog.json').write_text(json.dumps(self.catalog))

    def test_snapshot_preserves_every_file_and_is_reproducible(self):
        archive, manifest = evidence.pack(self.root, self.output)
        self.assertEqual(2, evidence.verify(archive, manifest))
        other, _ = evidence.pack(self.root, Path(self.temp.name) / 'other')
        self.assertEqual(archive.read_bytes(), other.read_bytes())
        with tarfile.open(archive) as bundle:
            self.assertEqual((self.root / 'record.json').read_bytes(), bundle.extractfile('record.json').read())

    def test_changed_download_is_rejected(self):
        archive, manifest = evidence.pack(self.root, self.output)
        archive.write_bytes(archive.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'archive hash or size'):
            evidence.verify(archive, manifest)

    def test_missing_required_evidence_is_rejected(self):
        self.catalog['roles']['raw'] = ['missing.json']
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, 'missing raw file'):
            evidence.pack(self.root, self.output)

    def test_absent_profiles_require_an_explanation(self):
        self.catalog['roles']['profiles'] = []
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, 'profiles requires'):
            evidence.pack(self.root, self.output)
        self.catalog['omissions'] = {'profiles': 'This uninstrumented experiment collected no profiles.'}
        self.write_catalog()
        archive, manifest = evidence.pack(self.root, self.output)
        self.assertEqual(2, evidence.verify(archive, manifest))

    def test_symlink_cannot_publish_files_outside_staging(self):
        (self.root / 'outside').symlink_to('/etc/passwd')
        with self.assertRaisesRegex(ValueError, 'nonregular'):
            evidence.pack(self.root, self.output)

    def test_existing_snapshot_cannot_be_overwritten(self):
        archive, manifest = evidence.pack(self.root, self.output)
        original = manifest.read_bytes()
        with self.assertRaisesRegex(ValueError, 'already exists'):
            evidence.pack(self.root, self.output)
        self.assertEqual(original, manifest.read_bytes())
        self.assertEqual(2, evidence.verify(archive, manifest))

    def test_output_cannot_be_part_of_input(self):
        with self.assertRaisesRegex(ValueError, 'outside'):
            evidence.pack(self.root, self.root / 'output')

    def test_unsafe_duplicate_extra_and_missing_archive_members_are_rejected(self):
        archive, manifest = evidence.pack(self.root, self.output)
        original = json.loads(manifest.read_text())
        for names in [('record.json', '../outside'), ('record.json', 'record.json'),
                      ('record.json', 'extra'), ('record.json',)]:
            with self.subTest(names=names):
                with tarfile.open(archive, 'w:gz') as bundle:
                    for name in names:
                        content = (self.root / 'record.json').read_bytes()
                        info = tarfile.TarInfo(name)
                        info.size = len(content)
                        bundle.addfile(info, io.BytesIO(content))
                original['archive']['size'] = archive.stat().st_size
                original['archive']['sha256'] = hashlib.sha256(archive.read_bytes()).hexdigest()
                manifest.write_text(json.dumps(original))
                with self.assertRaises(ValueError):
                    evidence.verify(archive, manifest)

    def test_archive_links_and_changed_member_bytes_are_rejected(self):
        archive, manifest = evidence.pack(self.root, self.output)
        original = json.loads(manifest.read_text())
        for link in (False, True):
            with self.subTest(link=link):
                with tarfile.open(archive, 'w:gz') as bundle:
                    info = tarfile.TarInfo('record.json')
                    if link:
                        info.type = tarfile.SYMTYPE
                        info.linkname = '/etc/passwd'
                        bundle.addfile(info)
                    else:
                        info.size = 7
                        bundle.addfile(info, io.BytesIO(b'changed'))
                original['archive']['size'] = archive.stat().st_size
                original['archive']['sha256'] = hashlib.sha256(archive.read_bytes()).hexdigest()
                manifest.write_text(json.dumps(original))
                with self.assertRaises(ValueError):
                    evidence.verify(archive, manifest)


if __name__ == '__main__':
    unittest.main()
