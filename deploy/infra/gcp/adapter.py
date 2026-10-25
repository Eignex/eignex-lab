#!/usr/bin/env python3
"""Mac-side GCP inventory/renewal adapter. ADC/impersonation, never downloaded service-account keys."""
import argparse
import json
from pathlib import Path
import sys
import time
import urllib.request
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'common'))
from capacity_files import write_atomic
from main import Cloud


def inventory(cloud, at=None):
    c = cloud.config; group = cloud.owned()
    items = []; token = None
    while True:
        page = cloud.request('POST', cloud.group + '/listManagedInstances', params={'pageToken':token} if token else {})
        items.extend(page.get('managedInstances', [])); token = page.get('nextPageToken')
        if not token:
            break
    instances = [{'id':(item.get('instance') or 'booting-' + (item.get('name') or str(index))).removeprefix('https://www.googleapis.com/compute/v1/'), 'pool':c['POOL'],
        'draining':item.get('currentAction') in ('DELETING', 'ABANDONING'),
        'countedInTarget':item.get('currentAction') not in ('DELETING', 'ABANDONING')} for index, item in enumerate(items)]
    for i in range(max(0, group['targetSize'] - sum(i['countedInTarget'] for i in instances))):
        instances.append({'id':f'booting-{i}', 'pool':c['POOL']})
    errors = [error.get('message', 'capacity unavailable')[:500] for item in items for error in item.get('lastAttempt', {}).get('errors', {}).get('errors', [])]
    return {'observedAt':int((time.time() if at is None else at)*1000), 'pools':[c['POOL']], 'instances':instances,
        'failures':{c['POOL']:'; '.join(errors)[:500]} if errors else {}}


def main():
    import google.auth
    from google.auth import impersonated_credentials
    from google.auth.transport.requests import AuthorizedSession, Request
    parser = argparse.ArgumentParser(); parser.add_argument('config',type=Path); parser.add_argument('--once',action='store_true')
    args = parser.parse_args(); config = json.loads(args.config.read_text())
    source, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/cloud-platform'])
    credentials = impersonated_credentials.Credentials(source_credentials=source, target_principal=config['serviceAccount'],
        target_scopes=['https://www.googleapis.com/auth/cloud-platform'], lifetime=600)
    cloud = Cloud(AuthorizedSession(credentials), config)
    identity = impersonated_credentials.IDTokenCredentials(credentials, target_audience=config['renewUrl'], include_email=True)
    while True:
        try:
            started = time.monotonic(); value = inventory(cloud)
            if time.monotonic() - started > 20:
                raise ValueError('inventory freshness bound exceeded')
            write_atomic(config['inventoryFile'], value)
            key = Path(config['capacityTokenFile']).read_text().strip()
            request = urllib.request.Request(config['coordinator'].rstrip('/') + '/capacity/grant/' + config['POOL'], headers={'Authorization':'Bearer ' + key})
            with urllib.request.urlopen(request, timeout=10) as response:
                grant = json.load(response)
            identity.refresh(Request())
            request = urllib.request.Request(config['renewUrl'], method='POST', data=json.dumps(grant).encode(),
                headers={'Authorization':'Bearer ' + identity.token, 'Content-Type':'application/json'})
            with urllib.request.urlopen(request, timeout=10) as response:
                json.load(response)
        except Exception as error:
            print('GCP capacity adapter held: ' + type(error).__name__, flush=True)
            if args.once:
                raise SystemExit(1)
        if args.once:
            break
        time.sleep(10)


if __name__ == '__main__':
    main()
