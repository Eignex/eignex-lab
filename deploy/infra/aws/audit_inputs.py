#!/usr/bin/env python3
"""Read regional input routing and account-level S3 billing without transferring any objects."""
import argparse
import datetime
import json
from pathlib import Path


def audit(session, bucket, region, endpoint_id, start, end):
    ec2 = session.client('ec2', region_name=region)
    s3 = session.client('s3', region_name=region)
    location = s3.get_bucket_location(Bucket=bucket)['LocationConstraint'] or 'us-east-1'
    endpoint, = ec2.describe_vpc_endpoints(VpcEndpointIds=[endpoint_id])['VpcEndpoints']
    valid = (location == region and endpoint['State'] == 'available' and endpoint['VpcEndpointType'] == 'Gateway'
             and endpoint['ServiceName'] == 'com.amazonaws.' + region + '.s3')
    routes = ec2.describe_route_tables(Filters=[dict(Name='vpc-id', Values=[endpoint['VpcId']])])['RouteTables']
    main = next(row['RouteTableId'] for row in routes if any(a.get('Main') for a in row['Associations']))
    explicit = {a['SubnetId']: row['RouteTableId'] for row in routes for a in row['Associations'] if 'SubnetId' in a}
    workers = []
    pages = ec2.get_paginator('describe_instances').paginate(Filters=[
        dict(Name='tag:lab:deployment', Values=['eignex-lab']),
        dict(Name='instance-state-name', Values=['pending', 'running']),
        dict(Name='vpc-id', Values=[endpoint['VpcId']]),
    ])
    for page in pages:
        for reservation in page['Reservations']:
            for instance in reservation['Instances']:
                pool = next((tag['Value'] for tag in instance.get('Tags', []) if tag['Key'] == 'lab:pool'), '')
                if pool.startswith('image-build'):
                    continue
                table = explicit.get(instance['SubnetId'], main)
                workers.append(dict(instance=instance['InstanceId'], pool=pool, routeTable=table,
                                    regionalRoute=table in endpoint['RouteTableIds']))
    billing = session.client('ce', region_name='us-east-1').get_cost_and_usage(
        TimePeriod=dict(Start=start, End=end), Granularity='DAILY', Metrics=['UnblendedCost', 'UsageQuantity'],
        Filter={'Dimensions': {'Key': 'SERVICE', 'Values': ['Amazon Simple Storage Service', 'AWS Data Transfer']}},
        GroupBy=[dict(Type='DIMENSION', Key='SERVICE'), dict(Type='DIMENSION', Key='USAGE_TYPE')])
    return dict(observedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), bucket=bucket, region=location,
                endpoint=endpoint_id, regionalConfigurationValid=valid,
                allObservedWorkersRouted=valid and all(row['regionalRoute'] for row in workers), workers=workers,
                billingScope='account-wide, not attributable to this bucket; recent usage may not have arrived',
                billing=billing['ResultsByTime'])


def main():
    import boto3
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bucket', required=True)
    parser.add_argument('--region', required=True)
    parser.add_argument('--endpoint', required=True)
    parser.add_argument('--start', required=True)
    parser.add_argument('--end', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cli-credentials', action='store_true', help='Use the AWS CLI credential provider, including aws login')
    args = parser.parse_args()
    session = boto3.Session()
    if args.cli_credentials:
        import subprocess
        credentials = json.loads(subprocess.check_output(['aws', 'configure', 'export-credentials', '--format', 'process']))
        session = boto3.Session(aws_access_key_id=credentials['AccessKeyId'], aws_secret_access_key=credentials['SecretAccessKey'],
                                aws_session_token=credentials.get('SessionToken'))
    report = audit(session, args.bucket, args.region, args.endpoint, args.start, args.end)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    args.output.chmod(0o600)
    print(json.dumps({key: value for key, value in report.items() if key != 'billing'}))
    return 0 if report['allObservedWorkersRouted'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
