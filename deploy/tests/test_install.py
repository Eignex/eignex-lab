"""Exercise installation with an isolated home and distribution, without registering services."""
import os
from pathlib import Path
import re
import plistlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class InstallTest(unittest.TestCase):
    def install(self, configured_data):
        with tempfile.TemporaryDirectory(prefix="lab-install-") as temporary:
            root = Path(temporary)
            project = root / "checkout"
            deploy = project / "deploy"
            deploy.mkdir(parents=True)
            for name in ("install.sh", "release.sh", "lab.env.example"):
                shutil.copy2(ROOT / "deploy" / name, deploy / name)
            home = root / "home"
            config = home / ".config/eignex-lab"
            config.mkdir(parents=True)
            settings = "LAB_DOCKER=none\n"
            if configured_data:
                settings += f'LAB_DATA="{root / "custom-data"}"\n'
            (config / "lab.env").write_text(settings)
            gradle = project / "gradlew"
            gradle.write_text('#!/bin/bash\n[[ "$*" != *printJavaHome* ]] || echo /test-jdk\nexit 0\n')
            gradle.chmod(0o755)
            launcher = project / "build/install/eignex-lab/bin/eignex-lab"
            launcher.parent.mkdir(parents=True)
            launcher.write_text('#!/bin/bash\nprintf "checked data: %s\\n" "$LAB_DATA"\n')
            launcher.chmod(0o755)
            environment = {k: v for k, v in os.environ.items() if not k.startswith("LAB_")}
            environment.update(HOME=str(home), LAB_INSTALL_DRY_RUN="1")
            result = subprocess.run(["bash", str(deploy / "install.sh")], env=environment,
                                    text=True, capture_output=True, check=True)
            units = Path(re.search(r"dry run: service files in (.+)", result.stdout)[1])
            try:
                expected = root / "custom-data" if configured_data else home / "eignex-lab-data"
                self.assertIn(f"checked data: {expected}", result.stdout)
                for role in ("api", "runner"):
                    if os.uname().sysname == "Darwin":
                        raw = (units / f"com.eignex.lab.{role}.plist").read_bytes()
                        plist = plistlib.loads(raw)
                        self.assertEqual(str(expected), plist["EnvironmentVariables"]["LAB_DATA"])
                        self.assertEqual(1, raw.count(b"<key>LAB_DATA</key>"))
                        self.assertEqual(str(expected), plist["WorkingDirectory"])
                        self.assertEqual([str(launcher), role], plist["ProgramArguments"][-2:])
                    else:
                        unit = (units / f"eignex-lab-{role}.service").read_text()
                        self.assertIn(f'Environment="LAB_DATA={expected}"', unit)
                        self.assertEqual(1, unit.count('Environment="LAB_DATA='))
                        self.assertIn(f"WorkingDirectory={expected}", unit)
                        self.assertIn(f"ExecStart={launcher} {role}", unit)
            finally:
                shutil.rmtree(units)

    def test_default_data_is_exported_to_host_check_and_services(self):
        self.install(configured_data=False)

    def test_explicit_data_is_preserved_without_duplicate_environment(self):
        self.install(configured_data=True)


if __name__ == "__main__":
    unittest.main()
