#!/usr/bin/env bash
# E2E entre processos da API v1: nginx (origem unica, TLS, limit_req) -> Platform (nekkora_platform), Portal
# (processo e papel nekkora_portal proprios) e canal do Agent (processo nekkora-agent-gateway, papel nekkora_ingestion)
# -> PostgreSQL 16 (001-025, instalacao limpa) e Redis.
#
# Migrations pelo MODELO DO MIGRADOR suportado pela 016 (revisao 5), como RealDatabase: nao vao direto para o
# initdb.d (seriam aplicadas pelo superusuario); db/bootstrap/aplicar_como_migrador.sh as aplica como
# migrador_nekkora, elevado so nas versoes que exigem, e o log e conferido (sem FALHA, fim 0|0|0, invariantes da 016
# depois da retirada).
#
# Sobe uma stack Compose NOVA e ISOLADA (projeto, rede E2E_REDE.0/24 - padrao 10.251.47 - e portas proprias; sem volume
# nomeado). A sub-rede e recusada antes de qualquer recurso se sobrepuser a QUALQUER rede Docker existente (E-AP-5: dois
# laboratorios ao mesmo tempo, cada um com E2E_PROJETO, portas e E2E_REDE proprios).
# Nunca toca a stack principal (nekkora-platform / nekkora-postgres / volume principal / porta 5432) nem as
# stacks de teste existentes; nunca executa "down -v" nem limpeza global. Segredos (senha do superusuario e
# dos papeis, chaves AES/HMAC) sao ALEATORIOS por execucao, so em variaveis de ambiente deste processo; o
# certificado TLS e descartavel e a chave privada e apagada assim que o nginx a carrega, e tambem nas saidas
# antecipadas TRATAVEIS (erro, INT, TERM), preservando o codigo de saida; SIGKILL nao e tratavel. Nada disso vai para arquivo versionado. A stack fica
# no ar ao final; o comando de remocao e impresso.
#
# Uso: api/e2e/run.sh
#      E2E_PROJETO=nekkora-e2e-b E2E_PORTA=55453 E2E_PG_PORTA=55454 E2E_REDE=10.251.57 api/e2e/run.sh
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
API="$(cd "$AQUI/.." && pwd)"
REPO="$(cd "$API/.." && pwd)"
export E2E_PROJETO="${E2E_PROJETO:-nekkora-e2e}"
export E2E_PORTA="${E2E_PORTA:-55443}"
export E2E_PG_PORTA="${E2E_PG_PORTA:-55444}"
export E2E_REDE="${E2E_REDE:-10.251.47}"
RODADA="$(date -u +%Y-%m-%dT%H%M%SZ)_${E2E_PROJETO}"
SAIDA="$AQUI/saidas/$RODADA"

falha() { echo "ERRO: $*" >&2; exit 1; }

# A chave privada TLS nunca fica em disco depois do script, tambem quando ele termina antes da hora: no sucesso ela
# ja foi apagada; no erro (falha, set -e) e na interrupcao tratavel (INT 130, TERM 143) e apagada aqui, com o codigo
# de saida preservado. Os diagnosticos ja gravados em $SAIDA (sem segredo) ficam. SIGKILL nao e tratavel.
limpar_chave() { rm -f "$SAIDA/tls/key.pem"; }
trap 'rc=$?; limpar_chave; exit $rc' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# --- protecao da stack principal e de recursos existentes ---
case "$E2E_PROJETO" in nekkora-platform|nekkora-portal-dev|nekkora-portal-013) falha "projeto '$E2E_PROJETO' pertence a outra stack";; esac
for p in "$E2E_PORTA" "$E2E_PG_PORTA"; do
    [ "$p" != "5432" ] || falha "porta 5432 e da stack principal"
    if docker ps --format '{{.Ports}}' | grep -q ":$p->"; then falha "porta $p ja publicada por outro container"; fi
    if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then falha "porta $p ja em uso no host"; fi
done
[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$E2E_PROJETO")" ] || falha "projeto $E2E_PROJETO ja tem containers"
docker network inspect "${E2E_PROJETO}_e2e" >/dev/null 2>&1 && falha "rede ${E2E_PROJETO}_e2e ja existe"
# sub-rede propria: prefixo /24 valido e sem sobreposicao com nenhuma rede Docker existente (mesma regra do runner de
# homologacao, ops/homologacao/lib/redes.py); recusa antes de criar qualquer recurso
python "$REPO/ops/homologacao/lib/redes.py" prefixo "$E2E_REDE" || falha "E2E_REDE invalida: $E2E_REDE (tres octetos, ex.: 10.251.57)"
docker network ls -q | xargs -r docker network inspect -f '{{range .IPAM.Config}}{{println .Subnet}}{{end}}' \
    | python "$REPO/ops/homologacao/lib/redes.py" sobreposicao "$E2E_REDE.0/24" \
    || falha "sub-rede $E2E_REDE.0/24 sobreposta a uma rede Docker existente (escolha outra E2E_REDE); nada foi criado"

mkdir -p "$SAIDA/migracoes_montadas/migracoes" "$SAIDA/migracoes_montadas/bootstrap" "$SAIDA/tls"
# Migrations: 001-025 escolhidas explicitamente (instalacao limpa), numa subpasta (o entrypoint so executa o primeiro
# nivel do initdb.d); o involucro 000_ chama o script do migrador apontando para ela.
for n in $(seq -w 1 25); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/migracoes_montadas/migracoes/"
done
cp "$REPO/db/bootstrap/aplicar_como_migrador.sh" "$SAIDA/migracoes_montadas/bootstrap/"
printf '#!/bin/sh\nset -e\nNEKKORA_MIGRACOES=/docker-entrypoint-initdb.d/migracoes sh /docker-entrypoint-initdb.d/bootstrap/aplicar_como_migrador.sh\n' \
    > "$SAIDA/migracoes_montadas/000_aplicar_como_migrador.sh"
chmod 0755 "$SAIDA/migracoes_montadas/000_aplicar_como_migrador.sh"
export E2E_MIGRACOES="./saidas/$RODADA/migracoes_montadas"
export E2E_TLS="./saidas/$RODADA/tls"

aleatorio() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
senha() { head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
export E2E_PG_SUPER_SENHA="$(senha)" E2E_PLATFORM_SENHA="$(senha)" E2E_PORTAL_SENHA="$(senha)" E2E_INGESTAO_SENHA="$(senha)"
export E2E_MFA_CHAVES="k1:$(aleatorio 32)" E2E_HMAC_CHAVE="$(aleatorio 32)"

# caminhos relativos: MSYS2_ARG_CONV_EXCL (necessario para o -subj) tambem impediria converter caminhos absolutos
(cd "$SAIDA/tls" && MSYS2_ARG_CONV_EXCL='*' openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost" -keyout key.pem -out cert.pem 2>/dev/null) || falha "certificado TLS descartavel"

compose() { docker compose -f "$AQUI/compose.e2e.yml" "$@"; }

echo "== build dos jars (sem testes)"
(cd "$API" && sh ./mvnw -q -DskipTests package)   # sh: o mvnw esta versionado sem bit de execucao

echo "== postgres e redis ($E2E_PROJETO)"
compose up -d postgres redis
for i in $(seq 1 90); do
    [ "$(compose logs postgres 2>/dev/null | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
    [ "$i" -lt 90 ] || falha "PostgreSQL nao terminou a inicializacao"
    sleep 2
done
# Modelo do migrador conferido como em RealDatabase.conferirModeloDoMigrador (so as linhas do migrador, sem segredo).
compose logs --no-color postgres 2>/dev/null | grep "aplicar_como_migrador" > "$SAIDA/aplicar_como_migrador.txt" || true
grep -q "aplicar_como_migrador: FALHA" "$SAIDA/aplicar_como_migrador.txt" && falha "migrador falhou (veja aplicar_como_migrador.txt)"
grep -Eq "aplicar_como_migrador: fim db=nekkora aplicadas=[0-9]+ estado=0\|0\|0" "$SAIDA/aplicar_como_migrador.txt" \
    || falha "migrador sem fim 0|0|0 (veja aplicar_como_migrador.txt)"
grep -q "aplicar_como_migrador: invariantes_016_pos_retirada=OK" "$SAIDA/aplicar_como_migrador.txt" \
    || falha "invariantes da 016 depois da retirada nao conferidas"
# Senhas dos papeis LOGIN reais (so pelo stdin; nada em arquivo). Os demais papeis ficam sem senha.
printf "ALTER ROLE nekkora_platform PASSWORD '%s';\nALTER ROLE nekkora_portal PASSWORD '%s';\nALTER ROLE nekkora_ingestion PASSWORD '%s';\n" \
    "$E2E_PLATFORM_SENHA" "$E2E_PORTAL_SENHA" "$E2E_INGESTAO_SENHA" \
    | compose exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U nekkora -d nekkora
compose exec -T postgres psql -X -At -U nekkora -d nekkora -c "SELECT count(*) FROM pg_proc WHERE proname LIKE 'portal\_%'" > "$SAIDA/funcoes_portal.txt"

echo "== platform, portal, canal do agent e nginx"
compose up -d --build platform portal agent-gateway nginx
BASE="https://localhost:$E2E_PORTA"
for i in $(seq 1 90); do
    a="$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/api/v1/csrf" || true)"
    b="$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/portal/api/v1/o/00000000-0000-4000-8000-000000000000/workspaces" || true)"
    # canal da maquina: sem o cabecalho de contrato, 400 (o processo responde)
    g="$(curl -sk -o /dev/null -w '%{http_code}' "$BASE/agent/api/v1/authorizations" || true)"
    [ "$a" = "200" ] && [ "$b" = "401" ] && [ "$g" = "400" ] && break
    [ "$i" -lt 90 ] || { compose logs --no-color > "$SAIDA/logs_falha_subida.txt"; falha "aplicacoes nao responderam (platform=$a portal=$b agente=$g)"; }
    sleep 2
done
rm -f "$SAIDA/tls/key.pem"   # o nginx ja carregou; a chave nao fica em disco

echo "== separacao de credenciais (ambiente dos containers)"
env_portal="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$(compose ps -q portal)" | cut -d= -f1)"
env_platform="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$(compose ps -q platform)" | cut -d= -f1)"
env_agente="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$(compose ps -q agent-gateway)" | cut -d= -f1)"
echo "$env_portal" | grep -qE '^NEKKORA_(DB_URL|DB_SENHA|MFA_|HMAC_|GATEWAY_)' && falha "portal recebeu variavel da Platform ou do canal"
echo "$env_platform" | grep -qE '^NEKKORA_(PORTAL_|GATEWAY_)' && falha "platform recebeu variavel do Portal ou do canal"
echo "$env_agente" | grep -qE '^NEKKORA_(DB_URL|DB_SENHA|MFA_|HMAC_|PORTAL_)' && falha "canal do agent recebeu variavel da Platform ou do Portal"
{ echo "portal:"; echo "$env_portal" | grep NEKKORA_; echo "platform:"; echo "$env_platform" | grep NEKKORA_; echo "agent-gateway:"; echo "$env_agente" | grep NEKKORA_; } \
    > "$SAIDA/variaveis_por_processo.txt"

echo "== cliente E2E (so pelo nginx)"
export E2E_BASE="$BASE"
export E2E_CERT="$(cygpath -w "$SAIDA/tls/cert.pem" 2>/dev/null || echo "$SAIDA/tls/cert.pem")"
export E2E_PG_URL="jdbc:postgresql://127.0.0.1:$E2E_PG_PORTA/nekkora"
export E2E_COMPOSE="$(cygpath -w "$AQUI/compose.e2e.yml" 2>/dev/null || echo "$AQUI/compose.e2e.yml")"
set +e
# CrossProcessE2E primeiro (ordem alfabetica): IdentityCredentialsE2E (credenciais da identidade, missao 002) e
# LinkedAccountsE2E (contas vinculadas, P3/P4) e AccessInvitationsE2E (acessos, 023) depois da rajada da borda
(cd "$API" && sh ./mvnw -q -pl nekkora-platform -am test -Dtest='AccessInvitationsE2E,AgentChannelE2E,CrossProcessE2E,IdentityCredentialsE2E,LinkedAccountsE2E' -Dsurefire.runOrder=alphabetical \
    -Dsurefire.failIfNoSpecifiedTests=false) > "$SAIDA/cliente_e2e.txt" 2>&1
rc=$?
set -e
cp "$API"/nekkora-platform/target/surefire-reports/*AgentChannelE2E.txt "$API"/nekkora-platform/target/surefire-reports/*AccessInvitationsE2E.txt "$API"/nekkora-platform/target/surefire-reports/*CrossProcessE2E.txt "$API"/nekkora-platform/target/surefire-reports/*IdentityCredentialsE2E.txt "$API"/nekkora-platform/target/surefire-reports/*LinkedAccountsE2E.txt "$SAIDA/" 2>/dev/null || true
# medicao opcional da ingestao do canal (E2E_MEDICAO="<agents> <execucoes> <eventos> <lote>"), com os segredos desta execucao
if [ -n "${E2E_MEDICAO:-}" ] && [ "$rc" -eq 0 ]; then
    echo "== medicao da ingestao do canal ($E2E_MEDICAO)"
    SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
    (cd "$API" && sh ./mvnw -q -pl nekkora-agent-gateway -am test-compile -DskipTests \
        && sh ./mvnw -q -pl nekkora-agent-gateway dependency:build-classpath -Dmdep.outputFile=target/cp-medicao.txt) > "$SAIDA/medicao_build.txt" 2>&1 \
        || falha "build da medicao (veja medicao_build.txt)"
    # shellcheck disable=SC2086
    (cd "$API/nekkora-agent-gateway" && java -cp "target/test-classes${SEP}target/classes${SEP}$(cat target/cp-medicao.txt)" \
        com.nekkora.agentgateway.bench.AgentBench $E2E_MEDICAO) > "$SAIDA/medicao.txt" 2>&1 || rc=$?
    cat "$SAIDA/medicao.txt"
fi
compose logs --no-color > "$SAIDA/logs_stack.txt"
grep -E "Tests run" "$SAIDA"/*AgentChannelE2E.txt "$SAIDA"/*AccessInvitationsE2E.txt "$SAIDA"/*CrossProcessE2E.txt "$SAIDA"/*IdentityCredentialsE2E.txt "$SAIDA"/*LinkedAccountsE2E.txt || true
echo "saidas: $SAIDA"
echo "remocao: docker compose -f \"$AQUI/compose.e2e.yml\" down   (com as mesmas variaveis E2E_*; ou: docker compose -p $E2E_PROJETO down)"
[ "$rc" -eq 0 ] || falha "cliente E2E falhou (rc=$rc) - veja $SAIDA/cliente_e2e.txt"
echo "E2E OK"
