#!/usr/bin/env python3
"""Attach an existing input bucket to a free regional S3 gateway endpoint, preserving its data and policies."""
import argparse
import json
from pathlib import Path


def statements(bucket, endpoint):
    arn = 'arn:aws:s3:::' + bucket
    network = {'StringEquals': {'aws:SourceVpce': endpoint}}
    return [
        dict(Sid='LabRegionalInputRead', Effect='Allow', Principal='*', Action='s3:GetObject',
             Resource=[arn + '/' + prefix + '/*' for prefix in ('corpus', 'corpus-packs', 'input-blobs', 'build-blobs')], Condition=network),
        dict(Sid='LabRegionalInputMiss', Effect='Allow', Principal='*', Action='s3:ListBucket', Resource=arn, Condition=network),
    ]


def native_policy(bucket):
    arn = 'arn:aws:s3:::' + bucket
    return dict(Version='2012-10-17', Statement=[
        dict(Effect='Allow', Action='s3:GetObject', Resource=[arn + '/corpus/*', arn + '/input-blobs/*', arn + '/corpus-packs/*', arn + '/build-blobs/*']),
        dict(Effect='Allow', Action='s3:ListBucket', Resource=arn),
        dict(Effect='Allow', Action='s3:PutObject', Resource=[arn + '/input-blobs/*', arn + '/corpus-packs/*', arn + '/build-blobs/*'],
             Condition={'StringEquals': {'s3:if-none-match': '*'}}),
    ])


BUILD_ARCHIVE_RULE = 'LabBuildArchiveExpiry'
BUILD_ARCHIVE_DAYS = 30


def lifecycle(rules):
    """Expire cloud build archives, which jobs need only while they run, keeping every other rule as it is."""
    rule = dict(ID=BUILD_ARCHIVE_RULE, Status='Enabled', Filter=dict(Prefix='build-blobs/'),
                Expiration=dict(Days=BUILD_ARCHIVE_DAYS), AbortIncompleteMultipartUpload=dict(DaysAfterInitiation=1))
    return [row for row in rules if row.get('ID') != BUILD_ARCHIVE_RULE] + [rule]


def main():
    import boto3
    from botocore.exceptions import ClientError
    parser = argparse.ArgumentParser()
    parser.add_argument('--bucket', required=True)
    parser.add_argument('--region', required=True)
    parser.add_argument('--vpc', required=True)
    parser.add_argument('--route-table', action='append', required=True)
    parser.add_argument('--native-user', required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    session = boto3.Session(region_name=args.region)
    ec2, s3, iam = (session.client(name) for name in ('ec2', 's3', 'iam'))
    if s3.get_bucket_location(Bucket=args.bucket)['LocationConstraint'] != args.region:
        raise ValueError('bucket and workers must use the same region')
    service = 'com.amazonaws.' + args.region + '.s3'
    existing = ec2.describe_vpc_endpoints(Filters=[dict(Name='vpc-id', Values=[args.vpc]),
                                                dict(Name='service-name', Values=[service])])['VpcEndpoints']
    created = not existing
    if existing:
        endpoint = existing[0]
        if endpoint['VpcEndpointType'] != 'Gateway':
            raise ValueError('input cache requires a gateway endpoint')
        missing = sorted(set(args.route_table) - set(endpoint.get('RouteTableIds', [])))
        if missing:
            ec2.modify_vpc_endpoint(VpcEndpointId=endpoint['VpcEndpointId'], AddRouteTableIds=missing)
    else:
        endpoint = ec2.create_vpc_endpoint(VpcId=args.vpc, ServiceName=service, VpcEndpointType='Gateway',
                                         RouteTableIds=args.route_table,
                                         TagSpecifications=[dict(ResourceType='vpc-endpoint', Tags=[dict(Key='Name', Value='eignex-lab-inputs')])])['VpcEndpoint']
    try:
        policy = json.loads(s3.get_bucket_policy(Bucket=args.bucket)['Policy'])
    except ClientError as failure:
        if failure.response['Error']['Code'] != 'NoSuchBucketPolicy':
            raise
        policy = dict(Version='2012-10-17', Statement=[])
    additions = statements(args.bucket, endpoint['VpcEndpointId'])
    previous = policy.copy()
    managed = {s['Sid'] for s in additions} | {'LabRegionalInputPublish'}
    policy = dict(policy, Statement=[row for row in policy['Statement'] if row.get('Sid') not in managed] + additions)
    args.receipt.write_text(json.dumps(dict(endpoint=endpoint['VpcEndpointId'], created=created, previousPolicy=previous), indent=2) + '\n')
    args.receipt.chmod(0o600)
    s3.put_bucket_policy(Bucket=args.bucket, Policy=json.dumps(policy))
    try:
        rules = s3.get_bucket_lifecycle_configuration(Bucket=args.bucket)['Rules']
    except ClientError as failure:
        if failure.response['Error']['Code'] != 'NoSuchLifecycleConfiguration':
            raise
        rules = []
    s3.put_bucket_lifecycle_configuration(Bucket=args.bucket, LifecycleConfiguration=dict(Rules=lifecycle(rules)))
    iam.put_user_policy(UserName=args.native_user, PolicyName='eignex-lab-input-cache', PolicyDocument=json.dumps(native_policy(args.bucket)))
    print(json.dumps(dict(endpoint=endpoint['VpcEndpointId'], region=args.region, bucket=args.bucket)))


if __name__ == '__main__':
    main()
