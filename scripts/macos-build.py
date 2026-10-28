#!/usr/bin/env python3
"""Build the native Mac preview with a checksum-pinned, unmodified VLC runtime."""
import argparse
import hashlib
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MAC = ROOT / "macos"
CACHE = MAC / "build"
VLC_VERSION = "3.0.23"
RUNTIME_SHA = "56ee657c3aaf5c71b4ab7d6e4f4a77f6eca54633e0bf42a93b8116eb1d1f6ec9"
SOURCE_SHA = "e891cae6aa3ccda69bf94173d5105cbc55c7a7d9b1d21b9b21666e69eff3e7e0"

def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)

def download(url, path, checksum):
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(url, timeout=60) as response, path.open("wb") as output:
            shutil.copyfileobj(response, output)
    if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        raise SystemExit("The playback runtime checksum does not match the pinned release.")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--vlc-app", type=Path, help="Use an already verified local VLC bundle for development.")
    parser.add_argument("--source-archive", type=Path)
    parser.add_argument("--arch", choices=["universal", "arm64", "x86_64"], default="universal")
    parser.add_argument("--skip-build", action="store_true", help="Repackage an already built executable.")
    args = parser.parse_args()
    tracked = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines() if "=" in line)
    version, code = tracked["versionName"], tracked["versionCode"]
    CACHE.mkdir(exist_ok=True)
    env = os.environ.copy()
    env["CLANG_MODULE_CACHE_PATH"] = str(CACHE / "ModuleCache")
    env["SWIFTPM_MODULECACHE_OVERRIDE"] = str(CACHE / "ModuleCache")
    common = ["--disable-sandbox", "--scratch-path", str(CACHE), "--cache-path", str(CACHE / "cache"),
              "--config-path", str(CACHE / "config"), "--security-path", str(CACHE / "security")]
    arches = ["arm64", "x86_64"] if args.arch == "universal" else [args.arch]
    arch_flags = [item for arch in arches for item in ["--arch", arch]]
    if not args.skip_build:
        run("swift", "build", "-c", "release", *common, *arch_flags, cwd=MAC, env=env)
    result = run("swift", "build", "-c", "release", *common, *arch_flags, "--show-bin-path",
                 cwd=MAC, env=env, capture_output=True, text=True)
    executable = Path(result.stdout.strip().splitlines()[-1]) / "Spare"
    if not executable.is_file():
        raise SystemExit("Swift did not produce the expected executable.")
    source = args.source_archive or CACHE / ("vlc-" + VLC_VERSION + ".tar.xz")
    download("https://download.videolan.org/pub/videolan/vlc/" + VLC_VERSION + "/vlc-" + VLC_VERSION + ".tar.xz", source, SOURCE_SHA)
    mount = CACHE / "vlc-mounted"
    attached = False
    try:
        if args.vlc_app:
            vlc = args.vlc_app
            metadata = plistlib.loads((vlc / "Contents/Info.plist").read_bytes())
            if metadata["CFBundleShortVersionString"] != VLC_VERSION:
                raise SystemExit("The local VLC runtime has the wrong version.")
        else:
            image = CACHE / ("vlc-" + VLC_VERSION + "-universal.dmg")
            download("https://download.videolan.org/pub/videolan/vlc/" + VLC_VERSION + "/macosx/" + image.name, image, RUNTIME_SHA)
            mount.mkdir(exist_ok=True)
            run("hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount, image)
            attached = True
            vlc = mount / "VLC.app"
        dist = ROOT / "dist/macos"
        dist.mkdir(parents=True, exist_ok=True)
        app = dist / "Spare.app"
        if app.exists():
            shutil.rmtree(app)
        contents = app / "Contents"
        resources = contents / "Resources"
        (contents / "MacOS").mkdir(parents=True)
        resources.mkdir()
        shutil.copy2(executable, contents / "MacOS/Spare")
        run("install_name_tool", "-add_rpath", "@executable_path/../Frameworks/VLCRuntime/lib", contents / "MacOS/Spare")
        runtime = contents / "Frameworks/VLCRuntime"
        runtime.mkdir(parents=True)
        for folder in ["lib", "plugins"]:
            shutil.copytree(vlc / "Contents/MacOS" / folder, runtime / folder, symlinks=True)
        for cache in runtime.rglob("plugins.dat"):
            cache.unlink()
        (resources / "LICENSE-MIT.txt").write_bytes((ROOT / "LICENSE").read_bytes())
        (resources / "THIRD-PARTY-NOTICES.md").write_bytes((MAC / "THIRD-PARTY-NOTICES.md").read_bytes())
        with tarfile.open(source) as archive:
            for filename in ["COPYING", "COPYING.LIB", "AUTHORS"]:
                matches = [entry for entry in archive.getmembers() if entry.name == "vlc-" + VLC_VERSION + "/" + filename]
                if matches:
                    (resources / ("VLC-" + filename + ".txt")).write_bytes(archive.extractfile(matches[0]).read())
        run("swift", ROOT / "scripts/macos-icon.swift", resources / "Spare.icns", env=env)
        plist = {
            "CFBundleIdentifier": "io.github.mot1us.spare.mac",
            "CFBundleName": "Spare", "CFBundleDisplayName": "Spare",
            "CFBundleExecutable": "Spare", "CFBundlePackageType": "APPL",
            "CFBundleShortVersionString": version, "CFBundleVersion": code,
            "CFBundleIconFile": "Spare", "LSMinimumSystemVersion": "13.0",
            "NSHighResolutionCapable": True, "NSPrincipalClass": "NSApplication",
            "NSAppTransportSecurity": {"NSAllowsArbitraryLoads": True},
            "NSHumanReadableCopyright": "Spare contributors. MIT app source; VLC runtime licenses included.",
        }
        (contents / "Info.plist").write_bytes(plistlib.dumps(plist))
        for library in sorted(runtime.rglob("*.dylib")):
            if not library.is_symlink():
                run("codesign", "--force", "--sign", "-", "--timestamp=none", library, capture_output=True)
        run("codesign", "--force", "--deep", "--sign", "-", "--timestamp=none", app)
        run("codesign", "--verify", "--deep", "--strict", app)
        for arch in arches:
            run("lipo", contents / "MacOS/Spare", "-verify_arch", arch)
            run("lipo", runtime / "lib/libvlc.5.dylib", "-verify_arch", arch)
        name = "Spare-" + version + "-macOS" + ("" if args.arch == "universal" else "-" + args.arch) + ".zip"
        output = dist / name
        if output.exists():
            output.unlink()
        run("ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", app, output)
        source_output = dist / source.name
        shutil.copy2(source, source_output)
        (dist / "MACOS-SHA256SUMS").write_text("".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in [output, source_output]))
        print("Mac preview ready: " + str(output))
    finally:
        if attached:
            run("hdiutil", "detach", mount)

if __name__ == "__main__":
    main()
