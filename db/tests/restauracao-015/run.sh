#!/usr/bin/env bash
# Validacao da 015_restauracao_logica.sql: backup LOGICO completo e restauracao em outra base.
#
#   A  cluster isolado: 001-012 pelo entrypoint, massa (db/tests/atualizacao-populada/sql/p01_popula.sql, lida,
#      superusuario, DECLARADO), 013 e 014 por psql. Backup ANTIGO (pg_dump -Fc, antes da 015).
#      Controle negativo: o backup antigo NAO restaura (a falha que motivou a 015).
#      Aplica a 015; mutacao: sem o search_path, o bloco de invariantes da 015 PRECISA falhar.
#      Backup NOVO (pg_dump -Fc --create).
#   B  cluster isolado: papeis (pg_dumpall --roles-only, sem o superusuario) e restauracao COMPLETA do backup
#      novo (pg_restore --create --exit-on-error). Comparacoes A x B: catalogo, papeis e configuracoes de papel,
#      dados linha a linha de todas as tabelas. Depois, verificacoes com os papeis reais (v01 nekkora_platform,
#      v02 nekkora_portal, de db/tests/atualizacao-populada).
#   C  cluster isolado: procedimento para BACKUP ANTIGO (anterior a 015): pg_restore --section=pre-data, a 015,
#      --section=data, --section=post-data. Comparacoes A x C: catalogo e dados.
#
# Nunca toca a stack principal nem as stacks de teste existentes; nao publica porta; senha aleatoria so no
# ambiente deste processo; os dumps ficam dentro dos containers. Os containers ficam no ar; a remocao e impressa.
# Uso: db/tests/restauracao-015/run.sh      (TESTE_PROJETO=nekkora-restauracao-b ENSAIO_N_EXEC=3000 ...)
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
PROJ="${TESTE_PROJETO:-nekkora-restauracao}"
N_EXEC="${ENSAIO_N_EXEC:-1500}"
A="${PROJ}-a"
B="${PROJ}-b"
C="${PROJ}-c"
REDE="${PROJ}-rede"
IMAGEM=postgres:16-alpine
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJ}"
POPULA="$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql"   # lidos, nunca alterados
V01="$REPO/db/tests/atualizacao-populada/sql/v01_platform.sql"
V02="$REPO/db/tests/atualizacao-populada/sql/v02_portal.sql"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
M015="$REPO/db/migrations/015_restauracao_logica.sql"
ORG=aaaaaaaa-0000-4000-8000-000000000001
EXT=aaaaaaaa-0000-4000-8000-0000000000e1
WS_REF=aaaaaaaa-0000-4000-8000-0000000000a1

falha() { echo "ERRO: $*" >&2; exit 1; }
winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

for c in "$A" "$B" "$C"; do
    case "$c" in nekkora-postgres|nekkora-portal-dev-postgres|nekkora-portal-013-postgres) falha "container $c pertence a outra stack";; esac
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done
docker network inspect "$REDE" >/dev/null 2>&1 && falha "rede $REDE ja existe"

mkdir -p "$SAIDA"/{mig_012,dados_a,dados_b,dados_c}
for n in $(seq -w 1 15); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    [ "$((10#$n))" -le 12 ] && cp "${f[0]}" "$SAIDA/mig_012/"
done
M013=("$REPO"/db/migrations/013_*.sql); M014=("$REPO"/db/migrations/014_*.sql)
sha256sum "$REPO"/db/migrations/0*.sql | sed "s#$REPO/##" > "$SAIDA/00_migracoes_sha256.txt"

export PGPASSWORD="$(head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"

psqlc() { local c="$1" db="$2" papel="$3"; shift 3; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U "$papel" -d "$db" "$@"; }
# stdin de /dev/null: dentro de "while read" o docker exec -i consumiria o resto da lista
consulta() { psqlc "$1" "$2" nekkora -c "$3" < /dev/null; }
lista_catalogo() { psqlc "$1" "$2" nekkora < "$LISTAGEM"; }
sobe() {   # $1 container, $2 POSTGRES_DB, $3 diretorio de init (opcional)
    local vol=()
    [ -n "${3:-}" ] && vol=(-v "$(winpath "$3"):/docker-entrypoint-initdb.d:ro")
    MSYS_NO_PATHCONV=1 docker run -d --name "$1" --network "$REDE" -e POSTGRES_DB="$2" -e POSTGRES_USER=nekkora -e PGPASSWORD \
        -e POSTGRES_PASSWORD="$PGPASSWORD" "${vol[@]}" "$IMAGEM" >/dev/null
    for i in $(seq 1 120); do
        [ "$(docker logs "$1" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && return 0
        sleep 2
    done
    falha "$1 nao ficou pronto"
}
papeis() {   # papeis do cluster (sem senha), atributos, configuracoes e pertinencias
    consulta "$1" postgres "SELECT format('%s|super=%s|login=%s|bypassrls=%s|inherit=%s|createrole=%s|createdb=%s|config=%s', rolname,
        rolsuper, rolcanlogin, rolbypassrls, rolinherit, rolcreaterole, rolcreatedb, coalesce(array_to_string(rolconfig, ';'), '-'))
        FROM pg_roles WHERE rolname LIKE 'nekkora%' ORDER BY 1"
    consulta "$1" postgres "SELECT format('membro|%s|%s', pg_get_userbyid(roleid), pg_get_userbyid(member)) FROM pg_auth_members
        WHERE pg_get_userbyid(member) LIKE 'nekkora%' ORDER BY 1"
    consulta "$1" postgres "SELECT format('dbconfig|%s|%s|%s', coalesce(d.datname, '-'), coalesce(pg_get_userbyid(s.setrole), '-'),
        array_to_string(s.setconfig, ';')) FROM pg_db_role_setting s LEFT JOIN pg_database d ON d.oid = s.setdatabase ORDER BY 1"
}
instantaneo() {   # todas as colunas de todas as tabelas, linhas COPY ordenadas
    local c="$1" db="$2" dest="$3" t
    consulta "$c" "$db" "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1" > "$dest/_tabelas.txt"
    while read -r t; do
        consulta "$c" "$db" "COPY public.$t TO STDOUT" | LC_ALL=C sort > "$dest/$t.txt"
    done < "$dest/_tabelas.txt"
}
restaura_papeis() {
    docker exec -e PGPASSWORD "$1" sh -c "pg_dumpall -h $A -U nekkora --roles-only | grep -vE '^(CREATE|ALTER) ROLE nekkora( |;)' \
        | psql -X -q -v ON_ERROR_STOP=1 -U nekkora -d postgres" > "$SAIDA/papeis_restaurados_$1.txt"
}

docker network create "$REDE" >/dev/null

echo "== A: 001-012 pelo entrypoint, massa, 013 e 014"
sobe "$A" nekkora "$SAIDA/mig_012"
TOK_PRE="$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
HASH_PRE="$(printf %s "$TOK_PRE" | sha256sum | cut -d' ' -f1)"
psqlc "$A" nekkora nekkora -v n_exec="$N_EXEC" -v tok_pre="$HASH_PRE" < "$POPULA" | tail -1 | tee "$SAIDA/01_massa.txt"
psqlc "$A" nekkora nekkora -q < "${M013[0]}" > /dev/null
psqlc "$A" nekkora nekkora -q < "${M014[0]}" > /dev/null
consulta "$A" nekkora "SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL" | grep -qx 0 || falha "014 nao revogou as sessoes pre-014"

echo "== B e C: clusters de destino"
sobe "$B" postgres
sobe "$C" postgres
docker exec -e PGPASSWORD "$B" sh -c "pg_dump -h $A -U nekkora -Fc --create -f /tmp/antigo.dump nekkora"
MSYS_NO_PATHCONV=1 docker exec "$B" pg_restore -l /tmp/antigo.dump | grep -c 'TABLE DATA' | sed 's/^/entradas TABLE DATA no backup antigo: /'
restaura_papeis "$B"
restaura_papeis "$C"

echo "== controle negativo: backup ANTIGO (sem 015) restaurado direto"
set +e
MSYS_NO_PATHCONV=1 docker exec "$B" pg_restore -U nekkora -d postgres --create --exit-on-error /tmp/antigo.dump > "$SAIDA/02_antigo_restauracao_direta.txt" 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || falha "backup antigo restaurou sem erro: o controle negativo nao reproduziu o achado"
grep -q 'function is_valid_relative_entrypoint(text) does not exist' "$SAIDA/02_antigo_restauracao_direta.txt" \
    || falha "backup antigo falhou por outro motivo (ver 02_...)"
consulta "$B" postgres "DROP DATABASE IF EXISTS nekkora" > /dev/null
echo "backup antigo: falha reproduzida (rc=$rc, is_valid_relative_entrypoint)"

echo "== A: aplica a 015 e testa a mutacao do bloco de invariantes"
psqlc "$A" nekkora nekkora < "$M015" > "$SAIDA/03_015_aplicada.txt"
INV="$(awk '/^-- === Invariantes ===/{f=1;next} /^COMMIT;/{f=0} f' "$M015")"
set +e
{ echo "BEGIN;"; echo "ALTER FUNCTION public.is_valid_command_contract(jsonb) RESET search_path;"; echo "$INV"; echo "ROLLBACK;"; } \
    | psqlc "$A" nekkora nekkora > "$SAIDA/04_mutacao_sem_search_path.txt" 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] && grep -q 'is_valid_command_contract sem o search_path esperado' "$SAIDA/04_mutacao_sem_search_path.txt" \
    || falha "mutacao (sem search_path) nao foi detectada pelas invariantes da 015"
consulta "$A" nekkora "SELECT array_to_string(proconfig, ';') FROM pg_proc WHERE oid = 'public.is_valid_command_contract(jsonb)'::regprocedure" \
    | grep -qx 'search_path=pg_catalog, public' || falha "a mutacao alterou A"
psqlc "$A" nekkora nekkora < "$AQUI/sql/c_funcoes_no_restore.sql" | tee "$SAIDA/05_funcoes_no_restore.txt"
echo "mutacao detectada e revertida"

echo "== backup NOVO de A e restauracao COMPLETA em B"
docker exec -e PGPASSWORD "$B" sh -c "pg_dump -h $A -U nekkora -Fc --create -f /tmp/novo.dump nekkora"
MSYS_NO_PATHCONV=1 docker exec "$B" pg_restore -U nekkora -d postgres --create --exit-on-error /tmp/novo.dump > "$SAIDA/06_novo_restauracao.txt" 2>&1 \
    || { cat "$SAIDA/06_novo_restauracao.txt"; falha "restauracao completa do backup novo falhou"; }
echo "restauracao completa: rc=0"

echo "== comparacoes A x B"
lista_catalogo "$A" nekkora > "$SAIDA/07_catalogo_a.txt"
lista_catalogo "$B" nekkora > "$SAIDA/07_catalogo_b.txt"
cmp -s "$SAIDA/07_catalogo_a.txt" "$SAIDA/07_catalogo_b.txt" || { diff "$SAIDA/07_catalogo_a.txt" "$SAIDA/07_catalogo_b.txt" > "$SAIDA/07_diff.txt"; falha "catalogo B difere de A"; }
papeis "$A" > "$SAIDA/08_papeis_a.txt"
papeis "$B" > "$SAIDA/08_papeis_b.txt"
cmp -s "$SAIDA/08_papeis_a.txt" "$SAIDA/08_papeis_b.txt" || { diff "$SAIDA/08_papeis_a.txt" "$SAIDA/08_papeis_b.txt"; falha "papeis de B diferem de A"; }
instantaneo "$A" nekkora "$SAIDA/dados_a"
instantaneo "$B" nekkora "$SAIDA/dados_b"
diff -r "$SAIDA/dados_a" "$SAIDA/dados_b" > /dev/null || falha "dados de B diferem de A"
TABS="$(wc -l < "$SAIDA/dados_a/_tabelas.txt")"
LINHAS="$(cat "$SAIDA"/dados_a/*.txt | wc -l)"
echo "catalogo ($(wc -l < "$SAIDA/07_catalogo_a.txt") linhas), papeis ($(wc -l < "$SAIDA/08_papeis_a.txt") linhas) e dados ($TABS tabelas, $LINHAS linhas) identicos" \
    | tee "$SAIDA/09_comparacao_a_b.txt"

echo "== papeis reais em B (v01 nekkora_platform, v02 nekkora_portal)"
TOK_NOVO="$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
HASH_NOVO="$(printf %s "$TOK_NOVO" | sha256sum | cut -d' ' -f1)"
CSRF="$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
psqlc "$B" nekkora nekkora_platform -v org="$ORG" -v ext="$EXT" -v hash_novo="$HASH_NOVO" -v csrf="$CSRF" \
    < "$V01" > "$SAIDA/10_v01_platform.txt" 2>&1 || { cat "$SAIDA/10_v01_platform.txt"; falha "v01"; }
grep -qx 'sem_marcador_msg=sessao nova exige marcador de MFA e CSRF, sem step-up nem revogacao' "$SAIDA/10_v01_platform.txt" || falha "v01: trigger de INSERT"
grep -qx 'reativacao_msg=sessao revogada nao pode ser reabilitada nem ter a revogacao alterada' "$SAIDA/10_v01_platform.txt" || falha "v01: trigger de UPDATE"
grep -qx 'sessao_nova=ok' "$SAIDA/10_v01_platform.txt" || falha "v01: sessao nova"
psqlc "$B" nekkora nekkora_portal -v org="$ORG" -v tok_pre="$TOK_PRE" -v tok_novo="$TOK_NOVO" \
    < "$V02" > "$SAIDA/11_v02_portal.txt" 2>&1 || { cat "$SAIDA/11_v02_portal.txt"; falha "v02"; }
grep -qx 'pre_014_sqlstate=NK401' "$SAIDA/11_v02_portal.txt" || falha "v02: sessao revogada aceita"
grep -qx "workspaces=$WS_REF" "$SAIDA/11_v02_portal.txt" || falha "v02: concessao restaurada nao aparece"
ESPERADO="$(consulta "$B" nekkora "SELECT md5(string_agg(execution_id::text, ',' ORDER BY execution_id)) FROM (
    SELECT execution_id FROM executions WHERE workspace_id = '$WS_REF' ORDER BY created_at DESC, execution_id DESC LIMIT 100) s")"
grep -qx "execucoes_ids=$ESPERADO" "$SAIDA/11_v02_portal.txt" || falha "v02: execucoes restauradas diferem"
# escrita que passa pelo CHECK corrigido (so vale para versao PUBLICADA: 005, CHECK status <> 2 OR contrato), com o
# papel real (RLS), depois da restauracao: publicar com entrypoint valido passa; com "../fora.py", o CHECK recusa
psqlc "$B" nekkora nekkora_platform > "$SAIDA/12_check_pos_restauracao.txt" 2>&1 <<SQL || { cat "$SAIDA/12_check_pos_restauracao.txt"; falha "CHECK pos-restauracao"; }
BEGIN;
SELECT set_config('nekkora.organization_id', '$ORG', true) \g /dev/null
CREATE TEMP TABLE base AS SELECT organization_id, workspace_id, automation_id, command_json, created_by_membership_id
FROM automation_versions WHERE workspace_id = '$WS_REF' LIMIT 1;
INSERT INTO automation_versions (organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id)
SELECT organization_id, workspace_id, automation_id, 1, command_json, created_by_membership_id FROM base RETURNING automation_version_id AS v_ok \gset
UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = created_by_membership_id
WHERE automation_version_id = :'v_ok';
\echo entrypoint_valido_sqlstate=:SQLSTATE linhas=:ROW_COUNT
INSERT INTO automation_versions (organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id)
SELECT organization_id, workspace_id, automation_id, 1, jsonb_set(command_json, '{entrypoint}', '"../fora.py"'), created_by_membership_id
FROM base RETURNING automation_version_id AS v_ruim \gset
\set ON_ERROR_STOP off
UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = created_by_membership_id
WHERE automation_version_id = :'v_ruim';
\echo entrypoint_invalido_sqlstate=:SQLSTATE
\echo entrypoint_invalido_msg=:LAST_ERROR_MESSAGE
ROLLBACK;
SQL
grep -qx 'entrypoint_valido_sqlstate=00000 linhas=1' "$SAIDA/12_check_pos_restauracao.txt" || falha "publicacao valida recusada apos a restauracao"
grep -qx 'entrypoint_invalido_sqlstate=23514' "$SAIDA/12_check_pos_restauracao.txt" && grep -q '^entrypoint_invalido_msg=.*automation_versions_check3' "$SAIDA/12_check_pos_restauracao.txt"     || falha "CHECK nao recusou entrypoint invalido apos a restauracao"
for t in "$TOK_PRE" "$TOK_NOVO" "$CSRF"; do
    ! grep -rqF -- "$t" "$SAIDA" || falha "token/CSRF gravado em saida"
    for c in "$A" "$B" "$C"; do ! docker logs "$c" 2>&1 | grep -qF -- "$t" || falha "token/CSRF no log de $c"; done
done
echo "v01, v02 e CHECK pos-restauracao OK"

echo "== C: procedimento para backup ANTIGO (pre-data -> 015 -> data -> post-data)"
MSYS_NO_PATHCONV=1 docker cp "$B:/tmp/antigo.dump" - | MSYS_NO_PATHCONV=1 docker cp - "$C:/tmp/"
{
    MSYS_NO_PATHCONV=1 docker exec "$C" pg_restore -U nekkora -d postgres --create --section=pre-data --exit-on-error /tmp/antigo.dump
    psqlc "$C" nekkora nekkora < "$M015"
    MSYS_NO_PATHCONV=1 docker exec "$C" pg_restore -U nekkora -d nekkora --section=data --exit-on-error /tmp/antigo.dump
    MSYS_NO_PATHCONV=1 docker exec "$C" pg_restore -U nekkora -d nekkora --section=post-data --exit-on-error /tmp/antigo.dump
} > "$SAIDA/13_procedimento_backup_antigo.txt" 2>&1 || { cat "$SAIDA/13_procedimento_backup_antigo.txt"; falha "procedimento para backup antigo falhou"; }
lista_catalogo "$C" nekkora > "$SAIDA/14_catalogo_c.txt"
cmp -s "$SAIDA/07_catalogo_a.txt" "$SAIDA/14_catalogo_c.txt" || { diff "$SAIDA/07_catalogo_a.txt" "$SAIDA/14_catalogo_c.txt" > "$SAIDA/14_diff.txt"; falha "catalogo C difere de A"; }
instantaneo "$C" nekkora "$SAIDA/dados_c"
diff -r "$SAIDA/dados_a" "$SAIDA/dados_c" > /dev/null || falha "dados de C diferem de A"
echo "backup antigo restaurado pelo procedimento: catalogo e dados identicos a A (com a 015)" | tee "$SAIDA/15_comparacao_a_c.txt"

rm -rf "$SAIDA"/dados_* "$SAIDA/mig_012"
echo "RESTAURACAO LOGICA (015) OK - saidas: $SAIDA"
echo "remocao: docker rm -f $A $B $C && docker network rm $REDE"
