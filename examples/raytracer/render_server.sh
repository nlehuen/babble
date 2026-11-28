#!/usr/bin/env bash
# Compiles examples/raytracer/server/*.java once into target/raytracer-server-classes
# (recompiling only when sources or babble.jar are newer) and starts RenderServer.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
JAR="$REPO_ROOT/target/babble-1.0-SNAPSHOT.jar"
CLASSES_DIR="$REPO_ROOT/target/raytracer-server-classes"
SRC_DIR="$SCRIPT_DIR/server"

if [[ ! -f "$JAR" ]]; then
    echo "Error : $JAR not found. Run 'mvn package -DskipTests' first." >&2
    exit 1
fi

NEEDS_COMPILE=0
if [[ ! -f "$CLASSES_DIR/org/babblelang/raytracer/server/RenderServer.class" ]]; then
    NEEDS_COMPILE=1
else
    for src in "$SRC_DIR"/*.java; do
        if [[ "$src" -nt "$CLASSES_DIR/org/babblelang/raytracer/server/RenderServer.class" ]]; then
            NEEDS_COMPILE=1
            break
        fi
    done
    if [[ "$JAR" -nt "$CLASSES_DIR/org/babblelang/raytracer/server/RenderServer.class" ]]; then
        NEEDS_COMPILE=1
    fi
fi

if [[ "$NEEDS_COMPILE" -eq 1 ]]; then
    mkdir -p "$CLASSES_DIR"
    javac --release 21 -cp "$JAR:$REPO_ROOT/target/lib/*" -d "$CLASSES_DIR" "$SRC_DIR"/*.java
fi

exec java -cp "$CLASSES_DIR:$JAR:$REPO_ROOT/target/lib/*" \
    org.babblelang.raytracer.server.RenderServer \
    --root "$SCRIPT_DIR" \
    "$@"
