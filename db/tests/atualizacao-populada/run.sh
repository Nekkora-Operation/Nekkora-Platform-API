#!/usr/bin/env bash
# Fase 3 - atualizacao POPULADA 001-012 -> 013 -> 014 -> 015 pelo caminho operacional (Flyway, SEM baseline automatico;
# procedimento do registro de decisoes 8.6/8.7/8.10, §6.2).
#
#   1. ORIGEM: cluster isolado com 001-012 pelo entrypoint (o mesmo caminho da stack principal) e massa gerada
#      (p01, superusuario, DECLARADO). Faz o papel da "base em uso"; depois da copia, NADA mais escreve nela.
#   2. COPIA: copia FISICA (pg_basebackup local na origem, levada ao PGDATA de outro container - equivale a copiar
#      o volume). Toda a atualizacao acontece SO na copia. A restauracao LOGICA (pg_dump/pg_restore) tambem e
#      tentada, num banco descartavel, e o resultado registrado: backup ANTERIOR a 015 falha (achado que motivou a
#      015; ver db/tests/restauracao-015). Depois da atualizacao, a restauracao logica da copia precisa passar (5e).
#   3. Antes do baseline: (a) catalogo da copia = catalogo de uma instalacao limpa 001-012 (nekkora_ref, mesmo
#      cluster), diff VAZIO; (b) nenhum historico de migration; (c) aprovacao registrada (neste ensaio, pelo
#      runner, por ser copia descartavel - em producao a aprovacao e humana e fica fora deste script).
#   4. Flyway: "migrate" sem baseline PRECISA falhar (prova de que nao ha baseline automatico); depois
#      "baseline -baselineVersion=012", "migrate", "validate", "info".
#   5. Depois: catalogo da copia = instalacao limpa 001-015 (nekkora_ref_alvo), exceto a tabela de historico do
#      Flyway; dados: cada linha anterior permanece identica, exceto as mudancas previstas da 014 (revogacao das
#      sessoes pre-014, permissao identity.mfa.reset e sua concessao ao ORG_ADMIN); v01/v02 com os papeis reais;
#      ORIGEM intacta (mesmos dados, sem historico do Flyway).
#
# Nunca toca a stack principal (nekkora-postgres / 5432) nem as stacks de teste existentes; nao publica porta;
# senha do superusuario aleatoria, so no ambiente deste processo. Os containers ficam no ar; a remocao e impressa.
# Uso: db/tests/atualizacao-populada/run.sh      (ENSAIO_PROJETO=nekkora-atualizacao-b ENSAIO_N_EXEC=3000 ...)
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
PROJ="${ENSAIO_PROJETO:-nekkora-atualizacao}"
N_EXEC="${ENSAIO_N_EXEC:-1500}"
ORIG="${PROJ}-origem"
COPIA="${PROJ}-copia"
REDE="${PROJ}-rede"
IMAGEM=postgres:16-alpine
FLYWAY=flyway/flyway:11-alpine
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJ}"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"   # reutilizada (lida), nunca alterada
ORG=aaaaaaaa-0000-4000-8000-000000000001
EXT=aaaaaaaa-0000-4000-8000-0000000000e1
WS_REF=aaaaaaaa-0000-4000-8000-0000000000a1

falha() { echo "ERRO: $*" >&2; exit 1; }
winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

# --- protecao ---
for c in "$ORIG" "$COPIA"; do
    case "$c" in nekkora-postgres|nekkora-portal-dev-postgres|nekkora-portal-013-postgres) falha "container $c pertence a outra stack";; esac
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done
docker network inspect "$REDE" >/dev/null 2>&1 && falha "rede $REDE ja existe"

mkdir -p "$SAIDA"/{mig_012,mig_alvo,dados_origem_antes,dados_copia_antes,dados_copia_depois,dados_origem_depois,colunas}
for n in $(seq -w 1 15); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig_alvo/"
    [ "$((10#$n))" -le 12 ] && cp "${f[0]}" "$SAIDA/mig_012/"
done
sha256sum "$SAIDA"/mig_alvo/*.sql | sed "s#$SAIDA/##" > "$SAIDA/00_migracoes_sha256.txt"

export PGPASSWORD="$(head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
export FLYWAY_PASSWORD="$PGPASSWORD"

psqlc() { local c="$1" db="$2" papel="$3"; shift 3; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U "$papel" -d "$db" "$@"; }
# stdin de /dev/null: dentro de "while read" o docker exec -i consumiria o resto da lista
consulta() { psqlc "$1" "$2" nekkora -c "$3" < /dev/null; }
lista_catalogo() { psqlc "$1" "$2" nekkora < "$LISTAGEM"; }
espera_pronto() {
    for i in $(seq 1 120); do
        [ "$(docker logs "$1" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && return 0
        docker ps -q --filter "name=^$1\$" | grep -q . || { docker logs "$1" > "$SAIDA/falha_$1.log" 2>&1; falha "$1 parou (ver falha_$1.log)"; }
        sleep 2
    done
    falha "$1 nao ficou pronto"
}
aplica() {   # instalacao limpa por psql, arquivo a arquivo (cada migration tem a propria transacao)
    local c="$1" db="$2" dir="$3"
    consulta "$c" postgres "CREATE DATABASE $db" >/dev/null
    for f in "$dir"/*.sql; do psqlc "$c" "$db" nekkora -q < "$f" > /dev/null; done
}
# Instantaneo dos dados: por tabela, as colunas registradas NO PRIMEIRO instantaneo (colunas novas da 014 ficam
# de fora, de proposito), linhas em texto COPY ordenadas (LC_ALL=C).
instantaneo() {
    local c="$1" db="$2" dest="$3"
    if [ ! -s "$SAIDA/colunas/_tabelas.txt" ]; then
        consulta "$c" "$db" "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1" > "$SAIDA/colunas/_tabelas.txt"
        while read -r t; do
            consulta "$c" "$db" "SELECT string_agg(quote_ident(attname), ',' ORDER BY attnum) FROM pg_attribute
                                 WHERE attrelid = 'public.$t'::regclass AND attnum > 0 AND NOT attisdropped" > "$SAIDA/colunas/$t.txt"
        done < "$SAIDA/colunas/_tabelas.txt"
    fi
    while read -r t; do
        consulta "$c" "$db" "COPY (SELECT $(cat "$SAIDA/colunas/$t.txt") FROM public.$t) TO STDOUT" | LC_ALL=C sort > "$dest/$t.txt"
    done < "$SAIDA/colunas/_tabelas.txt"
}
flyway() {
    MSYS_NO_PATHCONV=1 docker run --rm --network "$REDE" -e FLYWAY_PASSWORD -v "$(winpath "$SAIDA/mig_alvo"):/flyway/sql:ro" "$FLYWAY" \
        -url="jdbc:postgresql://$COPIA:5432/nekkora" -user=nekkora -locations=filesystem:/flyway/sql \
        -sqlMigrationPrefix= -sqlMigrationSeparator=_ -executeInTransaction=false -postgresql.transactional.lock=false \
        -baselineOnMigrate=false -validateMigrationNaming=true "$@"
}

PY=""
for c in python3 python; do "$c" -c 'import sys' >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || falha "Python 3 necessario para a comparacao linha a linha de user_sessions"

docker network create "$REDE" >/dev/null

echo "== 1. ORIGEM: 001-012 pelo entrypoint e massa ($N_EXEC execucoes por organizacao)"
MSYS_NO_PATHCONV=1 docker run -d --name "$ORIG" --network "$REDE" -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora -e PGPASSWORD \
    -e POSTGRES_PASSWORD="$PGPASSWORD" -v "$(winpath "$SAIDA/mig_012"):/docker-entrypoint-initdb.d:ro" "$IMAGEM" >/dev/null
espera_pronto "$ORIG"
TOK_PRE="$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
HASH_PRE="$(printf %s "$TOK_PRE" | sha256sum | cut -d' ' -f1)"
psqlc "$ORIG" nekkora nekkora -v n_exec="$N_EXEC" -v tok_pre="$HASH_PRE" < "$AQUI/sql/p01_popula.sql" | tail -1 | tee "$SAIDA/01_massa.txt"
consulta "$ORIG" nekkora "SELECT to_regclass('public.flyway_schema_history') IS NULL" | grep -qx t || falha "origem ja tem historico do Flyway"
instantaneo "$ORIG" nekkora "$SAIDA/dados_origem_antes"
lista_catalogo "$ORIG" nekkora > "$SAIDA/02_catalogo_origem.txt"

echo "== 2. COPIA fisica (pg_basebackup local na origem -> PGDATA de um container novo; equivale a copiar o volume)"
docker exec "$ORIG" sh -c 'rm -rf /tmp/copia && pg_basebackup -U nekkora -D /tmp/copia -Fp -X fetch -c fast'
MSYS_NO_PATHCONV=1 docker create --name "$COPIA" --network "$REDE" -e PGDATA=/var/lib/postgresql/copia -e PGPASSWORD     -e POSTGRES_PASSWORD="$PGPASSWORD" "$IMAGEM" >/dev/null
MSYS_NO_PATHCONV=1 docker cp "$ORIG:/tmp/copia" - | MSYS_NO_PATHCONV=1 docker cp -a - "$COPIA:/var/lib/postgresql/"
docker exec "$ORIG" rm -rf /tmp/copia
docker start "$COPIA" >/dev/null
for i in $(seq 1 60); do
    docker exec "$COPIA" pg_isready -U nekkora -d nekkora >/dev/null 2>&1 && break
    [ "$i" -lt 60 ] || { docker logs "$COPIA" > "$SAIDA/falha_copia.log" 2>&1; falha "copia nao subiu"; }
    sleep 1
done

# Backup ANTERIOR a 015 (nao interrompe o ensaio): restauracao LOGICA da base 001-012 populada. pg_restore zera o
# search_path; is_valid_command_contract (005, LANGUAGE sql, sem SET search_path) chama is_valid_relative_entrypoint
# sem schema, e o CHECK de automation_versions/executions falha no COPY. Resultado salvo em 03b.
set +e
docker exec -e PGPASSWORD "$COPIA" sh -c "psql -X -q -U nekkora -d postgres -c 'CREATE DATABASE restauracao_logica' &&     pg_dump -h $ORIG -U nekkora -Fc nekkora | pg_restore -U nekkora -d restauracao_logica --exit-on-error" > "$SAIDA/03b_restauracao_logica.txt" 2>&1
rc_logica=$?
set -e
consulta "$COPIA" postgres "DROP DATABASE IF EXISTS restauracao_logica" >/dev/null
if [ "$rc_logica" -ne 0 ]; then
    echo "backup anterior a 015: pg_dump/pg_restore da base 001-012 populada FALHOU, como esperado (rc=$rc_logica): $(grep -m1 -o 'function [a-z_]*([a-z]*) does not exist' "$SAIDA/03b_restauracao_logica.txt" || echo 'ver 03b')"
else
    echo "restauracao logica: sem erro"
fi
instantaneo "$COPIA" nekkora "$SAIDA/dados_copia_antes"
diff -r "$SAIDA/dados_origem_antes" "$SAIDA/dados_copia_antes" > /dev/null || falha "copia difere da origem nos dados"
lista_catalogo "$COPIA" nekkora > "$SAIDA/03_catalogo_copia.txt"
cmp -s "$SAIDA/02_catalogo_origem.txt" "$SAIDA/03_catalogo_copia.txt" || falha "copia difere da origem no catalogo"
echo "copia fiel: dados e catalogo identicos a origem"

echo "== 3. procedimento antes do baseline"
aplica "$COPIA" nekkora_ref "$SAIDA/mig_012"
lista_catalogo "$COPIA" nekkora_ref > "$SAIDA/04_catalogo_ref_001_012.txt"
diff "$SAIDA/04_catalogo_ref_001_012.txt" "$SAIDA/03_catalogo_copia.txt" > "$SAIDA/04_diff_pre_baseline.txt" \
    || falha "catalogo da copia difere da instalacao limpa 001-012 (ver 04_diff_pre_baseline.txt): baseline RECUSADO"
HIST="$(consulta "$COPIA" nekkora "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                                   WHERE c.relkind IN ('r','p') AND (c.relname ILIKE '%schema_history%' OR c.relname ILIKE 'schema_version%'
                                   OR c.relname ILIKE '%migration%')")"
[ "$HIST" = 0 ] || falha "ha tabela de historico de migration na copia: baseline RECUSADO"
{
    echo "aprovacao de baseline (ENSAIO): versao declarada 012"
    echo "  1. diff de catalogo copia x instalacao limpa 001-012: vazio ($(wc -l < "$SAIDA/03_catalogo_copia.txt") linhas comparadas)"
    echo "  2. historico de migration existente: nenhum"
    echo "  3. aprovado por: runner do ensaio, em copia descartavel ($(date -u +%Y-%m-%dT%H:%M:%SZ)). Em producao a aprovacao e humana."
} | tee "$SAIDA/05_aprovacao_baseline.txt"

echo "== 4. Flyway"
set +e
flyway migrate > "$SAIDA/06_flyway_migrate_sem_baseline.txt" 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || falha "flyway migrate sem baseline NAO falhou - baseline automatico?"
grep -qi "non-empty schema" "$SAIDA/06_flyway_migrate_sem_baseline.txt" || falha "falha do migrate sem baseline por outro motivo (ver 06_...)"
consulta "$COPIA" nekkora "SELECT to_regclass('public.flyway_schema_history') IS NULL" | grep -qx t || falha "migrate recusado deixou historico"
lista_catalogo "$COPIA" nekkora | cmp -s - "$SAIDA/03_catalogo_copia.txt" || falha "migrate recusado alterou o catalogo"
echo "migrate sem baseline recusado (rc=$rc), sem efeito"
flyway baseline -baselineVersion=012 "-baselineDescription=ensaio: copia populada conferida (05_aprovacao_baseline.txt)" > "$SAIDA/07_flyway_baseline.txt" 2>&1
flyway info > "$SAIDA/08_flyway_info_antes.txt" 2>&1
T0="$(date +%s)"
flyway migrate > "$SAIDA/09_flyway_migrate.txt" 2>&1 || { cat "$SAIDA/09_flyway_migrate.txt"; falha "flyway migrate falhou"; }
echo "migrate: $(( $(date +%s) - T0 )) s" | tee -a "$SAIDA/09_flyway_migrate.txt"
flyway validate > "$SAIDA/10_flyway_validate.txt" 2>&1 || falha "flyway validate falhou"
flyway info > "$SAIDA/11_flyway_info_depois.txt" 2>&1
consulta "$COPIA" nekkora "SELECT installed_rank || '|' || coalesce(version, '-') || '|' || description || '|' || type || '|' || success
                           FROM flyway_schema_history ORDER BY installed_rank" | tee "$SAIDA/12_historico.txt"
[ "$(cut -d'|' -f2,5 "$SAIDA/12_historico.txt" | tr '\n' ' ')" = "012|true 013|true 014|true 015|true " ] || falha "historico inesperado"

echo "== 5a. catalogo depois = instalacao limpa 001-015 (fora o historico do Flyway)"
aplica "$COPIA" nekkora_ref_alvo "$SAIDA/mig_alvo"
lista_catalogo "$COPIA" nekkora_ref_alvo > "$SAIDA/13_catalogo_ref_001_015.txt"
lista_catalogo "$COPIA" nekkora > "$SAIDA/13_catalogo_copia_depois.txt"
diff "$SAIDA/13_catalogo_ref_001_015.txt" "$SAIDA/13_catalogo_copia_depois.txt" > "$SAIDA/13_diff_depois.txt" || true
grep -v 'flyway_schema_history' "$SAIDA/13_diff_depois.txt" | grep -E '^[<>]' > "$SAIDA/13_diff_depois_sem_flyway.txt" || true
[ ! -s "$SAIDA/13_diff_depois_sem_flyway.txt" ] || falha "catalogo atualizado difere da instalacao limpa 001-015 (ver 13_diff_depois_sem_flyway.txt)"
grep -c '^>' "$SAIDA/13_diff_depois.txt" | sed 's/^/linhas so da copia (historico do Flyway): /'
diff "$SAIDA/03_catalogo_copia.txt" "$SAIDA/13_catalogo_copia_depois.txt" > "$SAIDA/13_diff_antes_depois.txt" || true
echo "mudancas de catalogo 012 -> 015 na copia (a unica alterada deve ser is_valid_command_contract, pela 015): removidas/alteradas $(grep -c '^<' "$SAIDA/13_diff_antes_depois.txt"), acrescentadas $(grep -c '^>' "$SAIDA/13_diff_antes_depois.txt")"
[ "$(grep -c '^<' "$SAIDA/13_diff_antes_depois.txt")" = 1 ] && grep -q '^< fn|is_valid_command_contract(jsonb)|.*|config=|' "$SAIDA/13_diff_antes_depois.txt"     && grep -q '^> fn|is_valid_command_contract(jsonb)|.*|config={"search_path=pg_catalog, public"}|' "$SAIDA/13_diff_antes_depois.txt"     || falha "012 -> 015 alterou objeto de 001-012 alem do search_path de is_valid_command_contract (ver 13_diff_antes_depois.txt)"

echo "== 5b. dados: tudo o que existia continua identico, exceto o previsto pela 014"
instantaneo "$COPIA" nekkora "$SAIDA/dados_copia_depois"
: > "$SAIDA/14_dados_diff.txt"
while read -r t; do
    rem="$(LC_ALL=C comm -23 "$SAIDA/dados_copia_antes/$t.txt" "$SAIDA/dados_copia_depois/$t.txt" | wc -l)"
    add="$(LC_ALL=C comm -13 "$SAIDA/dados_copia_antes/$t.txt" "$SAIDA/dados_copia_depois/$t.txt" | wc -l)"
    echo "$t removidas=$rem acrescentadas=$add linhas_antes=$(wc -l < "$SAIDA/dados_copia_antes/$t.txt")" >> "$SAIDA/14_dados_diff.txt"
done < "$SAIDA/colunas/_tabelas.txt"
grep -vE ' removidas=0 acrescentadas=0 ' "$SAIDA/14_dados_diff.txt" | tee "$SAIDA/14_dados_mudancas.txt"
obtido="$(sed -E 's/ linhas_antes=.*//' "$SAIDA/14_dados_mudancas.txt")"
echo "$obtido" | grep -q '^permissions removidas=0 acrescentadas=1$' || falha "permissions: mudanca inesperada"
echo "$obtido" | grep -qE "^role_permissions removidas=0 acrescentadas=$(consulta "$COPIA" nekkora "SELECT count(*) FROM roles WHERE code = 'ORG_ADMIN'")\$" \
    || falha "role_permissions: mudanca inesperada"
[ "$(echo "$obtido" | grep -vcE '^(permissions|role_permissions|user_sessions) ')" = 0 ] || falha "tabela fora do previsto mudou (ver 14_dados_mudancas.txt)"
LC_ALL=C comm -13 "$SAIDA/dados_copia_antes/permissions.txt" "$SAIDA/dados_copia_depois/permissions.txt" | grep -q 'identity.mfa.reset' || falha "permissao nova nao e identity.mfa.reset"
# user_sessions: as linhas que mudaram sao exatamente as que tinham revoked_at nulo, e so revoked_at mudou
psqlc "$COPIA" nekkora nekkora > "$SAIDA/15_sessoes.txt" <<SQL
SELECT 'ativas_agora=' || count(*) FROM user_sessions WHERE revoked_at IS NULL;
SELECT 'mfa_events=' || count(*) FROM mfa_events;
SELECT 'fatores=' || count(*) FROM user_mfa_factors;
SQL
cat "$SAIDA/15_sessoes.txt"
grep -qx 'ativas_agora=0' "$SAIDA/15_sessoes.txt" || falha "sessao pre-014 ainda nao revogada"
"$PY" - "$SAIDA" > "$SAIDA/16_sessoes_linha_a_linha.txt" <<'PY' || falha "user_sessions mudou alem de revoked_at nulo -> preenchido (ver 16_...)"
import sys
d = sys.argv[1]
cols = open(f"{d}/colunas/user_sessions.txt").read().strip().split(",")
iid, irev = cols.index("session_id"), cols.index("revoked_at")
ler = lambda p: {l.split("\t")[iid]: l.rstrip("\n").split("\t") for l in open(p, encoding="utf-8")}
antes, depois = ler(f"{d}/dados_copia_antes/user_sessions.txt"), ler(f"{d}/dados_copia_depois/user_sessions.txt")
assert antes.keys() == depois.keys(), "conjunto de sessoes mudou"
mudou = 0
for k, a in antes.items():
    b = depois[k]
    if a == b:
        assert a[irev] != "\\N", f"sessao {k} ativa nao foi revogada"
        continue
    outras = [i for i in range(len(a)) if a[i] != b[i] and i != irev]
    assert not outras, f"sessao {k}: colunas alem de revoked_at mudaram: {[cols[i] for i in outras]}"
    assert a[irev] == "\\N" and b[irev] != "\\N", f"sessao {k}: revoked_at alterado indevidamente"
    mudou += 1
print(f"sessoes={len(antes)} revogadas_pela_014={mudou} ja_revogadas_intactas={len(antes) - mudou}")
PY
cat "$SAIDA/16_sessoes_linha_a_linha.txt"

echo "== 5c. papeis reais na copia atualizada (v01 platform, v02 portal)"
TOK_NOVO="$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
HASH_NOVO="$(printf %s "$TOK_NOVO" | sha256sum | cut -d' ' -f1)"
CSRF="$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
psqlc "$COPIA" nekkora nekkora_platform -v org="$ORG" -v ext="$EXT" -v hash_novo="$HASH_NOVO" -v csrf="$CSRF" \
    < "$AQUI/sql/v01_platform.sql" > "$SAIDA/17_v01_platform.txt" 2>&1 || { cat "$SAIDA/17_v01_platform.txt"; falha "v01"; }
grep -E '^(sem_marcador|reativacao)_(sqlstate|msg)=|^sessao_nova=' "$SAIDA/17_v01_platform.txt"
grep -qx 'sem_marcador_msg=sessao nova exige marcador de MFA e CSRF, sem step-up nem revogacao' "$SAIDA/17_v01_platform.txt"     || falha "sessao sem marcador: recusa nao veio da trigger da 014"
grep -qx 'reativacao_msg=sessao revogada nao pode ser reabilitada nem ter a revogacao alterada' "$SAIDA/17_v01_platform.txt"     || falha "reativacao: recusa nao veio da trigger da 014"
grep -qE '^sem_marcador_sqlstate=[0-9A-Z]{5}$' "$SAIDA/17_v01_platform.txt" || falha "v01 sem SQLSTATE do caso sem marcador"
! grep -qE '^sem_marcador_sqlstate=00000$' "$SAIDA/17_v01_platform.txt" || falha "sessao sem marcador de MFA aceita"
grep -qE '^reativacao_sqlstate=[0-9A-Z]{5}$' "$SAIDA/17_v01_platform.txt" || falha "v01 sem SQLSTATE da reativacao"
! grep -qE '^reativacao_sqlstate=00000$' "$SAIDA/17_v01_platform.txt" || falha "reativacao de sessao revogada aceita"
psqlc "$COPIA" nekkora nekkora_portal -v org="$ORG" -v tok_pre="$TOK_PRE" -v tok_novo="$TOK_NOVO" \
    < "$AQUI/sql/v02_portal.sql" > "$SAIDA/18_v02_portal.txt" 2>&1 || { cat "$SAIDA/18_v02_portal.txt"; falha "v02"; }
cat "$SAIDA/18_v02_portal.txt"
grep -qx 'pre_014_sqlstate=NK401' "$SAIDA/18_v02_portal.txt" || falha "sessao pre-014 ainda aceita pelo portal"
grep -qx "workspaces=$WS_REF" "$SAIDA/18_v02_portal.txt" || falha "concessao pre-existente nao aparece"
ESPERADO_N="$(consulta "$COPIA" nekkora "SELECT least(count(*), 100) FROM executions WHERE workspace_id = '$WS_REF'")"
ESPERADO_MD5="$(consulta "$COPIA" nekkora "SELECT md5(string_agg(execution_id::text, ',' ORDER BY execution_id)) FROM (
    SELECT execution_id FROM executions WHERE workspace_id = '$WS_REF' ORDER BY created_at DESC, execution_id DESC LIMIT 100) s")"
grep -qx "execucoes_pagina=$ESPERADO_N" "$SAIDA/18_v02_portal.txt" || falha "pagina de execucoes: esperado $ESPERADO_N"
grep -qx "execucoes_ids=$ESPERADO_MD5" "$SAIDA/18_v02_portal.txt" || falha "execucoes da pagina diferem das pre-existentes mais recentes"
for t in "$TOK_PRE" "$TOK_NOVO" "$CSRF"; do
    ! grep -rqF -- "$t" "$SAIDA" || falha "token/CSRF gravado em saida"
    ! docker logs "$COPIA" 2>&1 | grep -qF -- "$t" || falha "token/CSRF no log do servidor"
done

echo "== 5e. restauracao LOGICA da copia atualizada (backup gerado depois da 015)"
consulta "$COPIA" postgres "CREATE DATABASE restauracao_pos_015" > /dev/null
docker exec -e PGPASSWORD "$COPIA" sh -c "pg_dump -h 127.0.0.1 -U nekkora -Fc nekkora | pg_restore -U nekkora -d restauracao_pos_015 --exit-on-error"     > "$SAIDA/19_restauracao_pos_015.txt" 2>&1 || { cat "$SAIDA/19_restauracao_pos_015.txt"; falha "restauracao logica depois da 015 falhou"; }
for db in nekkora restauracao_pos_015; do
    consulta "$COPIA" "$db" "SELECT string_agg(format('%s=%s', relname, n_live), ',' ORDER BY relname) FROM (
        SELECT c.relname, (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM public.%I', c.relname), false, true, '')))[1]::text AS n_live
        FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace WHERE ns.nspname = 'public' AND c.relkind = 'r') t"
done > "$SAIDA/19_contagens.txt"
[ "$(sed -n 1p "$SAIDA/19_contagens.txt")" = "$(sed -n 2p "$SAIDA/19_contagens.txt")" ] || falha "contagens da restauracao pos-015 diferem"
consulta "$COPIA" postgres "DROP DATABASE restauracao_pos_015" > /dev/null
echo "restauracao logica pos-015: rc=0, contagens por tabela identicas"

echo "== 5d. ORIGEM intacta"
instantaneo "$ORIG" nekkora "$SAIDA/dados_origem_depois"
diff -r "$SAIDA/dados_origem_antes" "$SAIDA/dados_origem_depois" > /dev/null || falha "ORIGEM mudou"
lista_catalogo "$ORIG" nekkora | cmp -s - "$SAIDA/02_catalogo_origem.txt" || falha "catalogo da ORIGEM mudou"
consulta "$ORIG" nekkora "SELECT to_regclass('public.flyway_schema_history') IS NULL" | grep -qx t || falha "ORIGEM ganhou historico do Flyway"
echo "origem: dados, catalogo e ausencia de historico inalterados"

rm -rf "$SAIDA"/dados_* # instantaneos grandes; os resumos ficam
echo "ENSAIO DE ATUALIZACAO POPULADA OK - saidas: $SAIDA"
echo "remocao: docker rm -f $ORIG $COPIA && docker network rm $REDE"
