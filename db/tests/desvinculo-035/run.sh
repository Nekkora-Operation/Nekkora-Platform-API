#!/usr/bin/env bash
# Suite de banco da 035_desvinculo_pela_maquina.sql (candidata; PLATFORM-AGENT-V2-004, T13). Tres clusters PostgreSQL 16
# DESCARTAVEIS e isolados (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack
# principal, os laboratorios do frontend nem a homologacao. Aplicacao pelo modelo suportado (db/bootstrap/aplicar_como_migrador.sh);
# a 035 nao tem janela de elevacao. Sequencia contigua: 001-035.
#
#   A (limpa):   001-035 pelo entrypoint; tabela da operacao com RLS forcada; gatilhos de guarda SECURITY INVOKER; marcador 035; 0|0|0.
#   B (populada): 001-034 + massa (vinculo ativo com credencial rotacionada, vinculo revogado pela administracao, Worker livre,
#                outra organizacao); 035 aplicada; os dados ficam IDENTICOS (colunas novas nulas) e o catalogo igual ao da limpa.
#   G (guarda):  001-035 + massa; com o papel REAL da maquina (nekkora_ingestion): sem operacao na mesma transacao ela nao
#                muda o status do Agent, nao encerra a associacao, nao revoga como maquina, nao encerra como pessoa; operacao so
#                para o vinculo ativo; outra organizacao invisivel; o desvinculo completo na mesma transacao passa; a operacao e
#                imutavel e o ato 14 so tem a maquina como autora.
# O protocolo (resposta perdida, concorrencia, revogacao administrativa, escopo, pedidos) e coberto por DesvinculoPelaMaquinaIT
# no gateway real.
#
# Uso: db/tests/desvinculo-035/run.sh   (Git Bash no Windows: MSYS_NO_PATHCONV=1 e definido aqui)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail
export MSYS_NO_PATHCONV=1

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
PROJETO="${TESTE_PROJETO:-nekkora-c035}"
LIMPA="${PROJETO}-limpa"; POP="${PROJETO}-populada"; GRD="${PROJETO}-guarda"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine

falha() {
    echo "ERRO: $*" >&2
    echo "containers mantidos para inspecao. Remocao: docker rm -f -v $LIMPA $POP $GRD" >&2
    exit 1
}
case "$PROJETO" in nekkora-platform|nekkora|*hml03*|*frontlab*) falha "projeto '$PROJETO' reservado";; esac
for c in "$LIMPA" "$POP" "$GRD"; do
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done

mkdir -p "$SAIDA/mig"
for n in $(seq -w 1 35); do
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
modelo() { docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE=035 -e NEKKORA_ATE=035 -e NEKKORA_MIGRACOES=/nekkora-migracoes "$1" sh /tmp/modelo.sh > "$2" 2>&1; }
estado_migrador() {
    consulta "$1" "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')"
}
marcador() { consulta "$1" "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }
dados() { consulta "$1" "SELECT 'agent|' || agent_id || '|' || status FROM agents
    UNION ALL SELECT 'assoc|' || assignment_id || '|' || status || '|' || coalesce(ended_by_membership_id::text, '-') || '|' || (ended_at IS NULL) FROM worker_agent_assignments
    UNION ALL SELECT 'cred|' || credential_id || '|' || coalesce(revocation_reason::text, '-') || '|' || coalesce(revoked_by_membership_id::text, '-') FROM agent_credentials
    UNION ALL SELECT 'evento|' || act || '|' || coalesce(actor_membership_id::text, '-') || '|' || agent_id FROM agent_admin_events
    UNION ALL SELECT 'worker|' || worker_id || '|' || status FROM workers ORDER BY 1"; }
dados_035() { consulta "$1" "SELECT 'maquina|' || count(*) FILTER (WHERE revoked_by_agent_id IS NOT NULL) FROM agent_credentials
    UNION ALL SELECT 'maquina-assoc|' || count(*) FILTER (WHERE ended_by_agent_id IS NOT NULL) FROM worker_agent_assignments
    UNION ALL SELECT 'operacoes|' || count(*) FROM agent_unlink_operations"; }
catalogo() {
    consulta "$1" "SELECT 'rls|' || relname || '|' || relrowsecurity || '|' || relforcerowsecurity FROM pg_class WHERE oid = to_regclass('public.agent_unlink_operations')
        UNION ALL SELECT 'definer|' || coalesce(bool_or(prosecdef)::text, 'AUSENTE') || '|' || count(*) FROM pg_proc WHERE proname IN ('agent_unlink_operation_on_insert',
            'agent_unlink_in_this_transaction', 'agent_status_unlink_guard', 'worker_agent_assignment_unlink_guard', 'agent_credential_unlink_guard')
        UNION ALL SELECT 'check|' || conrelid::regclass || '|' || conname || '|' || pg_get_constraintdef(oid) FROM pg_constraint
         WHERE contype = 'c' AND conrelid IN ('public.agent_credentials'::regclass, 'public.worker_agent_assignments'::regclass, 'public.agent_admin_events'::regclass,
                                              'public.run_requests'::regclass, 'public.agent_unlink_operations'::regclass)
        UNION ALL SELECT 'trigger|' || tgrelid::regclass || '|' || tgname || '|' || tgenabled::text FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE '%unlink%'
        UNION ALL SELECT 'grant|' || grantee || '|' || table_name || '|' || column_name || '|' || privilege_type FROM information_schema.column_privileges
         WHERE grantee = 'nekkora_ingestion' AND privilege_type = 'UPDATE' AND table_name IN ('agents', 'worker_agent_assignments', 'agent_credentials')
        ORDER BY 1"
}
massa() {   # $1 container, arquivos SQL seguintes (como administrador do cluster: preparacao, nao evidencia)
    local c="$1"; shift
    for f in "$@"; do psqlc "$c" < "$f" > /dev/null || falha "massa $f em $c"; done
}

# ================================================================== A. limpa
echo ">> A: instalacao limpa 001-035 ($LIMPA)"
sobe "$LIMPA" 35 035
echo "server_version=$(consulta "$LIMPA" 'SHOW server_version')" > "$SAIDA/ambiente.txt"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=035" ] || falha "A: marcador"
[ "$(estado_migrador "$LIMPA")" = "0|0|0" ] || falha "A: migrador fora de 0|0|0"
catalogo "$LIMPA" > "$SAIDA/A01_catalogo_035.txt"
grep -qx "rls|agent_unlink_operations|true|true" "$SAIDA/A01_catalogo_035.txt" || falha "A: RLS forcada na operacao"
grep -qx "definer|false|5" "$SAIDA/A01_catalogo_035.txt" || falha "A: funcoes da 035 (5, nenhuma SECURITY DEFINER)"
[ "$(grep -c '^trigger|.*|O$' "$SAIDA/A01_catalogo_035.txt")" = 7 ] || falha "A: gatilhos de guarda/insercao ligados"
grep -q "^check|agent_credentials|agent_credentials_reason_machine|" "$SAIDA/A01_catalogo_035.txt" || falha "A: CHECK do motivo 5"
grep -q "^check|worker_agent_assignments|worker_agent_assignments_end_author|" "$SAIDA/A01_catalogo_035.txt" || falha "A: CHECK de autoria da associacao"
grep "^check|agent_credentials|" "$SAIDA/A01_catalogo_035.txt" | grep -qF "(revoked_at IS NULL) = (revoked_by_membership_id IS NULL)" && falha "A: CHECK antiga de pessoa obrigatoria ainda presente"
grep "^check|worker_agent_assignments|" "$SAIDA/A01_catalogo_035.txt" | grep -qF "(ended_at IS NULL) = (ended_by_membership_id IS NULL)" && falha "A: CHECK antiga da associacao ainda presente"
grep -q "AGENT_UNLINKED" "$SAIDA/A01_catalogo_035.txt" || falha "A: motivo AGENT_UNLINKED"
grep -q "act >= 1) AND (act <= 14)" "$SAIDA/A01_catalogo_035.txt" || falha "A: ato 14"
[ "$(grep -c '^grant|nekkora_ingestion|' "$SAIDA/A01_catalogo_035.txt")" -ge 6 ] || falha "A: privilegios de coluna da maquina"
grep -q "^grant|nekkora_ingestion|worker_agent_assignments|ended_by_membership_id|" "$SAIDA/A01_catalogo_035.txt" && falha "A: a maquina encerra como pessoa"
echo "   A ok"

# ================================================================== B. populada
echo ">> B: base populada 001-034 ($POP)"
sobe "$POP" 34 034
massa "$POP" "$AQUI/sql/p035_base.sql"
dados "$POP" > "$SAIDA/B01_dados_034.txt"
[ "$(grep -c '^agent|' "$SAIDA/B01_dados_034.txt")" = 3 ] || falha "B: massa incompleta"
modelo "$POP" "$SAIDA/B02_035.txt" || { cat "$SAIDA/B02_035.txt"; falha "B: 035 na populada"; }
[ "$(estado_migrador "$POP")" = "0|0|0" ] || falha "B: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=035" ] || falha "B: marcador"
dados "$POP" | diff "$SAIDA/B01_dados_034.txt" - > "$SAIDA/B03_diferenca_dados.txt" || falha "B: dados alterados pela 035"
[ "$(dados_035 "$POP" | tr '\n' ' ')" = "maquina|0 maquina-assoc|0 operacoes|0 " ] || falha "B: autoria de maquina inventada"
catalogo "$POP" | diff "$SAIDA/A01_catalogo_035.txt" - > "$SAIDA/B04_diferenca_catalogo.txt" || falha "B: catalogo diferente da instalacao limpa"
echo "   B ok"

# ================================================================== G. guarda com o papel da maquina
echo ">> G: gatilhos com o papel real da maquina ($GRD)"
sobe "$GRD" 35 035
massa "$GRD" "$AQUI/sql/p035_base.sql"
dados "$GRD" > "$SAIDA/G00_dados_antes.txt"
docker exec -i "$GRD" psql -X -At -U nekkora -d nekkora < "$AQUI/sql/c035_guarda.sql" > "$SAIDA/G01_guarda.txt" 2>&1 || true
g() { awk -v k="$1" '$0 == k {f = 1; next} /^C[0-9]+$|^FIM$/ {f = 0} f' "$SAIDA/G01_guarda.txt"; }
for c in C1 C2 C3; do g "$c" | grep -q "ERROR:.*maquina so" || { cat "$SAIDA/G01_guarda.txt"; falha "G: $c nao recusado pela guarda"; }; done
# C4: encerrar como PESSOA nem chega ao gatilho: a maquina nao tem UPDATE em ended_by_membership_id
g C4 | grep -q "ERROR:.*permission denied for table worker_agent_assignments" || { cat "$SAIDA/G01_guarda.txt"; falha "G: C4 a maquina encerrou como pessoa"; }
g C5 | grep -q "ERROR:.*desvinculo so para o vinculo ativo" || { cat "$SAIDA/G01_guarda.txt"; falha "G: C5 operacao de vinculo nao ativo aceita"; }
grep -qx "C6|0" "$SAIDA/G01_guarda.txt" || falha "G: C6 outra organizacao visivel"
grep -qx "C7|4|2|true|0" "$SAIDA/G01_guarda.txt" || { cat "$SAIDA/G01_guarda.txt"; falha "G: C7 desvinculo completo"; }
g C7 | grep -q "ERROR" && { cat "$SAIDA/G01_guarda.txt"; falha "G: C7 com erro"; }
g C8 | grep -q "ERROR:.*permission denied" || { cat "$SAIDA/G01_guarda.txt"; falha "G: C8 operacao alteravel"; }
g C9 | grep -q "ERROR:.*permission denied" || { cat "$SAIDA/G01_guarda.txt"; falha "G: C9 operacao apagavel"; }
g C10 | grep -q "ERROR:.*agent_admin_events_machine_unlink" || { cat "$SAIDA/G01_guarda.txt"; falha "G: C10 ato 14 com pessoa"; }
grep -qx "FIM" "$SAIDA/G01_guarda.txt" || falha "G: roteiro interrompido"
dados "$GRD" | diff "$SAIDA/G00_dados_antes.txt" - > /dev/null || falha "G: algo ficou gravado"
echo "   G ok"

docker rm -f -v "$LIMPA" "$POP" "$GRD" > /dev/null
echo "035: A, B e G ok; saidas em $SAIDA"
