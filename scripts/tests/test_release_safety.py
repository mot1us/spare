"""Exercise release identity and device isolation gates without publishing."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from release_tag import require_new_tag


class ReleaseSafetyTest(unittest.TestCase):
    def test_existing_tag_is_rejected_without_a_release(self):
        with tempfile.TemporaryDirectory() as directory:
            remote = Path(directory) / "remote.git"
            subprocess.run(["git", "init", "--bare", str(remote)], check=True, capture_output=True)
            require_new_tag("1.2.3", str(remote))
            # A tag alone reproduces the gap: GitHub need not have a release for it.
            blob = subprocess.run(["git", "-C", str(remote), "hash-object", "-w", "--stdin"],
                                  input=b"previous source", check=True, capture_output=True).stdout.decode().strip()
            subprocess.run(["git", "-C", str(remote), "update-ref", "refs/tags/v1.2.3", blob], check=True)
            with self.assertRaisesRegex(ValueError, "already exists"):
                require_new_tag("1.2.3", str(remote))
            require_new_tag("1.2.4", str(remote))

    def test_failed_remote_lookup_is_not_treated_as_an_available_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(subprocess.CalledProcessError):
                require_new_tag("1.2.3", str(Path(directory) / "missing.git"))

    def test_device_scripts_reject_physical_targets_before_invoking_adb(self):
        env = dict(os.environ, ANDROID_HOME="/nonexistent-sdk", JAVA_HOME="/nonexistent-jdk")
        for script in ("device-test.sh", "update-device-test.sh", "release-smoke.sh"):
            for serial in ("physical-device", "192.0.2.1:5555", "emulator-"):
                with self.subTest(script=script, serial=serial):
                    result = subprocess.run(["bash", str(SCRIPTS / script)],
                                            env=dict(env, SPARE_TEST_SERIAL=serial),
                                            capture_output=True, text=True)
                    self.assertEqual(1, result.returncode)
                    self.assertIn("Use an isolated emulator", result.stdout)
                    self.assertEqual("", result.stderr)


if __name__ == "__main__":
    unittest.main()
