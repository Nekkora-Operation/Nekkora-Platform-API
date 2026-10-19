#!/usr/bin/env bash
# Validacao da 013_portal_externo.sql (portal externo, decisao 8.2) com os papeis reais.
#
# Sobe uma stack Compose NOVA e ISOLADA (projeto, container, volume, rede e porta proprios), aplica
# 001-013 pelo entrypoint a partir de db/migrations (instalacao limpa), monta a fixture pelos fluxos de
# produto e executa a suite do desenho (secao 7) e da missao, cada script com o seu papel, conectando
# DIRETAMENTE com ele (psql -U <papel>, socket local do container). Nunca toca a stack principal
# (nekkora-platform / nekkora-postgres / volume principal / porta 5432), nunca executa "down -v" nem
# limpeza global. A stack fica no ar ao final; o comando de remocao e impresso.
#
# Tokens de sessao: gerados aqui a cada execucao (32 bytes aleatorios, base64url - contrato D2),
# passados aos scripts por -v; so o hash vai para a base. Ao final, NENHUMA saida e NEM o log do
# servidor podem conter um desses tokens (verificacao explicita). Os unicos "tokens" que aparecem de
# proposito sao os marcadores do teste de logs, que nao correspondem a nenhuma sessao.
#
# Mutacoes (testes do proprio runner): depois da suite normal, cada variante deliberadamente errada e
# aplicada NA BASE DE TESTE, o teste correspondente PRECISA falhar com a mensagem esperada, a funcao
# original e restaurada (impressao digital conferida) e o teste e repetido com sucesso.
#
# Uso: db/tests/portal-013/run.sh
#      TESTE_PROJETO=nekkora-portal-013b TESTE_PORTA=55515 db/tests/portal-013/run.sh
# Nao rode perto de 00:00 UTC (a barreira de ingestao da fixture usa o dia UTC).
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
ASSERTS="$REPO/db/tests/seguranca-012/sql/_asserts.inc.sql"   # reutilizado (lido), nunca alterado
PROJETO="${TESTE_PROJETO:-nekkora-portal-013}"
PORTA="${TESTE_PORTA:-55513}"
export TESTE_CONTAINER="${PROJETO}-postgres"
VOLUME="${PROJETO}_nekkora_postgres_data"
REDE="${PROJETO}_default"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
MUT="$SAIDA/mutacoes"

falha() { echo "ERRO: $*" >&2; exit 1; }

# --- protecao da stack principal e de recursos existentes ---
case "$PROJETO" in nekkora-platform) falha "projeto '$PROJETO' e a stack principal";; esac
[ "$TESTE_CONTAINER" != "nekkora-postgres" ] || falha "container da stack principal"
[ "$VOLUME" != "nekkora-platform_nekkora_postgres_data" ] || falha "volume da stack principal"
[ "$PORTA" != "5432" ] || falha "porta 5432 e da stack principal"
docker container inspect "$TESTE_CONTAINER" >/dev/null 2>&1 && falha "container $TESTE_CONTAINER ja existe"
docker volume inspect "$VOLUME" >/dev/null 2>&1 && falha "volume $VOLUME ja existe - a suite exige base vazia"
docker network inspect "$REDE" >/dev/null 2>&1 && falha "rede $REDE ja existe"
if docker ps --format '{{.Ports}}' | grep -q ":$PORTA->"; then falha "porta $PORTA ja publicada por outro container"; fi
if (exec 3<>"/dev/tcp/127.0.0.1/$PORTA") 2>/dev/null; then falha "porta $PORTA ja em uso no host"; fi

mkdir -p "$SAIDA" "$MUT"
# Migrations testadas por esta suite: 001-013, escolhidas explicitamente. O repositorio pode ter
# migrations posteriores (ex.: 014); elas NAO entram nesta instalacao. Copia montada no entrypoint
# por caminho RELATIVO a raiz do repositorio (o Compose resolve a partir do docker-compose.yml).
MIGRACOES=()
for n in $(seq -w 1 13); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    MIGRACOES+=("${f[0]}")
done
MONT_REL="db/tests/portal-013/saidas/$(basename "$SAIDA")/migracoes_montadas"
mkdir -p "$REPO/$MONT_REL"
cp "${MIGRACOES[@]}" "$REPO/$MONT_REL/"
export TESTE_MIGRACOES="./$MONT_REL"
compose() {
    POSTGRES_PORT="$PORTA" docker compose -p "$PROJETO" -f "$REPO/docker-compose.yml" -f "$AQUI/compose.teste.yml" "$@"
}
psql_papel() { docker exec -i "$TESTE_CONTAINER" psql -X -d nekkora -U "$@"; }
consulta() { docker exec "$TESTE_CONTAINER" psql -X -At -d nekkora -U "$1" -c "$2"; }
com_asserts() { cat "$ASSERTS" "$AQUI/sql/_ids.inc.sql" "$AQUI/sql/_portal.inc.sql" "$AQUI/sql/$1"; }

# --- tokens de sessao desta execucao (contrato D2) ---
NOMES_TOK=(x y z gp gs gr ge gf gv gw rv us un ms q ra ra2 x2 x3 xrev xexp xina xb14 xb15 xtoq xfut xp adm w r k)
TOKV=()
declare -A TOK
for n in "${NOMES_TOK[@]}"; do
    t="$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
    [[ "$t" =~ ^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$ ]] || falha "token gerado fora do contrato D2"
    TOK[$n]="$t"
    TOKV+=(-v "tok_$n=$t")
done
# marcador do teste de logs: formato valido de token, sem sessao correspondente
gera_marcador() { printf 'MARCADORLOG%s' "$(head -c 96 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 31)"; printf 'A'; }

# roda <papel> <arquivo.sql> [nome-da-saida] [-v var=valor ...] - aborta a suite se o script falhar
roda() {
    local papel="$1" arq="$2" nome="${3:-}"; shift 2; [ $# -gt 0 ] && shift
    [ -n "$nome" ] || nome="$(basename "$arq" .sql)"
    echo ">> $nome (papel: $papel)"
    com_asserts "$arq" | psql_papel "$papel" "${TOKV[@]}" "$@" > "$SAIDA/$nome.txt" 2>&1 \
        || falha "$nome falhou (assercao ou erro inesperado - ver $SAIDA/$nome.txt)"
}
# roda_st <dir> <papel> <arquivo.sql> <nome> [-v ...] - devolve o codigo de saida em vez de abortar
roda_st() {
    local dir="$1" papel="$2" arq="$3" nome="$4"; shift 4
    local rc=0
    com_asserts "$arq" | psql_papel "$papel" "${TOKV[@]}" "$@" > "$dir/$nome.txt" 2>&1 || rc=$?
    return $rc
}
ancora() { consulta nekkora "SELECT clock_timestamp()"; }
refresca() { roda nekkora t_refresca.sql "$1" -v ancora="$(ancora)"; }
# espera ate <sql> devolver <valor> (30 s)
espera_condicao() {
    local sql="$1" valor="$2" i
    for i in $(seq 1 150); do
        [ "$(consulta nekkora "$sql")" = "$valor" ] && return 0
        sleep 0.2
    done
    return 1
}
# espera ate <arquivo> conter a linha <marcador> (30 s)
espera_arquivo() {
    local i
    for i in $(seq 1 150); do
        grep -qx -- "$2" "$1" 2>/dev/null && return 0
        sleep 0.2
    done
    return 1
}
# Duas sessoes concorrentes: aguarda CADA uma e verifica cada codigo separadamente.
aguarda() {
    local rotulo="$1" pid="$2" rc=0
    wait "$pid" || rc=$?
    echo "$rotulo: exit=$rc" >> "$SAIDA/sessoes_concorrentes.txt"
    return $rc
}
marcador_de() { sed -n "s/^$1=//p" "$2"; }

{
    echo "data_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit=$(cd "$REPO" && git rev-parse HEAD 2>/dev/null || echo desconhecido)"
    echo "branch=$(cd "$REPO" && git branch --show-current 2>/dev/null || echo desconhecido)"
    echo "arvore_limpa=$([ -z "$(cd "$REPO" && git status --porcelain 2>/dev/null)" ] && echo sim || echo nao)"
    echo "git_status_porcelain:"; (cd "$REPO" && git status --porcelain) | sed 's/^/  /'
    echo "migration_013=db/migrations/013_portal_externo.sql sha256=$(sha256sum "$REPO/db/migrations/013_portal_externo.sql" | cut -d' ' -f1)"
    echo "projeto=$PROJETO container=$TESTE_CONTAINER volume=$VOLUME rede=$REDE porta=$PORTA"
    docker version --format 'docker={{.Server.Version}}'
    echo "compose=$(docker compose version --short)"
    echo "migracoes_montadas:"; (cd "$REPO/$MONT_REL" && sha256sum 0*.sql | sed 's/^/  /')
} > "$SAIDA/ambiente.txt"

# ============================== instalacao limpa 001-013 ==============================
echo ">> subindo stack isolada $PROJETO (porta $PORTA)"
compose up -d > "$SAIDA/aplicacao_compose_up.txt" 2>&1
for _ in $(seq 1 90); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$TESTE_CONTAINER")" = healthy ] && break
    sleep 2
done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$TESTE_CONTAINER")" = healthy ] || falha "stack nao ficou healthy"
{
    echo "== entrypoint (migrations aplicadas / erros)"
    docker logs "$TESTE_CONTAINER" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|ERROR|FATAL|init process complete" || true
} > "$SAIDA/aplicacao_entrypoint.txt"
grep -qE "ERROR|FATAL" "$SAIDA/aplicacao_entrypoint.txt" && falha "erro na aplicacao das migrations"
[ "$(grep -c 'running /docker-entrypoint-initdb.d' "$SAIDA/aplicacao_entrypoint.txt")" = 13 ] || falha "esperadas 13 migrations aplicadas"
diff <(grep -o 'initdb.d/[0-9]*_[a-z_]*\.sql' "$SAIDA/aplicacao_entrypoint.txt" | sed 's|initdb.d/||') \
     <(cd "$REPO/$MONT_REL" && ls -1 0*.sql) > /dev/null || falha "ordem/conjunto de migrations aplicado difere da lista montada (001-013)"
[ "$(consulta nekkora 'SHOW cluster_name')" = nekkora-portal-013-isolada ] || falha "cluster_name inesperado"
echo "server_version=$(consulta nekkora 'SHOW server_version')" >> "$SAIDA/ambiente.txt"

# ============================== 1. catalogo ==============================
roda nekkora t00_catalogo.sql
echo ">> base de referencia nekkora_ref com 001-012 (mesmo cluster isolado) e diff de catalogo"
{
    consulta nekkora "CREATE DATABASE nekkora_ref"
    for f in $(cd "$REPO/$MONT_REL" && ls -1 0*.sql | grep -v '^013_'); do
        echo "== $f"
        # pelo stdin, a partir do mesmo arquivo do repositorio (sem caminho de container na linha de comando:
        # no Git Bash a conversao automatica de caminhos o corromperia)
        docker exec -i "$TESTE_CONTAINER" psql -X -q -v ON_ERROR_STOP=1 -U nekkora -d nekkora_ref < "$REPO/$MONT_REL/$f" 2>&1
    done
} > "$SAIDA/01_ref_001_012.txt" 2>&1 || falha "aplicacao de 001-012 na base de referencia falhou"
grep -qE "ERROR|FATAL" "$SAIDA/01_ref_001_012.txt" && falha "erro ao aplicar 001-012 em nekkora_ref"
lista_catalogo() { docker exec -i "$TESTE_CONTAINER" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$1" < "$AQUI/sql/c_listagem_catalogo.sql"; }
lista_catalogo nekkora_ref > "$SAIDA/01_catalogo_001_012.txt"
lista_catalogo nekkora     > "$SAIDA/01_catalogo_001_013.txt"
diff "$SAIDA/01_catalogo_001_012.txt" "$SAIDA/01_catalogo_001_013.txt" > "$SAIDA/01_diff_catalogo.txt" || true
{
    echo "linhas 001-012: $(wc -l < "$SAIDA/01_catalogo_001_012.txt")"
    echo "linhas 001-013: $(wc -l < "$SAIDA/01_catalogo_001_013.txt")"
    echo "removidas/alteradas (<): $(grep -c '^<' "$SAIDA/01_diff_catalogo.txt" || true)"
    echo "acrescentadas (>): $(grep -c '^>' "$SAIDA/01_diff_catalogo.txt" || true)"
    echo "acrescentadas sem relacao com o portal: $(grep '^>' "$SAIDA/01_diff_catalogo.txt" | grep -vc 'portal' || true)"
    echo "por tipo:"; grep '^>' "$SAIDA/01_diff_catalogo.txt" | cut -c3- | cut -d'|' -f1 | sort | uniq -c
} > "$SAIDA/01_diff_resumo.txt"
[ "$(grep -c '^<' "$SAIDA/01_diff_catalogo.txt" || true)" = 0 ] || falha "a 013 alterou/removeu objeto de 001-012 (ver 01_diff_catalogo.txt)"
[ "$(grep '^>' "$SAIDA/01_diff_catalogo.txt" | grep -vc 'portal' || true)" = 0 ] || falha "a 013 acrescentou objeto sem relacao com o portal"
[ "$(grep -c '^>' "$SAIDA/01_diff_catalogo.txt" || true)" -gt 0 ] || falha "diff vazio - a 013 nao foi aplicada?"
echo ">> invariantes da secao 15 da 012 e da secao 8 da 013, reexecutados"
{ awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql"
  awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/013_portal_externo.sql"; } > "$SAIDA/01_invariantes.sql"
[ "$(grep -c '^DO \$\$' "$SAIDA/01_invariantes.sql")" = 2 ] || falha "extracao dos blocos de invariantes falhou"
psql_papel nekkora -v ON_ERROR_STOP=1 < "$SAIDA/01_invariantes.sql" > "$SAIDA/01_invariantes.txt" 2>&1 || falha "invariantes falharam (ver 01_invariantes.txt)"

# ============================== fixture ==============================
BOOT="$REPO/db/bootstrap/001_organizacao_interna_nekkora.sql"
echo ">> f00_bootstrap (papel: nekkora)"
psql_papel nekkora -v org_id=99999999-0000-0000-0000-000000000099 -v org_name='Nekkora Internal' \
    -v staff_user_id=99999999-0000-0000-0000-0000000000aa -v staff_email=staff@nekkora.internal \
    -v staff_name='Nekkora Staff' -v limites='{"(1,1,0)","(2,1,0)","(3,1,0)","(4,2,)","(5,2,)"}' \
    < "$BOOT" > "$SAIDA/f00_bootstrap.txt" 2>&1 || falha "bootstrap falhou (ver $SAIDA/f00_bootstrap.txt)"
roda nekkora_provisioning f01_provisiona.sql
roda nekkora_platform     f02_fixture_platform.sql
roda nekkora_ingestion    f03_fixture_ingestao.sql
roda nekkora              f04_fixture_verifica.sql

# ============================== 2-7. bloco so de chamadas do portal ==============================
estado() { docker exec -i "$TESTE_CONTAINER" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora < "$AQUI/sql/s_estado.sql"; }
estado > "$SAIDA/s0_estado_antes.txt" || falha "snapshot de estado falhou"
refresca r1_refresca
roda nekkora_portal t05_privilegio.sql
roda nekkora        t05b_owner.sql
roda nekkora_portal t01_isolamento_escopo.sql
roda nekkora_portal t03_troca_contexto.sql
roda nekkora_portal t06_allowlist_paginacao.sql
roda nekkora_portal t08_robustez.sql
A4="$(ancora)"
roda nekkora        t04_prep.sql "" -v ancora="$A4"
roda nekkora_portal t04a_sessao_invalida.sql
roda nekkora_portal t04b_recurso_nao_autorizado.sql
roda nekkora        t04_verifica.sql "" -v ancora="$A4"
A4C="$(ancora)"
roda nekkora        t04c_prep.sql "" -v ancora="$A4C"
roda nekkora_portal t04c_toque.sql
roda nekkora        t04c_verifica.sql "" -v ancora="$A4C" \
    -v t1a="$(marcador_de MARCADOR_T1A "$SAIDA/t04c_toque.txt")" -v t1b="$(marcador_de MARCADOR_T1B "$SAIDA/t04c_toque.txt")" \
    -v t2a="$(marcador_de MARCADOR_T2A "$SAIDA/t04c_toque.txt")"
estado > "$SAIDA/s1_estado_depois.txt" || falha "snapshot de estado falhou"
diff "$SAIDA/s0_estado_antes.txt" "$SAIDA/s1_estado_depois.txt" > "$SAIDA/s_diff_estado.txt" \
    || falha "o bloco de chamadas do portal alterou dados alem de user_sessions.last_seen_at (ver s_diff_estado.txt)"
echo "conteudo de todas as $(wc -l < "$SAIDA/s0_estado_antes.txt") tabelas identico antes/depois (user_sessions sem last_seen_at)" >> "$SAIDA/s_diff_estado.txt"
# nenhuma saida do portal contem marcadores da fixture
if grep -l "MARCADOR_" "$SAIDA"/t0[1-8]*.txt | grep -v -e t04c_toque -e t09 ; then
    falha "marcador da fixture apareceu numa saida do portal (arquivos acima)"
fi

# ============================== 9. logs ==============================
roda nekkora_portal t09a_logs_papel.sql
roda nekkora        t09b_verifica_papel.sql
echo ">> t09_pgoptions (papel: nekkora_portal na conexao)"
for p in log_error_verbosity=verbose log_min_error_statement=error log_parameter_max_length=-1 log_statement=all \
         log_min_duration_statement=0 log_min_duration_sample=0 log_transaction_sample_rate=1; do
    if docker exec -e PGOPTIONS="-c $p" "$TESTE_CONTAINER" psql -X -d nekkora -U nekkora_portal -c 'SELECT 1' > "$SAIDA/t09_pgoptions_tmp.txt" 2>&1; then
        falha "conexao do portal com PGOPTIONS -c $p foi aceita"
    fi
    grep -q "permission denied to set parameter \"${p%%=*}\"" "$SAIDA/t09_pgoptions_tmp.txt" || falha "PGOPTIONS $p recusado por outro motivo"
    echo "PGOPTIONS -c $p -> $(head -1 "$SAIDA/t09_pgoptions_tmp.txt" | sed 's/.*FATAL: */FATAL: /')" >> "$SAIDA/t09_pgoptions.txt"
done
rm -f "$SAIDA/t09_pgoptions_tmp.txt"
teste_log() { # <rotulo> <papel> <arquivo> <presenca esperada: sim|nao> -> 0 se o log confirmou a expectativa
    local rot="$1" papel="$2" arq="$3" esperado="$4" m n
    m="$(gera_marcador)"
    roda_st "$5" "$papel" "$arq" "$rot" -v marcador="$m" || return 2
    sleep 1
    n="$(docker logs "$TESTE_CONTAINER" 2>&1 | grep -cF -- "$m" || true)"
    echo "$rot: marcador=$m ocorrencias_no_log_do_servidor=$n esperado=$esperado ocorrencias_na_saida_do_cliente=$(grep -cF -- "$m" "$5/$rot.txt" || true)" >> "$SAIDA/t09_log_servidor.txt"
    if [ "$esperado" = nao ]; then [ "$n" = 0 ]; else [ "$n" -gt 0 ]; fi
}
echo ">> t09c_token_no_log (papel: nekkora_portal) e t09d_controle_positivo (papel: nekkora)"
teste_log t09c_token_no_log nekkora_portal t09c_token_no_log.sql nao "$SAIDA" || falha "token-marcador do papel real apareceu no log do servidor (ou t09c falhou)"
teste_log t09d_controle_positivo nekkora t09d_controle_positivo.sql sim "$SAIDA" || falha "controle positivo: marcador NAO apareceu no log (a busca nao enxergaria vazamento)"

# ============================== estados: reativacao ==============================
roda nekkora_platform t10a_reativacao_platform.sql
refresca r2_refresca
roda nekkora_portal   t10b_reativacao_portal.sql
roda nekkora          t10c_verifica.sql

# ============================== 7. concorrencia ==============================
refresca r3_refresca
echo ">> concorrencia 7a (revogacao de concessao x transacao do portal aberta)"
com_asserts conc7a_portal.sql | psql_papel nekkora_portal "${TOKV[@]}" > "$SAIDA/conc7a_a_portal.txt" 2>&1 & PA=$!
sleep 2
roda nekkora_platform conc7a_platform.sql conc7a_b_platform
aguarda "7a sessao A (portal)" "$PA" || falha "7a: sessao A (portal) falhou (ver conc7a_a_portal.txt)"
L1="$(marcador_de MARCADOR_A_LEITURA1 "$SAIDA/conc7a_a_portal.txt")"; L2="$(marcador_de MARCADOR_A_LEITURA2 "$SAIDA/conc7a_a_portal.txt")"
BC="$(marcador_de MARCADOR_B_COMMIT "$SAIDA/conc7a_b_platform.txt")"
[ "$(consulta nekkora "SELECT '$L1'::timestamptz < '$BC'::timestamptz AND '$BC'::timestamptz < '$L2'::timestamptz")" = t ] \
    || falha "7a: ordem inesperada (leitura1=$L1 commit_B=$BC leitura2=$L2)"
echo "7a: leitura1=$L1 < commit_revogacao=$BC < leitura2=$L2 (mesma transacao do portal)" >> "$SAIDA/sessoes_concorrentes.txt"

echo ">> concorrencia 7b (revogacao da sessao espera a requisicao validada)"
com_asserts conc7b_portal_a.sql | psql_papel nekkora_portal "${TOKV[@]}" > "$SAIDA/conc7b_a_portal.txt" 2>&1 & PA=$!
sleep 2
com_asserts conc7b_platform_b.sql | psql_papel nekkora_platform "${TOKV[@]}" > "$SAIDA/conc7b_b_platform.txt" 2>&1 & PB=$!
sleep 2
RC=0; com_asserts conc_observador.sql | psql_papel nekkora -v bloqueado=nekkora_platform -v bloqueador=nekkora_portal > "$SAIDA/conc7b_c_observador.txt" 2>&1 || RC=$?
aguarda "7b sessao A (portal)" "$PA" || falha "7b: sessao A falhou (ver conc7b_a_portal.txt)"
aguarda "7b sessao B (platform)" "$PB" || falha "7b: sessao B falhou (ver conc7b_b_platform.txt)"
[ "$RC" -eq 0 ] || falha "7b: observador nao viu a revogacao bloqueada pelo portal (ver conc7b_c_observador.txt)"
AC="$(marcador_de MARCADOR_A_COMMIT "$SAIDA/conc7b_a_portal.txt")"; BC="$(marcador_de MARCADOR_B_COMMIT "$SAIDA/conc7b_b_platform.txt")"
[ "$(consulta nekkora "SELECT '$AC'::timestamptz <= '$BC'::timestamptz")" = t ] || falha "7b: revogacao confirmada antes do fim da requisicao ($AC / $BC)"
echo "7b: commit_portal=$AC <= commit_revogacao=$BC" >> "$SAIDA/sessoes_concorrentes.txt"
roda nekkora_portal conc7b_depois.sql

echo ">> concorrencia 7b-reverso (chamada espera a revogacao em curso e recebe NK401)"
com_asserts conc7br_platform_a.sql | psql_papel nekkora_platform "${TOKV[@]}" > "$SAIDA/conc7br_a_platform.txt" 2>&1 & PA=$!
sleep 2
com_asserts conc7br_portal_b.sql | psql_papel nekkora_portal "${TOKV[@]}" > "$SAIDA/conc7br_b_portal.txt" 2>&1 & PB=$!
sleep 2
RC=0; com_asserts conc_observador.sql | psql_papel nekkora -v bloqueado=nekkora_portal -v bloqueador=nekkora_platform > "$SAIDA/conc7br_c_observador.txt" 2>&1 || RC=$?
aguarda "7b-rev sessao A (platform)" "$PA" || falha "7b-reverso: sessao A falhou (ver conc7br_a_platform.txt)"
aguarda "7b-rev sessao B (portal)" "$PB" || falha "7b-reverso: sessao B falhou (ver conc7br_b_portal.txt)"
[ "$RC" -eq 0 ] || falha "7b-reverso: observador nao viu o portal bloqueado pela revogacao (ver conc7br_c_observador.txt)"

echo ">> concorrencia 7c (mudanca de estado entre a resolucao e a leitura)"
roda nekkora x_variante_pausa.sql
# caso_7c <dir> <caso> <esperado> <condicao-sql> <rotulo> ; resultados em RC_COORD RC_PORTAL RC_PLAT
caso_7c() {
    local dir="$1" caso="$2" esp="$3" cond="$4" rot="$5" pc pa
    RC_COORD=0; RC_PORTAL=0; RC_PLAT=0
    com_asserts conc7c_coord.sql | psql_papel nekkora -v cond="$cond" > "$dir/${rot}_coord.txt" 2>&1 & pc=$!
    if ! espera_condicao "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = 820713 AND granted" 1; then
        kill "$pc" 2>/dev/null || true; RC_COORD=90; return 1
    fi
    com_asserts conc7c_portal.sql | psql_papel nekkora_portal "${TOKV[@]}" -v caso="$caso" -v esperado="$esp" > "$dir/${rot}_portal.txt" 2>&1 & pa=$!
    # o coordenador so imprime o marcador depois de ver o portal parado no ponto de pausa (e de afirmar isso)
    espera_arquivo "$dir/${rot}_coord.txt" MARCADOR_PORTAL_PAUSADO || RC_PORTAL=91
    if [ "$caso" != controle ]; then roda_st "$dir" nekkora_platform conc7c_platform.sql "${rot}_platform" -v caso="$caso" -v acao=suspende || RC_PLAT=$?; fi
    wait "$pc" || RC_COORD=$?
    wait "$pa" || RC_PORTAL=$?
    if [ "$caso" != controle ]; then roda_st "$dir" nekkora_platform conc7c_platform.sql "${rot}_restaura" -v caso="$caso" -v acao=restaura || RC_PLAT=$?; fi
    echo "$rot: coordenador exit=$RC_COORD, portal exit=$RC_PORTAL, platform exit=$RC_PLAT" >> "$SAIDA/sessoes_concorrentes.txt"
    [ "$RC_COORD" -eq 0 ] && [ "$RC_PORTAL" -eq 0 ] && [ "$RC_PLAT" -eq 0 ]
}
K="40000000-0000-0001-0000-000000000101"
declare -A COND7C=(
  [controle]="true"
  [conta]="(SELECT status FROM public.users WHERE user_id = '$K') = 3"
  [vinculo]="(SELECT status FROM public.client_memberships WHERE user_id = '$K') = 3"
  [cliente]="(SELECT status FROM public.clients WHERE client_id = '40000000-0000-00c1-0000-000000000001') = 2"
  [org]="(SELECT status FROM public.organizations WHERE organization_id = '40000000-0000-0000-0000-000000000004') = 2"
  [concessao]="(SELECT status FROM public.client_workspace_grants WHERE grant_id = '40000000-0000-00c3-0000-000000000101') = 3"
  [workspace]="(SELECT status FROM public.workspaces WHERE workspace_id = '40000000-0000-0003-0000-000000000001') = 2")
bloco_7c() { # <dir> <prefixo> <casos...>
    local dir="$1" pre="$2" c esp; shift 2
    for c in "$@"; do
        refresca "${pre}_refresca_$c" > /dev/null
        esp=0; [ "$c" = controle ] && esp=1
        echo ">>   7c caso $c (esperado $esp linha(s))"
        caso_7c "$dir" "$c" "$esp" "${COND7C[$c]}" "${pre}_$c" || return 1
    done
}
bloco_7c "$SAIDA" conc7c controle conta vinculo cliente org concessao workspace || falha "7c: caso falhou (ver sessoes_concorrentes.txt e conc7c_*)"
roda nekkora x_variante_remove.sql

# ============================== mutacoes (testes do proprio runner) ==============================
impressao() { docker exec -i "$TESTE_CONTAINER" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora < "$AQUI/sql/f_impressao_portal.sql"; }
IMP0="$(impressao)"
echo "impressao_original=$IMP0" > "$MUT/resumo.txt"
for f in portal_resolve_session:"uuid,text" portal_list_workspaces:"uuid,text" portal_authorized_workspaces:"uuid,uuid,uuid,timestamptz"; do
    consulta nekkora "SELECT pg_get_functiondef('public.${f%%:*}(${f#*:})'::regprocedure)" > "$MUT/original_${f%%:*}.sql"
done
aplica_mutacao() { # <arquivo>
    { echo "== mutacao aplicada de proposito (teste do runner): $1"; psql_papel nekkora -v ON_ERROR_STOP=1 < "$AQUI/mutacoes/$1" 2>&1; } > "$MUT/aplica_${1%.sql}.txt" \
        || falha "mutacao $1 nao aplicou"
}
restaura() { # restaura as funcoes e o papel e confere a impressao digital
    local f
    for f in portal_resolve_session portal_list_workspaces portal_authorized_workspaces; do
        { cat "$MUT/original_$f.sql"; echo ";"; } | psql_papel nekkora -v ON_ERROR_STOP=1 > /dev/null 2>&1 || falha "restauracao de $f falhou"
    done
    consulta nekkora "ALTER ROLE nekkora_portal SET log_error_verbosity = 'terse'" > /dev/null
    [ "$(impressao)" = "$IMP0" ] || falha "restauracao nao reproduziu o estado original do portal ($1)"
    echo "$1: restaurado; impressao identica a original" >> "$MUT/resumo.txt"
}
exige_falha() { # <rotulo> <codigo> <arquivo-saida> <trecho esperado>
    [ "$2" -ne 0 ] || falha "MUTACAO $1 NAO FOI DETECTADA: o teste passou contra a variante errada"
    grep -qF -- "$4" "$3" || falha "mutacao $1: o teste falhou, mas sem a mensagem esperada ('$4') - ver $3"
    echo "$1: DETECTADA (exit=$2, mensagem: $(grep -m1 -F -- "$4" "$3" | sed 's/^ERROR: *//'))" >> "$MUT/resumo.txt"
}

echo ">> mutacao m1a (resolucao confia em GUC de usuario) -> t03 precisa falhar"
aplica_mutacao m1a_resolve_confia_guc_usuario.sql
refresca m1a_refresca > /dev/null; RC=0; roda_st "$MUT" nekkora_portal t03_troca_contexto.sql m1a_t03 || RC=$?
exige_falha m1a "$RC" "$MUT/m1a_t03.txt" "FALHA DE ASSERCAO: passo 6: list_workspaces identico ao baseline"
restaura m1a
refresca m1a_refresca2 > /dev/null; roda nekkora_portal t03_troca_contexto.sql m1a_t03_funcao_final

echo ">> mutacao m1b (funcao usa o fn_organization_id do chamador) -> t03 precisa falhar"
aplica_mutacao m1b_fn_org_do_chamador.sql
refresca m1b_refresca > /dev/null; RC=0; roda_st "$MUT" nekkora_portal t03_troca_contexto.sql m1b_t03 || RC=$?
exige_falha m1b "$RC" "$MUT/m1b_t03.txt" "FALHA DE ASSERCAO: chamada deveria ter sucesso (SQLSTATE NK401): passo 3: list_workspaces sem erro"
restaura m1b
refresca m1b_refresca2 > /dev/null; roda nekkora_portal t03_troca_contexto.sql m1b_t03_funcao_final

echo ">> mutacao m2 (leitura sem reconferencia de conta/vinculo/cliente/organizacao) -> 7c precisa falhar"
roda nekkora x_variante_pausa.sql m2_x_variante_pausa
aplica_mutacao m2_leitura_sem_reconferencia.sql
for c in conta vinculo cliente org; do
    refresca "m2_refresca_$c" > /dev/null
    caso_7c "$MUT" "$c" 0 "${COND7C[$c]}" "m2_7c_$c" || true
    [ "$RC_COORD" -eq 0 ] && [ "$RC_PLAT" -eq 0 ] || falha "m2/$c: coordenador ou platform falharam (nao e a deteccao esperada)"
    exige_falha "m2/$c" "$RC_PORTAL" "$MUT/m2_7c_${c}_portal.txt" "FALHA DE ASSERCAO: caso $c: leitura apos a mudanca confirmada"
done
restaura m2
bloco_7c "$SAIDA" m2_7c_funcao_final conta vinculo cliente org || falha "7c com a funcao restaurada falhou"
roda nekkora x_variante_remove.sql m2_x_variante_remove

echo ">> mutacao m3 (toque antes da validacao) -> t04a precisa falhar"
aplica_mutacao m3_toque_antes_da_validacao.sql
roda_st "$MUT" nekkora t04_prep.sql m3_t04_prep -v ancora="$(ancora)" || falha "m3: prep falhou"
RC=0; roda_st "$MUT" nekkora_portal t04a_sessao_invalida.sql m3_t04a || RC=$?
exige_falha m3 "$RC" "$MUT/m3_t04a.txt" "FALHA DE ASSERCAO: esperado NK401, obtido SQLSTATE 00000: sessao inativa ha 16 min (ws)"
restaura m3
A4="$(ancora)"
roda nekkora        t04_prep.sql m3_t04_prep_funcao_final -v ancora="$A4"
roda nekkora_portal t04a_sessao_invalida.sql m3_t04a_funcao_final
roda nekkora_portal t04b_recurso_nao_autorizado.sql m3_t04b_funcao_final
roda nekkora        t04_verifica.sql m3_t04_verifica_funcao_final -v ancora="$A4"

echo ">> mutacao m4 (papel sem log_error_verbosity = terse) -> busca no log precisa acusar o marcador"
aplica_mutacao m4_papel_sem_terse.sql
RC=0; teste_log m4_t09c nekkora_portal t09c_token_no_log.sql nao "$MUT" || RC=$?
[ "$RC" -eq 1 ] || falha "MUTACAO m4 NAO FOI DETECTADA (rc=$RC): o marcador nao apareceu no log com o papel sem terse"
echo "m4: DETECTADA (o marcador do papel apareceu no log do servidor sem log_error_verbosity = terse)" >> "$MUT/resumo.txt"
restaura m4
teste_log m4_t09c_funcao_final nekkora_portal t09c_token_no_log.sql nao "$SAIDA" || falha "t09c com o papel restaurado: marcador no log"

# ============================== conferencia final ==============================
roda nekkora t00_catalogo.sql 99_t00_catalogo_final
psql_papel nekkora -v ON_ERROR_STOP=1 < "$SAIDA/01_invariantes.sql" > "$SAIDA/99_invariantes_final.txt" 2>&1 || falha "invariantes finais falharam"
lista_catalogo nekkora > "$SAIDA/99_catalogo_001_013_final.txt"
cmp -s "$SAIDA/01_catalogo_001_013.txt" "$SAIDA/99_catalogo_001_013_final.txt" \
    || falha "catalogo final difere do catalogo logo apos a instalacao (variante/mutacao nao removida?)"
echo "catalogo final identico ao da instalacao (variantes e mutacoes removidas)" > "$SAIDA/99_catalogo_final.txt"

# Rede de seguranca: script quebrado, variavel nao substituida, falha de assercao fora das mutacoes.
if grep -l -E "syntax error at or near|unterminated quoted|invalid command \\\\|FALHA DE ASSERCAO" "$SAIDA"/*.txt; then
    falha "saida com erro de sintaxe, variavel nao substituida ou falha de assercao (arquivos acima)"
fi
# Nenhum token de sessao desta execucao pode aparecer em saida alguma nem no log do servidor.
# .log (nao .txt): as mutacoes gravam de proposito "FALHA DE ASSERCAO" no log do servidor
docker logs "$TESTE_CONTAINER" > "$SAIDA/99_log_servidor.log" 2>&1
for n in "${NOMES_TOK[@]}"; do
    if grep -rlF -- "${TOK[$n]}" "$SAIDA" >/dev/null; then falha "token de sessao ($n) apareceu numa saida ou no log do servidor"; fi
done
echo "nenhum dos ${#NOMES_TOK[@]} tokens de sessao aparece nas saidas nem no log do servidor" > "$SAIDA/zz_tokens_fora_das_saidas.txt"

echo "OK - saidas em $SAIDA"
echo "Stack de teste '$PROJETO' continua no ar (porta $PORTA). Remocao, quando quiser:"
echo "  docker rm -f $TESTE_CONTAINER && docker volume rm $VOLUME && docker network rm $REDE"
