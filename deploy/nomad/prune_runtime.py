#!/usr/bin/env python3
"""Prune obsolete lab runtime files, never job results or Nomad allocation state.

Dry-run is the default. Every deletion rechecks live references. Failed/cancelled
Nomad recipes remain pins too: an explicit retry still needs its frozen runtime.
Only release directories and named deployment leftovers are eligible.
"""
import argparse
import json
from pathlib import Path
import plistlib
import shutil
import sqlite3
import subprocess
from urllib.parse import quote


LEFTOVERS = (
    "release-*.zip", "queue-before-*.json", "native-services-before-*.json",
    "lab.env.before-*", "before-queue-efficiency-*", "before-gradle-cache-*",
    "before-git-archive-*", "activate_cutover_release.py",
)


def references(data, processes=None, service_paths=None):
    """Return private reference text in memory only; reports contain names/reasons."""
    result = []
    state = data / "nomad"
    for name in ("queue.json", "native-services.json", "coordinator.json", "capacity.json"):
        path = state / name
        if path.is_file():
            result.append((name, path.read_text()))
    if service_paths is None:
        service_paths = list(Path("/Library/LaunchDaemons").glob("com.eignex.lab.*.plist"))
        service_paths += list((Path.home() / "Library/LaunchAgents").glob("com.eignex.lab.*.plist"))
    for path in service_paths:
        result.append((path.name, json.dumps(plistlib.loads(path.read_bytes()))))
    if processes is None:
        processes = subprocess.run(["ps", "-axo", "command="], check=True, capture_output=True,
                                   text=True, timeout=15).stdout
    result.append(("running process", processes))
    database = data / "lab.db"
    if database.is_file():
        with sqlite3.connect("file:" + quote(str(database)) + "?mode=ro", uri=True) as db:
            tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if "execution_outbox" in tables:
                for payload, in db.execute("""SELECT o.payload FROM execution_outbox o
                    JOIN execution_sessions s ON s.id=o.session_id JOIN jobs j ON j.id=s.job_id
                    WHERE s.desired!='stop' OR o.acknowledged=0 OR j.status IN ('QUEUED','RUNNING')"""):
                    result.append(("live session or pending intent", payload))
            if "execution_queue_recipes" in tables:
                for settings, in db.execute("""SELECT r.settings FROM execution_queue_recipes r
                    JOIN jobs j ON j.id=r.job_id WHERE j.status IN ('QUEUED','RUNNING','FAILED','CANCELLED')"""):
                    result.append(("frozen active or retryable recipe", settings))
    return result


def plan(data, processes=None, service_paths=None):
    data = Path(data).resolve()
    releases = data / "releases"
    refs = references(data, processes, service_paths)
    kept = {}
    unused = []
    if releases.is_dir():
        links = [p.resolve() for p in releases.iterdir() if p.is_symlink()]
        for path in sorted(releases.iterdir()):
            if path.is_symlink() or not path.is_dir() or path.name.startswith("."):
                continue
            reasons = {why for why, text in refs if str(path) + "/" in text or str(path) + '"' in text}
            if path.resolve() in links:
                reasons.add("release symlink")
            if reasons:
                kept[path.name] = sorted(reasons)
            else:
                unused.append(str(path))
    leftovers = set()
    state = data / "nomad"
    for pattern in LEFTOVERS:
        for path in state.glob(pattern):
            if not path.is_symlink() and not any(str(path) in text or path.name in text for _, text in refs):
                leftovers.add(str(path))
    return {"data": str(data), "keptReleases": kept, "unusedReleases": unused,
            "unusedDeploymentFiles": sorted(leftovers)}


def apply(data, processes=None, service_paths=None):
    initial = plan(data, processes, service_paths)
    deleted = []
    for filename in initial["unusedReleases"] + initial["unusedDeploymentFiles"]:
        current = plan(data, processes, service_paths)
        if filename not in current["unusedReleases"] + current["unusedDeploymentFiles"]:
            continue
        path = Path(filename)
        if path.is_symlink():
            raise ValueError("refusing to follow a changed runtime symlink")
        # Retain small deployment provenance even when the executable distribution goes.
        metadata = path / "deployment.json"
        if filename in initial["unusedReleases"] and metadata.is_file():
            archive = Path(data) / "deployments" / "retired-runtime"
            archive.mkdir(mode=0o700, parents=True, exist_ok=True)
            value = json.loads(metadata.read_text())
            allowed = {key: value[key] for key in ("revision", "artifactRevision", "workflowRun", "sha256") if key in value}
            (archive / (path.name + ".json")).write_text(json.dumps(allowed, indent=2) + "\n")
        if path.is_dir():
            shutil.rmtree(path)
        elif path.is_file():
            path.unlink()
        deleted.append(filename)
    return {"deleted": deleted, **plan(data, processes, service_paths)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(json.dumps((apply if args.apply else plan)(args.data), indent=2))


if __name__ == "__main__":
    main()
