#!/usr/bin/env bash
# Benchmark do Argon2id (contrato 8.10, §4) no container, com o PasswordHasher REAL da Platform.
#
# PERFIL DE REFERENCIA DECLARADO (nao e o de producao, que ainda nao foi definido): eclipse-temurin:21-jre-alpine,
# --cpus=$BENCH_CPUS (padrao 2) e --memory=$BENCH_MEMORIA (padrao 1g), sem swap extra, JVM com
# -XX:MaxRAMPercentage=75 (a mesma do Dockerfile de api/e2e). Um container NOVO por perfil de custo. Os numeros
# medidos sao PROVISORIOS: servem para comparar perfis e dimensionar o semaforo, nao para fixar m/t.
#
# Nao usa banco, rede nem segredo. Uso: api/bench-argon2/run.sh
#      BENCH_CPUS=1 BENCH_MEMORIA=512m BENCH_PERFIS="19456:2 65536:3" api/bench-argon2/run.sh
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
API="$(cd "$AQUI/.." && pwd)"
CPUS="${BENCH_CPUS:-2}"
MEMORIA="${BENCH_MEMORIA:-1g}"
PERFIS="${BENCH_PERFIS:-19456:2 47104:1 65536:3 131072:3}"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_cpus${CPUS}_mem${MEMORIA}"
mkdir -p "$SAIDA/classes"

echo "== build (jar da Platform e classes de teste)"
(cd "$API" && ./mvnw -q -DskipTests package -pl nekkora-platform -am && ./mvnw -q -DskipTests test-compile -pl nekkora-platform)
cp "$API/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar" "$SAIDA/app.jar"
mkdir -p "$SAIDA/classes/com/nekkora/platform/auth/crypto"
cp "$API"/nekkora-platform/target/test-classes/com/nekkora/platform/auth/crypto/Argon2Benchmark.class "$SAIDA/classes/com/nekkora/platform/auth/crypto/"

{
    echo "perfil de referencia: eclipse-temurin:21-jre-alpine --cpus=$CPUS --memory=$MEMORIA --memory-swap=$MEMORIA, -XX:MaxRAMPercentage=75"
    echo "host: $(docker info --format '{{.OperatingSystem}} | {{.NCPU}} CPUs | {{.MemTotal}} bytes | kernel {{.KernelVersion}}')"
    echo "data: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
} > "$SAIDA/resultado.txt"

for p in $PERFIS; do
    m="${p%%:*}"
    t="${p##*:}"
    echo "== m=$m t=$t"
    MSYS_NO_PATHCONV=1 docker run --rm --cpus="$CPUS" --memory="$MEMORIA" --memory-swap="$MEMORIA" \
        -v "$(cygpath -w "$SAIDA" 2>/dev/null || echo "$SAIDA"):/bench:ro" eclipse-temurin:21-jre-alpine \
        java -XX:MaxRAMPercentage=75 -Dloader.path=/bench/classes -Dloader.main=com.nekkora.platform.auth.crypto.Argon2Benchmark \
        -cp /bench/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$m" "$t" | tee -a "$SAIDA/resultado.txt"
    echo >> "$SAIDA/resultado.txt"
done
rm -rf "$SAIDA/classes" "$SAIDA/app.jar"
echo "resultado: $SAIDA/resultado.txt"
