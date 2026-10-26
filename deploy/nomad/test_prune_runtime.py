import json
from pathlib import Path
import plistlib
import sqlite3
import tempfile
import unittest

from prune_runtime import apply, plan


class PruneRuntimeTest(unittest.TestCase):
    def test_keeps_process_service_symlink_session_and_retry_pins_without_touching_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            data = Path(temporary)
            releases = data / "releases"
            for name in ("current", "process", "service", "session", "retry", "unused"):
                (releases / name / "bin").mkdir(parents=True)
            (releases / "selected").symlink_to("current")
            (data / "nomad").mkdir()
            (data / "jobs/123").mkdir(parents=True)
            result = data / "jobs/123/record.json"
            result.write_text("historical result")
            backup = data / "nomad/queue-before-fixture.json"
            backup.write_text("unused configuration")
            service = data / "service.plist"
            service.write_bytes(plistlib.dumps({"ProgramArguments": [str(releases / "service/bin/lab")]}))
            with sqlite3.connect(data / "lab.db") as db:
                db.executescript("""
                    CREATE TABLE jobs(id INTEGER, status TEXT);
                    CREATE TABLE execution_sessions(id TEXT, job_id INTEGER, desired TEXT);
                    CREATE TABLE execution_outbox(session_id TEXT, payload TEXT, acknowledged INTEGER);
                    CREATE TABLE execution_queue_recipes(job_id INTEGER, settings TEXT);
                    INSERT INTO jobs VALUES (1, 'RUNNING'), (2, 'FAILED');
                    INSERT INTO execution_sessions VALUES ('live', 1, 'run');
                """)
                db.execute("INSERT INTO execution_outbox VALUES (?, ?, 1)",
                           ("live", json.dumps({"command": str(releases / "session/bin/lab")})))
                db.execute("INSERT INTO execution_queue_recipes VALUES (2, ?)",
                           (json.dumps({"command": str(releases / "retry/bin/lab")}),))
            report = apply(data, processes=str(releases / "process/bin/lab"), service_paths=[service])
            self.assertEqual([str(releases / "unused"), str(backup)], report["deleted"])
            self.assertEqual({"current", "process", "service", "session", "retry"}, set(report["keptReleases"]))
            self.assertEqual("historical result", result.read_text())

    def test_dry_run_preserves_unused_releases_and_never_follows_release_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            data = Path(temporary)
            (data / "releases/unused").mkdir(parents=True)
            (data / "nomad").mkdir()
            outside = data / "historical"
            outside.mkdir()
            (data / "releases/history-link").symlink_to(outside)
            report = plan(data, processes="", service_paths=[])
            self.assertEqual([str(data / "releases/unused")], report["unusedReleases"])
            self.assertTrue((data / "releases/unused").is_dir())
            apply(data, processes="", service_paths=[])
            self.assertTrue(outside.is_dir())


if __name__ == "__main__":
    unittest.main()
