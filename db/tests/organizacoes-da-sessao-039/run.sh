#!/usr/bin/env bash
# Suite de banco da 039_organizacoes_atuais_da_sessao.sql (candidata; AGENT-CLIENT-NAVEGACAO-002, T01). Dois clusters
# PostgreSQL 16 DESCARTAVEIS e isolados (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca
# toca a stack principal, os laboratorios do frontend nem a homologacao. Aplicacao pelo modelo suportado
# (db/bootstrap/aplicar_como_migrador.sh). Sequencia contigua: 001-039.
#
#   A (limpa):  001-039 pelo entrypoint, com a janela padrao (039 elevada); funcao do dono da identidade, SECURITY DEFINER,
#               EXECUTE so da Platform; a Platform continua sem privilegio em identity_users; sem contexto de identidade a
#               funcao nao devolve nada; limite invalido e recusado; marcador 039; migrador 0|0|0.
#   B (janela): 001-038; a 039 SEM a janela de elevacao e RECUSADA por privilegio e nada muda; COM a janela aplica e o
#               catalogo fica identico ao da instalacao limpa; plano de retorno (DROP FUNCTION) ensaiado.
# O comportamento com dados (organizacao nova sem novo login, revogacao, outra identidade, sessao encerrada) e coberto por
# DesktopNavigationIT, com o papel nekkora_platform e a base real.
#
# Uso: db/tests/organizacoes-da-sessao-039/run.sh   (Git Bash no Windows: MSYS_NO_PATHCONV=1 e definido aqui)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail
export MSYS_NO_PATHCONV=1

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
PROJETO="${TESTE_PROJETO:-nekkora-c039}"
LIMPA="${PROJETO}-limpa"; JAN="${PROJETO}-janela"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine
SEM_039="011 012 013 016 017 018 019 020 022 023 024 026 027 029"
FN="public.identity_session_organizations(uuid, uuid, integer)"

falha() {
    echo "ERRO: $*" >&2
    echo "containers mantidos para inspecao. Remocao: docker rm -f -v $LIMPA $JAN" >&2
    exit 1
}
case "$PROJETO" in nekkora-platform|nekkora|*hml03*|*frontlab*) falha "projeto '$PROJETO' reservado";; esac
for c in "$LIMPA" "$JAN"; do
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done

mkdir -p "$SAIDA/mig"
for n in $(seq -w 1 39); do
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
modelo() {   # $1 container, $2 lista de elevacao, $3 saida
    docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE=039 -e NEKKORA_ATE=039 -e NEKKORA_MIGRACOES=/nekkora-migracoes \
        -e NEKKORA_ELEVAR="$2" "$1" sh /tmp/modelo.sh > "$3" 2>&1
}
estado_migrador() {
    consulta "$1" "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')"
}
marcador() { consulta "$1" "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }
catalogo() {   # o que a 039 pode ter tocado: a funcao nova, as funcoes que decidem o acesso e os privilegios em identity_users
    consulta "$1" "SELECT 'fn|' || p.oid::regprocedure || '|' || pg_get_userbyid(p.proowner) || '|' || p.prosecdef || '|' || coalesce(array_to_string(p.proconfig, ','), '')
                     || '|' || coalesce(array_to_string(p.proacl, ','), '') || '|' || md5(p.prosrc)
                     FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public' AND p.proname LIKE 'identity\_%'
        UNION ALL SELECT 'acl_identity_users|' || coalesce(array_to_string(relacl, ','), '') FROM pg_class WHERE oid = 'public.identity_users'::regclass
        UNION ALL SELECT 'politica|' || tablename || '|' || policyname || '|' || array_to_string(roles, ',') FROM pg_policies
                    WHERE schemaname = 'public' AND tablename LIKE 'identity\_%' ORDER BY 1"
}

# ================================================================== A. limpa
echo ">> A: instalacao limpa 001-039 ($LIMPA)"
sobe "$LIMPA" 39 039
echo "server_version=$(consulta "$LIMPA" 'SHOW server_version')" > "$SAIDA/ambiente.txt"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=039" ] || falha "A: marcador"
[ "$(estado_migrador "$LIMPA")" = "0|0|0" ] || falha "A: migrador fora de 0|0|0"
grep -q "aplicada versao=039 elevada=SUPERUSER" "$SAIDA/entrypoint_$LIMPA.txt" || falha "A: 039 fora da janela padrao"
catalogo "$LIMPA" > "$SAIDA/A01_catalogo_039.txt"
grep -q "^fn|identity_session_organizations(uuid,uuid,integer)|nekkora_identity_owner|true|search_path=pg_catalog, pg_temp|" "$SAIDA/A01_catalogo_039.txt" \
    || falha "A: funcao fora do padrao (dono, SECURITY DEFINER, search_path)"
consulta "$LIMPA" "SELECT has_function_privilege('nekkora_platform', '$FN', 'EXECUTE') || '|' || has_function_privilege('public', '$FN', 'EXECUTE')
    || '|' || has_function_privilege('nekkora_portal', '$FN', 'EXECUTE')
    || '|' || has_table_privilege('nekkora_platform', 'public.identity_users', 'SELECT, INSERT, UPDATE, DELETE')
    || '|' || has_any_column_privilege('nekkora_platform', 'public.identity_users', 'SELECT, INSERT, UPDATE, REFERENCES')" > "$SAIDA/A02_privilegios.txt"
[ "$(cat "$SAIDA/A02_privilegios.txt")" = "true|false|false|false|false" ] || falha "A: privilegios: $(cat "$SAIDA/A02_privilegios.txt")"

echo "   A1: como a Platform, sem contexto de identidade: nenhuma linha; com contexto e sessao inexistente: nenhuma linha"
psqlc "$LIMPA" > "$SAIDA/A03_sem_contexto.txt" <<'SQL'
BEGIN;
SET LOCAL ROLE nekkora_platform;
SELECT 'sem_contexto|' || count(*) FROM public.identity_session_organizations('00000039-0000-4000-9000-000000000001', NULL, 201);
SELECT set_config('nekkora.identity_id', '00000039-0000-4000-9000-0000000000aa', true) IS NOT NULL;
SELECT 'sessao_inexistente|' || count(*) FROM public.identity_session_organizations('00000039-0000-4000-9000-000000000001', NULL, 201);
SELECT 'sessao_nula|' || count(*) FROM public.identity_session_organizations(NULL, NULL, 1);
SELECT 'fn_identity_restaurado|' || coalesce(current_setting('nekkora.fn_identity_id', true), '') || '|' || coalesce(current_setting('nekkora.fn_organization_id', true), '');
ROLLBACK;
SQL
grep -qx "sem_contexto|0" "$SAIDA/A03_sem_contexto.txt" || falha "A1: devolveu linhas sem contexto"
grep -qx "sessao_inexistente|0" "$SAIDA/A03_sem_contexto.txt" || falha "A1: devolveu linhas para sessao inexistente"
grep -qx "sessao_nula|0" "$SAIDA/A03_sem_contexto.txt" || falha "A1: devolveu linhas para sessao nula"
grep -qx "fn_identity_restaurado||" "$SAIDA/A03_sem_contexto.txt" || falha "A1: contexto interno da funcao nao foi restaurado"

echo "   A2: limite fora de 1..201 recusado; a Platform nao le identity_users; o portal nao executa a funcao"
for lim in 0 202 NULL; do
    if psqlc "$LIMPA" -c "SET ROLE nekkora_platform" -c "SELECT count(*) FROM public.identity_session_organizations(NULL, NULL, $lim)" < /dev/null > "$SAIDA/A04_limite_$lim.txt" 2>&1; then
        falha "A2: limite $lim aceito"
    fi
    grep -q "limite invalido" "$SAIDA/A04_limite_$lim.txt" || falha "A2: mensagem do limite $lim"
done
if psqlc "$LIMPA" -c "SET ROLE nekkora_platform" -c "SELECT count(*) FROM public.identity_users" < /dev/null > "$SAIDA/A05_identity_users.txt" 2>&1; then
    falha "A2: a Platform leu identity_users"
fi
grep -q "permission denied for table identity_users" "$SAIDA/A05_identity_users.txt" || falha "A2: recusa de identity_users"
if psqlc "$LIMPA" -c "SET ROLE nekkora_portal" -c "SELECT count(*) FROM public.identity_session_organizations(NULL, NULL, 1)" < /dev/null > "$SAIDA/A06_portal.txt" 2>&1; then
    falha "A2: o portal executou a funcao"
fi
grep -q "permission denied for function identity_session_organizations" "$SAIDA/A06_portal.txt" || falha "A2: recusa do portal"
echo "   A ok"

# ================================================================== B. janela de elevacao e retorno
echo ">> B: base 001-038; 039 sem e com a janela de elevacao ($JAN)"
sobe "$JAN" 38 038
catalogo "$JAN" > "$SAIDA/B01_catalogo_038.txt"
grep -q "identity_session_organizations" "$SAIDA/B01_catalogo_038.txt" && falha "B: funcao ja existia na 038"

echo "   B0: sem a janela, recusada por privilegio; nada muda"
if modelo "$JAN" "$SEM_039" "$SAIDA/B02_039_sem_janela.txt"; then falha "B0: 039 aplicada sem elevacao"; fi
grep -qi "must be able to SET ROLE \"nekkora_identity_owner\"\|must be owner\|permission denied" "$SAIDA/B02_039_sem_janela.txt" \
    || { cat "$SAIDA/B02_039_sem_janela.txt"; falha "B0: recusa nao foi por privilegio"; }
[ "$(estado_migrador "$JAN")" = "0|0|0" ] || falha "B0: migrador fora de 0|0|0"
catalogo "$JAN" | diff "$SAIDA/B01_catalogo_038.txt" - > /dev/null || falha "B0: catalogo alterado pela tentativa recusada"
[ "$(marcador "$JAN")" = "AUSENTE" ] || falha "B0: marcador gravado sem a 039"

echo "   B1: com a janela, aplicada; catalogo igual ao da instalacao limpa"
modelo "$JAN" "$SEM_039 039" "$SAIDA/B03_039.txt" || { cat "$SAIDA/B03_039.txt"; falha "B1: 039 com a janela"; }
grep -q "aplicada versao=039 elevada=SUPERUSER" "$SAIDA/B03_039.txt" || falha "B1: 039 sem registro da janela"
grep -q "fim    versao=039 SUPERUSER revogado, estado 0|0|0" "$SAIDA/B03_039.txt" || falha "B1: janela nao foi fechada"
[ "$(estado_migrador "$JAN")" = "0|0|0" ] || falha "B1: migrador fora de 0|0|0"
[ "$(marcador "$JAN")" = "nekkora-inicializacao-completa versao=039" ] || falha "B1: marcador"
catalogo "$JAN" | diff "$SAIDA/A01_catalogo_039.txt" - > "$SAIDA/B04_diferenca_catalogo.txt" || falha "B1: catalogo diferente da instalacao limpa"
# o que mudou da 038 para a 039: SO a linha da funcao nova
diff "$SAIDA/B01_catalogo_038.txt" "$SAIDA/A01_catalogo_039.txt" | grep '^[<>]' > "$SAIDA/B05_o_que_a_039_mudou.txt" || true
[ "$(wc -l < "$SAIDA/B05_o_que_a_039_mudou.txt")" = 1 ] || falha "B1: a 039 mudou mais que uma linha do catalogo"
grep -q "^> fn|identity_session_organizations(uuid,uuid,integer)|" "$SAIDA/B05_o_que_a_039_mudou.txt" || falha "B1: mudanca inesperada"

echo "   B2: reaplicar e recusado (ja existe); retorno ENSAIADO: DROP FUNCTION devolve o catalogo da 038"
if modelo "$JAN" "$SEM_039 039" "$SAIDA/B06_039_de_novo.txt"; then falha "B2: 039 aplicada duas vezes"; fi
grep -q "039: identity_session_organizations ja existe" "$SAIDA/B06_039_de_novo.txt" || falha "B2: mensagem da reaplicacao"
[ "$(estado_migrador "$JAN")" = "0|0|0" ] || falha "B2: migrador fora de 0|0|0 depois da recusa"
consulta "$JAN" "DROP FUNCTION $FN" > "$SAIDA/B07_retorno.txt"
catalogo "$JAN" | diff "$SAIDA/B01_catalogo_038.txt" - > /dev/null || falha "B2: o retorno nao devolveu o catalogo da 038"
echo "   B ok"

docker rm -f -v "$LIMPA" "$JAN" > /dev/null
echo "039: A e B ok; saidas em $SAIDA"
