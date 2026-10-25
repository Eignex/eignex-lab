#!/usr/bin/env python3
"""A separate, persistent single-node Nomad sandbox with mTLS and ACLs; never uses the lab database."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
VERSIONS = json.loads((HERE / "versions.json").read_text())


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, text=True, capture_output=True, **kwargs).stdout


def check(condition, detail):
    if not condition:
        raise RuntimeError(f"verification failed: {detail}")


class Sandbox:
    def __init__(self, state, port):
        self.state = Path(state).resolve()
        self.port = port
        self.name = "eignex-nomad-prototype-" + hashlib.sha256(str(self.state).encode()).hexdigest()[:12]
        self.nomad = self.state / "bin" / "nomad"

    def env(self):
        env = os.environ.copy()
        for key in list(env):
            if key.startswith("NOMAD_"):
                del env[key]
        env.update(NOMAD_ADDR=f"https://127.0.0.1:{self.port}",
                   NOMAD_CACERT=str(self.state / "nomad-agent-ca.pem"),
                   NOMAD_CLIENT_CERT=str(self.state / "global-cli-nomad.pem"),
                   NOMAD_CLIENT_KEY=str(self.state / "global-cli-nomad-key.pem"))
        if (self.state / "token").is_file():
            env["NOMAD_TOKEN"] = (self.state / "token").read_text().strip()
        return env

    def cli(self, *args):
        return run([self.nomad, *args], env=self.env())

    def api(self, path, method="GET", body=None):
        context = ssl.create_default_context(cafile=str(self.state / "nomad-agent-ca.pem"))
        context.load_cert_chain(self.state / "global-cli-nomad.pem", self.state / "global-cli-nomad-key.pem")
        request = urllib.request.Request(f"https://127.0.0.1:{self.port}/v1/{path}", method=method,
                                         data=json.dumps(body).encode() if body is not None else None)
        if (self.state / "token").is_file():
            request.add_header("X-Nomad-Token", (self.state / "token").read_text().strip())
        request.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(request, context=context, timeout=5) as response:
            value = response.read()
            return json.loads(value) if value else None

    def install(self):
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.state.chmod(0o700)
        (self.state / "bin").mkdir(exist_ok=True)
        machine = {"x86_64": "amd64", "aarch64": "arm64", "arm64": "arm64"}[platform.machine()]
        system = platform.system().lower()
        pin = VERSIONS["nomad"]
        archive = f"nomad_{pin['version']}_{system}_{machine}.zip"
        with urllib.request.urlopen(f"https://releases.hashicorp.com/nomad/{pin['version']}/{archive}", timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != pin["sha256"][f"{system}_{machine}"]:
            raise RuntimeError("Nomad archive checksum mismatch")
        binary = zipfile.ZipFile(io.BytesIO(data)).read("nomad")
        self.nomad.write_bytes(binary)
        self.nomad.chmod(0o755)
        if not (self.state / "nomad-agent-ca.pem").exists():
            run([self.nomad, "tls", "ca", "create"], cwd=self.state)
        for role in ("server", "cli"):
            if not (self.state / f"global-{role}-nomad.pem").exists():
                run([self.nomad, "tls", "cert", "create", f"-{role}"], cwd=self.state)
        for path in self.state.glob("*-key.pem"):
            path.chmod(0o600)
        print(f"Verified Nomad {pin['version']}; private sandbox state: {self.state}", flush=True)

    def config(self):
        linux = platform.system() == "Linux"
        (self.state / "status").mkdir(exist_ok=True)
        # JSON configuration avoids interpolating paths into HCL strings or shell commands.
        return {
            "name": self.name, "data_dir": str(self.state / "data"), "datacenter": "lab-prototype",
            "bind_addr": "127.0.0.1", "log_level": "INFO",
            "ports": {"http": self.port, "rpc": self.port + 1, "serf": self.port + 2},
            "advertise": {"http": f"127.0.0.1:{self.port}", "rpc": f"127.0.0.1:{self.port + 1}",
                          "serf": f"127.0.0.1:{self.port + 2}"},
            "server": {"enabled": True, "bootstrap_expect": 1},
            "client": {"enabled": True, "node_class": "lab-prototype-linux" if linux else "lab-prototype-mac",
                       "host_volume": [{"lab-status": {"path": str(self.state / "status"), "read_only": True}}],
                       "servers": [f"127.0.0.1:{self.port + 1}"], "reserved": {"cpu": 500, "memory": 256}},
            "acl": {"enabled": True},
            "tls": {"http": True, "rpc": True, "verify_server_hostname": True, "verify_https_client": True,
                    "ca_file": str(self.state / "nomad-agent-ca.pem"),
                    "cert_file": str(self.state / "global-server-nomad.pem"),
                    "key_file": str(self.state / "global-server-nomad-key.pem")},
            "plugin": [{"raw_exec": {"config": [{"enabled": not linux}]}}],
            "consul": {"auto_advertise": False, "server_auto_join": False, "client_auto_join": False},
        }

    def start(self):
        if not self.nomad.exists() or not (self.state / "global-cli-nomad.pem").exists():
            self.install()
        config = self.state / "agent.json"
        config.write_text(json.dumps(self.config(), indent=2))
        if platform.system() == "Linux":
            image = run(["docker", "build", "-q", "--build-arg", f"BASE={VERSIONS['linux_agent_image']}",
                         "-f", HERE / "agent.Dockerfile", HERE]).strip()
            # A root Linux client needs host cgroups and the Docker socket. All task/data paths retain identical
            # host/container names so the Docker driver's allocation mounts resolve on the actual Docker host.
            run(["docker", "run", "-d", "--name", self.name, "--privileged", "--network", "host",
                 "--cgroupns", "host", "--label", f"eignex.lab.nomad.prototype={self.state}",
                 "--mount", f"type=bind,src={self.state},dst={self.state}",
                 "--mount", "type=bind,src=/sys/fs/cgroup,dst=/sys/fs/cgroup",
                 "--mount", "type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock",
                 image, self.nomad, "agent", "-config", config])
        else:
            with (self.state / "agent.log").open("a") as log:
                child = subprocess.Popen([str(self.nomad), "agent", "-config", str(config)],
                                         stdout=log, stderr=log, start_new_session=True)
            (self.state / "agent.pid").write_text(str(child.pid))
        self.wait_ready()
        if not (self.state / "token").exists():
            token = json.loads(self.cli("acl", "bootstrap", "-json"))["SecretID"]
            path = self.state / "token"
            path.touch(mode=0o600, exist_ok=False)
            path.write_text(token)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            nodes = self.api("nodes")
            if any(node["Status"] == "ready" for node in nodes):
                print(f"Sandbox ready at https://127.0.0.1:{self.port} with TLS and ACLs", flush=True)
                return
            time.sleep(1)
        raise RuntimeError("client did not become ready; inspect the sandbox agent logs")

    def wait_ready(self):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                if self.api("status/leader"):
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.5)
        raise RuntimeError("Nomad leader did not become ready; inspect the sandbox agent logs")

    def stop(self):
        if platform.system() == "Linux":
            inspected = subprocess.run(["docker", "inspect", self.name], text=True, capture_output=True)
            if inspected.returncode and "No such object" in inspected.stderr:
                print("Sandbox agent is not running", flush=True)
                return
            inspected.check_returncode()
            info = json.loads(inspected.stdout)[0]
            if info["Config"]["Labels"].get("eignex.lab.nomad.prototype") != str(self.state):
                raise RuntimeError("refusing to stop a container not owned by this sandbox")
            (self.state / "agent.log").write_text(run(["docker", "logs", self.name]))
            run(["docker", "rm", "-f", self.name])
            if (self.state / "data").exists():
                # The root client writes private state; return ownership so `gradlew clean` works for the operator.
                run(["docker", "run", "--rm", "--network", "none", "--mount",
                     f"type=bind,src={self.state / 'data'},dst=/data", info["Image"],
                     "chown", "-R", f"{os.getuid()}:{os.getgid()}", "/data"])
        else:
            # Match the recorded process and its state path before signaling; never trust a reused PID alone.
            pid_file = self.state / "agent.pid"
            if not pid_file.exists():
                print("Sandbox agent is not running", flush=True)
                return
            pid = int(pid_file.read_text())
            command = run(["ps", "-p", pid, "-o", "command="]).strip()
            if str(self.nomad) not in command or str(self.state / "agent.json") not in command:
                raise RuntimeError("sandbox PID no longer names this agent")
            os.kill(pid, 15)
            pid_file.unlink()
        print("Stopped sandbox agent; state retained", flush=True)

    def allocations(self, job):
        return self.api(f"job/{job}/allocations")

    def wait_allocation(self, job, terminal=False):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            allocations = self.allocations(job)
            for alloc in allocations:
                if alloc["ClientStatus"] in (("complete", "failed") if terminal else ("running", "complete", "failed")):
                    return self.api(f"allocation/{alloc['ID']}")
            time.sleep(0.5)
        raise RuntimeError(f"allocation did not {'finish' if terminal else 'start'} for {job}")

    def containers(self, allocation):
        return run(["docker", "ps", "-q", "--filter", f"label=com.hashicorp.nomad.alloc_id={allocation}"]).split()

    def deregister(self, job):
        try:
            self.api(f"job/{job}?purge=true", "DELETE")
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise

    def verify(self, lab, image):
        if platform.system() != "Linux":
            raise RuntimeError("automated hard-limit checks require Linux; use the native probe instructions on Mac")
        if not image or "@sha256:" not in image:
            raise RuntimeError("--image must be a Python 3 image pinned with @sha256:<digest>")
        nodes = [node for node in self.api("nodes") if node["Status"] == "ready"]
        if len(nodes) != 1:
            raise RuntimeError("verification requires exactly one ready sandbox node")
        node = self.api(f"node/{nodes[0]['ID']}")
        cpu = node["NodeResources"]["Cpu"]
        capacity = {"usableMHz": int(node["Attributes"]["cpu.usablecompute"]), "logicalCpus": cpu["TotalCpuCores"]}
        probe = (HERE / "probe.py").read_text()
        jobs = []
        reports = []

        def submit(kind, cpu_millis, seconds=12, oom=False):
            job = f"lab-probe-{kind}-{uuid.uuid4().hex[:10]}"
            manifest = {"id": job, "platform": "linux", "nodeClass": "lab-prototype-linux", "cpuCapacity": capacity,
                        "budget": {"cpuMillis": cpu_millis, "memoryMiB": 128 if oom else 256}, "image": image,
                        "command": ["python3", "-u", "-c", probe, "--seconds", str(seconds)] + (["--oom"] if oom else [])}
            path = self.state / f"{job}.json"
            path.write_text(json.dumps(manifest))
            rendered = json.loads(run([lab, "nomad-spec", path]))
            self.api("jobs", "POST", {"Job": rendered})
            jobs.append(job)
            return job

        try:
            # Three independent sessions, like two measured experiments and a concurrent build.
            for kind, budget in (("solve-a", 500), ("solve-b", 500), ("build", 250)):
                job = submit(kind, budget)
                self.wait_allocation(job)
            print("Three bounded sessions started on one node", flush=True)
            allocations = [self.wait_allocation(job, terminal=True) for job in jobs]
            starts, ends = [], []
            for alloc, budget in zip(allocations, (500, 500, 250)):
                task = alloc["TaskStates"]["worker"]
                if task["Failed"]:
                    raise RuntimeError(f"resource probe failed: {task['Events']}")
                starts.append(task["StartedAt"])
                ends.append(task["FinishedAt"])
                report = json.loads(self.cli("alloc", "logs", alloc["ID"], "worker").strip().splitlines()[-1])
                quota, period = (int(x) for x in report["cpuMax"].split())
                check(0 <= budget / 1000 - quota / period < 0.02, report)
                check(int(report["memoryMax"]) == 256 * 1024 * 1024, report)
                check(report["cpuRate"] <= budget / 1000 + 0.15, report)
                check(int(report["cpuAfter"]["nr_throttled"]) > int(report["cpuBefore"]["nr_throttled"]), report)
                check(report["childExits"] == [0, 0, 0], report)
                reports.append(report)
            check(len({alloc["NodeID"] for alloc in allocations}) == 1, "sessions used different nodes")
            check(max(starts) < min(ends), "sessions did not overlap")
            print("Verified shared placement, CPU quotas, throttling and memory ceilings for detached children", flush=True)

            oom = self.wait_allocation(submit("oom", 250, oom=True), terminal=True)
            task = oom["TaskStates"]["worker"]
            check(task["Failed"], "memory-exhaustion probe unexpectedly succeeded")
            check(any(event.get("ExitCode") == 137 or "oom" in json.dumps(event).lower() for event in task["Events"]), task)
            print("Verified memory exhaustion terminates the bounded task", flush=True)

            cancel_job = submit("cancel", 250, seconds=120)
            cancel = self.wait_allocation(cancel_job)
            check(self.containers(cancel["ID"]), "cancel probe has no running container")
            # Give its detached descendants time to start before cancelling.
            time.sleep(2)
            self.deregister(cancel_job)
            deadline = time.monotonic() + 45
            while self.containers(cancel["ID"]) and time.monotonic() < deadline:
                time.sleep(0.5)
            check(not self.containers(cancel["ID"]), "cancel left a container and its detached processes running")
            print("Verified cancellation cleans up the task container and detached descendants", flush=True)
            result = {"nomadVersion": VERSIONS["nomad"]["version"], "image": image, "capacity": capacity,
                      "resourceProbes": reports, "oom": "passed", "cancel": "passed", "nativeMac": "not-run"}
            (self.state / "verification.json").write_text(json.dumps(result, indent=2))
        finally:
            for job in jobs:
                self.deregister(job)

    def native_probe(self, lab):
        if platform.system() != "Darwin":
            raise RuntimeError("native-probe requires the Mac; Linux hard-limit checks use verify")
        nodes = [node for node in self.api("nodes") if node["Status"] == "ready"]
        check(len(nodes) == 1, "native probe requires exactly one ready sandbox node")
        node = self.api(f"node/{nodes[0]['ID']}")
        job = "lab-probe-native-" + uuid.uuid4().hex[:10]
        manifest = {"id": job, "platform": "mac", "nodeClass": "lab-prototype-mac",
                    "cpuCapacity": {"usableMHz": int(node["Attributes"]["cpu.usablecompute"]),
                                    "logicalCpus": node["NodeResources"]["Cpu"]["TotalCpuCores"]},
                    "budget": {"cpuMillis": 1000, "memoryMiB": 256},
                    "command": [sys.executable, str(HERE / "probe.py"), "--seconds", "12"]}
        path = self.state / f"{job}.json"
        path.write_text(json.dumps(manifest))
        rendered = json.loads(run([lab, "nomad-spec", path]))
        try:
            self.api("jobs", "POST", {"Job": rendered})
            alloc = self.wait_allocation(job, terminal=True)
            check(not alloc["TaskStates"]["worker"]["Failed"], alloc["TaskStates"]["worker"])
            result = json.loads(self.cli("alloc", "logs", alloc["ID"], "worker").strip().splitlines()[-1])
            check(result["platform"] == "darwin" and result["enforcement"] == "advisory-native", result)
            (self.state / "native-verification.json").write_text(json.dumps(result, indent=2))
            print("Native Mac process probe passed; real solver/JFR and abrupt-death supervision gates remain", flush=True)
        finally:
            self.deregister(job)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", default=str(ROOT / "build" / "nomad-prototype"))
    parser.add_argument("--port", type=int, default=14646)
    parser.add_argument("--lab", default=str(ROOT / "build/install/eignex-lab/bin/eignex-lab"))
    parser.add_argument("--image", default=VERSIONS["probe_image"])
    parser.add_argument("action", choices=("install", "start", "stop", "verify", "native-probe", "cli"))
    parser.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65533:
        parser.error("port must be 1024..65533")
    sandbox = Sandbox(args.state, args.port)
    try:
        if args.action == "verify":
            sandbox.verify(args.lab, args.image)
        elif args.action == "native-probe":
            sandbox.native_probe(args.lab)
        elif args.action == "cli":
            print(sandbox.cli(*args.args), end="")
        else:
            getattr(sandbox, args.action)()
    except (OSError, RuntimeError, ValueError, AssertionError, subprocess.CalledProcessError) as error:
        print(f"sandbox: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stderr, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
