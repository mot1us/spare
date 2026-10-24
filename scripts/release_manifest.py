#!/usr/bin/env python3
"""Create public, version-pinned update metadata beside the signed APK."""
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from version import ROOT, read_version

name, code = read_version()
directory = ROOT / 'dist'
apk = directory / 'spare.apk'
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
certificate = subprocess.run([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], check=True, capture_output=True, text=True).stdout
expected = (ROOT / 'release-certificate.sha256').read_text().strip()
assert f'Signer #1 certificate SHA-256 digest: {expected}' in certificate, 'Release signing identity does not match the backed-up key'
badging = subprocess.run([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
assert "package: name='io.github.mot1us.spare'" in badging, 'Wrong APK package'
assert f"versionCode='{code}'" in badging and f"versionName='{name}'" in badging, 'Native APK version and metadata differ'
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(directory / 'SHA256SUMS').write_text(f'{digest}  spare.apk\n')
(directory / 'update.json').write_text(json.dumps({
    'versionName': name, 'versionCode': code,
    'apkUrl': f'https://github.com/mot1us/spare/releases/download/v{name}/spare.apk',
    'sha256': digest,
}, indent=2) + '\n')
changelog = (ROOT / 'CHANGELOG.md').read_text()
notes = changelog.split(f'## {name} ', 1)[1].split('\n', 1)[1].split('\n## ', 1)[0].strip()
(directory / 'release-notes.md').write_text(notes + '\n')
