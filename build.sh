#!/usr/bin/env bash
# Compiles src/ into dist/swarmcron.jar. javac and jar only, nothing else.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$ROOT_DIR/src"
BUILD_DIR="$ROOT_DIR/build/classes"
DIST_DIR="$ROOT_DIR/dist"
JAR_PATH="$DIST_DIR/swarmcron.jar"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$DIST_DIR"

echo "Compiling sources from $SRC_DIR ..."
find "$SRC_DIR" -name '*.java' > "$ROOT_DIR/build/sources.txt"
javac -d "$BUILD_DIR" @"$ROOT_DIR/build/sources.txt"

echo "Packaging $JAR_PATH ..."
jar --create --file "$JAR_PATH" --main-class com.swarmcron.Main -C "$BUILD_DIR" .

echo "Built $JAR_PATH"
