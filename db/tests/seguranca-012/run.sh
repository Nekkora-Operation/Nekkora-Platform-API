#!/usr/bin/env bash
# Validacao da 012_security_rls.sql com os papeis reais.
#
# Sobe uma stack Compose NOVA e ISOLADA (projeto, container, volume e porta proprios), aplica
# 001-012 pelo entrypoint e executa a suite na ordem, cada script com o seu papel, gravando as
# saidas em saidas/<timestamp>_<projeto>/. Nunca toca a stack principal (nekkora-platform), nunca
# executa "down -v" nem limpeza global do Docker; a remocao da stack de teste fica com quem rodou.
#
# Uso (Git Bash / Linux / macOS, com Docker):
#   db/tests/seguranca-012/run.sh
#   TESTE_PROJETO=nekkora-seg-2 TESTE_PORTA=55451 db/tests/seguranca-012/run.sh
#
# Nao rode perto de 00:00 UTC: varios cenarios dependem de "hoje" em UTC.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
PROJETO="${TESTE_PROJETO:-nekkora-seg-012}"
PORTA="${TESTE_PORTA:-55450}"
export TESTE_CONTAINER="${PROJETO}-postgres"
VOLUME="${PROJETO}_nekkora_postgres_data"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"

falha() { echo "ERRO: $*" >&2; exit 1; }

case "$PROJETO" in nekkora-platform) falha "projeto '$PROJETO' e a stack principal";; esac
[ "$TESTE_CONTAINER" != "nekkora-postgres" ] || falha "container da stack principal"
[ "$PORTA" != "5432" ] || falha "porta 5432 e da stack principal"
docker volume inspect "$VOLUME" >/dev/null 2>&1 && falha "volume $VOLUME ja existe - a suite exige base vazia"
docker container inspect "$TESTE_CONTAINER" >/dev/null 2>&1 && falha "container $TESTE_CONTAINER ja existe"

mkdir -p "$SAIDA"
compose() {
    POSTGRES_PORT="$PORTA" docker compose -p "$PROJETO" -f "$REPO/docker-compose.yml" -f "$AQUI/compose.teste.yml" "$@"
}
psql_papel() { docker exec -i "$TESTE_CONTAINER" psql -X -d nekkora -U "$@"; }
# Todo script de teste recebe, na mesma sessao, o helper de assercoes (sql/_asserts.inc.sql), que
# liga ON_ERROR_STOP e define pg_temp.afirma / pg_temp.espera_erro: resultado inesperado = exit != 0.
com_asserts() { cat "$AQUI/sql/_asserts.inc.sql" "$AQUI/sql/$1"; }
# roda <papel> <arquivo.sql> [nome-da-saida] [-v var=valor ...]
roda() {
    local papel="$1" arq="$2" nome="${3:-$(basename "$2" .sql)}"; shift 3 2>/dev/null || shift $#
    echo ">> $nome (papel: $papel)"
    com_asserts "$arq" | psql_papel "$papel" "$@" > "$SAIDA/$nome.txt" 2>&1 \
        || falha "$nome falhou (assercao ou erro inesperado - ver $SAIDA/$nome.txt)"
}
# Duas sessoes concorrentes: aguarda AS DUAS e verifica cada codigo separadamente (um "wait A B"
# conjunto devolve so o codigo do ultimo processo e esconderia a falha de A).
aguarda_sessoes() {
    local rotulo="$1" pa="$2" pb="$3" ra=0 rb=0
    wait "$pa" || ra=$?
    wait "$pb" || rb=$?
    echo "$rotulo: sessao A exit=$ra, sessao B exit=$rb" >> "$SAIDA/sessoes_concorrentes.txt"
    [ "$ra" -eq 0 ] || falha "$rotulo: sessao A falhou (exit=$ra)"
    [ "$rb" -eq 0 ] || falha "$rotulo: sessao B falhou (exit=$rb)"
}
consulta() { docker exec "$TESTE_CONTAINER" psql -X -At -d nekkora -U "$1" -c "$2"; }

{
    echo "data_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo desconhecido)"
    echo "arvore_limpa=$([ -z "$(git -C "$REPO" status --porcelain 2>/dev/null)" ] && echo sim || echo nao)"
    echo "projeto=$PROJETO container=$TESTE_CONTAINER volume=$VOLUME porta=$PORTA"
    docker version --format 'docker={{.Server.Version}}'
    echo "compose=$(docker compose version --short)"
} > "$SAIDA/ambiente.txt"

echo ">> subindo stack isolada $PROJETO"
compose up -d > "$SAIDA/aplicacao_compose_up.txt" 2>&1
for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$TESTE_CONTAINER")" = healthy ] && break
    sleep 2
done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$TESTE_CONTAINER")" = healthy ] || falha "stack nao ficou healthy"
{
    echo "== healthcheck"
    docker inspect -f '{{range .State.Health.Log}}{{.ExitCode}} {{.Output}}{{end}}' "$TESTE_CONTAINER" | grep -v '^$' | tail -1
    echo "== entrypoint (migrations aplicadas / erros)"
    docker logs "$TESTE_CONTAINER" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|ERROR|FATAL|init process complete" || true
} > "$SAIDA/aplicacao_entrypoint.txt"
grep -qE "ERROR|FATAL" "$SAIDA/aplicacao_entrypoint.txt" && falha "erro na aplicacao das migrations"
[ "$(grep -c 'running /docker-entrypoint-initdb.d' "$SAIDA/aplicacao_entrypoint.txt")" = 12 ] || falha "esperadas 12 migrations aplicadas"

# --- TESTE DO PROPRIO RUNNER (nunca numa execucao normal) ---
# TESTE_MUTACAO=<arquivo.sql>: aplicado como superusuario logo apos as migrations, para introduzir
# de proposito um defeito (concessao indevida, trigger desabilitada...). A suite DEVE falhar.
if [ -n "${TESTE_MUTACAO:-}" ]; then
    echo ">> MUTACAO DELIBERADA: $TESTE_MUTACAO"
    { echo "== mutacao aplicada de proposito (teste do runner): $TESTE_MUTACAO"; cat "$TESTE_MUTACAO"; echo "== resultado";
      psql_papel nekkora -v ON_ERROR_STOP=1 < "$TESTE_MUTACAO" 2>&1; } > "$SAIDA/00_mutacao.txt" || falha "mutacao nao aplicou"
fi

# --- catalogo x matriz ---
roda nekkora 00_catalogo_vs_matriz.sql

# --- provisionamento ---
# Procedimento inicial (db/bootstrap): organizacao interna + primeiro staff + cinco limites, pelo
# executor administrativo, numa transacao. Tres execucoes: variavel ausente (deve falhar), real,
# repeticao (deve ser recusada - ja existe organizacao).
BOOT="$REPO/db/bootstrap/001_organizacao_interna_nekkora.sql"
BOOT_VARS=(-v org_id=99999999-0000-0000-0000-000000000099 -v org_name='Nekkora Internal'
           -v staff_user_id=99999999-0000-0000-0000-0000000000aa -v staff_email=staff@nekkora.internal
           -v staff_name='Nekkora Staff' -v limites='{"(1,1,0)","(2,1,0)","(3,1,0)","(4,2,)","(5,2,)"}')
echo ">> 01_bootstrap (papel: nekkora)"
{ echo "== execucao sem variaveis (deve falhar)"
  psql_papel nekkora < "$BOOT" 2>&1 && echo "INESPERADO: aceitou sem variaveis" || echo "exit=$? (rejeitado)"
  echo "== execucao real"
  psql_papel nekkora "${BOOT_VARS[@]}" < "$BOOT" 2>&1 || falha "bootstrap falhou (ver $SAIDA/01_bootstrap.txt)"
  echo "== repeticao (deve ser recusada)"
  psql_papel nekkora "${BOOT_VARS[@]}" < "$BOOT" 2>&1 && echo "INESPERADO: aceitou repeticao" || echo "exit=$? (recusado)"
} > "$SAIDA/01_bootstrap.txt"
grep -q "INESPERADO" "$SAIDA/01_bootstrap.txt" && falha "bootstrap aceitou execucao que devia recusar"
roda nekkora_provisioning 02_provisionamento.sql
roda nekkora              03_verifica_provisionamento.sql

# --- platform: fluxos de produto em A e B ---
for par in "aaaaaaaa-0000-0000-0000-00000000000a aaaaaaaa A" "bbbbbbbb-0000-0000-0000-00000000000b bbbbbbbb B"; do
    set -- $par
    roda nekkora_platform 04_platform_setup.inc.sql "04_platform_setup_$3" -v org="$1" -v p="$2"
done

# --- bootstrap de ORG_ADMIN (desvio pelo platform ANTES, caminho autorizado depois) ---
roda nekkora_platform     05b_bootstrap_desvio_platform.sql
roda nekkora_provisioning 05_bootstrap_org_admin.sql
roda nekkora              05c_verifica_bootstrap.sql

# --- isolamento, ingestao, scheduler ---
roda nekkora_platform  06_platform_isolamento.sql
roda nekkora_ingestion 07_ingestao.sql
roda nekkora_scheduler 08_scheduler.sql

# --- suspensao com ocorrencia pendente: scheduler materializa, platform suspende, scheduler processa ---
OCC2="$(consulta nekkora_scheduler "SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', clock_timestamp())")"
echo "occ2=$OCC2 (materializada por nekkora_scheduler)" > "$SAIDA/09_ocorrencia_pendente.txt"
[[ "$OCC2" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || falha "materializacao da ocorrencia pendente falhou (occ2='$OCC2')"
roda nekkora_platform  09a_suspensao_platform.sql  "" -v occ2="$OCC2"
roda nekkora_scheduler 09b_suspensao_scheduler.sql "" -v occ2="$OCC2"
roda nekkora_platform  09c_retomada_platform.sql   "" -v occ2="$OCC2"

# --- manutencao ---
roda nekkora_retention_executor 10_retencao.sql
roda nekkora                    11a_fixture_historica.sql
roda nekkora_retention_executor 11b_fechamento_historico.sql
roda nekkora                    11c_risco_sem_contexto.sql

# --- concorrencia 1: materializacao concorrente da mesma identidade ---
echo ">> concorrencia 1 (materializacao)"
TS="$(consulta nekkora "SELECT clock_timestamp()")"
# TESTE DO PROPRIO RUNNER: TESTE_FORCAR_FALHA_A=1 faz so a sessao A falhar (depois do commit).
FALHA_A=(); [ -n "${TESTE_FORCAR_FALHA_A:-}" ] && FALHA_A=(-v forcar_falha_a=1)
com_asserts conc1_a_materializa.sql | psql_papel nekkora_scheduler -v ts="$TS" "${FALHA_A[@]}" > "$SAIDA/conc1_a_materializa.txt" 2>&1 & PA=$!
sleep 3
com_asserts conc1_b_materializa.sql | psql_papel nekkora_scheduler -v ts="$TS" > "$SAIDA/conc1_b_materializa.txt" 2>&1 & PB=$!
sleep 2
com_asserts conc_observador.sql | psql_papel nekkora -v bloqueado=nekkora_scheduler -v bloqueador=nekkora_scheduler > "$SAIDA/conc1_c_observador.txt" 2>&1 & PC=$!
RC=0; wait "$PC" || RC=$?
aguarda_sessoes "concorrencia 1" "$PA" "$PB"
[ "$RC" -eq 0 ] || falha "concorrencia 1: observador nao viu B bloqueada por A (ver $SAIDA/conc1_c_observador.txt)"
OCC_A="$(sed -n 's/^MARCADOR_OCC_A=//p' "$SAIDA/conc1_a_materializa.txt")"
OCC_B="$(sed -n 's/^MARCADOR_OCC_B=//p' "$SAIDA/conc1_b_materializa.txt")"
[ -n "$OCC_A" ] && [ "$OCC_A" = "$OCC_B" ] || falha "concorrencia 1: ids diferentes ou ausentes (A='$OCC_A', B='$OCC_B')"
roda nekkora conc1_verifica.sql "" -v ts="$TS" -v occ_a="$OCC_A" -v occ_b="$OCC_B"

# --- concorrencia 2: reconsolidacao x insercao ainda nao confirmada ---
echo ">> concorrencia 2 (reconsolidacao x ingestao)"
com_asserts conc2_a_ingestao.sql | psql_papel nekkora_ingestion "${FALHA_A[@]}" > "$SAIDA/conc2_a_ingestao.txt" 2>&1 & PA=$!
sleep 3
com_asserts conc2_b_reconsolida.sql | psql_papel nekkora_retention_executor > "$SAIDA/conc2_b_reconsolida.txt" 2>&1 & PB=$!
sleep 3
com_asserts conc_observador.sql | psql_papel nekkora -v bloqueado=nekkora_retention_executor -v bloqueador=nekkora_ingestion > "$SAIDA/conc2_c_observador.txt" 2>&1 & PC=$!
RC=0; wait "$PC" || RC=$?
aguarda_sessoes "concorrencia 2" "$PA" "$PB"
[ "$RC" -eq 0 ] || falha "concorrencia 2: observador nao viu a reconsolidacao bloqueada pela ingestao (ver $SAIDA/conc2_c_observador.txt)"
roda nekkora conc2_verifica.sql

# --- papel substituido: login precisa falhar por papel inexistente ---
if docker exec "$TESTE_CONTAINER" psql -X -d nekkora -U nekkora_app -c 'SELECT 1' > "$SAIDA/98_login_nekkora_app.txt" 2>&1; then
    falha "login de nekkora_app deveria falhar"
fi
grep -q 'role "nekkora_app" does not exist' "$SAIDA/98_login_nekkora_app.txt" || falha "login de nekkora_app falhou por outro motivo (ver 98)"

roda nekkora 99_resumo.sql

# Rede de seguranca adicional: script quebrado ou variavel psql nao substituida.
if grep -l -E "syntax error at or near|unterminated quoted|invalid command \\\\|FALHA DE ASSERCAO" "$SAIDA"/*.txt; then
    falha "saida com erro de sintaxe, variavel nao substituida ou falha de assercao (arquivos acima)"
fi

echo "OK - saidas em $SAIDA"
echo "Stack de teste '$PROJETO' continua no ar (porta $PORTA). Remocao, quando quiser:"
echo "  docker rm -f $TESTE_CONTAINER && docker volume rm $VOLUME && docker network rm ${PROJETO}_default"
