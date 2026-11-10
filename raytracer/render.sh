#!/bin/sh
# Builds Babble, then renders raytracer/raytracer.ba to raytracer/demo.png.
set -e
cd "$(dirname "$0")/.."
mvn -q -B -DskipTests package dependency:build-classpath -Dmdep.includeScope=runtime -Dmdep.outputFile=target/classpath.txt
exec java -cp "target/classes:$(cat target/classpath.txt)" raytracer/Run.java "${1:-raytracer/raytracer.ba}"
