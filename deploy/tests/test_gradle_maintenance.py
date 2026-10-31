import fcntl
import gzip
import multiprocessing
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gradle_maintenance import clean_home, clean_service_logs


def hold_lock(path, ready, release):
    with open(path, "r+b") as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX)
        ready.set()
        release.wait(15)


class GradleMaintenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.logs = self.home / "daemon/9.7.0"
        self.logs.mkdir(parents=True)
        self.cache = self.home / "caches/build-cache-1"
        self.cache.mkdir(parents=True)
        self.lock = self.cache / "build-cache-1.lock"
        self.lock.write_bytes(b"Gradle lock protocol must remain unchanged")

    def clean(self, active=()):
        return clean_home(self.home, set(active), 10, 8, 3, 7, time.time())

    def test_rotation_keeps_three_bounded_tails_and_preserves_inode(self):
        path = self.logs / "daemon-123.out.log"
        path.touch()
        inode = path.stat().st_ino
        for tail in (b"11111111", b"22222222", b"33333333", b"44444444"):
            path.write_bytes(b"discard old content" + tail)
            self.assertEqual(1, self.clean()["rotated"])
        self.assertEqual(inode, path.stat().st_ino)
        self.assertEqual(b"", path.read_bytes())
        for index, expected in enumerate((b"44444444", b"33333333", b"22222222"), 1):
            with gzip.open(str(path) + ".%s.gz" % index) as archive:
                self.assertEqual(expected, archive.read())
        self.assertFalse(Path(str(path) + ".4.gz").exists())

    def test_active_daemon_keeps_its_open_log_and_offset(self):
        path = self.logs / "daemon-123.out.log"
        with path.open("wb") as writer:
            writer.write(b"active log must not be truncated")
            writer.flush()
            self.assertEqual(1, self.clean(active=[123])["activeLogsSkipped"])
            writer.write(b" next line")
        self.assertEqual(b"active log must not be truncated next line", path.read_bytes())

    def test_expiry_preserves_active_logs_and_registry(self):
        for name in ("daemon-123.out.log", "daemon-456.out.log", "daemon-456.out.log.1.gz", "registry.bin"):
            path = self.logs / name
            path.write_bytes(b"old")
            os.utime(path, (0, 0))
        self.assertEqual(2, self.clean(active=[123])["expiredLogs"])
        self.assertTrue((self.logs / "daemon-123.out.log").exists())
        self.assertTrue((self.logs / "registry.bin").exists())

    def test_eviction_keeps_newest_outputs_and_never_removes_metadata_or_links(self):
        for name, at in (("a", 1), ("b", 2), ("c", 3)):
            path = self.cache / name
            path.write_bytes(b"12345")
            os.utime(path, (at, at))
        (self.cache / "gc.properties").write_text("metadata")
        outside = self.home / "outside"
        outside.write_text("preserved")
        (self.cache / "d").symlink_to(outside)
        (self.logs / "daemon-789.out.log").symlink_to(outside)
        report = self.clean()
        self.assertEqual(5, report["cacheBytesRemoved"])
        self.assertFalse((self.cache / "a").exists())
        self.assertTrue((self.cache / "b").exists())
        self.assertEqual("metadata", (self.cache / "gc.properties").read_text())
        self.assertEqual(b"Gradle lock protocol must remain unchanged", self.lock.read_bytes())
        self.assertEqual("preserved", outside.read_text())

    def test_locked_cache_is_skipped_until_other_process_releases_it(self):
        (self.cache / "a").write_bytes(b"x" * 20)
        context = multiprocessing.get_context("spawn")
        ready, release = context.Event(), context.Event()
        process = context.Process(target=hold_lock, args=(str(self.lock), ready, release))
        process.start()
        try:
            self.assertTrue(ready.wait(10))
            self.assertEqual("in use", self.clean()["caches"][0]["skipped"])
            self.assertTrue((self.cache / "a").exists())
        finally:
            release.set()
            process.join(10)
            if process.is_alive():
                process.terminate()
                process.join()
        self.assertEqual(20, self.clean()["cacheBytesRemoved"])

    def test_missing_lock_never_creates_a_gradle_lock_or_deletes_outputs(self):
        self.lock.unlink()
        (self.cache / "a").write_bytes(b"x" * 20)
        self.assertEqual(0, self.clean()["cacheBytesRemoved"])
        self.assertFalse(self.lock.exists())


class ServiceLogTest(unittest.TestCase):
    def test_service_logs_are_bounded_in_place_and_removed_services_logs_expire(self):
        with tempfile.TemporaryDirectory() as temporary:
            logs = Path(temporary)
            live = logs / "runner.log"
            live.write_bytes(b"x" * 20)
            inode = live.stat().st_ino
            old = logs / "relay.log"
            old.write_bytes(b"old")
            (logs / "relay.log.1.gz").write_bytes(b"tail")
            month_ago = time.time() - 30 * 86400
            os.utime(old, (month_ago, month_ago))
            report = clean_service_logs(logs, 8, 3, 7, time.time())
            self.assertEqual(["runner.log"], report["rotated"])
            self.assertEqual(["relay.log", "relay.log.1.gz"], report["removed"])
            self.assertEqual(inode, live.stat().st_ino)
            self.assertEqual(0, live.stat().st_size)
            self.assertEqual(b"x" * 8, gzip.decompress((logs / "runner.log.1.gz").read_bytes()))
            self.assertEqual(["runner.log", "runner.log.1.gz"], sorted(p.name for p in logs.iterdir()))


if __name__ == "__main__":
    unittest.main()
