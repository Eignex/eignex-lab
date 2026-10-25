"""Independent GCP lease renewal/expiry; IAM request.time also fences a stale Mac scaler."""
import base64
import copy
from datetime import datetime, timezone
import json
import os
import time
from urllib.parse import quote
from capacity_grants import verify, LEASE_SECONDS


def timestamp(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')


def renew_binding(policy, role, member, title, until):
    policy = copy.deepcopy(policy)
    bindings = [b for b in policy.get('bindings', []) if b.get('role') == role and member in b.get('members', [])]
    if len(bindings) != 1 or bindings[0].get('members') != [member] or bindings[0].get('condition', {}).get('title') != title:
        raise ValueError('dedicated scaler lease binding missing or widened')
    bindings[0]['condition']['expression'] = "request.time < timestamp('" + timestamp(until) + "')"
    policy['version'] = 3
    # Keep the etag, all other bindings, and unrelated condition data exactly intact.
    if not policy.get('etag'):
        raise ValueError('IAM policy concurrency token required')
    return policy


class Cloud:
    def __init__(self, http, config):
        self.http = http; self.config = config
        self.group = 'https://compute.googleapis.com/compute/v1/projects/' + config['PROJECT'] + '/zones/' + config['ZONE'] + '/instanceGroupManagers/' + config['GROUP']
        self.iam = 'https://cloudresourcemanager.googleapis.com/v1/projects/' + config['PROJECT']
        self.lease = 'https://storage.googleapis.com/storage/v1/b/' + config['BUCKET'] + '/o/' + quote('leases/' + config['POOL'] + '.json', safe='')

    def request(self, method, url, **kwargs):
        response = self.http.request(method, url, timeout=5, **kwargs)
        response.raise_for_status()
        return response.json() if response.content else {}

    def owned(self):
        group = self.request('GET', self.group)
        if group.get('description') != self.config['OWNERSHIP']:
            raise ValueError('capacity group ownership mismatch')
        return group

    def signing_key(self):
        result = self.request('GET', 'https://secretmanager.googleapis.com/v1/' + self.config['SIGNING_SECRET'] + '/versions/latest:access')
        return base64.b64decode(result['payload']['data']).decode()

    def write_lease(self, until):
        url = 'https://storage.googleapis.com/upload/storage/v1/b/' + self.config['BUCKET'] + '/o'
        self.request('POST', url, params={'uploadType':'media', 'name':'leases/' + self.config['POOL'] + '.json'},
            data=json.dumps({'leaseUntil':until}), headers={'Content-Type':'application/json'})

    def read_lease(self):
        response = self.http.request('GET', self.lease, params={'alt':'media'}, timeout=5)
        if response.status_code == 404:
            return 0
        response.raise_for_status()
        try:
            return int(response.json()['leaseUntil'])
        except (ValueError, TypeError, KeyError):
            return 0

    def renew_iam(self, until):
        policy = self.request('POST', self.iam + ':getIamPolicy', json={'options':{'requestedPolicyVersion':3}})
        policy = renew_binding(policy, self.config['SCALER_ROLE'], self.config['SCALER_MEMBER'], self.config['LEASE_TITLE'], until)
        self.request('POST', self.iam + ':setIamPolicy', json={'policy':policy})

    def close(self):
        self.request('POST', self.group + '/resize', params={'size':0})


def renew_pool(grant, cloud, key, clock=time.time):
    c = cloud.config
    def fresh():
        verify(grant, key, c['POOL'], int(c['MAX_NODES']), int(clock()*1000))
    fresh(); cloud.owned(); fresh()
    until = int(clock()) + LEASE_SECONDS
    cloud.write_lease(until)
    fresh(); cloud.renew_iam(until)
    return {'leaseUntil':until}


def expire_pool(cloud, clock=time.time):
    at = int(clock()); until = cloud.read_lease()
    if at < until <= at + LEASE_SECONDS:
        return {'expired':False}
    cloud.owned()
    # The scaler binding expires without this function. Resizing zero stops MIG replacement independently.
    cloud.close()
    return {'expired':True}


def runtime():
    import google.auth
    from google.auth.transport.requests import AuthorizedSession
    credentials, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/cloud-platform'])
    return Cloud(AuthorizedSession(credentials), dict(os.environ))


def renew(request):
    try:
        cloud = runtime()
        return renew_pool(request.get_json(), cloud, cloud.signing_key())
    except ValueError:
        return {'error':'capacity grant or ownership rejected'}, 403


def expire(_request):
    return expire_pool(runtime())
