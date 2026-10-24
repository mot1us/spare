#!/usr/bin/env python3
"""Check the single tracked native version, or print it for release tooling."""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read_version():
    values = dict(line.split('=', 1) for line in (ROOT / 'version.properties').read_text().splitlines() if '=' in line)
    name, code = values['versionName'], int(values['versionCode'])
    assert re.fullmatch(r'\d+\.\d+\.\d+', name), 'Use a stable semantic version'
    assert 0 < code <= 2_100_000_000, 'Use a positive, increasing Android version code'
    assert f'## {name} ' in (ROOT / 'CHANGELOG.md').read_text(), 'Add a changelog entry for this version'
    return name, code

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--name', action='store_true')
    parser.add_argument('--channel', action='store_true')
    args = parser.parse_args()
    name, code = read_version()
    channel = dict(line.split('=', 1) for line in (ROOT / 'version.properties').read_text().splitlines() if '=' in line)['releaseChannel']
    assert channel in ('prerelease', 'stable')
    print(channel if args.channel else name if args.name else f'Spare {name} / versionCode {code}: version and changelog match ({channel})')
