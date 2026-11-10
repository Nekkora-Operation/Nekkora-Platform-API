#!/usr/bin/env bash
# Suite de banco da 033_rascunho_unico_por_automacao.sql (candidata; PLATFORM-AGENT-V2-002, T09). Tres clusters PostgreSQL 16
# DESCARTAVEIS e isolados (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack
# principal, os laboratorios do frontend nem a homologacao. Aplicacao pelo modelo suportado (db/bootstrap/aplicar_como_migrador.sh);
# a 033 nao tem janela de elevacao. A 032 esta reservada (T02 da PLATFORM-V2-001) e nao existe: a sequencia aplicada e 001-031 + 033.
#
#   A (limpa):     001-031 + 033 pelo entrypoint; indice parcial da 033; marcador 033; migrador 0|0|0.
#   B (populada, sem conflito): 001-031 + massa (publicada + rascunho, rascunho LEGADO sem pacote, so publicadas, outra
#                  organizacao); 033 aplicada; os dados ficam IDENTICOS; o diagnostico lista so o legado sem pacote
#                  (informativo) e nenhuma automacao com mais de um rascunho. Depois: um segundo rascunho na mesma automacao
#                  e recusado pelo banco (23505), e o primeiro rascunho de outra automacao e aceito.
#   C (com repeticoes): 033 PARA na pre-condicao listando os rascunhos de cada automacao com mais de um; nada muda (dados,
#                  catalogo, migrador 0|0|0); o diagnostico mostra os mesmos registros. Depois da decisao humana (SIMULADA
#                  aqui so para provar o caminho), a 033 aplica e o marcador volta.
# O comportamento da API (rascunho so com o ZIP aceito, expectativa, repeticao, 409) e coberto por RascunhoUnicoIT.
#
# Uso: db/tests/rascunho-unico-033/run.sh   (Git Bash no Windows: MSYS_NO_PATHCONV=1 e definido aqui)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail
export MSYS_NO_PATHCONV=1

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
DIAG="$REPO/db/diagnosticos/033_rascunhos_por_automacao.sql"
PROJETO="${TESTE_PROJETO:-nekkora-c033}"
LIMPA="${PROJETO}-limpa"; POP="${PROJETO}-populada"; REP="${PROJETO}-repetidos"
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
for n in $(seq -w 1 31) 33; do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
ls "$REPO"/db/migrations/032_*.sql >/dev/null 2>&1 && falha "existe uma 032: rever a numeracao desta suite"
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
dados() { consulta "$1" "SELECT automation_version_id, automation_id, version_number, status, md5(command_json::text), created_at, coalesce(published_at::text, '-')
    FROM automation_versions ORDER BY automation_version_id"; }
catalogo() {
    consulta "$1" "SELECT 'idx|' || coalesce((SELECT pg_get_indexdef(to_regclass('public.ux_automation_versions_one_draft'))), 'AUSENTE')
        UNION ALL SELECT 'rls|' || relname || '|' || relrowsecurity || '|' || relforcerowsecurity FROM pg_class
         WHERE oid = 'public.automation_versions'::regclass ORDER BY 1"
}
massa() {   # $1 container, arquivos SQL seguintes (como administrador do cluster: preparacao, nao evidencia)
    local c="$1"; shift
    for f in "$@"; do psqlc "$c" < "$f" > /dev/null || falha "massa $f em $c"; done
    consulta "$c" "DROP TABLE public.tmp_cmd_033" > /dev/null
}
inserir_rascunho() {   # $1 container, $2 automacao, $3 numero (gatilhos e FKs desligados como na massa; o INDICE da 033 continua valendo)
    consulta "$1" "SET session_replication_role = replica; INSERT INTO automation_versions (organization_id, workspace_id, automation_id, version_number, status,
        command_json, created_by_membership_id) SELECT organization_id, workspace_id, automation_id, $3, 1, '{\"contract_version\":1}'::jsonb,
        created_by_membership_id FROM automations WHERE automation_id = '$2'"
}

# ================================================================== A. limpa
echo ">> A: instalacao limpa 001-031 + 033 ($LIMPA)"
sobe "$LIMPA" 32 033
echo "server_version=$(consulta "$LIMPA" 'SHOW server_version')" > "$SAIDA/ambiente.txt"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=033" ] || falha "A: marcador"
[ "$(estado_migrador "$LIMPA")" = "0|0|0" ] || falha "A: migrador fora de 0|0|0"
catalogo "$LIMPA" > "$SAIDA/A01_catalogo_033.txt"
grep -q "^idx|CREATE UNIQUE INDEX ux_automation_versions_one_draft ON public.automation_versions USING btree (organization_id, automation_id) WHERE (status = 1)$" "$SAIDA/A01_catalogo_033.txt" || falha "A: indice"
grep -q "^rls|automation_versions|true|true$" "$SAIDA/A01_catalogo_033.txt" || falha "A: RLS forcada de automation_versions"
echo "   A ok"

# ================================================================== B. populada sem conflito
echo ">> B: base populada 001-031 sem rascunhos repetidos ($POP)"
sobe "$POP" 31 031
massa "$POP" "$AQUI/sql/p033_base.sql"
dados "$POP" > "$SAIDA/B01_dados_031.txt"
[ "$(wc -l < "$SAIDA/B01_dados_031.txt")" = 6 ] || falha "B: massa incompleta"
psqlc "$POP" < "$DIAG" > "$SAIDA/B02_diagnostico.txt"
grep -q "00000033-0000-4000-8004-000000000021" "$SAIDA/B02_diagnostico.txt" || falha "B: diagnostico sem o rascunho legado sem pacote"
[ "$(grep -c '00000033-0000-4000-8004' "$SAIDA/B02_diagnostico.txt")" = 1 ] || falha "B: diagnostico listou mais que o legado sem pacote"
modelo "$POP" "$SAIDA/B03_033.txt" || { cat "$SAIDA/B03_033.txt"; falha "B: 033 na populada"; }
[ "$(estado_migrador "$POP")" = "0|0|0" ] || falha "B: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=033" ] || falha "B: marcador"
dados "$POP" | diff "$SAIDA/B01_dados_031.txt" - > "$SAIDA/B04_diferenca_dados.txt" || falha "B: dados alterados pela 033"
catalogo "$POP" | diff "$SAIDA/A01_catalogo_033.txt" - > "$SAIDA/B05_diferenca_catalogo.txt" || falha "B: catalogo diferente da instalacao limpa"
echo "   B1: segundo rascunho na mesma automacao recusado pelo banco; primeiro rascunho de outra automacao aceito"
if inserir_rascunho "$POP" 00000033-0000-4000-8002-000000000001 3 > "$SAIDA/B06_segundo_rascunho.txt" 2>&1; then falha "B1: segundo rascunho aceito"; fi
grep -q 'duplicate key value violates unique constraint "ux_automation_versions_one_draft"' "$SAIDA/B06_segundo_rascunho.txt" || { cat "$SAIDA/B06_segundo_rascunho.txt"; falha "B1: recusa"; }
inserir_rascunho "$POP" 00000033-0000-4000-8002-000000000003 3 > "$SAIDA/B07_rascunho_outra.txt" 2>&1 || { cat "$SAIDA/B07_rascunho_outra.txt"; falha "B1: rascunho de automacao so com publicadas"; }
echo "   B ok"

# ================================================================== C. com repeticoes
echo ">> C: base 001-031 com rascunhos repetidos ($REP)"
sobe "$REP" 31 031
massa "$REP" "$AQUI/sql/p033_base.sql" "$AQUI/sql/p033_repetidos.sql"
dados "$REP" > "$SAIDA/C01_dados_031.txt"
catalogo "$REP" > "$SAIDA/C01_catalogo_031.txt"
psqlc "$REP" < "$DIAG" > "$SAIDA/C02_diagnostico.txt"
for v in 51 52 53 62 63; do
    grep -q "00000033-0000-4000-8004-0000000000$v" "$SAIDA/C02_diagnostico.txt" || falha "C: diagnostico sem o rascunho $v"
done

echo "   C0: 033 recusada"
if modelo "$REP" "$SAIDA/C03_033_recusada.txt"; then falha "C0: 033 aplicada com rascunhos repetidos"; fi
grep -q "033: ha automacoes com mais de um rascunho; nada foi alterado" "$SAIDA/C03_033_recusada.txt" && ! grep -q "could not create unique index" "$SAIDA/C03_033_recusada.txt" || { cat "$SAIDA/C03_033_recusada.txt"; falha "C0: mensagem"; }
for v in 51 52 53 62 63; do
    grep -q "00000033-0000-4000-8004-0000000000$v" "$SAIDA/C03_033_recusada.txt" || falha "C0: rascunho $v nao listado na recusa"
done
for v in 12 21 41 61; do
    grep -q "00000033-0000-4000-8004-0000000000$v" "$SAIDA/C03_033_recusada.txt" && falha "C0: listou versao $v, que nao e rascunho repetido"
done
[ "$(estado_migrador "$REP")" = "0|0|0" ] || falha "C0: migrador fora de 0|0|0"
dados "$REP" | diff "$SAIDA/C01_dados_031.txt" - > /dev/null || falha "C0: dados alterados"
catalogo "$REP" | diff "$SAIDA/C01_catalogo_031.txt" - > /dev/null || falha "C0: catalogo alterado"

echo "   C1: decisao humana (SIMULADA) e 033 aplicada"
psqlc "$REP" < "$AQUI/sql/p033_decisao.sql" > "$SAIDA/C05_decisao.txt"
modelo "$REP" "$SAIDA/C06_033.txt" || { cat "$SAIDA/C06_033.txt"; falha "C1: 033 depois da decisao"; }
[ "$(marcador "$REP")" = "nekkora-inicializacao-completa versao=033" ] || falha "C1: marcador"
[ "$(estado_migrador "$REP")" = "0|0|0" ] || falha "C1: migrador fora de 0|0|0"
[ "$(consulta "$REP" 'SELECT count(*) FROM automation_versions')" = "$(( $(wc -l < "$SAIDA/C01_dados_031.txt") - 3 ))" ] || falha "C1: quantidade de versoes inesperada"
echo "   C ok"

docker rm -f -v "$LIMPA" "$POP" "$REP" > /dev/null
echo "033: A, B e C ok; saidas em $SAIDA"
