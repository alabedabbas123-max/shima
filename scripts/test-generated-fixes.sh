#!/usr/bin/env bash
# Run the real JNI host tests, then compile representative generated fixes against API 21.
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
    export JAVA_HOME
fi
WORK=$(mktemp -d "${TMPDIR:-/tmp}/shima-generated-fixes.XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT
export SHIMA_FIXTURE_DIR="$WORK/fixtures"
"$ROOT/scripts/test-host.sh"
find "$WORK/fixtures" -name '*.java' -print > "$WORK/sources.txt"
if [[ -n "${ECJ_JAR:-}" ]]; then
    "$JAVA_HOME/bin/java" -jar "$ECJ_JAR" -nowarn -1.8 -encoding UTF-8 -proc:none -bootclasspath "$ANDROID_JAR" -d "$WORK/classes" @"$WORK/sources.txt"
else
    "$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 -proc:none -bootclasspath "$ANDROID_JAR" -d "$WORK/classes" @"$WORK/sources.txt"
fi
echo 'Representative generated Java fixes compile against the supplied API 21 stubs.'
