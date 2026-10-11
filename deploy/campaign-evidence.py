#!/usr/bin/env python3
"""Package and verify active campaign evidence without extracting archives."""

import argparse
import gzip
import hashlib
import json
import re
import tarfile
import tempfile
from pathlib import Path, PurePosixPath

ROLES = ('specifications', 'raw', 'profiles', 'provenance', 'summaries', 'validation')


def digest(stream):
    return hashlib.file_digest(stream, 'sha256').hexdigest()


def file_digest(path):
    with path.open('rb') as stream:
        return digest(stream)


def safe_name(name):
    path = PurePosixPath(name)
    if not name or name == '.' or path.is_absolute() or '..' in path.parts or path.as_posix() != name or '\\' in name:
        raise ValueError(f'unsafe path: {name!r}')
    return name


def catalog_check(catalog, files):
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,63}', catalog['campaign']):
        raise ValueError('campaign must be a lowercase slug')
    if not catalog.get('purpose') or not catalog.get('references') or not catalog.get('limitations'):
        raise ValueError('catalog requires purpose, references and limitations')
    for role in ROLES:
        paths = catalog['roles'][role]
        if not paths and not catalog.get('omissions', {}).get(role):
            raise ValueError(f'{role} requires paths or an explicit omission reason')
        for name in paths:
            if safe_name(name) not in files:
                raise ValueError(f'missing {role} file: {name}')


def verify(archive, manifest):
    data = json.loads(manifest.read_text())
    if data['version'] != 1:
        raise ValueError('unsupported manifest version')
    if archive.name != safe_name(data['archive']['name']):
        raise ValueError('archive name differs from manifest')
    if archive.stat().st_size != data['archive']['size'] or file_digest(archive) != data['archive']['sha256']:
        raise ValueError('archive hash or size differs from manifest')
    expected = data['files']
    for name in expected:
        safe_name(name)
    seen = set()
    with tarfile.open(archive, 'r:gz') as bundle:
        for member in bundle:
            name = safe_name(member.name)
            if not member.isfile() or name in seen or name not in expected:
                raise ValueError(f'unexpected, duplicate or nonregular member: {name}')
            seen.add(name)
            with bundle.extractfile(member) as stream:
                if member.size != expected[name]['size'] or digest(stream) != expected[name]['sha256']:
                    raise ValueError(f'file hash or size differs: {name}')
        if seen != set(expected):
            raise ValueError(f'missing members: {sorted(set(expected) - seen)}')
        with bundle.extractfile('catalog.json') as stream:
            catalog = json.load(stream)
    catalog_check(catalog, expected)
    if catalog != data['catalog']:
        raise ValueError('catalog differs from manifest')
    return len(seen)


def pack(root, output):
    root = root.resolve()
    output = output.resolve()
    if output == root or root in output.parents:
        raise ValueError('output must be outside the staged evidence tree')
    paths = {}
    for path in sorted(root.rglob('*')):
        name = safe_name(path.relative_to(root).as_posix())
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise ValueError(f'nonregular staged path: {name}')
        if path.is_file():
            paths[name] = path
    catalog = json.loads(paths['catalog.json'].read_text())
    catalog_check(catalog, paths)
    files = {name: {'size': path.stat().st_size, 'sha256': file_digest(path)} for name, path in paths.items()}
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output) as staging:
        staging = Path(staging)
        temp = staging / 'bundle.tar.gz'
        with temp.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w', format=tarfile.PAX_FORMAT) as bundle:
                for name, path in paths.items():
                    info = tarfile.TarInfo(name)
                    info.size = files[name]['size']
                    info.mode = 0o644
                    with path.open('rb') as stream:
                        bundle.addfile(info, stream)
        sha = file_digest(temp)
        stem = f"{catalog['campaign']}-{sha}"
        archive = staging / f'{stem}.tar.gz'
        temp.rename(archive)
        manifest = staging / f'{stem}.manifest.json'
        manifest.write_text(json.dumps({'version': 1, 'catalog': catalog, 'files': files,
                                       'archive': {'name': archive.name, 'size': archive.stat().st_size,
                                                   'sha256': sha}}, indent=2, sort_keys=True) + '\n')
        verify(archive, manifest)
        # Exclusive creation keeps published snapshot names from being overwritten.
        destinations = [output / archive.name, output / manifest.name]
        if any(path.exists() for path in destinations):
            raise ValueError('snapshot already exists')
        created = []
        try:
            for source, target in zip((archive, manifest), destinations):
                with target.open('xb') as destination:
                    created.append(target)
                    with source.open('rb') as stream:
                        while block := stream.read(1024 * 1024):
                            destination.write(block)
        except BaseException:
            for path in created:
                path.unlink()
            raise
    return destinations


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    prepare = sub.add_parser('pack')
    prepare.add_argument('root', type=Path)
    prepare.add_argument('output', type=Path)
    check = sub.add_parser('verify')
    check.add_argument('archive', type=Path)
    check.add_argument('manifest', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'pack':
            for path in pack(args.root, args.output):
                print(path)
        else:
            print(f'verified {verify(args.archive, args.manifest)} files')
    except (ValueError, KeyError, OSError, tarfile.TarError, json.JSONDecodeError) as error:
        parser.exit(1, f'{error}\n')


if __name__ == '__main__':
    main()
