#!/usr/bin/env python3
"""Same-user native process service, outside Nomad's raw_exec failure boundary.

A private Unix connection owns a tree. EOF, lease expiry, or timeout ends it. A
random inherited marker finds children that detach and are reparented. PID/start
identity is rechecked before every signal. This assumes trusted lab executables
preserve their inherited marker; it is not an isolation boundary for hostile code.
"""
import argparse
import ctypes
import fcntl
import json
import os
from pathlib import Path
import select
import signal
import socket
import subprocess
import sys
import threading
import time
import uuid

MARKER = "EIGNEX_PROCESS_OWNER"
MAC = sys.platform == "darwin"
REGISTRY = None
LAUNCH_LOCK = threading.Lock()
STOPPING = threading.Event()
NATIVE_LOCK = None
OWNER_LOCK = threading.Lock()
OWNER = None
OWNER_REFS = 0
EXPERIMENT_MARKERS = {}
OWNER_FILE = None


class BsdInfo(ctypes.Structure):
    _fields_ = [(name, ctypes.c_uint32) for name in (
        "flags", "status", "xstatus", "pid", "ppid", "uid", "gid", "ruid", "rgid", "svuid", "svgid", "rfu1")]
    _fields_ += [("comm", ctypes.c_char * 16), ("name", ctypes.c_char * 32)]
    _fields_ += [(name, ctypes.c_uint32) for name in ("nfiles", "pgid", "pjobc", "e_tdev", "e_tpgid")]
    _fields_ += [("nice", ctypes.c_int32), ("start_sec", ctypes.c_uint64), ("start_usec", ctypes.c_uint64)]


def identity(pid):
    try:
        if MAC:
            info = BsdInfo()
            lib = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
            if lib.proc_pidinfo(pid, 3, 0, ctypes.byref(info), ctypes.sizeof(info)) != ctypes.sizeof(info):
                return None
            return info.start_sec, info.start_usec
        return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
    except (OSError, ValueError, IndexError):
        return None


def owned(marker):
    result = {}
    if MAC:
        # -E prints launch environment on Darwin; never print or persist this output.
        text = subprocess.run(["/bin/ps", "-E", "-ww", "-A", "-o", "pid=,command="],
                              capture_output=True, text=True, timeout=5, check=True).stdout
        for line in text.splitlines():
            parts = line.strip().split(None, 1)
            if len(parts) == 2 and f"{MARKER}={marker}" in parts[1].split():
                pid = int(parts[0])
                result[pid] = identity(pid)
    else:
        needle = f"{MARKER}={marker}".encode()
        for path in Path("/proc").glob("[0-9]*/environ"):
            try:
                if needle in path.read_bytes().split(b"\0"):
                    pid = int(path.parent.name)
                    result[pid] = identity(pid)
            except OSError:
                pass
    return {pid: start for pid, start in result.items() if start is not None}


def send_signal(processes, sig):
    for pid, start in processes.items():
        if identity(pid) == start:
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                pass


def cleanup(marker, grace_ms=0):
    if grace_ms:
        send_signal(owned(marker), signal.SIGTERM)
        until = time.monotonic() + grace_ms / 1000
        while time.monotonic() < until and owned(marker):
            time.sleep(.05)
    # Freeze before killing so a surviving parent cannot fork between scans.
    found = {}
    for _ in range(20):
        current = owned(marker)
        send_signal(current, signal.SIGSTOP)
        fresh = current.keys() - found.keys()
        found.update(current)
        if not fresh:
            break
    send_signal(found, signal.SIGKILL)


def reserve(connection, experiment):
    global OWNER, OWNER_REFS
    if not isinstance(experiment, str) or not experiment or len(experiment) > 80:
        raise ValueError("invalid experiment owner")
    acquired = False
    try:
        while not acquired:
            with OWNER_LOCK:
                if STOPPING.is_set():
                    return
                if OWNER is None:
                    try:
                        if NATIVE_LOCK is not None:
                            fcntl.lockf(NATIVE_LOCK, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        OWNER = experiment
                        if OWNER_FILE is not None:
                            OWNER_FILE.write_text(experiment)
                    except BlockingIOError:
                        pass
                if OWNER == experiment:
                    OWNER_REFS += 1
                    acquired = True
            if not acquired:
                ready, _, _ = select.select([connection], [], [], .1)
                if ready and not connection.recv(64):
                    return
                if STOPPING.is_set():
                    return
        connection.sendall(b'{"reserved":true}\n')
        expiry = time.monotonic() + 80
        while time.monotonic() < expiry and not STOPPING.is_set():
            ready, _, _ = select.select([connection], [], [], .5)
            if ready:
                if not connection.recv(64):
                    break
                expiry = time.monotonic() + 80
    finally:
        if acquired:
            with LAUNCH_LOCK:
                with OWNER_LOCK:
                    OWNER_REFS -= 1
                    if OWNER_REFS == 0:
                        try:
                            for marker, owner in list(EXPERIMENT_MARKERS.items()):
                                if owner == experiment:
                                    cleanup(marker)
                            if OWNER_FILE is not None:
                                OWNER_FILE.unlink(missing_ok=True)
                            OWNER = None
                            if NATIVE_LOCK is not None:
                                fcntl.lockf(NATIVE_LOCK, fcntl.LOCK_UN)
                        except Exception:
                            STOPPING.set()
                            raise


def serve_request(connection):
    marker = uuid.uuid4().hex
    process = None
    cleaned = False
    try:
        connection.settimeout(5)
        pending = b""
        while b"\n" not in pending:
            data = connection.recv(65536)
            if not data:
                raise ConnectionError("worker disconnected before request")
            pending += data
            if len(pending) > 1024 * 1024:
                raise ValueError("request too large")
        line, pending = pending.split(b"\n", 1)
        request = json.loads(line)
        if request.get("operation") == "reserve":
            connection.settimeout(None)
            reserve(connection, request["experiment"])
            return
        if NATIVE_LOCK is not None:
            with OWNER_LOCK:
                if OWNER is None or request.get("experiment") != OWNER:
                    raise ValueError("native execution has no host reservation")
        argv = request["argv"]
        if not isinstance(argv, list) or not argv or not all(isinstance(x, str) and "\0" not in x for x in argv):
            raise ValueError("invalid argv")
        timeout = request["timeoutSec"]
        if not 0 < timeout <= 604800:
            raise ValueError("invalid timeout")
        environment = dict(request["environment"])
        environment[MARKER] = marker
        lease_ms = request["leaseMs"]
        if not 0 < lease_ms <= 80000:
            raise ValueError("invalid lease")
        with open(request["stdout"], "wb") as out, open(request["stderr"], "wb") as err:
            with LAUNCH_LOCK:
                if NATIVE_LOCK is not None:
                    with OWNER_LOCK:
                        if OWNER is None or request.get("experiment") != OWNER:
                            raise ValueError("native reservation ended before launch")
                if STOPPING.is_set():
                    raise RuntimeError("service stopping")
                (REGISTRY / marker).write_text(marker)
                EXPERIMENT_MARKERS[marker] = request.get("experiment")
                process = subprocess.Popen(argv, cwd=request["directory"], env=environment, stdout=out,
                                           stderr=err, start_new_session=True)
            deadline = time.monotonic() + timeout
            lease = time.monotonic() + lease_ms / 1000
            connection.settimeout(None)
            code = None
            while process.poll() is None:
                current = time.monotonic()
                if current >= lease:
                    raise TimeoutError("lease expired")
                if current >= deadline:
                    cleanup(marker, min(request.get("graceMs", 10000), max(0, int((lease-current)*1000))))
                    code = 124
                    break
                ready, _, _ = select.select([connection], [], [], 0 if pending else .1)
                if ready:
                    # Heartbeats are deliberately small, newline-delimited remaining monotonic lease durations.
                    data = connection.recv(64)
                    if not data:
                        raise ConnectionError("worker disconnected")
                    pending += data
                while b"\n" in pending:
                    line, pending = pending.split(b"\n", 1)
                    remaining = int(line)
                    if not 0 < remaining <= 80000:
                        raise ValueError("invalid lease renewal")
                    lease = time.monotonic() + remaining / 1000
                if len(pending) > 64:
                    raise ValueError("heartbeat too large")
            if code is None:
                code = process.wait()
            cleanup(marker)
            cleaned = True
            process.wait(timeout=10)
            connection.sendall((json.dumps({"exitCode": code if code >= 0 else 128-code}) + "\n").encode())
            # Keep the read side open until the worker consumes the result. Closing with an unread
            # heartbeat can reset a Unix socket and hide an otherwise completed process's exit.
            connection.shutdown(socket.SHUT_WR)
            connection.settimeout(5)
            try:
                while connection.recv(64):
                    pass
            except (OSError,):
                pass
    except Exception as error:
        print(f"supervisor request ended: {type(error).__name__}", file=sys.stderr, flush=True)
        # Do not log requests: they can contain paths and runtime credentials.
        try:
            cleanup(marker)
            cleaned = True
        except Exception:
            STOPPING.set()  # Retain the registry and refuse new launches until recovery succeeds.
        if process is not None:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                pass
        try:
            connection.sendall(b'{"error":"process supervision ended"}\n')
        except OSError:
            pass
    finally:
        if cleaned:
            (REGISTRY / marker).unlink(missing_ok=True)
        with LAUNCH_LOCK:
            EXPERIMENT_MARKERS.pop(marker, None)
        connection.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--socket", type=Path, required=True)
    parser.add_argument("--local-lock", type=Path)
    args = parser.parse_args()
    global REGISTRY, NATIVE_LOCK, OWNER_FILE
    os.umask(0o077)
    args.socket.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock = open(str(args.socket) + ".lock", "a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    if args.local_lock:
        args.local_lock.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        NATIVE_LOCK = open(args.local_lock, "a")
        OWNER_FILE = Path(str(args.local_lock) + ".owner")
        fcntl.lockf(NATIVE_LOCK, fcntl.LOCK_EX)
    REGISTRY = args.socket.parent / (args.socket.name + ".owners")
    REGISTRY.mkdir(mode=0o700, exist_ok=True)
    for record in REGISTRY.iterdir():
        if len(record.name) == 32 and all(c in "0123456789abcdef" for c in record.name):
            cleanup(record.name)
            record.unlink()
    if OWNER_FILE is not None:
        OWNER_FILE.unlink(missing_ok=True)
        fcntl.lockf(NATIVE_LOCK, fcntl.LOCK_UN)
    args.socket.unlink(missing_ok=True)

    def stop(_signal, _frame):
        STOPPING.set()
        with LAUNCH_LOCK:
            for record in REGISTRY.iterdir():
                cleanup(record.name)
                record.unlink(missing_ok=True)
        if OWNER_FILE is not None:
            OWNER_FILE.unlink(missing_ok=True)
        args.socket.unlink(missing_ok=True)
        os._exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    with socket.socket(socket.AF_UNIX) as server:
        server.bind(str(args.socket))
        server.listen(64)
        while True:
            connection, _ = server.accept()
            threading.Thread(target=serve_request, args=(connection,), daemon=False).start()


if __name__ == "__main__":
    main()
