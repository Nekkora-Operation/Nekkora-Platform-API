#!/usr/bin/env bash
# Suite de banco da 034_organizacoes_identificaveis.sql (candidata; PLATFORM-AGENT-V2-003, T10). Tres clusters PostgreSQL 16
# DESCARTAVEIS e isolados (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack
# principal, os laboratorios do frontend nem a homologacao. Aplicacao pelo modelo suportado (db/bootstrap/aplicar_como_migrador.sh);
# a 034 nao tem janela de elevacao. Sequencia contigua: 001-034.
#
#   A (limpa):     001-034 pelo entrypoint; tabelas, indice unico por criadora, RLS forcada, funcoes nao SECURITY DEFINER;
#                  marcador 034; migrador 0|0|0.
#   B (populada, sem conflito): 001-033 + massa (mesmo nome em criadoras diferentes, provisionada pela operacao, ambigua,
#                  convite); 034 aplicada; organizacoes, contas, vinculos e eventos IDENTICOS; identificadores na ordem de
#                  criacao (com sufixo na colisao); autoria SO onde ha evidencia inequivoca.
#   C (com repeticao): a mesma criadora com dois nomes iguais apos a normalizacao: 034 PARA listando os dois; nada muda;
#                  depois da decisao humana (SIMULADA: renomear uma), a 034 aplica.
# O comportamento (criacao, 409, concorrencia, convite, lista, identificador) e coberto por OrganizacaoNomeUnicoIT.
#
# Uso: db/tests/autorizacao-vigente-033/run.sh   (Git Bash no Windows: MSYS_NO_PATHCONV=1 e definido aqui)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail
export MSYS_NO_PATHCONV=1

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
DIAG="$REPO/db/diagnosticos/034_organizacoes_por_criadora.sql"
PROJETO="${TESTE_PROJETO:-nekkora-c034}"
LIMPA="${PROJETO}-limpa"; POP="${PROJETO}-populada"; REP="${PROJETO}-repetida"
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
for n in $(seq -w 1 34); do
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
modelo() { docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE=034 -e NEKKORA_ATE=034 -e NEKKORA_MIGRACOES=/nekkora-migracoes "$1" sh /tmp/modelo.sh > "$2" 2>&1; }
estado_migrador() {
    consulta "$1" "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')"
}
marcador() { consulta "$1" "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }
dados() { consulta "$1" "SELECT 'org|' || organization_id || '|' || name || '|' || status FROM organizations
    UNION ALL SELECT 'conta|' || user_id || '|' || organization_id || '|' || email FROM users
    UNION ALL SELECT 'membro|' || membership_id || '|' || status FROM organization_memberships
    UNION ALL SELECT 'vinculo|' || identity_id || '|' || user_id || '|' || link_channel FROM identity_users
    UNION ALL SELECT 'evento|' || event_id || '|' || channel FROM identity_provisioning_events ORDER BY 1"; }
catalogo() {
    consulta "$1" "SELECT 'idx|' || coalesce((SELECT pg_get_indexdef(to_regclass('public.ux_organization_authorship_creator_name'))), 'AUSENTE')
        UNION ALL SELECT 'uq|' || coalesce((SELECT pg_get_indexdef(to_regclass('public.ux_organization_handles_handle'))), 'AUSENTE')
        UNION ALL SELECT 'definer|' || coalesce(bool_or(prosecdef)::text, 'AUSENTE') FROM pg_proc WHERE proname IN ('organization_name_key', 'organization_handle_base',
            'organization_handle_allocate', 'organization_on_insert_handle', 'organization_authorship_on_cadastro', 'organization_authorship_on_rename',
            'organization_authorship_protect')
        UNION ALL SELECT 'rls|' || relname || '|' || relrowsecurity || '|' || relforcerowsecurity FROM pg_class
         WHERE oid IN (to_regclass('public.organization_handles'), to_regclass('public.organization_authorship'), 'public.organizations'::regclass,
                       'public.users'::regclass) ORDER BY 1"
}
massa() {   # $1 container, arquivos SQL seguintes (como administrador do cluster: preparacao, nao evidencia)
    local c="$1"; shift
    for f in "$@"; do psqlc "$c" < "$f" > /dev/null || falha "massa $f em $c"; done
}
org() { echo "00000034-0000-4000-9002-0000000000$1"; }
ident() { echo "00000034-0000-4000-9001-0000000000$1"; }
handles() { consulta "$1" "SELECT organization_id || '|' || handle FROM organization_handles ORDER BY 1"; }
autoria() { consulta "$1" "SELECT organization_id || '|' || creator_identity_id || '|' || name_key || '|' || evidence FROM organization_authorship ORDER BY 1"; }

# ================================================================== A. limpa
echo ">> A: instalacao limpa 001-034 ($LIMPA)"
sobe "$LIMPA" 34 034
echo "server_version=$(consulta "$LIMPA" 'SHOW server_version')" > "$SAIDA/ambiente.txt"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=034" ] || falha "A: marcador"
[ "$(estado_migrador "$LIMPA")" = "0|0|0" ] || falha "A: migrador fora de 0|0|0"
catalogo "$LIMPA" > "$SAIDA/A01_catalogo_034.txt"
grep -q "^idx|CREATE UNIQUE INDEX ux_organization_authorship_creator_name ON public.organization_authorship USING btree (creator_identity_id, md5(name_key))$" "$SAIDA/A01_catalogo_034.txt" || falha "A: indice da criadora"
grep -q "^uq|CREATE UNIQUE INDEX ux_organization_handles_handle ON public.organization_handles USING btree (handle)$" "$SAIDA/A01_catalogo_034.txt" || falha "A: identificador unico"
grep -q "^definer|false$" "$SAIDA/A01_catalogo_034.txt" || falha "A: funcao SECURITY DEFINER"
[ "$(grep -c '^rls|.*|true|true$' "$SAIDA/A01_catalogo_034.txt")" = 4 ] || falha "A: RLS forcada"
echo "   A ok"

# ================================================================== B. populada sem conflito
echo ">> B: base populada 001-033 sem repeticao por criadora ($POP)"
sobe "$POP" 33 033
massa "$POP" "$AQUI/sql/p034_base.sql"
dados "$POP" > "$SAIDA/B01_dados_033.txt"
[ "$(grep -c '^org|' "$SAIDA/B01_dados_033.txt")" = 4 ] || falha "B: massa incompleta"
psqlc "$POP" < "$DIAG" > "$SAIDA/B02_diagnostico.txt"
[ "$(sed -n '/== 1\./,/== 2\./p' "$SAIDA/B02_diagnostico.txt" | grep -c '00000034-0000-4000-9002')" = 0 ] || falha "B: diagnostico listou repeticao"
for o in 03 04; do sed -n '/== 2\./,/== 3\./p' "$SAIDA/B02_diagnostico.txt" | grep -q "$(org $o)" || falha "B: diagnostico sem a org $o sem autoria"; done
for o in 01 02; do sed -n '/== 2\./,/== 3\./p' "$SAIDA/B02_diagnostico.txt" | grep -q "$(org $o)" && falha "B: diagnostico listou a org $o, que tem autoria"; done
modelo "$POP" "$SAIDA/B03_034.txt" || { cat "$SAIDA/B03_034.txt"; falha "B: 034 na populada"; }
[ "$(estado_migrador "$POP")" = "0|0|0" ] || falha "B: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=034" ] || falha "B: marcador"
dados "$POP" | diff "$SAIDA/B01_dados_033.txt" - > "$SAIDA/B04_diferenca_dados.txt" || falha "B: dados alterados pela 034"
catalogo "$POP" | diff "$SAIDA/A01_catalogo_034.txt" - > "$SAIDA/B05_diferenca_catalogo.txt" || falha "B: catalogo diferente da instalacao limpa"
handles "$POP" > "$SAIDA/B06_identificadores.txt"
autoria "$POP" > "$SAIDA/B07_autoria.txt"
grep -qx "$(org 01)|padaria-central" "$SAIDA/B06_identificadores.txt" || falha "B: identificador da org 1"
grep -qx "$(org 02)|padaria-central-2" "$SAIDA/B06_identificadores.txt" || falha "B: identificador da org 2 (sufixo)"
grep -qx "$(org 03)|padaria-central-3" "$SAIDA/B06_identificadores.txt" || falha "B: identificador da org 3 (sufixo)"
grep -qx "$(org 04)|construcao-cia" "$SAIDA/B06_identificadores.txt" || falha "B: identificador da org 4 (sem acento)"
[ "$(wc -l < "$SAIDA/B07_autoria.txt")" = 2 ] || falha "B: autoria fora das duas inequivocas"
grep -qx "$(org 01)|$(ident 01)|padaria central|migracao_034" "$SAIDA/B07_autoria.txt" || falha "B: autoria da org 1"
grep -qx "$(org 02)|$(ident 02)|padaria central|migracao_034" "$SAIDA/B07_autoria.txt" || falha "B: autoria da org 2"
echo "   B ok"

# ================================================================== C. com repeticao
echo ">> C: base 001-033 com a mesma criadora repetindo o nome ($REP)"
sobe "$REP" 33 033
massa "$REP" "$AQUI/sql/p034_base.sql" "$AQUI/sql/p034_repetida.sql"
dados "$REP" > "$SAIDA/C01_dados_033.txt"
catalogo "$REP" > "$SAIDA/C01_catalogo_033.txt"
psqlc "$REP" < "$DIAG" > "$SAIDA/C02_diagnostico.txt"
for o in 01 06; do sed -n '/== 1\./,/== 2\./p' "$SAIDA/C02_diagnostico.txt" | grep -q "$(org $o)" || falha "C: diagnostico sem a org $o"; done

echo "   C0: 034 recusada"
if modelo "$REP" "$SAIDA/C03_034_recusada.txt"; then falha "C0: 034 aplicada com repeticao"; fi
grep -q "034: ha identidades que criaram mais de uma organizacao com o mesmo nome; nada foi alterado" "$SAIDA/C03_034_recusada.txt" || { cat "$SAIDA/C03_034_recusada.txt"; falha "C0: mensagem"; }
for o in 01 06; do grep -q "$(org $o)" "$SAIDA/C03_034_recusada.txt" || falha "C0: org $o nao listada"; done
for o in 02 03 04; do grep -q "$(org $o)" "$SAIDA/C03_034_recusada.txt" && falha "C0: listou a org $o, que nao repete"; done
[ "$(estado_migrador "$REP")" = "0|0|0" ] || falha "C0: migrador fora de 0|0|0"
dados "$REP" | diff "$SAIDA/C01_dados_033.txt" - > /dev/null || falha "C0: dados alterados"
catalogo "$REP" | diff "$SAIDA/C01_catalogo_033.txt" - > /dev/null || falha "C0: catalogo alterado"

echo "   C1: decisao humana (SIMULADA: renomear uma) e 034 aplicada"
psqlc "$REP" < "$AQUI/sql/p034_decisao.sql" > "$SAIDA/C05_decisao.txt"
modelo "$REP" "$SAIDA/C06_034.txt" || { cat "$SAIDA/C06_034.txt"; falha "C1: 034 depois da decisao"; }
[ "$(marcador "$REP")" = "nekkora-inicializacao-completa versao=034" ] || falha "C1: marcador"
[ "$(estado_migrador "$REP")" = "0|0|0" ] || falha "C1: migrador fora de 0|0|0"
[ "$(consulta "$REP" 'SELECT count(*) FROM organization_handles')" = 5 ] || falha "C1: identificadores"
autoria "$REP" | grep -qx "$(org 06)|$(ident 01)|padaria central filial|migracao_034" || falha "C1: autoria da org 6"
echo "   C ok"

docker rm -f -v "$LIMPA" "$POP" "$REP" > /dev/null
echo "034: A, B e C ok; saidas em $SAIDA"
