#!/usr/bin/env python3
"""Create the full, source-only sh129 deliverable without publishing signing material."""
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ORIGINAL_SHA256 = "c17b0635e64cb55cda8a0c69d1afcee357a9b7f79e285d76a08041a916d562f8"
PATCH = ROOT / "integrations/sh129/quality-from-sh129.patch"
GUIDE = ROOT / "integrations/sh129/FULL_PROJECT.ar.md"
OUTPUT = ROOT / "integrations/sh129/sh129-complete-adjusted.zip"


def excluded(name):
    path = PurePosixPath(name)
    lower = name.lower()
    return (lower.startswith("signing/") or path.name.lower() in {"key.properties", "local.properties", ".env"}
            or lower.endswith((".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"))
            or lower.startswith("app/src/main/jnilibs/"))


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: package-sh129-full.py /path/to/original-sh129.zip")
    archive = Path(sys.argv[1]).resolve()
    if hashlib.sha256(archive.read_bytes()).hexdigest() != ORIGINAL_SHA256:
        raise SystemExit("Original archive does not match the expected SHA-256")
    with tempfile.TemporaryDirectory(prefix="shima-full-project-") as temporary:
        target = Path(temporary)
        modes = {}
        with zipfile.ZipFile(archive) as original:
            app_gradle = original.read("app/build.gradle").decode("utf-8")
            secrets = []
            for key in ("storePassword", "keyPassword"):
                match = re.search(r"(?m)^\s*" + key + r"\s*=?\s*['\"]([^'\"]+)['\"]", app_gradle)
                if not match or len(match.group(1)) < 8:
                    raise SystemExit("Could not safely identify original signing configuration")
                secrets.append(match.group(1).encode("utf-8"))
            for info in original.infolist():
                name = info.filename
                path = PurePosixPath(name)
                if path.is_absolute() or ".." in path.parts or stat.S_ISLNK(info.external_attr >> 16):
                    raise SystemExit("Unsafe archive entry: " + name)
                if info.is_dir() or excluded(name):
                    continue
                dest = target.joinpath(*path.parts)
                dest.parent.mkdir(parents=True, exist_ok=True)
                data = original.read(info)
                # Redact occurrences in reports or text; no content or credential is printed.
                if b"\0" not in data:
                    for secret in secrets:
                        data = data.replace(secret, b"[REDACTED]")
                dest.write_bytes(data)
                modes[name] = (info.external_attr >> 16) & 0o777 or 0o644
        subprocess.run(["git", "apply", "--check", str(PATCH)], cwd=target, check=True)
        subprocess.run(["git", "apply", str(PATCH)], cwd=target, check=True)
        gradle = target / "app/build.gradle"
        content = gradle.read_text(encoding="utf-8")
        content, removed = re.subn(r"(?ms)^    signingConfigs \{.*?^    \}\s*\n", "", content, count=1)
        if removed != 1:
            raise SystemExit("Unexpected signing block in app/build.gradle")
        content, debug_removed = re.subn(r"debug\s*\{\s*signingConfig\s+signingConfigs\.stable\s*\}", "debug { }", content)
        content, release_removed = re.subn(r"(?m)^\s*signingConfig\s+signingConfigs\.stable\s*\n", "\n", content)
        if debug_removed != 1 or release_removed != 1 or "signingConfigs.stable" in content:
            raise SystemExit("Unexpected signing references in app/build.gradle")
        gradle.write_text(content, encoding="utf-8")
        (target / "README_SHIMA_UPDATE.ar.md").write_bytes(GUIDE.read_bytes())
        # Carry current SDK documentation rather than the older copy from the app archive.
        for doc in (ROOT / "docs").glob("*.md"):
            destination = target / "analyzer/docs" / doc.name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(doc.read_bytes())
        for file in target.rglob("*"):
            if file.is_file():
                data = file.read_bytes()
                if any(secret in data for secret in secrets):
                    raise SystemExit("Original signing credential still present in " + str(file.relative_to(target)))
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(OUTPUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as result:
            for file in sorted(p for p in target.rglob("*") if p.is_file()):
                name = file.relative_to(target).as_posix()
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                mode = modes.get(name, 0o644)
                if name == "gradlew" or name.endswith(".sh"):
                    mode |= 0o111
                info.external_attr = (stat.S_IFREG | mode) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                result.writestr(info, file.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    print(f"Created {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size} bytes)")
    print("SHA-256", hashlib.sha256(OUTPUT.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
