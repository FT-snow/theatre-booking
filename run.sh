#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${JAVA_HOME:-}" && -x "$HOME/.local/opt/jdk-21/usr/lib/jvm/java-21-openjdk-amd64/bin/java" ]]; then
    export JAVA_HOME="$HOME/.local/opt/jdk-21/usr/lib/jvm/java-21-openjdk-amd64"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
    export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=$JAVA_HOME/lib/security/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi

export DB_USER="${DB_USER:-root}"

if [[ -z "${DB_PASSWORD:-}" ]]; then
    read -r -s -p "MySQL password: " DB_PASSWORD
    printf '\n'
    export DB_PASSWORD
fi

exec ./mvnw compile exec:java
