#!/usr/bin/env python3
"""Bound local Gradle output caches, rotate stopped daemons' logs, bound the lab services' own logs, and follow the
connection's public address when a dynamic DNS settings file is present.

Run periodically as the lab user. Cache eviction uses Gradle's POSIX byte-range
lock without modifying its lock protocol. Active daemons' logs are left alone:
Gradle 9.7 opens them without O_APPEND, so copy/truncate disrupts live readers.
"""
import argparse
import fcntl
import gzip
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import time


def regular(path):
    return stat.S_ISREG(path.lstat().st_mode)


def active_daemons():
    output = subprocess.run(["ps", "-axo", "pid=,command="], check=True,
                            capture_output=True, text=True, timeout=10).stdout
    matches = (re.match(r"\s*(\d+)\s+(.+)", line) for line in output.splitlines())
    return {int(match[1]) for match in matches if match and
            "org.gradle.launcher.daemon.bootstrap.GradleDaemon" in match[2]}


def rotate(path, limit, copies):
    """Keep bounded diagnostic tails, including on the first oversized log."""
    with path.open("r+b") as source:
        size = os.fstat(source.fileno()).st_size
        if size <= limit:
            return False
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".log-rotation-", delete=False) as out:
            temporary = Path(out.name)
            try:
                source.seek(max(0, size - limit))
                with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as zipped:
                    remaining = min(size, limit)
                    while remaining:
                        chunk = source.read(min(remaining, 1024 * 1024))
                        if not chunk:
                            break
                        zipped.write(chunk)
                        remaining -= len(chunk)
                out.flush()
                os.fsync(out.fileno())
                for index in range(copies, 1, -1):
                    previous = path.with_name(path.name + ".%s.gz" % (index - 1))
                    if previous.exists() and regular(previous):
                        previous.replace(path.with_name(path.name + ".%s.gz" % index))
                temporary.replace(path.with_name(path.name + ".1.gz"))
                source.truncate(0)
            finally:
                temporary.unlink(missing_ok=True)
    return True


def clean_home(home, active, cache_bytes, log_bytes, copies, age_days, now):
    report = {"home": str(home), "rotated": 0, "expiredLogs": 0,
              "activeLogsSkipped": 0, "cacheBytesRemoved": 0, "caches": []}
    daemon = home / "daemon"
    if daemon.is_dir() and not daemon.is_symlink():
        for version in daemon.iterdir():
            if not version.is_dir() or version.is_symlink():
                continue
            for path in version.iterdir():
                match = re.fullmatch(r"daemon-(\d+)\.out\.log(?:\.[1-9]\d*\.gz)?", path.name)
                if not match or not regular(path):
                    continue
                if path.name.endswith(".log") and int(match[1]) in active:
                    report["activeLogsSkipped"] += 1
                    continue
                if now - path.stat().st_mtime > age_days * 86400:
                    path.unlink()
                    report["expiredLogs"] += 1
                elif path.name.endswith(".log") and rotate(path, log_bytes, copies):
                    report["rotated"] += 1
    caches = home / "caches"
    if not caches.is_dir() or caches.is_symlink():
        return report
    for cache in sorted(caches.glob("build-cache-*")):
        if not cache.is_dir() or cache.is_symlink():
            continue
        lock_path = cache / (cache.name + ".lock")
        if not lock_path.exists() or not regular(lock_path):
            report["caches"].append({"path": str(cache), "skipped": "missing regular Gradle lock"})
            continue
        with lock_path.open("r+b") as lock:
            try:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                report["caches"].append({"path": str(cache), "skipped": "in use"})
                continue
            # Gradle hashes are hexadecimal strings, sometimes without leading zeroes.
            entries = [(p, p.stat()) for p in cache.iterdir()
                       if re.fullmatch(r"[0-9a-f]{1,32}", p.name) and regular(p)]
            size = sum(s.st_size for _, s in entries)
            for path, info in sorted(entries, key=lambda item: max(item[1].st_atime, item[1].st_mtime)):
                if size <= cache_bytes:
                    break
                path.unlink()
                size -= info.st_size
                report["cacheBytesRemoved"] += info.st_size
            report["caches"].append({"path": str(cache), "bytes": size, "limitBytes": cache_bytes})
    return report


def clean_service_logs(logs, log_bytes, copies, age_days, now):
    """The lab services' logs under [logs]: launchd appends to them, so an in-place truncation is safe while they run.
    A log nothing has written to for [age_days] belongs to a removed service and goes, with its rotated tails."""
    report = {"path": str(logs), "rotated": [], "removed": []}
    if not logs.is_dir():
        return report
    for path in sorted(logs.iterdir()):
        if not regular(path):
            continue
        tail = re.fullmatch(r"(.+\.log)\.[1-9]\d*\.gz", path.name)
        base = logs / tail[1] if tail else path
        if not (tail or path.name.endswith(".log")):
            continue
        if not base.exists() or now - base.lstat().st_mtime > age_days * 86400:
            path.unlink()
            report["removed"].append(path.name)
        elif not tail and rotate(path, log_bytes, copies):
            report["rotated"].append(path.name)
    return report


def public_ip():
    """This connection's public IPv4 address, asked of OpenDNS's resolver rather than of a web service."""
    output = subprocess.run(["dig", "+short", "+time=5", "+tries=2", "myip.opendns.com", "@resolver1.opendns.com"],
                            check=True, capture_output=True, text=True, timeout=30).stdout.strip()
    if not re.fullmatch(r"(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})", output) or \
            any(int(part) > 255 for part in output.split(".")):
        raise ValueError("no public IPv4 address in resolver answer")
    return output


def digitalocean(token, method, path, body=None):
    import urllib.request
    request = urllib.request.Request("https://api.digitalocean.com/v2/" + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read() or b"null")


def restart_nomad_agents(nomad):
    """Stop the server and client agents; launchd starts them again with the rewritten configuration."""
    output = subprocess.run(["ps", "-axo", "pid=,command="], check=True, capture_output=True, text=True, timeout=10).stdout
    for line in output.splitlines():
        match = re.match(r"\s*(\d+)\s+(.+)", line)
        if match and " agent " in match[2] and any(str(nomad / name) in match[2] for name in ("server.json", "client.json")):
            os.kill(int(match[1]), 15)


def follow_public_ip(data, settings, address=public_ip, api=digitalocean, restart=restart_nomad_agents):
    """
    Keep the lab reachable after the connection's public address changes: point the DNS record at it, and make the
    Nomad server advertise it, since cloud clients and this machine's client reach the server through it. A client
    keeps dialing the old address it learned, so both agents restart; running allocations survive that.
    """
    ip = address()
    report = {"ip": ip}
    domain, name = settings["domain"], settings["name"]
    records = api(settings["token"], "GET", f"domains/{domain}/records?type=A&name={name}.{domain}")["domain_records"]
    if len(records) != 1:
        raise ValueError(f"expected one A record for {name}.{domain}, found {len(records)}")
    if records[0]["data"] != ip:
        api(settings["token"], "PUT", f"domains/{domain}/records/{records[0]['id']}", {"data": ip})
        report["dns"] = {"from": records[0]["data"], "to": ip}
    server = data / "nomad" / "server.json"
    if server.exists():
        config = json.loads(server.read_text())
        rpc = config.get("advertise", {}).get("rpc", "")
        host, _, port = rpc.rpartition(":")
        if host and host != ip:
            config["advertise"]["rpc"] = f"{ip}:{port}"
            temporary = server.with_name(server.name + ".tmp")
            temporary.write_text(json.dumps(config, indent=2) + "\n")
            temporary.chmod(stat.S_IMODE(server.stat().st_mode))
            temporary.replace(server)
            restart(server.parent)
            report["nomad"] = {"from": rpc, "to": config["advertise"]["rpc"]}
    return report


def run(data, homes, cache_bytes=2 * 1024**3, log_bytes=20 * 1024**2, copies=3, age_days=7, dns_settings=None):
    directory = data / "maintenance"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (directory / "gradle-maintenance.lock").open("a+b") as lock:
        try:
            fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {"skipped": "maintenance already running"}
        active = active_daemons()  # Fail closed if the process inventory cannot be read.
        now = time.time()
        report = {"at": now, "homes": [], "errors": []}
        for home in dict.fromkeys(p.expanduser().resolve() for p in homes):
            try:
                report["homes"].append(clean_home(home, active, cache_bytes, log_bytes, copies, age_days, now))
            except (OSError, subprocess.SubprocessError) as failure:
                report["errors"].append({"home": str(home), "error": str(failure)})
        try:
            report["logs"] = clean_service_logs(data / "logs", log_bytes, copies, age_days, now)
        except OSError as failure:
            report["errors"].append({"logs": str(data / "logs"), "error": str(failure)})
        if dns_settings is not None:
            try:
                report["publicAddress"] = follow_public_ip(data, dns_settings)
            except (OSError, ValueError, KeyError, subprocess.SubprocessError) as failure:
                report["errors"].append({"publicAddress": str(failure)})
        with tempfile.NamedTemporaryFile(mode="w", dir=directory, delete=False) as out:
            json.dump(report, out, indent=2)
            temporary = Path(out.name)
        temporary.replace(directory / "gradle-maintenance.json")
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--gradle-home", action="append", type=Path)
    parser.add_argument("--cache-gib", type=float, default=2)
    parser.add_argument("--log-mib", type=float, default=20)
    parser.add_argument("--copies", type=int, default=3)
    parser.add_argument("--log-age-days", type=int, default=7)
    parser.add_argument("--quiet", action="store_true")
    # {"token": ..., "domain": "eignex.com", "name": "lab"}: a DigitalOcean token scoped to DNS. Absent: no updates.
    parser.add_argument("--dns-settings", type=Path, default=Path.home() / ".config/eignex-lab/dynamic-dns.json")
    args = parser.parse_args()
    if min(args.cache_gib, args.log_mib, args.copies, args.log_age_days) <= 0:
        parser.error("retention limits must be positive")
    report = run(args.data, args.gradle_home or [args.data / "gradle", Path.home() / ".gradle"],
                 int(args.cache_gib * 1024**3), int(args.log_mib * 1024**2), args.copies, args.log_age_days,
                 json.loads(args.dns_settings.read_text()) if args.dns_settings.is_file() else None)
    if not args.quiet or report.get("errors"):
        print(json.dumps(report))
    return 1 if report.get("errors") else 0


if __name__ == "__main__":
    raise SystemExit(main())
