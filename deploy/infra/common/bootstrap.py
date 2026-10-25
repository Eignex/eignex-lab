#!/usr/bin/env python3
"""Provider-neutral Linux host preparation. The worker has no access to this configuration or identity."""
import json
import os
import re
from pathlib import Path
import subprocess
import sys

ROOT = Path('/etc/eignex-lab')


def render(config, secret, instance_id, probe, cpus, memory_mib):
    if probe.get('detected') != '1' or probe.get('arch') != config['architecture']:
        raise ValueError('worker architecture or capability detection mismatch')
    if not set(config['minimumFeatures']).issubset(probe.get('features', '').split()):
        raise ValueError('worker SIMD capability mismatch')
    if cpus != config['vcpus'] or memory_mib < config['memoryMiB'] * .95:
        raise ValueError('worker shape differs from the homogeneous pool')
    if not config['servers'] or not re.fullmatch(r'[^\s]+@sha256:[0-9a-f]{64}', config['workerImage']):
        raise ValueError('explicit servers and immutable worker image required')
    for field in ('ca', 'certificate', 'privateKey'):
        if not secret.get(field, '').startswith('-----BEGIN '):
            raise ValueError('mutual TLS material required')
    return {
        'data_dir': '/var/lib/nomad', 'datacenter': config['datacenter'], 'bind_addr': secret.get('bindAddress', '0.0.0.0'),
        'name': config['pool'] + '-' + instance_id.rsplit('/', 1)[-1], 'leave_on_terminate': True,
        'acl': {'enabled': True}, 'tls': {'http': True, 'rpc': True, 'verify_server_hostname': True, 'verify_https_client': True,
            'ca_file': str(ROOT / 'ca.pem'), 'cert_file': str(ROOT / 'client.pem'), 'key_file': str(ROOT / 'client-key.pem')},
        'client': {'enabled': True, 'servers': config['servers'], 'node_class': config['nodeClass'],
            'meta': {'lab_capacity_id': instance_id, 'lab_pool': config['pool'], 'lab_cpu_model': probe.get('model', '')},
            'reserved': {'cpu': config.get('reservedMHz', 2000), 'memory': 1024},
            'host_volume': {'lab-status': {'path': '/run/eignex-lab/status', 'read_only': True}},
            'drain_on_shutdown': {'deadline': '60s', 'force': False, 'ignore_system_jobs': False}},
        'consul': {'auto_advertise': False, 'server_auto_join': False, 'client_auto_join': False},
        'plugin': [{'raw_exec': {'config': [{'enabled': False}]}}, {'docker': {'config': [{'allow_privileged': False, 'volumes': {'enabled': False}}]}}],
    }


def install(config, secret, instance_id):
    output = subprocess.check_output(['sh', '/opt/eignex-lab/node-capabilities.sh'], text=True)
    probe = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    memory = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemTotal:'))) // 1024
    agent = render(config, secret, instance_id, probe, os.cpu_count(), memory)
    # Images are baked with the complete toolchain and worker, avoiding network-dependent boot-time builds.
    subprocess.run(['/usr/local/bin/nomad', 'version'], check=True, capture_output=True)
    subprocess.run(['docker', 'image', 'inspect', config['workerImage']], check=True, capture_output=True)
    ROOT.mkdir(mode=0o700, exist_ok=True); ROOT.chmod(0o700)
    for name, value in [('ca.pem', secret['ca']), ('client.pem', secret['certificate']), ('client-key.pem', secret['privateKey']), ('client.json', json.dumps(agent))]:
        path = ROOT / name; path.write_text(value); path.chmod(0o600)
    # Block container access to metadata/host IAM while allowing GCP DNS (UDP/TCP 53).
    for port in ('80', '443'):
        rule = ['-d', '169.254.169.254/32', '-p', 'tcp', '--dport', port, '-j', 'REJECT']
        if subprocess.run(['iptables', '-C', 'DOCKER-USER', *rule], capture_output=True).returncode:
            subprocess.run(['iptables', '-I', 'DOCKER-USER', '1', *rule], check=True)
    network = secret.get('networkCommand')
    if network:
        if not isinstance(network, list) or not network[0].startswith('/'):
            raise ValueError('private network join must be an explicit trusted executable argv')
        subprocess.run(network, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    Path('/run/eignex-lab/status').mkdir(mode=0o755, parents=True, exist_ok=True)
    subprocess.run(['systemctl', 'enable', '--now', 'nomad-client.service', 'lab-interruption.service', 'lab-lifetime.timer'], check=True)


if __name__ == '__main__':
    # A provider wrapper passes a secret via stdin; it never appears in user data, argv or logs.
    install(json.loads(Path(sys.argv[1]).read_text()), json.load(sys.stdin), sys.argv[2])
