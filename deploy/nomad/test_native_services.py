import json
from pathlib import Path
import plistlib
import tempfile
import unittest
from native_services import render


class NativeServicesTest(unittest.TestCase):
    def settings(self, root):
        tls = dict(http=True, rpc=True, verify_server_hostname=True, verify_https_client=True,
                   ca_file="/private/ca", cert_file="/private/cert", key_file="/private/key")
        server = dict(data_dir=str(root / "server"), acl=dict(enabled=True), tls=tls, server=dict(enabled=True, bootstrap_expect=1))
        client = dict(data_dir=str(root / "client"), acl=dict(enabled=True), tls=tls,
                      client=dict(enabled=True, servers=["10.0.0.1:4647"], reserved=dict(cpu=500, memory=512)),
                      plugin=[dict(raw_exec=dict(config=[dict(enabled=True)]))])
        for role, value in (("server", server), ("client", client)):
            (root / (role + ".json")).write_text(json.dumps(value))
        return dict(dataDir=str(root), labLauncher="/lab/releases/current/bin/eignex-lab", workerLauncher="/lab/releases/pinned/bin/eignex-lab",
                    nomadBinary="/lab/nomad-2.0.7", python="/usr/bin/python3", supervisorSocket=str(root / "process.sock"),
                    localLock=str(root / "native.lock"), home="/Users/lab", path="/usr/bin:/bin", javaHome="/lab/jdk25",
                    serverConfig=str(root / "server.json"), clientConfig=str(root / "client.json"), coordinatorConfig=str(root / "coordinator.json"),
                    labEnvironment={"LAB_JOBS": "/Volumes/lab/jobs"})

    def test_four_independent_services_and_pinned_supervisor(self):
        with tempfile.TemporaryDirectory() as path:
            settings = self.settings(Path(path))
            plists, pinned, content = render(settings)
            self.assertEqual(4, len(plists))
            self.assertIn("supervisor-", pinned.name)
            self.assertIn(b"def cleanup", content)
            for label, data in plists.items():
                plist = plistlib.loads(data)
                self.assertEqual(label, plist["Label"])
                self.assertNotIn("-dev", plist["ProgramArguments"])
                if not label.endswith("nomad-coordinator"):
                    self.assertNotIn("LAB_JOBS", plist["EnvironmentVariables"])
                if label.endswith("process-supervisor"):
                    self.assertIn(settings["localLock"], plist["ProgramArguments"])

    def test_rejects_unversioned_worker_and_conflicting_lock(self):
        with tempfile.TemporaryDirectory() as path:
            settings = self.settings(Path(path))
            settings["workerLauncher"] = settings["labLauncher"]
            with self.assertRaises(ValueError):
                render(settings)
            settings = self.settings(Path(path))
            settings["labEnvironment"]["LAB_NATIVE_EXECUTION_LOCK"] = "/other/lock"
            with self.assertRaises(ValueError):
                render(settings)

    def test_input_signer_is_separate_and_never_inherits_lab_configuration(self):
        with tempfile.TemporaryDirectory() as path:
            settings = self.settings(Path(path))
            settings["inputSigner"] = dict(python="/cache/venv/bin/python", script="/cache/input_signer.py", config="/cache/signer.json")
            settings["labEnvironment"]["LAB_SHARED_INPUT_SIGNER"] = "http://127.0.0.1:8093/"
            plists, _, _ = render(settings)
            signer = plistlib.loads(plists["com.eignex.lab.input-signer"])
            self.assertEqual(["/cache/venv/bin/python", "/cache/input_signer.py", "--config", "/cache/signer.json"], signer["ProgramArguments"])
            self.assertNotIn("LAB_SHARED_INPUT_SIGNER", signer["EnvironmentVariables"])

    def test_rejects_development_or_unprotected_agent(self):
        with tempfile.TemporaryDirectory() as path:
            settings = self.settings(Path(path))
            client = Path(settings["clientConfig"])
            value = json.loads(client.read_text()); value["tls"]["verify_https_client"] = False
            client.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                render(settings)

    def test_loopback_agent_requires_explicit_http_advertise(self):
        with tempfile.TemporaryDirectory() as path:
            settings = self.settings(Path(path))
            client = Path(settings["clientConfig"])
            value = json.loads(client.read_text()); value["bind_addr"] = "127.0.0.1"
            client.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, "advertise"):
                render(settings)
            value["advertise"] = {"http": "127.0.0.1:4649"}
            client.write_text(json.dumps(value))
            render(settings)


if __name__ == "__main__":
    unittest.main()
