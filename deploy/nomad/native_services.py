#!/usr/bin/env python3
"""Render or install independent Mac services. Ordinary lab updates never restart Nomad or its supervisor."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import pwd
import shutil
import subprocess
import urllib.request
import zipfile
import io

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def absolute(value):
    path = Path(value)
    if not path.is_absolute():
        raise ValueError("service paths must be absolute")
    return path


def render(settings):
    data = absolute(settings["dataDir"])
    state = data / "nomad"
    launcher = absolute(settings["labLauncher"])
    worker = absolute(settings["workerLauncher"])
    if "current" in worker.parts:
        raise ValueError("worker launcher must pin an immutable release")
    binary = absolute(settings["nomadBinary"])
    python = absolute(settings["python"])
    socket = absolute(settings["supervisorSocket"])
    local_lock = absolute(settings["localLock"])
    env = {"HOME": str(absolute(settings["home"])), "PATH": settings["path"], "JAVA_HOME": str(absolute(settings["javaHome"])),
           "LAB_DATA": str(data), "LAB_NATIVE_EXECUTION_LOCK": str(local_lock)} | settings.get("labEnvironment", {})
    if env["LAB_DATA"] != str(data) or env["LAB_NATIVE_EXECUTION_LOCK"] != str(local_lock):
        raise ValueError("native workers and maintenance must use the same data and ownership lock")
    script = ROOT / "src/main/resources/nomad/process_supervisor.py"
    content = script.read_bytes()
    pinned = state / ("supervisor-" + hashlib.sha256(content).hexdigest() + ".py")
    server_path = absolute(settings["serverConfig"])
    client_path = absolute(settings["clientConfig"])
    server = json.loads(server_path.read_text()); client = json.loads(client_path.read_text())
    for value in (server, client):
        if value.get("bind_addr") in ("127.0.0.1", "::1") and not value.get("advertise", {}).get("http"):
            raise ValueError("a loopback agent requires an explicit HTTP advertise address")
        if value.get("dev_mode") or not value.get("acl", {}).get("enabled"):
            raise ValueError("Nomad must use persistent non-dev state and ACLs")
        tls = value.get("tls", {})
        if not all(tls.get(key) for key in ("http", "rpc", "verify_server_hostname", "verify_https_client", "ca_file", "cert_file", "key_file")):
            raise ValueError("Nomad requires mutual TLS for HTTP and RPC")
        absolute(value["data_dir"])
    if not server.get("server", {}).get("enabled") or server["server"].get("bootstrap_expect") != 1:
        raise ValueError("initial server must be explicitly single-server")
    if not client.get("client", {}).get("enabled") or not client["client"].get("servers"):
        raise ValueError("native client requires an explicit server route")
    if not any(plugin.get("raw_exec", {}).get("config", [{}])[0].get("enabled") for plugin in client.get("plugin", [])):
        raise ValueError("native client requires raw_exec")
    if client["client"].get("reserved", {}).get("memory", 0) <= 0:
        raise ValueError("native client requires a host memory reserve")
    commands = {
        "nomad-server": [str(binary), "agent", "-config", str(server_path)],
        "nomad-client": [str(binary), "agent", "-config", str(client_path)],
        "process-supervisor": [str(python), str(pinned), "--socket", str(socket), "--local-lock", str(local_lock)],
        "nomad-coordinator": [str(launcher), "nomad-coordinator", str(absolute(settings["coordinatorConfig"]))],
    }
    if settings.get("inputSigner"):
        signer = settings["inputSigner"]
        commands["input-signer"] = [str(absolute(signer["python"])), str(absolute(signer["script"])),
                                    "--config", str(absolute(signer["config"]))]
    user = settings.get("user", pwd.getpwuid(os.getuid()).pw_name)
    if user == "root":
        raise ValueError("native execution must run as the lab user, never root")
    plists = {}
    for role, command in commands.items():
        label = "com.eignex.lab." + role
        values = {
            "Label": label, "UserName": user, "ProgramArguments": command, "WorkingDirectory": str(data),
            "RunAtLoad": True, "KeepAlive": True, "ThrottleInterval": 30,
            "StandardOutPath": str(data / "logs" / (role + ".log")),
            "StandardErrorPath": str(data / "logs" / (role + ".log")),
            # Supervisor and Nomad agents never inherit the lab's provider/control configuration.
            "EnvironmentVariables": env if role == "nomad-coordinator" else {k: env[k] for k in ("PATH", "HOME", "JAVA_HOME")},
        }
        plists[label] = plistlib.dumps(values)
    return plists, pinned, content


def install_runtime(state, server_ip):
    import platform
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    state.chmod(0o700)
    versions = json.loads((HERE / "versions.json").read_text())["nomad"]
    arch = {"arm64": "arm64", "x86_64": "amd64"}[platform.machine()]
    url = f"https://releases.hashicorp.com/nomad/{versions['version']}/nomad_{versions['version']}_darwin_{arch}.zip"
    with urllib.request.urlopen(url, timeout=60) as response:
        archive = response.read()
    if hashlib.sha256(archive).hexdigest() != versions["sha256"]["darwin_" + arch]:
        raise ValueError("Nomad checksum mismatch")
    binary = state / "bin" / ("nomad-" + versions["version"])
    binary.parent.mkdir(exist_ok=True)
    data = zipfile.ZipFile(io.BytesIO(archive)).read("nomad")
    if binary.exists() and binary.read_bytes() != data:
        raise ValueError("refusing to overwrite a pinned Nomad binary")
    if not binary.exists():
        binary.write_bytes(data); binary.chmod(0o755)
    def run(*args):
        subprocess.run([str(binary), *args], cwd=state, check=True, capture_output=True)
    if not (state / "nomad-agent-ca.pem").exists():
        run("tls", "ca", "create")
    for role in ("server", "client", "cli"):
        if not (state / f"global-{role}-nomad.pem").exists():
            args = ("-additional-ipaddress", server_ip) if role == "server" else ()
            run("tls", "cert", "create", "-" + role, *args)
    for key in state.glob("*-key.pem"):
        key.chmod(0o600)
    print(f"Verified native Nomad {versions['version']} and initialized private TLS files in {state}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--restart-agents", action="store_true")
    parser.add_argument("--init-runtime", type=Path)
    parser.add_argument("--server-ip", default="127.0.0.1")
    args = parser.parse_args()
    if args.init_runtime:
        if os.uname().sysname != "Darwin":
            raise ValueError("native runtime installation requires macOS")
        install_runtime(args.init_runtime.resolve(), args.server_ip)
        return
    if not args.config or bool(args.output) == args.install:
        parser.error("provide --config and exactly one of --output or --install")
    settings = json.loads(args.config.read_text())
    plists, pinned, script = render(settings)
    output = args.output or absolute(settings["dataDir"]) / "nomad/service-files"
    output.mkdir(mode=0o700, parents=True, exist_ok=True)
    for name, content in plists.items():
        (output / (name + ".plist")).write_bytes(content)
    if not args.install:
        print(f"Rendered native service files in {output}; registered nothing")
        return
    if os.uname().sysname != "Darwin":
        raise ValueError("service installation requires macOS")
    running = {}
    for label, content in plists.items():
        live = subprocess.run(["launchctl", "print", "system/" + label], capture_output=True).returncode == 0
        running[label] = live
        installed = Path("/Library/LaunchDaemons") / (label + ".plist")
        if live and not label.endswith("nomad-coordinator") and not args.restart_agents:
            if not installed.exists() or installed.read_bytes() != content:
                raise ValueError("agent/supervisor changes require explicit --restart-agents maintenance")
    pinned.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if pinned.exists() and pinned.read_bytes() != script:
        raise ValueError("pinned supervisor differs")
    if not pinned.exists():
        pinned.write_bytes(script); pinned.chmod(0o700)
    (absolute(settings["dataDir"]) / "logs").mkdir(exist_ok=True)
    for label in plists:
        if running[label] and not label.endswith("nomad-coordinator") and not args.restart_agents:
            continue
        if running[label]:
            subprocess.run(["sudo", "launchctl", "bootout", "system/" + label], check=True)
        target = Path("/Library/LaunchDaemons") / (label + ".plist")
        subprocess.run(["sudo", "install", "-m", "644", "-o", "root", "-g", "wheel", str(output / target.name), str(target)], check=True)
        subprocess.run(["sudo", "launchctl", "bootstrap", "system", str(target)], check=True)
    print("Native services installed. Ordinary lab updates restart only the coordinator/API/runner.")


if __name__ == "__main__":
    main()
