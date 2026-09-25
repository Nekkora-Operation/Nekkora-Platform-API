#!/usr/bin/env bash
# Regressao da renomeacao Radiant -> Nekkora sobre a baseline 001-011.
#
# Sobe uma stack Compose NOVA e ISOLADA (projeto, container, volume e porta proprios), aplica
# 001-011 pelo entrypoint e executa T01-T10 + concorrencia focada, gravando cada saida em
# saidas/<timestamp>_<projeto>/. Nunca toca a stack principal (nekkora-platform), nunca executa
# "down -v" nem limpeza global do Docker. Remocao da stack de teste fica a cargo de quem rodou.
#
# Uso (Git Bash / Linux / macOS, com Docker):
#   db/tests/regressao-renomeacao/run.sh
#   REGRESSAO_PROJETO=nekkora-regressao-2 REGRESSAO_PORTA=55433 db/tests/regressao-renomeacao/run.sh
#
# Nao rode perto da virada de 00:00 UTC: varios cenarios dependem de "hoje" em UTC.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
PROJETO="${REGRESSAO_PROJETO:-nekkora-regressao}"
PORTA="${REGRESSAO_PORTA:-55432}"
export REGRESSAO_CONTAINER="${PROJETO}-postgres"
VOLUME="${PROJETO}_nekkora_postgres_data"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"

falha() { echo "ERRO: $*" >&2; exit 1; }

# --- travas: nunca a stack principal, sempre base nova ---
case "$PROJETO" in nekkora-platform) falha "projeto '$PROJETO' e a stack principal";; esac
[ "$REGRESSAO_CONTAINER" != "nekkora-postgres" ] || falha "container da stack principal"
[ "$PORTA" != "5432" ] || falha "porta 5432 e da stack principal"
if docker volume inspect "$VOLUME" >/dev/null 2>&1; then
    falha "volume $VOLUME ja existe - a regressao exige base vazia. Use outro REGRESSAO_PROJETO ou remova esse volume conscientemente."
fi
if docker container inspect "$REGRESSAO_CONTAINER" >/dev/null 2>&1; then
    falha "container $REGRESSAO_CONTAINER ja existe."
fi

mkdir -p "$SAIDA"
compose() {
    POSTGRES_PORT="$PORTA" docker compose -p "$PROJETO" \
        -f "$REPO/docker-compose.yml" -f "$AQUI/compose.regressao.yml" "$@"
}
# psql dentro do container (autenticacao local da imagem oficial); papel conecta diretamente.
roda() { # roda <papel> <arquivo.sql>
    local papel="$1" arq="$2" nome
    nome="$(basename "$arq" .sql)"
    echo ">> $nome (papel: $papel)"
    docker exec -i "$REGRESSAO_CONTAINER" psql -X -d nekkora -U "$papel" < "$AQUI/sql/$arq" > "$SAIDA/$nome.txt" 2>&1 \
        || falha "$nome terminou com erro fatal (ver $SAIDA/$nome.txt)"
}

{
    echo "data_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo desconhecido)"
    echo "arvore_limpa=$([ -z "$(git -C "$REPO" status --porcelain 2>/dev/null)" ] && echo sim || echo nao)"
    echo "projeto=$PROJETO container=$REGRESSAO_CONTAINER volume=$VOLUME porta=$PORTA"
    docker version --format 'docker={{.Server.Version}}'
    echo "compose=$(docker compose version --short)"
} > "$SAIDA/ambiente.txt"

# --- T01: aplicacao limpa ---
echo ">> subindo stack isolada $PROJETO"
compose up -d > "$SAIDA/t01_compose_up.txt" 2>&1
for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$REGRESSAO_CONTAINER")" = healthy ] && break
    sleep 2
done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$REGRESSAO_CONTAINER")" = healthy ] || falha "stack nao ficou healthy"
{
    echo "== healthcheck"; docker inspect -f '{{json .Config.Healthcheck.Test}}' "$REGRESSAO_CONTAINER"
    docker inspect -f '{{range .State.Health.Log}}{{.ExitCode}} {{.Output}}{{end}}' "$REGRESSAO_CONTAINER" | grep -v '^$' | tail -1
    echo "== entrypoint (migrations aplicadas / erros)"
    docker logs "$REGRESSAO_CONTAINER" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|ERROR|FATAL|init process complete" || true
    echo "== mounts"; docker inspect -f '{{range .Mounts}}{{.Type}} {{.Source}} -> {{.Destination}}{{"\n"}}{{end}}' "$REGRESSAO_CONTAINER"
} > "$SAIDA/t01_entrypoint_healthcheck.txt"
if grep -qE "ERROR|FATAL" "$SAIDA/t01_entrypoint_healthcheck.txt"; then falha "erro na aplicacao das migrations"; fi
[ "$(grep -c 'running /docker-entrypoint-initdb.d' "$SAIDA/t01_entrypoint_healthcheck.txt")" = 11 ] || falha "esperadas 11 migrations aplicadas"
roda nekkora 00_t01_identidade.sql

# --- T02: arquivos e Docker (catalogo vem em seguida) ---
{
    echo "== repositorio, exceto esta pasta de testes e .git (esperado: nada)"
    grep -rIin radiant "$REPO" --exclude-dir=.git --exclude-dir=regressao-renomeacao || echo "(nenhuma ocorrencia)"
    echo "== .git/config (remoto externo; URL omitida)"
    grep -ic radiant "$REPO/.git/config" 2>/dev/null || true
    echo "== pasta de testes (ocorrencias intencionais: testes do nome antigo e documentacao)"
    grep -rIil radiant "$AQUI/sql" "$AQUI/README.md" 2>/dev/null | sed "s#$AQUI/##" || true
    echo "== docker (containers, volumes, redes)"
    { docker ps -a --format '{{.Names}}'; docker volume ls -q; docker network ls --format '{{.Name}}'; } | grep -i radiant || echo "(nenhuma ocorrencia)"
} > "$SAIDA/t02_arquivos_docker.txt" 2>&1
roda nekkora 01_t02_catalogo.sql

# --- T03 ---
roda nekkora 02_t03_privilegios.sql

# --- T04 / T06 / T05 / T07 (ordem importa: fixtures encadeadas) ---
roda nekkora                    03_t04_fluxo.sql
roda nekkora                    04_t02_guc_antigo.sql
roda nekkora_app                05_t06a_ingestao_running.sql
roda nekkora                    06_t04b_finalizar.sql
roda nekkora_app                07_t06b_atrasada_reenvio.sql
roda nekkora                    08_t05_agendamento.sql
roda nekkora                    09_t05b_suspensao.sql
roda nekkora_app                10_t07_negativos_app.sql
roda nekkora                    11_t07b_camada_trigger_admin.sql

# --- T09 / T10 ---
roda nekkora                    12_t09_prep_org_b.sql
roda nekkora_retention_executor 13_t09_t10_executor.sql

# --- T08 (preparacao artificial de dados historicos, so nesta base isolada) ---
roda nekkora                    14_t08_prep_1_execucao.sql
roda nekkora                    15_t08_prep_2_historico.sql
roda nekkora                    16_t08_prep_3_verifica.sql
roda nekkora_retention_executor 17_t08_fechamento.sql
roda nekkora_app                18_t08_congelado_app.sql
roda nekkora                    19_t08_congelado_admin.sql

# --- Concorrencia focada: A segura FOR SHARE, B fecha, C observa enquanto B espera ---
echo ">> concorrencia (A: nekkora_app, B: nekkora_retention_executor, C: nekkora)"
docker exec -i "$REGRESSAO_CONTAINER" psql -X -d nekkora -U nekkora_app < "$AQUI/sql/conc_a_ingestao.sql" > "$SAIDA/conc_a_ingestao.txt" 2>&1 &
PID_A=$!
sleep 3
docker exec -i "$REGRESSAO_CONTAINER" psql -X -d nekkora -U nekkora_retention_executor < "$AQUI/sql/conc_b_fechamento.sql" > "$SAIDA/conc_b_fechamento.txt" 2>&1 &
PID_B=$!
sleep 4
docker exec -i "$REGRESSAO_CONTAINER" psql -X -d nekkora -U nekkora < "$AQUI/sql/conc_c_observador.sql" > "$SAIDA/conc_c_observador.txt" 2>&1
# aguarda as DUAS sessoes e verifica cada codigo (um "wait A B" conjunto devolve so o do ultimo)
RA=0; wait "$PID_A" || RA=$?
RB=0; wait "$PID_B" || RB=$?
[ "$RA" -eq 0 ] || falha "concorrencia: sessao A falhou (exit=$RA)"
[ "$RB" -eq 0 ] || falha "concorrencia: sessao B falhou (exit=$RB)"

roda nekkora 20_resumo.sql

echo "OK - saidas em $SAIDA"
echo "Stack de teste '$PROJETO' continua no ar (porta $PORTA). Remocao, quando quiser:"
echo "  docker compose -p $PROJETO down   (e depois: docker volume rm $VOLUME)"
