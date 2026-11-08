#!/usr/bin/env python3
"""Select bounded routine TV checks; full coverage stays available and runs weekly."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SMOKE = [
    "encryptedStorageAuthenticatesDataAndNeverStoresPlaintext",
    "networkImportPreviewRemoteFavoritesAndResume",
    "cachedStartupDoesNotDownloadVodOrSweepUnvisitedChannels",
]

def select(suite, request):
    methods = {}
    for path in (ROOT / "app/src/androidTest/java/io/github/mot1us/spare").glob("*Test.kt"):
        for name in re.findall(r"@Test\s+fun\s+(\w+)\s*\(", path.read_text()):
            if name in methods:
                raise ValueError("Ambiguous TV test: " + name)
            methods[name] = "io.github.mot1us.spare." + path.stem + "#" + name
    if suite == "full":
        return "", len(methods)
    if suite != "focused":
        raise ValueError("Unknown TV suite: " + str(suite))
    targets = request.get("tvTests", [])
    if not isinstance(targets, list) or any(not isinstance(name, str) for name in targets):
        raise ValueError("tvTests must be a list of test method names")
    chosen = list(dict.fromkeys(SMOKE + targets))
    missing = [name for name in chosen if name not in methods]
    if missing:
        raise ValueError("Unknown TV tests: " + ", ".join(missing))
    return ",".join(methods[name] for name in chosen), len(chosen)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", choices=["full", "focused"], default="full")
    args = parser.parse_args()
    classes, count = select(args.suite, json.loads((ROOT / "release-request.json").read_text()))
    print(json.dumps({"suite": args.suite, "classes": classes, "count": count}))
