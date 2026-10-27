#!/usr/bin/env python3
"""Real worker -> private API -> durable Store acceptance against an isolated Nomad cluster."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from sandbox import Sandbox, ROOT, run


def main():
    state = (ROOT / "build/nomad-connected").resolve()
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock = open(state / "probe.lock", "a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    box = Sandbox(state, 14676)
    registry = "eignex-nomad-connected-" + hashlib.sha256(str(state).encode()).hexdigest()[:10]
    started = registered = False
    try:
        box.start()
        started = True
        box.cli("acl", "policy", "apply", "probe-coordinator", ROOT / "deploy/nomad/coordinator-policy.hcl")
        token = json.loads(box.cli("acl", "token", "create", "-json", "-name=probe-coordinator", "-policy=probe-coordinator"))["SecretID"]
        coordinator_token = state / "coordinator.token"
        coordinator_token.touch(mode=0o600, exist_ok=True)
        coordinator_token.chmod(0o600)
        coordinator_token.write_text(token)
        password = state / "store-password"
        password.touch(mode=0o600, exist_ok=True)
        password.write_text("isolated-probe-keystore")
        run(["openssl", "pkcs12", "-export", "-in", state / "global-cli-nomad.pem", "-inkey", state / "global-cli-nomad-key.pem",
             "-out", state / "client.p12", "-passout", f"file:{password}"])
        trust = state / "trust.p12"
        trust.unlink(missing_ok=True)
        java_home = run([ROOT / "gradlew", "-q", "printJavaHome"], cwd=ROOT).strip()
        run([Path(java_home) / "bin/keytool", "-importcert", "-noprompt", "-alias", "nomad", "-file", state / "nomad-agent-ca.pem",
             "-keystore", trust, "-storetype", "PKCS12", "-storepass:file", password])
        for path in (trust, state / "client.p12"):
            path.chmod(0o600)
        image = "127.0.0.1:15007/lab-worker:connected-probe"
        run(["docker", "run", "-d", "--rm", "--name", registry, "-p", "127.0.0.1:15007:5000",
             "--label", "eignex.lab.nomad.connected=true",
             "registry@sha256:01cd312e67026f06f5d89c3e133a4a9adc7cb9eeb9b1194e0b2954dfd770efd2"])
        registered = True
        subprocess.run([ROOT / "gradlew", "installDist"], cwd=ROOT, check=True)
        subprocess.run(["docker", "build", "-f", ROOT / "deploy/nomad/worker.Dockerfile", "--build-arg", "WORKER_RELEASE=connected-probe", "-t", image, ROOT], check=True)
        run(["docker", "push", image])
        digest = json.loads(run(["docker", "image", "inspect", image]))[0]["RepoDigests"][0]
        nodes = box.api("nodes")
        node = box.api("node/" + next(n["ID"] for n in nodes if n["Status"] == "ready"))
        env = os.environ | {
            "LAB_NOMAD_INTEGRATION": "1", "LAB_NOMAD_TEST_STATE": str(state), "LAB_NOMAD_TEST_ADDRESS": "https://127.0.0.1:14676",
            "LAB_NOMAD_TEST_IMAGE": digest, "LAB_NOMAD_TEST_API_PORT": "18091",
            "LAB_NOMAD_TEST_MHZ": node["Attributes"]["cpu.usablecompute"],
            "LAB_NOMAD_TEST_CPUS": str(node["NodeResources"]["Cpu"]["TotalCpuCores"]),
        }
        subprocess.run([ROOT / "gradlew", ":test", "--tests", "com.eignex.lab.ConnectedNomadTest", "--rerun-tasks"], cwd=ROOT, env=env, check=True, timeout=600)
        print("Connected Nomad worker, signed identity, hard limits, paired commit and restart checks passed.")
    except Exception:
        if started:
            for job in box.api("jobs"):
                for allocation in box.api("job/" + job["ID"] + "/allocations"):
                    if allocation.get("ClientStatus") == "failed":
                        print(box.cli("alloc", "logs", "-stderr", allocation["ID"], "worker"), file=sys.stderr)
        raise
    finally:
        if started:
            box.stop()
        if registered:
            run(["docker", "stop", registry])


if __name__ == "__main__":
    main()
