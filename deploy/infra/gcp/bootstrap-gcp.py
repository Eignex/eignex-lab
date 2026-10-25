#!/usr/bin/env python3
"""Host identity bootstrap with metadata ADC; no service-account private key is created."""
import base64
import json
import subprocess
import sys
import urllib.request


def metadata(path):
    request = urllib.request.Request('http://169.254.169.254/computeMetadata/v1/' + path, headers={'Metadata-Flavor':'Google'})
    with urllib.request.urlopen(request, timeout=3) as response:
        return response.read().decode()


secret, config = sys.argv[1:]
project = metadata('project/project-id'); zone = metadata('instance/zone').rsplit('/', 1)[-1]
name = metadata('instance/name')
token = json.loads(metadata('instance/service-accounts/default/token'))['access_token']
request = urllib.request.Request('https://secretmanager.googleapis.com/v1/' + secret + '/versions/latest:access', headers={'Authorization':'Bearer ' + token})
with urllib.request.urlopen(request, timeout=5) as response:
    value = base64.b64decode(json.load(response)['payload']['data'])
instance = f'projects/{project}/zones/{zone}/instances/{name}'
subprocess.run(['python3', '/opt/eignex-lab/bootstrap.py', config, instance], input=value, check=True)
