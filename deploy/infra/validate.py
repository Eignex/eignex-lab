#!/usr/bin/env python3
"""Credential-free formatting/schema validation with a checksum-pinned OpenTofu binary."""
import hashlib
import io
from pathlib import Path
import platform
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VERSION = '1.10.6'
SHA256 = '15b7bed76420b50da3e121769c43341df8cd57d751ca14e6dbe9c850124c6dac'
if platform.system() != 'Linux' or platform.machine() != 'x86_64':
    raise SystemExit('Use OpenTofu 1.10.6 on this platform; the validation installer pins Linux amd64 only.')
binary = ROOT/'build/infra-tools/tofu'
if not binary.exists():
    with urllib.request.urlopen(f'https://github.com/opentofu/opentofu/releases/download/v{VERSION}/tofu_{VERSION}_linux_amd64.zip', timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError('OpenTofu checksum mismatch')
    binary.parent.mkdir(parents=True, exist_ok=True)
    binary.write_bytes(zipfile.ZipFile(io.BytesIO(data)).read('tofu')); binary.chmod(0o755)
for module in sorted((ROOT/'deploy/infra').glob('*/versions.tf')):
    folder = module.parent
    for command in [('fmt','-check'), ('init','-backend=false','-input=false','-lockfile=readonly'), ('validate','-no-color')]:
        subprocess.run([str(binary), '-chdir=' + str(folder), *command], check=True)
