#!/bin/sh
# Builds Babble, then renders examples/raytracer-fable/raytracer.ba to examples/raytracer-fable/demo.png.
set -e
cd "$(dirname "$0")/../.."
mvn -q -B -DskipTests package dependency:build-classpath -Dmdep.includeScope=runtime -Dmdep.outputFile=target/classpath.txt
exec java -cp "target/classes:$(cat target/classpath.txt)" examples/raytracer-fable/Run.java "${1:-examples/raytracer-fable/raytracer.ba}"
