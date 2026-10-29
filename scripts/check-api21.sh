#!/usr/bin/env bash
# Compile the production Java sources against API 21 stubs; this is not a device test.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [[ $# -ne 1 || ! -f "$1" ]]; then
    echo "Usage: $0 /path/to/platforms/android-21/android.jar" >&2
    exit 1
fi
ANDROID_JAR=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_PATH=$(command -v java || true)
    if [[ -z "$JAVA_PATH" ]]; then echo 'Set JAVA_HOME to a JDK 17 installation.' >&2; exit 1; fi
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$JAVA_PATH")")")
fi
WORK=$(mktemp -d "${TMPDIR:-/tmp}/shima-api21-check.XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes"
find "$ROOT/analyzer/src/main/java" -name '*.java' -print > "$WORK/sources.txt"
if [[ -n "${ECJ_JAR:-}" ]]; then
    "$JAVA_HOME/bin/java" -jar "$ECJ_JAR" -1.8 -encoding UTF-8 -proc:none -bootclasspath "$ANDROID_JAR" -d "$WORK/classes" @"$WORK/sources.txt"
else
    "$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 -proc:none -bootclasspath "$ANDROID_JAR" -d "$WORK/classes" @"$WORK/sources.txt"
fi
echo 'Production Java sources compile against the supplied API 21 stubs.'
