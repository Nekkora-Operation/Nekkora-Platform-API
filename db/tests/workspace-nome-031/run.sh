#!/usr/bin/env bash
# Suite de banco da 031_workspace_nome_unico.sql (candidata; PLATFORM-V2-001, T03). Tres clusters PostgreSQL 16 DESCARTAVEIS
# e isolados (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal, os
# laboratorios do frontend nem a homologacao. Aplicacao pelo modelo suportado (db/bootstrap/aplicar_como_migrador.sh); a 031
# nao tem janela de elevacao.
#
#   A (limpa):     001-031 pelo entrypoint; indice e restricoes da 031; marcador 031; migrador 0|0|0.
#   B (populada, sem conflito): 001-030 + massa (mesmo nome em organizacoes diferentes, arquivado, acentos); 031 aplicada;
#                  os dados ficam IDENTICOS; o diagnostico nao lista nada.
#   C (com repeticoes, como as observadas no laboratorio: dois "Testando" ativos na mesma organizacao, e variantes de caixa e
#                  espacos noutra): 031 PARA na pre-condicao listando os registros; nada muda (dados, indice, restricoes,
#                  migrador 0|0|0); o diagnostico mostra os mesmos registros. Depois da decisao humana (renomear um de cada
#                  par, feita aqui so para provar o caminho), a 031 aplica e o marcador volta.
# O comportamento da API (409 legivel, renomeacao, corrida) e coberto por WorkspaceNomeUnicoIT (nekkora-platform).
#
# Uso: db/tests/workspace-nome-031/run.sh   (Git Bash no Windows: MSYS_NO_PATHCONV=1 e definido aqui)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail
export MSYS_NO_PATHCONV=1

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
DIAG="$REPO/db/diagnosticos/031_workspaces_nomes_repetidos.sql"
PROJETO="${TESTE_PROJETO:-nekkora-c031}"
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
for n in $(seq -w 1 31); do
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
modelo() { docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE=031 -e NEKKORA_ATE=031 -e NEKKORA_MIGRACOES=/nekkora-migracoes "$1" sh /tmp/modelo.sh > "$2" 2>&1; }
estado_migrador() {
    consulta "$1" "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')"
}
marcador() { consulta "$1" "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }
dados() { consulta "$1" "SELECT workspace_id, organization_id, name, status, coalesce(client_id::text, '-'), created_at, updated_at FROM workspaces ORDER BY workspace_id"; }
catalogo() {   # indice da 031 e restricoes da auditoria
    consulta "$1" "SELECT 'idx|' || coalesce((SELECT pg_get_indexdef(to_regclass('public.ux_workspaces_organization_name_key'))), 'AUSENTE')
        UNION ALL SELECT 'con|' || conname || '|' || pg_get_constraintdef(oid) FROM pg_constraint
         WHERE conrelid = 'public.automation_admin_events'::regclass AND contype = 'c'
        UNION ALL SELECT 'rls|' || relname || '|' || relrowsecurity || '|' || relforcerowsecurity FROM pg_class
         WHERE oid = 'public.workspaces'::regclass ORDER BY 1"
}
massa() {   # $1 container, $2 arquivo SQL (como administrador do cluster: preparacao, nao evidencia)
    psqlc "$1" < "$2" > /dev/null || falha "massa $2 em $1"
}

# ================================================================== A. limpa
echo ">> A: instalacao limpa 001-031 ($LIMPA)"
sobe "$LIMPA" 31 031
echo "server_version=$(consulta "$LIMPA" 'SHOW server_version'); ctype=$(consulta "$LIMPA" "SELECT datctype FROM pg_database WHERE datname = 'nekkora'")" > "$SAIDA/ambiente.txt"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=031" ] || falha "A: marcador"
[ "$(estado_migrador "$LIMPA")" = "0|0|0" ] || falha "A: migrador fora de 0|0|0"
catalogo "$LIMPA" > "$SAIDA/A01_catalogo_031.txt"
grep -q "^idx|CREATE UNIQUE INDEX ux_workspaces_organization_name_key ON public.workspaces USING btree (organization_id, md5(lower(btrim(name))))$" "$SAIDA/A01_catalogo_031.txt" || falha "A: indice"
grep -q "CHECK (((act >= 1) AND (act <= 7)))" "$SAIDA/A01_catalogo_031.txt" || falha "A: ato 7"
grep -q "automation_admin_events_automation_required|CHECK (((act = ANY (ARRAY\[1, 7\])) OR (automation_id IS NOT NULL)))" "$SAIDA/A01_catalogo_031.txt" || falha "A: automacao exigida"
grep -q "(act = 1) OR (automation_id IS NOT NULL)" "$SAIDA/A01_catalogo_031.txt" && falha "A: CHECK antiga da 028 ainda presente"
grep -q "^rls|workspaces|true|true$" "$SAIDA/A01_catalogo_031.txt" || falha "A: RLS forcada de workspaces"
echo "   A ok"

# ================================================================== B. populada sem conflito
echo ">> B: base populada 001-030 sem nomes repetidos ($POP)"
sobe "$POP" 30 030
massa "$POP" "$AQUI/sql/p031_sem_conflito.sql"
dados "$POP" > "$SAIDA/B01_dados_030.txt"
[ "$(wc -l < "$SAIDA/B01_dados_030.txt")" = 7 ] || falha "B: massa incompleta"
psqlc "$POP" < "$DIAG" > "$SAIDA/B02_diagnostico.txt"
grep -vqE '^(BEGIN|ROLLBACK)$' "$SAIDA/B02_diagnostico.txt" && falha "B: diagnostico listou repeticoes numa base sem conflito"
modelo "$POP" "$SAIDA/B03_031.txt" || { cat "$SAIDA/B03_031.txt"; falha "B: 031 na populada"; }
[ "$(estado_migrador "$POP")" = "0|0|0" ] || falha "B: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=031" ] || falha "B: marcador"
dados "$POP" | diff "$SAIDA/B01_dados_030.txt" - > "$SAIDA/B04_diferenca_dados.txt" || falha "B: dados alterados pela 031"
catalogo "$POP" | diff "$SAIDA/A01_catalogo_031.txt" - > "$SAIDA/B05_diferenca_catalogo.txt" || falha "B: catalogo diferente da instalacao limpa"
echo "   B ok"

# ================================================================== C. com repeticoes
echo ">> C: base 001-030 com nomes repetidos ($REP)"
sobe "$REP" 30 030
massa "$REP" "$AQUI/sql/p031_repetidos.sql"
dados "$REP" > "$SAIDA/C01_dados_030.txt"
catalogo "$REP" > "$SAIDA/C01_catalogo_030.txt"
psqlc "$REP" < "$DIAG" > "$SAIDA/C02_diagnostico.txt"
[ "$(grep -c '|testando|' "$SAIDA/C02_diagnostico.txt")" = 2 ] || falha "C: diagnostico sem os dois Testando"
[ "$(grep -c '|relatorios|' "$SAIDA/C02_diagnostico.txt")" = 3 ] || falha "C: diagnostico sem as tres variantes de Relatorios"
grep -q '|unico|' "$SAIDA/C02_diagnostico.txt" && falha "C: diagnostico listou nome sem repeticao"

echo "   C0: 031 recusada"
if modelo "$REP" "$SAIDA/C03_031_recusada.txt"; then falha "C0: 031 aplicada com nomes repetidos"; fi
grep -q "031: ha workspaces com o mesmo nome na mesma organizacao; nada foi alterado" "$SAIDA/C03_031_recusada.txt" && ! grep -q "could not create unique index" "$SAIDA/C03_031_recusada.txt" || { cat "$SAIDA/C03_031_recusada.txt"; falha "C0: mensagem"; }
for w in $(consulta "$REP" "SELECT workspace_id FROM workspaces w WHERE (SELECT count(*) FROM workspaces x WHERE x.organization_id = w.organization_id
                                AND lower(btrim(x.name)) = lower(btrim(w.name))) > 1"); do
    grep -q "$w" "$SAIDA/C03_031_recusada.txt" || falha "C0: registro $w nao listado na recusa"
done
[ "$(estado_migrador "$REP")" = "0|0|0" ] || falha "C0: migrador fora de 0|0|0"
grep -q "00000031-0000-4000-8002-000000000007" "$SAIDA/C03_031_recusada.txt" && falha "C0: listou workspace sem repeticao na propria organizacao"
dados "$REP" | diff "$SAIDA/C01_dados_030.txt" - > /dev/null || falha "C0: dados alterados"
catalogo "$REP" | diff "$SAIDA/C01_catalogo_030.txt" - > /dev/null || falha "C0: catalogo alterado"
echo "marcador depois da recusa: $(marcador "$REP") (o migrador apaga o marcador no inicio; volta no proximo sucesso)" > "$SAIDA/C04_marcador.txt"

echo "   C1: decisao humana (renomear um de cada repeticao) e 031 aplicada"
psqlc "$REP" < "$AQUI/sql/p031_decisao.sql" > "$SAIDA/C05_decisao.txt"
psqlc "$REP" < "$DIAG" | grep -vqE '^(BEGIN|ROLLBACK)$' && falha "C1: diagnostico ainda lista repeticoes"
modelo "$REP" "$SAIDA/C06_031.txt" || { cat "$SAIDA/C06_031.txt"; falha "C1: 031 depois da decisao"; }
[ "$(marcador "$REP")" = "nekkora-inicializacao-completa versao=031" ] || falha "C1: marcador"
[ "$(estado_migrador "$REP")" = "0|0|0" ] || falha "C1: migrador fora de 0|0|0"
[ "$(consulta "$REP" 'SELECT count(*) FROM workspaces')" = "$(wc -l < "$SAIDA/C01_dados_030.txt")" ] || falha "C1: quantidade de workspaces mudou"
echo "   C ok"

docker rm -f -v "$LIMPA" "$POP" "$REP" > /dev/null
echo "031: A, B e C ok; saidas em $SAIDA"
