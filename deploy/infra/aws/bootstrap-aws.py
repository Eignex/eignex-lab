#!/usr/bin/env python3
import json
import subprocess
import sys
import urllib.request
import boto3
from botocore.config import Config
secret, region, config = sys.argv[1:]
request = urllib.request.Request('http://169.254.169.254/latest/api/token', method='PUT', headers={'X-aws-ec2-metadata-token-ttl-seconds': '60'})
with urllib.request.urlopen(request, timeout=3) as response:
    token = response.read().decode()
request = urllib.request.Request('http://169.254.169.254/latest/meta-data/instance-id', headers={'X-aws-ec2-metadata-token': token})
with urllib.request.urlopen(request, timeout=3) as response:
    instance = response.read().decode()
client = boto3.client('secretsmanager', region_name=region, config=Config(connect_timeout=3, read_timeout=5, retries={'total_max_attempts': 3}))
value = client.get_secret_value(SecretId=secret)['SecretString']
subprocess.run(['python3', '/opt/eignex-lab/bootstrap.py', config, instance], input=value.encode(), check=True)
