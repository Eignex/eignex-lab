#!/usr/bin/env python3
"""Render private Nomad Autoscaler configuration. Provisioning credentials stay in the target plugin."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import platform
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent


def render(settings, capacity, output):
    if not output.is_absolute():
        raise ValueError('private output directory must be absolute')
    pools = {pool['id']: pool for pool in capacity['pools']}
    if set(pools) != {target['pool'] for target in settings['targets']}:
        raise ValueError('configure exactly one target for every capacity pool')
    if len(settings['targets']) != len(pools):
        raise ValueError('duplicate target pool')
    token = Path(settings['capacityTokenFile']).read_text().strip()
    if len(token) < 32:
        raise ValueError('private capacity key too short')
    files = {}
    policies = output / 'policies'
    agent = {'log_level': 'WARN', 'log_json': True, 'http': {'bind_address': '127.0.0.1', 'bind_port': settings.get('healthPort', 18092)},
             'nomad': settings['nomad'] | {'token': Path(settings['nomadTokenFile']).read_text().strip()},
             'policy': {'dir': str(policies), 'source': {'nomad': {'enabled': False}}},
             'apm': {'lab-demand': {'driver': 'prometheus', 'config': {'address': settings['coordinator'].rstrip('/') + '/capacity', 'header_Authorization': 'Bearer ' + token}}},
             'target': {}, 'strategy': {'pass-through': {'driver': 'pass-through'}}}
    if agent['nomad'].get('skip_verify') or not agent['nomad'].get('address', '').startswith('https://'):
        raise ValueError('Nomad TLS verification required')
    for target in settings['targets']:
        pool = pools[target['pool']]
        driver = target['driver']
        if driver not in ('aws-asg', 'gce-mig'):
            raise ValueError('unsupported reference target')
        name = 'pool-' + pool['id']
        agent['target'][name] = {'driver': driver, 'config': target.get('config', {})}
        options = target['options'] | {'node_class': pool['nodeClass'], 'node_selector_strategy': 'empty',
            'node_drain_deadline': '60s', 'node_purge': 'true', 'node_drain_ignore_system_jobs': 'false'}
        files[Path('policies') / (pool['id'] + '.json')] = {'scaling': {pool['id']: {'enabled': True, 'min': 0, 'max': pool['maxNodes'], 'policy': {
            'cooldown': '30s', 'evaluation_interval': '10s', 'on_check_error': 'fail',
            'check': {'fresh-demand': {'source': 'lab-demand', 'query': 'lab_capacity_desired_nodes{pool="' + pool['id'] + '"}',
                'query_window': 'instant', 'on_error': 'fail', 'strategy': {'pass-through': {}}}},
            'target': {name: options}}}}}
    files[Path('agent.json')] = agent
    return files


def install(output):
    pin = json.loads((HERE / 'versions.json').read_text())['autoscaler']
    system = {'Darwin': 'darwin', 'Linux': 'linux'}[platform.system()]
    arch = {'arm64': 'arm64', 'aarch64': 'arm64', 'x86_64': 'amd64'}[platform.machine()]
    name = f"nomad-autoscaler_{pin['version']}_{system}_{arch}.zip"
    with urllib.request.urlopen(f"https://releases.hashicorp.com/nomad-autoscaler/{pin['version']}/{name}", timeout=60) as response:
        value = response.read()
    if hashlib.sha256(value).hexdigest() != pin['sha256'][system + '_' + arch]:
        raise ValueError('autoscaler checksum mismatch')
    binary = output / ('nomad-autoscaler-' + pin['version'])
    data = zipfile.ZipFile(io.BytesIO(value)).read('nomad-autoscaler')
    if binary.exists() and binary.read_bytes() != data:
        raise ValueError('pinned autoscaler already differs')
    binary.write_bytes(data); binary.chmod(0o700)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('settings', type=Path); parser.add_argument('capacity', type=Path)
    parser.add_argument('output', type=Path); parser.add_argument('--install-runtime', action='store_true')
    args = parser.parse_args()
    files = render(json.loads(args.settings.read_text()), json.loads(args.capacity.read_text()), args.output)
    args.output.mkdir(mode=0o700, parents=True, exist_ok=True); args.output.chmod(0o700)
    for relative, value in files.items():
        path = args.output / relative; path.parent.mkdir(mode=0o700, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + '\n'); path.chmod(0o600)
    if args.install_runtime:
        install(args.output)


if __name__ == '__main__':
    main()
