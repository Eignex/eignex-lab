#!/usr/bin/env python3
"""Mac-side AWS inventory + renewal adapter. It cannot directly open the group or write leases."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import time
import re
import urllib.error
import urllib.request
import sys
import threading
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
from capacity_files import write_atomic


class RenewalError(RuntimeError):
    """Only pool names and bounded error codes, never grants, credentials or provider response bodies."""


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



def definitions(config):
    pools = [config | pool for pool in config["pools"]] if "pools" in config else [config]
    if not pools or len({pool["pool"] for pool in pools}) != len(pools) or len({pool["group"] for pool in pools}) != len(pools):
        raise ValueError("configure distinct pools and groups")
    return pools


def publish_inventory(config, asg):
    started = time.monotonic()
    pools = definitions(config)
    # Each pool is independent; serial SDK calls can age a larger fleet past the
    # collector's freshness bound before its next inventory can be published.
    with ThreadPoolExecutor(max_workers=min(4, len(pools))) as workers:
        observations = list(workers.map(lambda pool: inventory(asg, pool["group"], pool["deployment"], pool["pool"]), pools))
    value = {"observedAt": min(item["observedAt"] for item in observations),
             "pools": [pool["pool"] for pool in pools],
             "instances": [instance for item in observations for instance in item["instances"]],
             "failures": {pool: reason for item in observations for pool, reason in item["failures"].items()}}
    if time.monotonic() - started > 20:
        raise ValueError("inventory request exceeded freshness bound")
    write_atomic(config["inventoryFile"], value)


def renew_capacity(config, functions):
    pools = definitions(config)
    key = Path(config["capacityTokenFile"]).read_text().strip()
    def renew(pool):
        try:
            request = urllib.request.Request(config["coordinator"].rstrip("/") + "/capacity/grant/" + pool["pool"], headers={"Authorization": "Bearer " + key})
            with urllib.request.urlopen(request, timeout=10) as response:
                grant = json.load(response)
            result = functions.invoke(FunctionName=pool["renewFunction"], InvocationType="RequestResponse", Payload=json.dumps(grant).encode())
            try:
                if result.get("FunctionError"):
                    try:
                        kind = json.load(result["Payload"]).get("errorType", "provider_error")
                    except (ValueError, TypeError):
                        kind = "provider_error"
                    if not isinstance(kind, str) or not re.fullmatch(r"[A-Za-z0-9_.]{1,80}", kind):
                        kind = "provider_error"
                    return pool["pool"], kind
            finally:
                result["Payload"].close()
        except urllib.error.HTTPError as error:
            error.close()
            return pool["pool"], f"HTTP_{error.code}"
        except Exception as error:
            # SDK exception messages can include request material. Report the exception class only.
            code = getattr(error, "response", {}).get("Error", {}).get("Code", type(error).__name__)
            if not isinstance(code, str) or not re.fullmatch(r"[A-Za-z0-9_.]{1,80}", code):
                code = type(error).__name__
            return pool["pool"], code
        return None
    with ThreadPoolExecutor(max_workers=min(4, len(pools))) as workers:
        failed = [pool for pool in workers.map(renew, pools) if pool is not None]
    if failed:
        raise RenewalError("capacity renewal held for " + ", ".join(f"{pool}={reason}" for pool, reason in failed))


def once(config, asg, functions):
    publish_inventory(config, asg)
    renew_capacity(config, functions)


def main():
    import boto3
    from botocore.config import Config
    parser = argparse.ArgumentParser(); parser.add_argument("config", type=Path); parser.add_argument("--once", action="store_true")
    args = parser.parse_args(); config = json.loads(args.config.read_text())
    sdk = Config(connect_timeout=3, read_timeout=5, retries={"total_max_attempts": 2})
    session = boto3.Session(profile_name=config.get("profile"), region_name=config["region"])
    asg = session.client("autoscaling", config=sdk); functions = session.client("lambda", config=sdk)
    def run(operation):
        while True:
            try:
                operation()
            except Exception as error:
                detail = f": {error}" if isinstance(error, RenewalError) else ""
                print(f"AWS capacity adapter held: {type(error).__name__}{detail}", flush=True)
            time.sleep(10)
    if args.once:
        try:
            once(config, asg, functions)
        except Exception as error:
            detail = f": {error}" if isinstance(error, RenewalError) else ""
            print(f"AWS capacity adapter held: {type(error).__name__}{detail}", flush=True)
            raise SystemExit(1)
        return
    # Publishing inventory must never wait for coordinator HTTP or provider renewal calls across the fleet.
    threading.Thread(target=run, args=(lambda: publish_inventory(config, asg),), name="aws-inventory", daemon=True).start()
    run(lambda: renew_capacity(config, functions))


if __name__ == "__main__":
    main()
