#!/usr/bin/env python3
"""Bounded resource probe, also usable natively on the Mac. It never connects to the lab or a provider."""

import argparse
import json
import os
from pathlib import Path
import resource
import subprocess
import sys
import time


def cgroup_file(name):
    path = Path("/sys/fs/cgroup") / name
    return path.read_text().strip() if path.is_file() else None


def counters(name):
    value = cgroup_file(name)
    return dict(line.split() for line in value.splitlines()) if value else {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=float, default=12)
    parser.add_argument("--children", type=int, default=3)
    parser.add_argument("--oom", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 120 or not 1 <= args.children <= 8:
        parser.error("seconds must be 1..120 and children 1..8")
    if args.oom:
        # Only run this mode in the bounded Linux container. Fail before allocating on a native host.
        limit = cgroup_file("memory.max")
        if limit is None or limit == "max" or int(limit) > 512 * 1024 * 1024:
            parser.error("OOM probe requires a cgroup memory.max of at most 512 MiB")
        print(json.dumps({"oomProbe": True, "memoryMax": limit}), flush=True)
        allocation = bytearray(int(limit) * 2)
        print(len(allocation))
        return 1

    before = counters("cpu.stat")
    start = time.monotonic()
    children = []
    try:
        # Detached sessions exercise cleanup of descendants outside the immediate process group.
        program = "import time,sys; end=time.monotonic()+float(sys.argv[1]); x=1\nwhile time.monotonic()<end: x=(x*3+1)%1000003"
        for _ in range(args.children):
            children.append(subprocess.Popen([sys.executable, "-c", program, str(args.seconds)], start_new_session=True))
        exits = [child.wait() for child in children]
    finally:
        for child in children:
            if child.poll() is None:
                child.kill()
                child.wait()
    elapsed = time.monotonic() - start
    own = resource.getrusage(resource.RUSAGE_SELF)
    child_use = resource.getrusage(resource.RUSAGE_CHILDREN)
    cpu = own.ru_utime + own.ru_stime + child_use.ru_utime + child_use.ru_stime
    print(json.dumps({
        "platform": sys.platform,
        "cpuMax": cgroup_file("cpu.max"),
        "memoryMax": cgroup_file("memory.max"),
        "cpuBefore": before,
        "cpuAfter": counters("cpu.stat"),
        "memoryEvents": counters("memory.events"),
        "wallSeconds": elapsed,
        "cpuSeconds": cpu,
        "cpuRate": cpu / elapsed,
        "children": len(children),
        "childExits": exits,
        "pid": os.getpid(),
        "enforcement": "hard-quota" if cgroup_file("cpu.max") else "advisory-native",
    }), flush=True)
    return int(any(exits))


if __name__ == "__main__":
    sys.exit(main())
