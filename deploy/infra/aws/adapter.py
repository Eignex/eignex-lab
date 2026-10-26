#!/usr/bin/env python3
"""Mac-side AWS inventory + renewal adapter. It cannot directly open the group or write leases."""
import argparse
import json
from pathlib import Path
import time
import urllib.request
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
from capacity_files import write_atomic


def inventory(asg, group, deployment, pool, at=None):
    groups = asg.describe_auto_scaling_groups(AutoScalingGroupNames=[group])["AutoScalingGroups"]
    if not groups:
        raise ValueError("pool group missing")
    value = groups[0]
    tags = {item["Key"]: item["Value"] for item in value["Tags"]}
    if tags.get("lab:deployment") != deployment or tags.get("lab:pool") != pool:
        raise ValueError("pool ownership mismatch")
    instances = [{"id": item["InstanceId"], "pool": pool, "draining": item["LifecycleState"].startswith("Terminating"),
                  "countedInTarget": not item["LifecycleState"].startswith("Terminating")} for item in value["Instances"]]
    covered = sum(item["countedInTarget"] for item in instances)
    for i in range(max(0, value["DesiredCapacity"] - covered)):
        instances.append({"id": f"booting-{i}", "pool": pool})
    activities = asg.describe_scaling_activities(AutoScalingGroupName=group, MaxRecords=1)["Activities"]
    failures = {pool: activities[0].get("StatusMessage", "capacity unavailable")[:500]} if activities and activities[0]["StatusCode"] == "Failed" else {}
    return {"observedAt": int((time.time() if at is None else at) * 1000), "pools": [pool], "instances": instances, "failures": failures}



def once(config, asg, functions):
    started = time.monotonic()
    definitions = [config | pool for pool in config["pools"]] if "pools" in config else [config]
    if not definitions or len({pool["pool"] for pool in definitions}) != len(definitions) or len({pool["group"] for pool in definitions}) != len(definitions):
        raise ValueError("configure distinct pools and groups")
    observations = [inventory(asg, pool["group"], pool["deployment"], pool["pool"]) for pool in definitions]
    value = {"observedAt": min(item["observedAt"] for item in observations),
             "pools": [pool["pool"] for pool in definitions],
             "instances": [instance for item in observations for instance in item["instances"]],
             "failures": {pool: reason for item in observations for pool, reason in item["failures"].items()}}
    if time.monotonic() - started > 20:
        raise ValueError("inventory request exceeded freshness bound")
    write_atomic(config["inventoryFile"], value)
    key = Path(config["capacityTokenFile"]).read_text().strip()
    failed = []
    for pool in definitions:
        try:
            request = urllib.request.Request(config["coordinator"].rstrip("/") + "/capacity/grant/" + pool["pool"], headers={"Authorization": "Bearer " + key})
            with urllib.request.urlopen(request, timeout=10) as response:
                grant = json.load(response)
            result = functions.invoke(FunctionName=pool["renewFunction"], InvocationType="RequestResponse", Payload=json.dumps(grant).encode())
            result["Payload"].close()
            if result.get("FunctionError"):
                raise RuntimeError("provider rejected capacity renewal")
        except Exception:
            failed.append(pool["pool"])
    if failed:
        raise RuntimeError("capacity renewal held for " + ", ".join(failed))


def main():
    import boto3
    from botocore.config import Config
    parser = argparse.ArgumentParser(); parser.add_argument("config", type=Path); parser.add_argument("--once", action="store_true")
    args = parser.parse_args(); config = json.loads(args.config.read_text())
    sdk = Config(connect_timeout=3, read_timeout=5, retries={"total_max_attempts": 2})
    session = boto3.Session(profile_name=config.get("profile"), region_name=config["region"])
    asg = session.client("autoscaling", config=sdk); functions = session.client("lambda", config=sdk)
    while True:
        try:
            once(config, asg, functions)
        except Exception as error:
            print(f"AWS capacity adapter held: {type(error).__name__}", flush=True)
            if args.once:
                raise SystemExit(1)
        if args.once:
            break
        time.sleep(10)


if __name__ == "__main__":
    main()
