#!/bin/sh
# Runs the dependency-free JVM tests (no Android SDK needed). Needs a JDK, and network on the first run for org.json.
set -eu
cd "$(dirname "$0")/.."
out=build/core-tests
jar=build/json-20240303.jar
src=app/src/main/java/local/byd/arrival
tests=app/src/test/java/local/byd/arrival
tables=app/src/main/assets/bangcle_tables.bin
mkdir -p "$out"
[ -f "$jar" ] || curl -fsSL -o "$jar" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
echo "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  $jar" | shasum -a 256 -c - >/dev/null
javac -nowarn -cp "$jar" -d "$out" \
  "$src/Bangcle.java" "$src/Crypto.java" "$src/BydApi.java" "$src/Actions.java" "$src/ClimatePlan.java" \
  "$src/Routine.java" "$src/ArrivalGate.java" "$src/ProximityGate.java" "$src/UpdateCheck.java" "$src/Regions.java" \
  "$tests/CoreTest.java" "$tests/ApiTest.java" tools/*.java
java -cp "$jar:$out" local.byd.arrival.CoreTest "$tables" tools/cipher-vectors.txt
java -cp "$jar:$out" local.byd.arrival.ApiTest "$tables"
