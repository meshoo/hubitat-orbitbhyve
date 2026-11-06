#!/usr/bin/env bash
# Off-hub checks for this repo (they don't replace testing on a real hub; see CLAUDE.md).
#   1. CompileCheck: every app/driver parses and compiles with Groovy 2.4 (Hubitat's version).
#   2. V2Harness:     runs the v2 app and drivers with stubbed Hubitat APIs against fake B-hyve data.
# Needs Java. Downloads groovy-all 2.4.21 once into ${CACHE_DIR:-$HOME/.cache/hubitat-orbitbhyve}.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${CACHE_DIR:-$HOME/.cache/hubitat-orbitbhyve}"
JAR="$CACHE/groovy-all-2.4.21.jar"
mkdir -p "$CACHE/stub-classes"
if [ ! -s "$JAR" ] || ! unzip -tq "$JAR" >/dev/null 2>&1; then
  curl -fsSL -o "$JAR" https://repo1.maven.org/maven2/org/codehaus/groovy/groovy-all/2.4.21/groovy-all-2.4.21.jar
fi
java -cp "$JAR" org.codehaus.groovy.tools.FileSystemCompiler -d "$CACHE/stub-classes" \
  "$ROOT/tests/stubs/groovyx/net/http/HttpResponseException.groovy" >/dev/null
CP="$JAR:$CACHE/stub-classes"
cd "$ROOT"
echo "== compile-check"
java -cp "$CP" groovy.ui.GroovyMain tests/CompileCheck.groovy apps/*.groovy apps/tools/*.groovy drivers/*.groovy v2/apps/*.groovy v2/drivers/*.groovy
echo "== v2-harness"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" groovy.ui.GroovyMain tests/V2Harness.groovy "$ROOT"
