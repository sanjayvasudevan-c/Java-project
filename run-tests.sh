#!/usr/bin/env bash
# Compiles src/ + test/ and runs the hand-rolled test harness (AllTests).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$ROOT_DIR/build/test-classes"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

find "$ROOT_DIR/src" "$ROOT_DIR/test" -name '*.java' > "$ROOT_DIR/build/test-sources.txt"
javac -d "$BUILD_DIR" @"$ROOT_DIR/build/test-sources.txt"

java -cp "$BUILD_DIR" com.swarmcron.test.AllTests
