#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVAC_PATH=$(command -v javac || true)
    if [[ -z "$JAVAC_PATH" ]]; then echo 'Set JAVA_HOME to a JDK 17 installation.' >&2; exit 1; fi
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$JAVAC_PATH")")")
fi
JAVA_BIN=${JAVA_BIN:-"$JAVA_HOME/bin/java"}
JNI_INCLUDE_DIR=${JNI_INCLUDE_DIR:-"$JAVA_HOME/include"}
OS_INCLUDE="$JNI_INCLUDE_DIR/linux"
if [[ ! -f "$JNI_INCLUDE_DIR/jni.h" ]]; then echo 'JDK JNI headers not found.' >&2; exit 1; fi
WORK=$(mktemp -d "${TMPDIR:-/tmp}/shima-host-test.XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/vendor" "$WORK/obj" "$WORK/classes"
tar -xzf "$ROOT/third_party/parsers-0.25.0-java-0.23.5-kotlin-1.1.0.tar.gz" -C "$WORK/vendor"
V="$WORK/vendor"
CFLAGS=(-std=c11 -D_POSIX_C_SOURCE=200809L -D_DEFAULT_SOURCE -fPIC -O1)
cc "${CFLAGS[@]}" -I"$V/tree-sitter/lib/include" -I"$V/tree-sitter/lib/src" -c "$V/tree-sitter/lib/src/lib.c" -o "$WORK/obj/core.o"
cc "${CFLAGS[@]}" -I"$V/java/src" -c "$V/java/src/parser.c" -o "$WORK/obj/java.o"
cc "${CFLAGS[@]}" -I"$V/kotlin/src" -c "$V/kotlin/src/parser.c" -o "$WORK/obj/kotlin.o"
cc "${CFLAGS[@]}" -I"$V/kotlin/src" -c "$V/kotlin/src/scanner.c" -o "$WORK/obj/scanner.o"
cc "${CFLAGS[@]}" -I"$JNI_INCLUDE_DIR" -I"$OS_INCLUDE" -I"$V/tree-sitter/lib/include" -c "$ROOT/analyzer/src/main/cpp/native_bridge.c" -o "$WORK/obj/bridge.o"
cc -shared "$WORK"/obj/*.o -o "$WORK/libshima_analyzer.so"
# Android-only UI helpers are compiled against real Android stubs by check-api21.sh.
find "$ROOT/analyzer/src/main/java" "$ROOT/tests/host/stubs" -name '*.java' ! -path '*/com/shima/analyzer/ui/*' -print > "$WORK/sources.txt"
echo "$ROOT/tests/host/HostTests.java" >> "$WORK/sources.txt"
if [[ -n "${ECJ_JAR:-}" ]]; then
    "$JAVA_BIN" -jar "$ECJ_JAR" -1.8 -encoding UTF-8 -proc:none -d "$WORK/classes" @"$WORK/sources.txt"
else
    "$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 -d "$WORK/classes" @"$WORK/sources.txt"
fi
"$JAVA_BIN" -ea -Djava.library.path="$WORK" -cp "$WORK/classes" HostTests
