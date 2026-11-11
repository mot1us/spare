#!/usr/bin/env python3
"""Refuse to attach newly built artifacts to an existing version tag."""
import subprocess

from version import read_version


def require_new_tag(name, remote="origin"):
    # gh release create --target does not move an existing tag. Reusing one can
    # publish binaries from a different commit than the release's source tree.
    result = subprocess.run(
        ["git", "ls-remote", "--exit-code", "--tags", remote, f"refs/tags/v{name}"],
        capture_output=True, text=True, timeout=60,
    )
    if result.returncode == 0:
        raise ValueError(f"Tag v{name} already exists; increment the tracked version instead of reusing it.")
    if result.returncode != 2:
        # Only git's explicit no-matching-ref status proves the tag is absent.
        result.check_returncode()


if __name__ == "__main__":
    require_new_tag(read_version()[0])
