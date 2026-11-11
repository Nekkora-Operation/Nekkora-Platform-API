#!/usr/bin/env bash
# Suite de banco da 033_autorizacao_local_vigente_unica.sql (candidata; PLATFORM-AGENT-V2-003, T12). Tres clusters PostgreSQL 16
# DESCARTAVEIS e isolados (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack
# principal, os laboratorios do frontend nem a homologacao. Aplicacao pelo modelo suportado (db/bootstrap/aplicar_como_migrador.sh);
# a 033 nao tem janela de elevacao. Sequencia contigua: 001-033.
#
#   A (limpa):     001-033 pelo entrypoint; indice da vigente, gatilho com a regra (nao SECURITY DEFINER); marcador 033; 0|0|0.
#   B (populada, sem conflito): 001-032 + massa (vigente, revogada e vencida no mesmo escopo; monitoramento; outro Worker;
#                  outra organizacao); 033 aplicada; os dados ficam IDENTICOS; o diagnostico nao lista nenhum escopo repetido.
#   C (com repeticoes): 033 PARA na pre-condicao listando os escopos com mais de uma vigente; nada muda (dados, catalogo,
#                  migrador 0|0|0); o diagnostico mostra os mesmos registros. Depois da decisao humana (SIMULADA aqui so para
#                  provar o caminho: REVOGAR, nunca apagar), a 033 aplica, o marcador volta e nenhuma linha some.
# O comportamento (clique repetido, duas abas, duas pessoas, chamada direta, revogacao, vencimento, escopos distintos e
# isolamento) e coberto por AutorizacaoVigenteUnicaIT, com a API e o papel real da Platform.
#
# Uso: db/tests/autorizacao-vigente-033/run.sh   (Git Bash no Windows: MSYS_NO_PATHCONV=1 e definido aqui)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail
export MSYS_NO_PATHCONV=1

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
DIAG="$REPO/db/diagnosticos/033_autorizacoes_vigentes_repetidas.sql"
PROJETO="${TESTE_PROJETO:-nekkora-c033}"
LIMPA="${PROJETO}-limpa"; POP="${PROJETO}-populada"; REP="${PROJETO}-repetidas"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine

falha() {
    echo "ERRO: $*" >&2
    echo "containers mantidos para inspecao. Remocao: docker rm -f -v $LIMPA $POP $REP" >&2
    exit 1
}
case "$PROJETO" in nekkora-platform|nekkora|*hml03*|*frontlab*) falha "projeto '$PROJETO' reservado";; esac
for c in "$LIMPA" "$POP" "$REP"; do
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done

mkdir -p "$SAIDA/mig"
for n in $(seq -w 1 33); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
sha256sum "$SAIDA"/mig/0*.sql | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt"
caminho() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

psqlc() { local c="$1"; shift; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora "$@"; }
consulta() { psqlc "$1" -c "$2" < /dev/null; }
sobe() {   # $1 container, $2 quantidade esperada, $3 ultima versao aplicada no entrypoint
    docker create --name "$1" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
        -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE="$3" \
        -e NEKKORA_MIGRACOES=/nekkora-migracoes "$IMAGEM" postgres -c cluster_name="$1" > /dev/null
    docker cp "$(caminho "$SAIDA/mig")" "$1":/nekkora-migracoes > /dev/null
    docker cp "$(caminho "$SAIDA/modelo.sh")" "$1":/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh > /dev/null
    docker cp "$(caminho "$SAIDA/modelo.sh")" "$1":/tmp/modelo.sh > /dev/null
    docker start "$1" > /dev/null
    for _ in $(seq 1 300); do
        [ "$(docker logs "$1" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
        docker ps -q -f "name=^$1\$" | grep -q . || break
        sleep 1
    done
    docker logs "$1" 2>&1 | grep -E "aplicar_como_migrador|ERROR|FATAL|init process complete" > "$SAIDA/entrypoint_$1.txt" || true
    grep -qE "ERROR|FATAL|aplicar_como_migrador: FALHA" "$SAIDA/entrypoint_$1.txt" && falha "erro na aplicacao das migrations em $1"
    [ "$(grep -c 'aplicar_como_migrador: aplicada versao=' "$SAIDA/entrypoint_$1.txt")" = "$2" ] || falha "esperadas $2 migrations em $1"
    grep -q "aplicar_como_migrador: fim db=nekkora aplicadas=$2 estado=0|0|0" "$SAIDA/entrypoint_$1.txt" || falha "migrador nao terminou 0|0|0 em $1"
}
modelo() { docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE=033 -e NEKKORA_ATE=033 -e NEKKORA_MIGRACOES=/nekkora-migracoes "$1" sh /tmp/modelo.sh > "$2" 2>&1; }
estado_migrador() {
    consulta "$1" "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')"
}
marcador() { consulta "$1" "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }
dados() { consulta "$1" "SELECT authorization_id, organization_id, worker_workspace_assignment_id, automation_id, coalesce(automation_version_id::text, '-'), source_kind,
    granted_at, valid_until, coalesce(revoked_at::text, '-'), coalesce(revoked_by_membership_id::text, '-') FROM local_run_authorizations ORDER BY authorization_id"; }
catalogo() {
    consulta "$1" "SELECT 'idx|' || coalesce((SELECT pg_get_indexdef(to_regclass('public.ix_local_run_authorizations_vigente'))), 'AUSENTE')
        UNION ALL SELECT 'regra|' || (position('ux_local_run_authorizations_vigente' IN pg_get_functiondef('public.local_run_authorization_on_insert()'::regprocedure)) > 0)
        UNION ALL SELECT 'definer|' || prosecdef FROM pg_proc WHERE oid = 'public.local_run_authorization_on_insert()'::regprocedure
        UNION ALL SELECT 'rls|' || relname || '|' || relrowsecurity || '|' || relforcerowsecurity FROM pg_class
         WHERE oid = 'public.local_run_authorizations'::regclass ORDER BY 1"
}
massa() {   # $1 container, arquivos SQL seguintes (como administrador do cluster: preparacao, nao evidencia)
    local c="$1"; shift
    for f in "$@"; do psqlc "$c" < "$f" > /dev/null || falha "massa $f em $c"; done
}
id() { echo "00000033-0000-4000-9007-0000000000$1"; }

# ================================================================== A. limpa
echo ">> A: instalacao limpa 001-033 ($LIMPA)"
sobe "$LIMPA" 33 033
echo "server_version=$(consulta "$LIMPA" 'SHOW server_version')" > "$SAIDA/ambiente.txt"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=033" ] || falha "A: marcador"
[ "$(estado_migrador "$LIMPA")" = "0|0|0" ] || falha "A: migrador fora de 0|0|0"
catalogo "$LIMPA" > "$SAIDA/A01_catalogo_033.txt"
grep -q "^idx|CREATE INDEX ix_local_run_authorizations_vigente ON public.local_run_authorizations USING btree (organization_id, worker_workspace_assignment_id, automation_id, automation_version_id) WHERE (revoked_at IS NULL)$" "$SAIDA/A01_catalogo_033.txt" || falha "A: indice"
grep -q "^regra|true$" "$SAIDA/A01_catalogo_033.txt" || falha "A: gatilho sem a regra da vigente"
grep -q "^definer|false$" "$SAIDA/A01_catalogo_033.txt" || falha "A: gatilho SECURITY DEFINER"
grep -q "^rls|local_run_authorizations|true|true$" "$SAIDA/A01_catalogo_033.txt" || falha "A: RLS forcada"
echo "   A ok"

# ================================================================== B. populada sem conflito
echo ">> B: base populada 001-032 sem vigentes repetidas ($POP)"
sobe "$POP" 32 032
massa "$POP" "$AQUI/sql/p033_base.sql"
dados "$POP" > "$SAIDA/B01_dados_032.txt"
[ "$(wc -l < "$SAIDA/B01_dados_032.txt")" = 6 ] || falha "B: massa incompleta"
psqlc "$POP" < "$DIAG" > "$SAIDA/B02_diagnostico.txt"
[ "$(sed -n '/== 1\./,/== 2\./p' "$SAIDA/B02_diagnostico.txt" | grep -c '00000033-0000-4000-9007')" = 0 ] || falha "B: diagnostico listou escopo repetido"
modelo "$POP" "$SAIDA/B03_033.txt" || { cat "$SAIDA/B03_033.txt"; falha "B: 033 na populada"; }
[ "$(estado_migrador "$POP")" = "0|0|0" ] || falha "B: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=033" ] || falha "B: marcador"
dados "$POP" | diff "$SAIDA/B01_dados_032.txt" - > "$SAIDA/B04_diferenca_dados.txt" || falha "B: dados alterados pela 033"
catalogo "$POP" | diff "$SAIDA/A01_catalogo_033.txt" - > "$SAIDA/B05_diferenca_catalogo.txt" || falha "B: catalogo diferente da instalacao limpa"
echo "   B ok"

# ================================================================== C. com repeticoes
echo ">> C: base 001-032 com vigentes repetidas ($REP)"
sobe "$REP" 32 032
massa "$REP" "$AQUI/sql/p033_base.sql" "$AQUI/sql/p033_repetidas.sql"
dados "$REP" > "$SAIDA/C01_dados_032.txt"
catalogo "$REP" > "$SAIDA/C01_catalogo_032.txt"
psqlc "$REP" < "$DIAG" > "$SAIDA/C02_diagnostico.txt"
for v in 11 51 21 52 53; do
    sed -n '/== 1\./,/== 2\./p' "$SAIDA/C02_diagnostico.txt" | grep -q "$(id $v)" || falha "C: diagnostico sem a autorizacao $v"
done

echo "   C0: 033 recusada"
if modelo "$REP" "$SAIDA/C03_033_recusada.txt"; then falha "C0: 033 aplicada com vigentes repetidas"; fi
grep -q "033: ha escopos com mais de uma autorizacao local vigente; nada foi alterado" "$SAIDA/C03_033_recusada.txt" || { cat "$SAIDA/C03_033_recusada.txt"; falha "C0: mensagem"; }
for v in 11 51 21 52 53; do
    grep -q "$(id $v)" "$SAIDA/C03_033_recusada.txt" || falha "C0: autorizacao $v nao listada na recusa"
done
for v in 12 13 31 41; do
    grep -q "$(id $v)" "$SAIDA/C03_033_recusada.txt" && falha "C0: listou $v, que nao e vigente repetida"
done
[ "$(estado_migrador "$REP")" = "0|0|0" ] || falha "C0: migrador fora de 0|0|0"
dados "$REP" | diff "$SAIDA/C01_dados_032.txt" - > /dev/null || falha "C0: dados alterados"
catalogo "$REP" | diff "$SAIDA/C01_catalogo_032.txt" - > /dev/null || falha "C0: catalogo alterado"

echo "   C1: decisao humana (SIMULADA: revogar, nunca apagar) e 033 aplicada"
psqlc "$REP" < "$AQUI/sql/p033_decisao.sql" > "$SAIDA/C05_decisao.txt"
modelo "$REP" "$SAIDA/C06_033.txt" || { cat "$SAIDA/C06_033.txt"; falha "C1: 033 depois da decisao"; }
[ "$(marcador "$REP")" = "nekkora-inicializacao-completa versao=033" ] || falha "C1: marcador"
[ "$(estado_migrador "$REP")" = "0|0|0" ] || falha "C1: migrador fora de 0|0|0"
[ "$(consulta "$REP" 'SELECT count(*) FROM local_run_authorizations')" = "$(wc -l < "$SAIDA/C01_dados_032.txt")" ] || falha "C1: alguma autorizacao sumiu"
[ "$(consulta "$REP" 'SELECT count(*) FROM local_run_authorizations WHERE revoked_at IS NULL AND valid_until > clock_timestamp()')" = 4 ] || falha "C1: vigentes"
echo "   C ok"

docker rm -f -v "$LIMPA" "$POP" "$REP" > /dev/null
echo "033: A, B e C ok; saidas em $SAIDA"
