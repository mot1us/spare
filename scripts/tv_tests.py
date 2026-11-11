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

def java_hash(text):
    """String.hashCode, as AndroidJUnitRunner's ShardingFilter uses it to place each test."""
    value = 0
    for unit in text.encode("utf-16-be").hex(" ", 2).split():
        value = (31 * value + int(unit, 16)) & 0xFFFFFFFF
    return value - (1 << 32) if value >= 1 << 31 else value

def in_shard(test, shard, shards):
    # Description.hashCode() is the hash of "method(fully.qualified.Class)".
    cls, method = test.split("#")
    value = java_hash(method + "(" + cls + ")")
    return abs(value) % shards == shard if value != -(1 << 31) else value % shards == shard

def shard_count(tests, shard, shards):
    return sum(1 for test in tests if in_shard(test, shard, shards))

def select(suite, request, shard=0, shards=1):
    methods = {}
    for path in (ROOT / "app/src/androidTest/java/io/github/mot1us/spare").glob("*Test.kt"):
        for name in re.findall(r"@Test\s+fun\s+(\w+)\s*\(", path.read_text()):
            if name in methods:
                raise ValueError("Ambiguous TV test: " + name)
            methods[name] = "io.github.mot1us.spare." + path.stem + "#" + name
    if suite == "full":
        return "", shard_count(methods.values(), shard, shards)
    if suite != "focused":
        raise ValueError("Unknown TV suite: " + str(suite))
    targets = request.get("tvTests", [])
    if not isinstance(targets, list) or any(not isinstance(name, str) for name in targets):
        raise ValueError("tvTests must be a list of test method names")
    chosen = list(dict.fromkeys(SMOKE + targets))
    missing = [name for name in chosen if name not in methods]
    if missing:
        raise ValueError("Unknown TV tests: " + ", ".join(missing))
    return ",".join(methods[name] for name in chosen), shard_count([methods[name] for name in chosen], shard, shards)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", choices=["full", "focused"], default="full")
    parser.add_argument("--shard", type=int, default=0)
    parser.add_argument("--shards", type=int, default=1)
    args = parser.parse_args()
    classes, count = select(args.suite, json.loads((ROOT / "release-request.json").read_text()), args.shard, args.shards)
    print(json.dumps({"suite": args.suite, "classes": classes, "count": count}))
