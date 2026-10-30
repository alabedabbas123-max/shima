#!/usr/bin/env bash
# Exercise the supplied sh129 application bridge without unpacking signing material.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [[ $# != 1 || ! -f "$1" ]]; then
    echo "Usage: $0 /path/to/original-sh129.zip" >&2
    exit 2
fi
ARCHIVE=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVAC_PATH=$(command -v javac || true)
    if [[ -z "$JAVAC_PATH" ]]; then echo 'Set JAVA_HOME to a JDK 17 installation.' >&2; exit 2; fi
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$JAVAC_PATH")")")
fi
JAVA_BIN=${JAVA_BIN:-"$JAVA_HOME/bin/java"}
JNI_INCLUDE_DIR=${JNI_INCLUDE_DIR:-"$JAVA_HOME/include"}
if [[ ! -f "$JNI_INCLUDE_DIR/jni.h" ]]; then echo 'JDK JNI headers not found.' >&2; exit 2; fi
if [[ -n "${ECJ_JAR:-}" ]]; then
    COMPILER=("$JAVA_BIN" -jar "$ECJ_JAR" -17)
else
    COMPILER=("$JAVA_HOME/bin/javac" -source 17 -target 17)
fi
WORK=$(mktemp -d "${TMPDIR:-/tmp}/shima-sh129-test.XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/vendor" "$WORK/obj" "$WORK/native" "$WORK/empty" "$WORK/classes" "$WORK/main-classes"
# Extract only Java sources and qa.sh. Never copy the project's signing directory
# or its build.gradle (which may contain signing configuration) into the test tree.
python3 - "$ARCHIVE" "$WORK/project" <<'PY'
import pathlib, stat, sys, zipfile
archive, destination = sys.argv[1:]
destination = pathlib.Path(destination)
with zipfile.ZipFile(archive) as z:
    for entry in z.infolist():
        path = pathlib.PurePosixPath(entry.filename)
        if entry.is_dir():
            continue
        if path.is_absolute() or '..' in path.parts or stat.S_ISLNK(entry.external_attr >> 16):
            raise SystemExit('Unsafe archive entry: ' + entry.filename)
        name = entry.filename
        allowed = name in ('qa.sh', 'qa/RegressionSuite.java', 'analyzer/build.gradle', 'analyzer/src/main/cpp/native_bridge.c') or (
            name.endswith('.java') and name.startswith((
                'app/src/main/java/', 'analyzer/src/main/java/', 'qa/host-stubs/', 'qa/probes/'
            ))
        )
        if not allowed:
            continue
        target = destination.joinpath(*path.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        with z.open(entry) as source, target.open('wb') as output:
            while True:
                data = source.read(65536)
                if not data:
                    break
                output.write(data)
PY
# This cumulative patch starts from the unmodified sh129 archive and includes the prior focus fix.
PATCH="$ROOT/integrations/sh129/quality-from-sh129.patch"
git -C "$WORK/project" apply --check "$PATCH"
git -C "$WORK/project" apply "$PATCH"
P="$WORK/project"
# Rebuild the same parser backend for the host; this is not an Android AAR or APK.
tar -xzf "$ROOT/third_party/parsers-0.25.0-java-0.23.5-kotlin-1.1.0.tar.gz" -C "$WORK/vendor"
V="$WORK/vendor"
CFLAGS=(-std=c11 -D_POSIX_C_SOURCE=200809L -D_DEFAULT_SOURCE -fPIC -O1)
cc "${CFLAGS[@]}" -I"$V/tree-sitter/lib/include" -I"$V/tree-sitter/lib/src" -c "$V/tree-sitter/lib/src/lib.c" -o "$WORK/obj/core.o"
cc "${CFLAGS[@]}" -I"$V/java/src" -c "$V/java/src/parser.c" -o "$WORK/obj/java.o"
cc "${CFLAGS[@]}" -I"$V/kotlin/src" -c "$V/kotlin/src/parser.c" -o "$WORK/obj/kotlin.o"
cc "${CFLAGS[@]}" -I"$V/kotlin/src" -c "$V/kotlin/src/scanner.c" -o "$WORK/obj/scanner.o"
cc "${CFLAGS[@]}" -I"$JNI_INCLUDE_DIR" -I"$JNI_INCLUDE_DIR/linux" -I"$V/tree-sitter/lib/include" -c "$P/analyzer/src/main/cpp/native_bridge.c" -o "$WORK/obj/bridge.o"
cc -shared "$WORK"/obj/*.o -o "$WORK/native/libshima_analyzer.so"
SOURCEPATH="$P/qa/host-stubs:$P/qa/probes:$P/analyzer/src/main/java:$P/app/src/main/java"
mapfile -d '' -t APP_STUBS < <(find "$P/qa/host-stubs" -name '*.java' -print0)
"${COMPILER[@]}" -nowarn -encoding UTF-8 -proc:none -sourcepath "$SOURCEPATH" -d "$WORK/classes" \
    "${APP_STUBS[@]}" \
    "$P/app/src/main/java/almlk/AnalyzerBridge.java" \
    "$P/app/src/main/java/almlk/EditorFocusEdge.java" \
    "$P/app/src/main/java/almlk/AnalyzerSignalQueue.java" \
    "$P/qa/AnalyzerSignalQueueRegression.java" \
    "$ROOT/tests/integration/Sh129SyntaxCheck.java"
"$JAVA_BIN" -ea -Djava.library.path="$WORK/native" -cp "$WORK/classes" AnalyzerSignalQueueRegression native
"$JAVA_BIN" -ea -Djava.library.path="$WORK/empty" -cp "$WORK/classes" AnalyzerSignalQueueRegression unavailable
"$JAVA_BIN" -ea -Djava.library.path="$WORK/native" -cp "$WORK/classes" Sh129SyntaxCheck \
    "$P/app/src/main/java/com/shima/demo/MainActivity.java" \
    "$P/app/src/main/java/almlk/AnalyzerBridge.java" \
    "$P/app/src/main/java/almlk/AnalyzerSignalQueue.java" \
    "$P/app/src/main/java/almlk/AnalyzerWorkspacePlan.java" \
    "$P/app/src/main/java/almlk/LiveDiagnostics.java" \
    "$P/app/src/main/java/com/almlkid/kay/ui/AlmlkEditor.java" \
    "$P/app/src/main/java/com/almlkid/kay/ui/CodeLanguageTools.java" \
    "$P/app/src/main/java/almlk/JavaAttributor.java" \
    "$P/app/src/main/java/almlk/JavaCodeAnalyzer.java" \
    "$P/app/src/main/java/almlk/AnalyzerProtocol.java" \
    "$P/app/src/main/java/almlk/AnalyzerErrorUnit.java" \
    "$P/app/src/main/java/com/almlkid/analyzer/AnalyzerWire.java" \
    "$P/app/src/main/java/com/almlkid/analyzer/AnalyzerConnection.java" \
    "$P/app/src/main/java/com/almlkid/analyzer/AnalyzerService.java" \
    "$P/app/src/main/java/com/almlkid/ui/DependencyCycle.java" \
    "$P/app/src/main/java/com/almlkid/ui/SequentialPanelHost.java"
# The app's host Handler runs post() inline. Substitute the SDK's queued main-loop
# doubles for this separate worker-to-main dispatch regression.
mapfile -d '' -t MAIN_STUBS < <(find "$P/qa/host-stubs" -name '*.java' \
    ! -path '*/android/os/Handler.java' ! -path '*/android/os/Looper.java' -print0)
"${COMPILER[@]}" -nowarn -encoding UTF-8 -proc:none -sourcepath "$SOURCEPATH" -d "$WORK/main-classes" \
    "${MAIN_STUBS[@]}" \
    "$ROOT/tests/host/stubs/android/os/Handler.java" \
    "$ROOT/tests/host/stubs/android/os/Looper.java" \
    "$P/app/src/main/java/almlk/AnalyzerBridge.java" \
    "$ROOT/tests/integration/Sh129MainThreadDispatch.java" \
    "$ROOT/tests/integration/Sh129QualityRegression.java"
"$JAVA_BIN" -ea -Djava.library.path="$WORK/native" -cp "$WORK/main-classes" Sh129MainThreadDispatch
"$JAVA_BIN" -ea -Djava.library.path="$WORK/native" -cp "$WORK/main-classes" Sh129QualityRegression

# Compile the actual generation coordinator against controlled Android/resolver/dialog doubles.
# This does not test real Gradle resolution or Android lifecycle behavior.
mkdir -p "$WORK/dependency-classes"
mapfile -d '' -t DEPENDENCY_STUBS < <(find "$ROOT/tests/integration/dependency-stubs" -name '*.java' -print0)
"${COMPILER[@]}" -nowarn -encoding UTF-8 -proc:none \
    -sourcepath "$ROOT/tests/integration/dependency-stubs" -d "$WORK/dependency-classes" \
    "${DEPENDENCY_STUBS[@]}" \
    "$ROOT/tests/host/stubs/android/os/Handler.java" \
    "$ROOT/tests/host/stubs/android/os/Looper.java" \
    "$P/app/src/main/java/com/almlkid/ui/DependencyCycle.java" \
    "$ROOT/tests/integration/Sh129DependencyCycleRegression.java"
"$JAVA_BIN" -ea -cp "$WORK/dependency-classes" Sh129DependencyCycleRegression

# Exercise the real connection/service protocol with deterministic Android/Messenger doubles.
# This is a JVM transport regression, not an instrumented Binder/device test.
mkdir -p "$WORK/transport-classes"
mapfile -d '' -t TRANSPORT_STUBS < <(find "$ROOT/tests/integration/transport-stubs" -name '*.java' -print0)
"${COMPILER[@]}" -nowarn -encoding UTF-8 -proc:none \
    -classpath "$WORK/main-classes" \
    -sourcepath "$ROOT/tests/integration/transport-stubs:$P/qa/host-stubs:$P/app/src/main/java:$P/analyzer/src/main/java" \
    -d "$WORK/transport-classes" \
    "${TRANSPORT_STUBS[@]}" \
    "$P/app/src/main/java/com/almlkid/analyzer/AnalyzerCarrier.java" \
    "$P/app/src/main/java/com/almlkid/analyzer/AnalyzerWire.java" \
    "$P/app/src/main/java/com/almlkid/analyzer/AnalyzerConnection.java" \
    "$P/app/src/main/java/com/almlkid/analyzer/AnalyzerService.java" \
    "$ROOT/tests/integration/Sh129TransportRegression.java" \
    "$ROOT/tests/integration/Sh129LegacyRegression.java"
"$JAVA_BIN" -ea -Djava.library.path="$WORK/native" -cp "$WORK/transport-classes:$WORK/main-classes" Sh129TransportRegression
"$JAVA_BIN" -ea -Djava.library.path="$WORK/native" -cp "$WORK/transport-classes:$WORK/main-classes" Sh129LegacyRegression
