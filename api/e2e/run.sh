#!/usr/bin/env bash
# E2E entre processos da API v1: nginx (origem unica, TLS, limit_req) -> Platform (nekkora_platform) e Portal
# (processo e papel nekkora_portal proprios) -> PostgreSQL 16 (001-015, instalacao limpa) e Redis.
#
# Sobe uma stack Compose NOVA e ISOLADA (projeto, rede 10.251.47.0/24 e portas proprias; sem volume nomeado).
# Nunca toca a stack principal (nekkora-platform / nekkora-postgres / volume principal / porta 5432) nem as
# stacks de teste existentes; nunca executa "down -v" nem limpeza global. Segredos (senha do superusuario e
# dos papeis, chaves AES/HMAC) sao ALEATORIOS por execucao, so em variaveis de ambiente deste processo; o
# certificado TLS e descartavel e a chave privada e apagada assim que o nginx a carrega. Nada disso vai para
# arquivo versionado. A stack fica no ar ao final; o comando de remocao e impresso.
#
# Uso: api/e2e/run.sh
#      E2E_PROJETO=nekkora-e2e-b E2E_PORTA=55453 E2E_PG_PORTA=55454 api/e2e/run.sh
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
API="$(cd "$AQUI/.." && pwd)"
REPO="$(cd "$API/.." && pwd)"
export E2E_PROJETO="${E2E_PROJETO:-nekkora-e2e}"
export E2E_PORTA="${E2E_PORTA:-55443}"
export E2E_PG_PORTA="${E2E_PG_PORTA:-55444}"
RODADA="$(date -u +%Y-%m-%dT%H%M%SZ)_${E2E_PROJETO}"
SAIDA="$AQUI/saidas/$RODADA"

falha() { echo "ERRO: $*" >&2; exit 1; }

# --- protecao da stack principal e de recursos existentes ---
case "$E2E_PROJETO" in nekkora-platform|nekkora-portal-dev|nekkora-portal-013) falha "projeto '$E2E_PROJETO' pertence a outra stack";; esac
for p in "$E2E_PORTA" "$E2E_PG_PORTA"; do
    [ "$p" != "5432" ] || falha "porta 5432 e da stack principal"
    if docker ps --format '{{.Ports}}' | grep -q ":$p->"; then falha "porta $p ja publicada por outro container"; fi
    if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then falha "porta $p ja em uso no host"; fi
done
[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$E2E_PROJETO")" ] || falha "projeto $E2E_PROJETO ja tem containers"
docker network inspect "${E2E_PROJETO}_e2e" >/dev/null 2>&1 && falha "rede ${E2E_PROJETO}_e2e ja existe"

mkdir -p "$SAIDA/migracoes_montadas" "$SAIDA/tls"
# Migrations: 001-015 escolhidas explicitamente (instalacao limpa).
for n in $(seq -w 1 15); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/migracoes_montadas/"
done
export E2E_MIGRACOES="./saidas/$RODADA/migracoes_montadas"
export E2E_TLS="./saidas/$RODADA/tls"

aleatorio() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
senha() { head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
export E2E_PG_SUPER_SENHA="$(senha)" E2E_PLATFORM_SENHA="$(senha)" E2E_PORTAL_SENHA="$(senha)"
export E2E_MFA_CHAVES="k1:$(aleatorio 32)" E2E_HMAC_CHAVE="$(aleatorio 32)"

# caminhos relativos: MSYS2_ARG_CONV_EXCL (necessario para o -subj) tambem impediria converter caminhos absolutos
(cd "$SAIDA/tls" && MSYS2_ARG_CONV_EXCL='*' openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost" -keyout key.pem -out cert.pem 2>/dev/null) || falha "certificado TLS descartavel"

compose() { docker compose -f "$AQUI/compose.e2e.yml" "$@"; }

echo "== build dos jars (sem testes)"
(cd "$API" && ./mvnw -q -DskipTests package)

echo "== postgres e redis ($E2E_PROJETO)"
compose up -d postgres redis
for i in $(seq 1 90); do
    [ "$(compose logs postgres 2>/dev/null | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
    [ "$i" -lt 90 ] || falha "PostgreSQL nao terminou a inicializacao"
    sleep 2
done
# Senhas dos papeis LOGIN reais (so pelo stdin; nada em arquivo). Os demais papeis ficam sem senha.
printf "ALTER ROLE nekkora_platform PASSWORD '%s';\nALTER ROLE nekkora_portal PASSWORD '%s';\n" "$E2E_PLATFORM_SENHA" "$E2E_PORTAL_SENHA" \
    | compose exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U nekkora -d nekkora
compose exec -T postgres psql -X -At -U nekkora -d nekkora -c "SELECT count(*) FROM pg_proc WHERE proname LIKE 'portal\_%'" > "$SAIDA/funcoes_portal.txt"

echo "== platform, portal e nginx"
compose up -d --build platform portal nginx
BASE="https://localhost:$E2E_PORTA"
for i in $(seq 1 90); do
    a="$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/api/v1/csrf" || true)"
    b="$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/portal/api/v1/o/00000000-0000-4000-8000-000000000000/workspaces" || true)"
    [ "$a" = "200" ] && [ "$b" = "401" ] && break
    [ "$i" -lt 90 ] || { compose logs --no-color > "$SAIDA/logs_falha_subida.txt"; falha "aplicacoes nao responderam (platform=$a portal=$b)"; }
    sleep 2
done
rm -f "$SAIDA/tls/key.pem"   # o nginx ja carregou; a chave nao fica em disco

echo "== separacao de credenciais (ambiente dos containers)"
env_portal="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$(compose ps -q portal)" | cut -d= -f1)"
env_platform="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$(compose ps -q platform)" | cut -d= -f1)"
echo "$env_portal" | grep -qE '^NEKKORA_(DB_URL|DB_SENHA|MFA_|HMAC_)' && falha "portal recebeu variavel da Platform"
echo "$env_platform" | grep -q '^NEKKORA_PORTAL_' && falha "platform recebeu variavel do Portal"
{ echo "portal:"; echo "$env_portal" | grep NEKKORA_; echo "platform:"; echo "$env_platform" | grep NEKKORA_; } > "$SAIDA/variaveis_por_processo.txt"

echo "== cliente E2E (so pelo nginx)"
export E2E_BASE="$BASE"
export E2E_CERT="$(cygpath -w "$SAIDA/tls/cert.pem" 2>/dev/null || echo "$SAIDA/tls/cert.pem")"
export E2E_PG_URL="jdbc:postgresql://127.0.0.1:$E2E_PG_PORTA/nekkora"
export E2E_COMPOSE="$(cygpath -w "$AQUI/compose.e2e.yml" 2>/dev/null || echo "$AQUI/compose.e2e.yml")"
set +e
(cd "$API" && ./mvnw -q -pl nekkora-platform -am test -Dtest=CrossProcessE2E -Dsurefire.failIfNoSpecifiedTests=false) > "$SAIDA/cliente_e2e.txt" 2>&1
rc=$?
set -e
cp "$API"/nekkora-platform/target/surefire-reports/*CrossProcessE2E.txt "$SAIDA/" 2>/dev/null || true
compose logs --no-color > "$SAIDA/logs_stack.txt"
grep -E "Tests run" "$SAIDA"/*CrossProcessE2E.txt || true
echo "saidas: $SAIDA"
echo "remocao: docker compose -f \"$AQUI/compose.e2e.yml\" down   (com as mesmas variaveis E2E_*; ou: docker compose -p $E2E_PROJETO down)"
[ "$rc" -eq 0 ] || falha "cliente E2E falhou (rc=$rc) - veja $SAIDA/cliente_e2e.txt"
echo "E2E OK"
