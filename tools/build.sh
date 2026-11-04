#!/bin/bash
# MiTV Firestick build (this VM: fully offline).
# The build VM's Gradle daemon cannot reach public Maven repositories
# (egress proxy blocks its TLS), so all dependencies were mirrored into
# ./localrepo by tools/mvnwalk.py and the build runs --offline against
# that file repository (see settings.gradle.kts).
#
# On a normal machine with internet, use the wrapper directly instead:
#   ./gradlew assembleRelease
# (public repos in settings.gradle.kts resolve the same pinned versions;
#  no localrepo needed. A release build without the keystore in
#  keystore/ comes out UNSIGNED — see "Firma" in README.md.)
#
# Overrides for this script:
#   MITV_GRADLE=./gradlew (or another Gradle binary)
#   MITV_ONLINE=1         (drop --offline; needs reachable Maven repos)
#   JAVA_HOME, GRADLE_HOME (defaults: $HOME/jdk21, $HOME/gradle-8.7)
set -e
cd "$(dirname "$0")/.."
if [ -z "$JAVA_HOME" ] && [ -d "$HOME/jdk21" ]; then
    export JAVA_HOME="$HOME/jdk21"
fi
if [ -n "$JAVA_HOME" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true -Xmx1024m"
GRADLE_BIN="${MITV_GRADLE:-${GRADLE_HOME:-$HOME/gradle-8.7}/bin/gradle}"
OFFLINE=(--offline)
if [ "${MITV_ONLINE:-0}" = "1" ]; then OFFLINE=(); fi
exec "$GRADLE_BIN" --no-daemon \
  -Djava.net.preferIPv4Stack=true "${OFFLINE[@]}" "$@"
