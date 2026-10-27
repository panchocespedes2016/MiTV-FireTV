#!/bin/bash
# MiTV Firestick offline build.
# The build VM's Gradle daemon cannot reach public Maven repositories
# (egress proxy blocks its TLS), so all dependencies were mirrored into
# ./localrepo by tools/mvnwalk.py and the build runs fully --offline
# against that file repository (see settings.gradle.kts).
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk21}"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true -Xmx1024m"
exec "$HOME/gradle-8.7/bin/gradle" --offline --no-daemon \
  -Djava.net.preferIPv4Stack=true "$@"
