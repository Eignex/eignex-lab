#!/usr/bin/env python3
"""Host-only interruption adapter. Drain through the client's RPC identity; no node-write ACL on workers."""
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request


def metadata(path, headers=None, method='GET'):
    request = urllib.request.Request('http://169.254.169.254/' + path, method=method, headers=headers or {})
    with urllib.request.urlopen(request, timeout=2) as response:
        return response.read().decode()


def interrupted(provider):
    if provider == 'aws':
        token = metadata('latest/api/token', {'X-aws-ec2-metadata-token-ttl-seconds': '60'}, 'PUT')
        headers = {'X-aws-ec2-metadata-token': token}
        try:
            metadata('latest/meta-data/spot/instance-action', headers)
            return True
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            return False
    if provider == 'gcp':
        return metadata('computeMetadata/v1/instance/preempted', {'Metadata-Flavor': 'Google'}).strip().lower() == 'true'
    raise ValueError('unknown infrastructure interruption adapter')


def main():
    config = json.loads(Path('/etc/eignex-lab/node.json').read_text())
    while True:
        try:
            if interrupted(config['provider']):
                Path('/run/eignex-lab/status/draining').touch(mode=0o644)
                # Nomad drain_on_shutdown disables scheduling immediately, then gives the current iteration 60s.
                subprocess.run(['systemctl', 'stop', 'nomad-client.service'], check=True, timeout=90)
                return
        except Exception as error:
            print('interruption adapter held: ' + type(error).__name__, flush=True)
        time.sleep(5)


if __name__ == '__main__':
    main()
