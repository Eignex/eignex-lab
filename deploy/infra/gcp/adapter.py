#!/usr/bin/env python3
"""Mac-side GCP inventory/renewal adapter. ADC/impersonation, never downloaded service-account keys."""
import argparse
import json
from pathlib import Path
import sys
import threading
import time
import urllib.request
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'common'))
from capacity_contract import check as check_contract
from capacity_files import write_atomic
from inventory import observation
from main import Cloud


def inventory(cloud, at=None):
    c = cloud.config; group = cloud.owned()
    items = []; token = None
    while True:
        page = cloud.request('POST', cloud.group + '/listManagedInstances', params={'pageToken':token} if token else {})
        items.extend(page.get('managedInstances', [])); token = page.get('nextPageToken')
        if not token:
            break
    instances = [((item.get('instance') or 'booting-' + (item.get('name') or str(index))).removeprefix('https://www.googleapis.com/compute/v1/'),
        item.get('currentAction') in ('DELETING', 'ABANDONING')) for index, item in enumerate(items)]
    errors = [error.get('message', 'capacity unavailable')[:500] for item in items for error in item.get('lastAttempt', {}).get('errors', {}).get('errors', [])]
    return observation(c['POOL'], instances, group['targetSize'], '; '.join(errors), at)


def publish_inventory(cloud, config):
    started = time.monotonic(); value = inventory(cloud)
    if time.monotonic() - started > 20:
        raise ValueError('inventory freshness bound exceeded')
    write_atomic(config['inventoryFile'], value)


def renew_capacity(config, identity, refresh):
    key = Path(config['capacityTokenFile']).read_text().strip()
    request = urllib.request.Request(config['coordinator'].rstrip('/') + '/capacity/grant/' + config['POOL'], headers={'Authorization':'Bearer ' + key})
    with urllib.request.urlopen(request, timeout=10) as response:
        grant = json.load(response)
    refresh()
    request = urllib.request.Request(config['renewUrl'], method='POST', data=json.dumps(grant).encode(),
        headers={'Authorization':'Bearer ' + identity.token, 'Content-Type':'application/json'})
    with urllib.request.urlopen(request, timeout=10) as response:
        json.load(response)


def main():
    import google.auth
    from google.auth import impersonated_credentials
    from google.auth.transport.requests import AuthorizedSession, Request
    parser = argparse.ArgumentParser(); parser.add_argument('config',type=Path); parser.add_argument('--once',action='store_true')
    args = parser.parse_args(); config = json.loads(args.config.read_text())
    # With the lab's pool contract (`eignex-lab capacity-export`), this pool and its limit must be the lab's.
    check_contract(config.get('contract'), config['POOL'], maxNodes=int(config['MAX_NODES']))
    source, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/cloud-platform'])
    credentials = impersonated_credentials.Credentials(source_credentials=source, target_principal=config['serviceAccount'],
        target_scopes=['https://www.googleapis.com/auth/cloud-platform'], lifetime=600)
    cloud = Cloud(AuthorizedSession(credentials), config)
    identity = impersonated_credentials.IDTokenCredentials(credentials, target_audience=config['renewUrl'], include_email=True)
    inventory_step = lambda: publish_inventory(cloud, config)
    renewal_step = lambda: renew_capacity(config, identity, lambda: identity.refresh(Request()))
    if args.once:
        try:
            inventory_step(); renewal_step()
        except Exception as error:
            print('GCP capacity adapter held: ' + type(error).__name__, flush=True)
            raise SystemExit(1)
        return
    def run(operation):
        while True:
            try:
                operation()
            except Exception as error:
                print('GCP capacity adapter held: ' + type(error).__name__, flush=True)
            time.sleep(10)
    # Publishing inventory must never wait for the coordinator or the renewal function, as on AWS: a slow renewal
    # would otherwise age the inventory past the coordinator's freshness bound.
    threading.Thread(target=run, args=(inventory_step,), name='gcp-inventory', daemon=True).start()
    run(renewal_step)


if __name__ == '__main__':
    main()
