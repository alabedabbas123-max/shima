#!/usr/bin/env bash
set -euo pipefail
AAR=${1:-analyzer/build/outputs/aar/analyzer-release.aar}
if [[ ! -f "$AAR" ]]; then echo "AAR not found: $AAR" >&2; exit 1; fi
WORK=$(mktemp -d "${TMPDIR:-/tmp}/shima-aar-check.XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT
unzip -Z -1 "$AAR" > "$WORK/entries"
require_entry() {
    if ! grep -Fxq "$1" "$WORK/entries"; then echo "Missing AAR entry: $1" >&2; exit 1; fi
}
require_entry AndroidManifest.xml
require_entry classes.jar
require_entry proguard.txt
require_entry assets/shima-analyzer/THIRD_PARTY_NOTICES.txt
for abi in armeabi-v7a arm64-v8a x86 x86_64; do require_entry "jni/$abi/libshima_analyzer.so"; done
unzip -p "$AAR" classes.jar > "$WORK/classes.jar"
unzip -Z -1 "$WORK/classes.jar" > "$WORK/classes"
grep -Fxq 'com/shima/analyzer/CodeAnalyzer.class' "$WORK/classes"
grep -Fxq 'com/shima/analyzer/core/NativeBridge.class' "$WORK/classes"
echo "AAR structure verified: $AAR"
