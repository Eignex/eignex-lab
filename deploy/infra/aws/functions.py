"""AWS-specific capacity lease renewal and independent expiry. Workers cannot invoke renewal."""
import os
import time
from capacity_grants import verify, LEASE_SECONDS


def clients():
    import boto3
    from botocore.config import Config
    config = Config(connect_timeout=3, read_timeout=5, retries={"total_max_attempts": 2})
    return tuple(boto3.client(name, config=config) for name in ("autoscaling", "ec2", "dynamodb", "secretsmanager"))


def owned_group(asg, group, deployment, pool):
    groups = asg.describe_auto_scaling_groups(AutoScalingGroupNames=[group])["AutoScalingGroups"]
    if not groups:
        return None
    value = groups[0]
    tags = {item["Key"]: item["Value"] for item in value["Tags"]}
    if tags.get("lab:deployment") != deployment or tags.get("lab:pool") != pool:
        raise ValueError("capacity group ownership mismatch")
    return value


def renew_pool(grant, asg, ddb, key, table, group, deployment, pool, maximum, clock=time.time):
    verify(grant, key, pool, maximum, int(clock() * 1000))
    current = owned_group(asg, group, deployment, pool)
    if current is None or current["MaxSize"] not in (0, maximum) or current["MinSize"] != 0:
        raise ValueError("capacity group finite limit differs from configuration")
    verify(grant, key, pool, maximum, int(clock() * 1000))
    until = int(clock()) + LEASE_SECONDS
    ddb.put_item(TableName=table, Item={"pool": {"S": pool}, "lease_until": {"N": str(until)}})
    # Persist expiry before opening capacity. A crash here leaves a closed group, never an unleased open one.
    verify(grant, key, pool, maximum, int(clock() * 1000))
    if current["MaxSize"] == 0:
        asg.update_auto_scaling_group(AutoScalingGroupName=group, MinSize=0, MaxSize=maximum)
    return {"leaseUntil": until}


def expire_pool(asg, ec2, ddb, table, group, deployment, pool, clock=time.time):
    at = int(clock())
    row = ddb.get_item(TableName=table, Key={"pool": {"S": pool}}, ConsistentRead=True).get("Item", {})
    try:
        until = int(row.get("lease_until", {}).get("N", "0"))
    except (ValueError, TypeError):
        until = 0
    if at < until <= at + LEASE_SECONDS:
        return {"expired": False}
    current = owned_group(asg, group, deployment, pool)
    if current is not None:
        protected = [item["InstanceId"] for item in current.get("Instances", []) if item.get("ProtectedFromScaleIn")]
        for offset in range(0, len(protected), 50):
            asg.set_instance_protection(AutoScalingGroupName=group, InstanceIds=protected[offset:offset+50], ProtectedFromScaleIn=False)
        # Killing instances alone would cause replacements. Clamp the complete capacity state first.
        asg.update_auto_scaling_group(AutoScalingGroupName=group, MinSize=0, DesiredCapacity=0, MaxSize=0)
    ids = []
    pages = ec2.get_paginator("describe_instances").paginate(Filters=[
        {"Name": "tag:lab:deployment", "Values": [deployment]}, {"Name": "tag:lab:pool", "Values": [pool]},
        {"Name": "instance-state-name", "Values": ["pending", "running", "stopping", "stopped"]},
    ])
    for page in pages:
        ids.extend(item["InstanceId"] for reservation in page["Reservations"] for item in reservation["Instances"])
    for offset in range(0, len(ids), 100):
        ec2.terminate_instances(InstanceIds=ids[offset:offset+100])
    return {"expired": True, "terminated": len(ids)}


def renew(event, _context):
    asg, _ec2, ddb, secrets = clients()
    key = secrets.get_secret_value(SecretId=os.environ["SIGNING_SECRET"])["SecretString"]
    return renew_pool(event, asg, ddb, key, os.environ["TABLE"], os.environ["GROUP"], os.environ["DEPLOYMENT"], os.environ["POOL"], int(os.environ["MAX_NODES"]))


def expire(_event, _context):
    asg, ec2, ddb, _secrets = clients()
    return expire_pool(asg, ec2, ddb, os.environ["TABLE"], os.environ["GROUP"], os.environ["DEPLOYMENT"], os.environ["POOL"])
