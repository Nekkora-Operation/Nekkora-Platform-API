#!/usr/bin/env bash
# Suite de banco da 026_retencao_eventos_agent.sql (candidata). Dois clusters PostgreSQL 16 DESCARTAVEIS e isolados (containers
# proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal nem os ambientes de homologacao.
#
# Modelo de aplicacao: db/bootstrap/aplicar_como_migrador.sh, SUPERUSER em 011-013, 016-020, 022-024 e 026, BYPASSRLS so na
# 014; a 025 SEM janela. A 026 PRECISA da janela (cabecalho; caso B0).
#
#   A (limpa):    001-026 pelo entrypoint; 025 sem janela e 026 com; invariantes 012-026 DEPOIS da retirada (adaptacoes
#                 declaradas: as da suite da 025 e, nas invariantes da 025, a politica de contexto de funcao e o acesso de
#                 nekkora_retention_owner SO em agent_run_events, 30 gatilhos no canal e a regra "tabelas vazias", que so vale
#                 na aplicacao da 025).
#   B (populada): 001-012 + massa (004-007), 013-015 + massa MFA, 016-024, 025 + massa do canal (registro e eventos).
#     B0 - 026 SEM a janela: recusada por privilegio (dono das tabelas e das funcoes de manutencao), nada muda.
#     B1 - 026 falhando no MEIO (funcao homonima da funcao de gatilho que ela cria, depois dos ALTER): nada muda.
#     B2 - 026: nenhum dado anterior muda (instantaneo das colunas anteriores identico); nenhum evento expurgado; diff de
#          catalogo EXATAMENTE o previsto (esperado/B24_diff_esperado.txt); catalogo = instalacao limpa A; invariantes.
#     B3 - reaplicar a 026 e recusado pela pre-condicao; nada muda.
#   C (comportamento, base limpa A, papeis reais):
#     C1 - barreira: o evento inserido pela ingestao recebe received_at do servidor (o enviado e ignorado) e a linha-barreira
#          do recurso 6 do dia; evento nao nasce expurgado;
#     C2 - fechamento do recurso 6 (nekkora_retention_executor) conta os eventos do Agent do dia e os bytes do envelope;
#          reconsolidacao do dia aberto; guard de categoria unica por transacao;
#     C3 - expurgo da categoria 3 (funcao do dono, EXECUTE nao concedido): so dia fechado, so politica finita vigente no
#          recebimento, so depois do prazo; redige envelope e campos de etapa; identidade, content_sha256, classificacao e
#          instantes ficam; agent_runs nao muda; auditoria em organization_purge_runs; reenvio do mesmo (run, sequence) nao
#          duplica;
#     C4 - redacao: qualquer outra atualizacao recusada (mesmo pelo dono das funcoes); dia aberto recusado; ingestao e
#          Platform sem UPDATE;
#     C5 - contraprovas das invariantes da 026 (cada uma em BEGIN ... ROLLBACK).
#
# O comportamento das rotas (ACK depois do expurgo, consultas) e coberto pelos testes Java: AgentGatewayIT
# (nekkora-agent-gateway) e AgentAdminIT (nekkora-platform).
#
# Uso: db/tests/retencao-agent-026/run.sh   (Windows/Git Bash: ver README da suite)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
INSTANTANEO="$REPO/db/tests/relogio-018/sql/c_instantaneo_gera.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018 019 020 022 023 024 026}"
SEM_026="011 012 013 016 017 018 019 020 022 023 024"
PROJETO="${TESTE_PROJETO:-nekkora-c026}"
LIMPA="${PROJETO}-limpa"
POP="${PROJETO}-populada"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine
PY=python3; "$PY" -c 1 >/dev/null 2>&1 || PY=python

falha() {
    echo "ERRO: $*" >&2
    echo "containers mantidos para inspecao. Remocao: docker rm -f -v $LIMPA $POP" >&2
    exit 1
}
case "$PROJETO" in nekkora-platform|nekkora|*hml03*) falha "projeto '$PROJETO' reservado";; esac
for c in "$LIMPA" "$POP"; do
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done

mkdir -p "$SAIDA/mig"
for n in $(seq -w 1 26); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
M023="$SAIDA/mig/023_acessos_convites.sql"
M024="$SAIDA/mig/024_acessos_decisoes_reset_vinculado.sql"
M025="$SAIDA/mig/025_canal_agent.sql"
M026="$SAIDA/mig/026_retencao_eventos_agent.sql"
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
sha256sum "$SAIDA"/mig/0*.sql 2>/dev/null | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt" \
    || shasum -a 256 "$SAIDA"/mig/0*.sql | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt"

psqlc() { local c="$1" db="$2"; shift 2; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$db" "$@"; }
consulta() { psqlc "$1" "$2" -c "$3" < /dev/null; }
lista() { psqlc "$1" nekkora < "$LISTAGEM" | awk '/^(rel|relacl|col|colacl|con|idx|trg|pol|fn|fnacl|type|nsp|nspacl|ext|defacl|dbacl)\|/ { if (l != "") print l; l = $0; next } { l = l " " $0 } END { if (l != "") print l }'; }
sobe() {   # $1 container, $2 quantidade esperada, $3 ultima versao aplicada no entrypoint
    docker create --name "$1" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
        -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE="$3" \
        -e NEKKORA_ELEVAR="$ELEVAR" -e NEKKORA_MIGRACOES=/nekkora-migracoes \
        "$IMAGEM" postgres -c cluster_name="$1" > /dev/null
    docker cp "$SAIDA/mig" "$1":/nekkora-migracoes > /dev/null
    docker cp "$SAIDA/modelo.sh" "$1":/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh > /dev/null
    docker cp "$SAIDA/modelo.sh" "$1":/tmp/modelo.sh > /dev/null
    docker start "$1" > /dev/null
    for _ in $(seq 1 180); do
        [ "$(docker logs "$1" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
        docker ps -q -f "name=^$1\$" | grep -q . || break
        sleep 1
    done
    docker logs "$1" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|sourcing|aplicar_como_migrador|ERROR|FATAL|init process complete" > "$SAIDA/entrypoint_$1.txt" || true
    grep -qE "ERROR|FATAL|aplicar_como_migrador: FALHA" "$SAIDA/entrypoint_$1.txt" && falha "erro na aplicacao das migrations em $1 (ver entrypoint_$1.txt)"
    [ "$(grep -c 'aplicar_como_migrador: aplicada versao=' "$SAIDA/entrypoint_$1.txt")" = "$2" ] || falha "esperadas $2 migrations em $1"
    grep -q "aplicar_como_migrador: fim db=nekkora aplicadas=$2 estado=0|0|0" "$SAIDA/entrypoint_$1.txt" || falha "migrador nao terminou 0|0|0 em $1"
    [ "$(consulta "$1" nekkora 'SHOW cluster_name')" = "$1" ] || falha "cluster inesperado em $1"
}
modelo() {   # $1 container, $2 desde, $3 ate, $4 arquivo de saida, $5 lista de elevacao (padrao $ELEVAR)
    docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE="$2" -e NEKKORA_ATE="$3" -e NEKKORA_ELEVAR="${5-$ELEVAR}" \
        -e NEKKORA_MIGRACOES=/nekkora-migracoes "$1" sh /tmp/modelo.sh > "$4" 2>&1
}
estado_migrador() {
    consulta "$1" nekkora "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')
        || ' dono_do_banco=' || pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()"
}
adapta() {   # $1 arquivo, $2 trecho, $3 substituto: substitui um trecho que precisa existir exatamente uma vez
    "$PY" -c 'import sys
s = open(sys.argv[1], encoding="utf-8").read()
assert s.count(sys.argv[2]) == 1, "trecho a adaptar nao encontrado exatamente uma vez: " + sys.argv[2][:60]
sys.stdout.write(s.replace(sys.argv[2], sys.argv[3]))' "$1" "$2" "$3"
}
ACEITE_ANTES="'identity_account_is_linked(uuid,uuid), identity_challenge_lookup(text), '"
ACEITE_DEPOIS="'identity_accept_invitation(text,text,uuid), identity_account_is_linked(uuid,uuid), identity_challenge_lookup(text), '"
FECHA_ANTES="        'identity_link_account(text,text,uuid), '"
FECHA_DEPOIS="        'identity_invitations_close_unauthorized(uuid,uuid[]), '
        'identity_link_account(text,text,uuid), '"
# invariantes da 025 adaptadas a 026 (declarado no cabecalho): politica de funcao e acesso do dono das funcoes de manutencao
# SO em agent_run_events; 30 gatilhos (prevent_update saiu; redacao e received_at entraram); sem a regra "tabelas vazias"
inv025_adaptada() {
    awk '/^-- === 7\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M025" > "$SAIDA/_inv025.sql"
    adapta "$SAIDA/_inv025.sql" "JOIN pg_roles r ON r.oid = x WHERE p.polrelid = ('public.' || t)::regclass)" \
        "JOIN pg_roles r ON r.oid = x WHERE p.polrelid = ('public.' || t)::regclass AND p.polname = 'tenant_request_context')" > "$SAIDA/_inv025b.sql"
    adapta "$SAIDA/_inv025b.sql" "    WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = p)" \
        "    WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = p) AND NOT (p = 'nekkora_retention_owner' AND t = 'agent_run_events')" > "$SAIDA/_inv025c.sql"
    adapta "$SAIDA/_inv025c.sql" "IF v_n <> 29 THEN RAISE EXCEPTION '025: % gatilhos do canal ligados (esperados 29)', v_n; END IF;" \
        "IF v_n <> 30 THEN RAISE EXCEPTION '025: % gatilhos do canal ligados (esperados 30: 29 - prevent_update de agent_run_events + redacao + received_at, da 026)', v_n; END IF;" > "$SAIDA/_inv025d.sql"
    adapta "$SAIDA/_inv025d.sql" "    IF EXISTS (SELECT 1 FROM public.agent_enrollments) OR EXISTS (SELECT 1 FROM public.agent_runs)
       OR EXISTS (SELECT 1 FROM public.local_run_authorizations) THEN
        RAISE EXCEPTION '025: tabelas do canal nao nascem vazias';
    END IF;" ""
}
invariantes() {   # $1 container, $2 nome; sempre DEPOIS da retirada
    awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/014_mfa_sessoes.sql" > "$SAIDA/_inv014.sql"
    awk '/^-- === 12\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/017_identidade_cadastro.sql" > "$SAIDA/_inv017.sql"
    awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/019_identidade_regeneracao_codigos.sql" > "$SAIDA/_inv019.sql"
    awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/020_identidade_recuperacao_senha.sql" > "$SAIDA/_inv020.sql"
    adapta "$SAIDA/_inv017.sql" "        'identity_password_change(text,text,text), '" \
        "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '
        'identity_recovery_codes_regenerate(text,text[]), '" > "$SAIDA/_inv017b.sql"
    adapta "$SAIDA/_inv017b.sql" "IF v_n <> 43 THEN RAISE EXCEPTION 'invariante 1: % tabelas com organization_id (esperadas 43)', v_n; END IF;" \
        "IF v_n <> 53 THEN RAISE EXCEPTION 'invariante 1: % tabelas com organization_id (esperadas 53: 43 + access_events da 023 + 9 do canal da 025)', v_n; END IF;" > "$SAIDA/_inv017c.sql"
    adapta "$SAIDA/_inv019.sql" "        'identity_password_change(text,text,text), '" \
        "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '" > "$SAIDA/_inv019b.sql"
    adapta "$SAIDA/_inv019b.sql" "'CHECK (((event_type >= 1) AND (event_type <= 15)))'" "'CHECK (((event_type >= 1) AND (event_type <= 17)))'" > "$SAIDA/_inv019c.sql"
    adapta "$SAIDA/_inv017c.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS" > "$SAIDA/_inv017d.sql"
    adapta "$SAIDA/_inv019c.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS" > "$SAIDA/_inv019d.sql"
    adapta "$SAIDA/_inv020.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS" > "$SAIDA/_inv020d.sql"
    awk '/^-- === 11\. /{f=1} f && /^COMMIT;/{exit} f' "$M023" > "$SAIDA/_inv023.sql"
    adapta "$SAIDA/_inv023.sql" "IF v_n <> 44 THEN RAISE EXCEPTION '023: % tabelas com organization_id (esperadas 44)', v_n; END IF;"         "IF v_n <> 53 THEN RAISE EXCEPTION '023: % tabelas com organization_id (esperadas 53: 44 + 9 do canal da 025)', v_n; END IF;" > "$SAIDA/_inv023b.sql"
    { awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql"
      awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/013_portal_externo.sql"
      adapta "$SAIDA/_inv014.sql" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname AND p.policyname NOT IN ('ops_function_context', 'identity_function_context')) <> 1"
      awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql"
      adapta "$SAIDA/_inv017d.sql" "$FECHA_ANTES" "$FECHA_DEPOIS"
      adapta "$SAIDA/_inv019d.sql" "$FECHA_ANTES" "$FECHA_DEPOIS"
      adapta "$SAIDA/_inv020d.sql" "$FECHA_ANTES" "$FECHA_DEPOIS"
      awk '/^-- === 5\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/021_correio_fila.sql"
      awk '/^-- === 10\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/022_ativacao_organizacoes.sql"
      cat "$SAIDA/_inv023b.sql"
      awk '/^-- === 6\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M024"
      inv025_adaptada
      awk '/^-- === 8\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M026"
    } > "$SAIDA/${2}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${2}_invariantes.sql")" = 13 ] || falha "extracao dos invariantes ($2) falhou"
    estado_migrador "$1" > "$SAIDA/${2}_estado_migrador.txt"
    [ "$(cat "$SAIDA/${2}_estado_migrador.txt")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "invariantes ($2) fora do estado pos-retirada"
    { echo "BEGIN;"; cat "$SAIDA/${2}_invariantes.sql"; echo "ROLLBACK;"; } | psqlc "$1" nekkora > "$SAIDA/${2}_invariantes.txt" 2>&1 \
        || { cat "$SAIDA/${2}_invariantes.txt"; falha "invariantes ($2) falharam"; }
}
marcador() { consulta "$1" nekkora "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }
recusa_sql() {   # $1 container, $2 rotulo, $3 trecho esperado, $4 sql (numa transacao propria)
    local o rc=0
    o="$(printf '%s\n' "$4" | psqlc "$1" nekkora 2>&1)" || rc=$?
    echo "$2|rc=$rc|$(echo "$o" | grep -m1 -oE 'ERROR: .*' | cut -c1-160)" >> "$SAIDA/C_recusas.txt"
    [ "$rc" != 0 ] && echo "$o" | grep -qF "$3" || falha "$2: esperado '$3' (rc=$rc: $o)"
}
passa_sql() {   # $1 container, $2 rotulo, $3 sql (numa transacao propria; deve passar)
    local o rc=0
    o="$(printf '%s\n' "$3" | psqlc "$1" nekkora 2>&1)" || rc=$?
    echo "$2|rc=$rc" >> "$SAIDA/C_passam.txt"
    [ "$rc" = 0 ] || falha "$2: deveria passar (rc=$rc: $o)"
}
como() { printf "BEGIN; SET LOCAL ROLE %s; SELECT set_config('nekkora.organization_id', '%s', true);\n%s\nCOMMIT;" "$1" "$2" "$3"; }
FP=5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d
ART=$(printf 'ab%.0s' $(seq 32))
evento() {   # $1 run, $2 sequence, $3 event_code: envelope minimo coerente com a projecao (a validacao fina e do gateway)
    printf "('%s', '%s', %s, md5('%s-%s') || md5('%s'), '{\"sequence\":%s,\"event_code\":\"%s\",\"step_id\":\"passo_%s\"}', 1, '%s', 1, 1, 'passo_%s', now())" \
        "$O" "$1" "$2" "$1" "$2" "$2" "$2" "$3" "$2" "$3" "$2"
}
massa_canal() {   # $1 container: organizacao com Worker, Agent, autorizacao e um registro (como na suite da 025); define O, RUN...
    local c="$1"
    u() { consulta "$c" nekkora "SELECT gen_random_uuid()"; }
    O="$(u)"; O2="$(u)"; ADM="$(u)"; ADMM="$(u)"; WS="$(u)"; AUT="$(u)"; VER="$(u)"
    WK="$(u)"; AG="$(u)"; WAA="$(u)"; WWA="$(u)"; CRED="$(u)"; AUTZ="$(u)"; RUN="$(u)"
    psqlc "$c" nekkora > "$SAIDA/massa_canal_$c.txt" 2>&1 <<SQL || { cat "$SAIDA/massa_canal_$c.txt"; falha "massa do canal em $c"; }
BEGIN;
INSERT INTO organizations (organization_id, name, status) VALUES ('$O', 'Org retencao', 1), ('$O2', 'Outra org', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
  ('$ADM', '$O', 1, 'adm-$O@o.test', now(), 2, 'Admin');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('$ADMM', '$O', '$ADM', 1, 2);
INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
  SELECT '$O', '$ADMM', role_id, 1, 1, '$ADMM' FROM roles WHERE code = 'ORG_ADMIN';
INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, provisioned_by_user_id)
  SELECT '$O', c, 1, now(), 2, NULL, '$ADM' FROM generate_series(1, 5) c;
-- organizacao ATIVADA (regra da capacidade zero da 022: aguardando ativacao nao tem capacidade); fixture como as classificadas
-- pela 022 (origem 2), sem o ato operacional de ativacao, que nao e o objeto desta suite
SET LOCAL session_replication_role = replica;
UPDATE ops_activation.organization_states SET state = 2, origin = 2, activated_at = now() WHERE organization_id = '$O';
SET LOCAL session_replication_role = origin;
INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('$WS', '$O', 'WS', 1);
INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('$AUT', '$O', '$WS', 'Biografias', 1, '$ADMM');
INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) VALUES
  ('$VER', '$O', '$WS', '$AUT', 1, jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','p','sha256','$ART'),
   'agent_package',jsonb_build_object('fingerprint_rule','nekkora-agent/package-fingerprint/1','fingerprint','$FP'),'runtime','python3.12',
   'entrypoint','main.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '$ADMM');
UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '$ADMM' WHERE automation_version_id = '$VER';
INSERT INTO workers (worker_id, organization_id, name, status) VALUES ('$WK', '$O', 'W', 1);
INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES ('$AG', '$O', 'A', '0.1.0', 2);
INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES ('$WAA', '$O', '$WK', '$AG', 1, '$ADMM');
INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('$WWA', '$O', '$WK', '$WS', 1, '$ADMM');
INSERT INTO agent_credentials (credential_id, organization_id, agent_id, credential_hash, issued_by_membership_id, expires_at) VALUES ('$CRED', '$O', '$AG', repeat('c', 64), '$ADMM', now() + interval '90 days');
COMMIT;
SQL
    passa_sql "$c" "massa_autoriza_$c" "$(como nekkora_platform "$O" "INSERT INTO local_run_authorizations (authorization_id, organization_id, workspace_id, worker_id,
        worker_workspace_assignment_id, automation_id, automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('$AUTZ', '$O', '$WS', '$WK',
        '$WWA', '$AUT', '$VER', '$FP', '$ADMM', now() + interval '30 days');")"
    passa_sql "$c" "massa_registra_$c" "$(como nekkora_ingestion "$O" "INSERT INTO agent_runs (agent_run_id, organization_id, workspace_id, automation_id, automation_version_id,
        authorization_id, agent_id, worker_id, worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state)
        VALUES ('$RUN', '$O', '$WS', '$AUT', '$VER', '$AUTZ', '$AG', '$WK', '$WAA', 'ex-$(printf '%s' "$RUN" | tr -d '-' | cut -c1-32)', '$FP', 1, 3);")"
}

# ================================================================== A. instalacao limpa 001-026
echo ">> A: instalacao limpa 001-026 ($LIMPA)"
sobe "$LIMPA" 26 026
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" > "$SAIDA/ambiente.txt"
grep -q "aplicada versao=025 elevada=nao" "$SAIDA/entrypoint_$LIMPA.txt" || falha "025 deveria ser aplicada SEM janela"
grep -q "aplicada versao=026 elevada=SUPERUSER" "$SAIDA/entrypoint_$LIMPA.txt" || falha "026 deveria ser aplicada COM a janela"
grep -q "invariantes_016_pos_retirada=OK" "$SAIDA/entrypoint_$LIMPA.txt" || falha "016 §19 pos-retirada ausente (depois da 026)"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=026" ] || falha "marcador da instalacao limpa"
invariantes "$LIMPA" A01
lista "$LIMPA" > "$SAIDA/A02_catalogo_026.txt"

# ================================================================== B. base populada 025 -> 026
echo ">> B: base populada 001-025 (+ canal) -> 026 ($POP)"
sobe "$POP" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" 013 015 "$SAIDA/B02_013_015.txt" || { cat "$SAIDA/B02_013_015.txt"; falha "013-015 na populada"; }
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
modelo "$POP" 016 025 "$SAIDA/B04_016_025.txt" || { cat "$SAIDA/B04_016_025.txt"; falha "016-025 na populada"; }
grep -q "aplicada versao=025 elevada=nao" "$SAIDA/B04_016_025.txt" || falha "B: 025 deveria ser aplicada sem janela"
consulta "$POP" nekkora "CREATE ROLE backup_nekkora LOGIN BYPASSRLS; GRANT pg_read_all_data TO backup_nekkora" > /dev/null
massa_canal "$POP"
passa_sql "$POP" "B_eventos_025" "$(como nekkora_ingestion "$O" "INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source,
    event_code, severity, category, step_id, observed_utc) VALUES $(evento "$RUN" 1 RUNNING), $(evento "$RUN" 2 STEP_COMPLETED), $(evento "$RUN" 3 STEP_COMPLETED);")"
[ "$(consulta "$POP" nekkora "SELECT (SELECT count(*) FROM agent_run_events) = 3 AND (SELECT count(*) FROM execution_events) > 0")" = t ] \
    || falha "massa sem eventos do Agent ou da 007"
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B07_instantaneo.sql"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B07_dados_025.txt"
lista "$POP" > "$SAIDA/B07_catalogo_025.txt"

echo "   B0: 026 sem a janela"
if modelo "$POP" 026 026 "$SAIDA/B00_026_sem_janela.txt" "$SEM_026"; then falha "B0: 026 aplicada sem a janela"; fi
grep -qE "must be owner of (table|relation) organization_usage_daily" "$SAIDA/B00_026_sem_janela.txt" \
    || { cat "$SAIDA/B00_026_sem_janela.txt"; falha "B0: recusa diferente da falta de privilegio esperada"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B0: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B00b_dados.txt"
diff "$SAIDA/B07_dados_025.txt" "$SAIDA/B00b_dados.txt" > /dev/null || falha "B0: dados alterados"
lista "$POP" > "$SAIDA/B00b_catalogo.txt"
diff "$SAIDA/B07_catalogo_025.txt" "$SAIDA/B00b_catalogo.txt" > /dev/null || falha "B0: catalogo alterado"

echo "   B1: 026 falhando no meio"
# funcao homonima da funcao de gatilho que a 026 cria na sec. 4, DEPOIS dos ALTER de catalogo e das colunas novas: nada fica
consulta "$POP" nekkora "CREATE FUNCTION public.agent_run_event_received_at() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'" > /dev/null
if modelo "$POP" 026 026 "$SAIDA/B10_026_falha.txt"; then falha "B1: 026 aplicada com a funcao homonima"; fi
grep -qF "026: retencao dos eventos do Agent ja aplicada" "$SAIDA/B10_026_falha.txt" \
    || grep -qF 'function "agent_run_event_received_at" already exists' "$SAIDA/B10_026_falha.txt" \
    || { cat "$SAIDA/B10_026_falha.txt"; falha "B1: recusa diferente da esperada"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B1: migrador fora de 0|0|0"
consulta "$POP" nekkora "DROP FUNCTION public.agent_run_event_received_at()" > /dev/null
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B11_dados.txt"
diff "$SAIDA/B07_dados_025.txt" "$SAIDA/B11_dados.txt" > /dev/null || falha "B1: dados alterados"
lista "$POP" > "$SAIDA/B11_catalogo.txt"
diff "$SAIDA/B07_catalogo_025.txt" "$SAIDA/B11_catalogo.txt" > /dev/null || falha "B1: catalogo alterado"

echo "   B1b: falha DEPOIS dos ALTER de catalogo (gatilho homonimo do de redacao)"
# a pre-condicao recusa a funcao homonima acima antes de qualquer ALTER; este caso falha no meio de verdade: o gatilho de redacao
# ja existe com o nome que a sec. 5 cria, entao a 026 falha depois dos ALTER (catalogos, colunas, funcao e gatilho de received_at)
consulta "$POP" nekkora "CREATE TRIGGER trg_agent_run_events_enforce_redaction_or_reject BEFORE UPDATE ON public.agent_run_events FOR EACH ROW EXECUTE FUNCTION public.prevent_update()" > /dev/null
if modelo "$POP" 026 026 "$SAIDA/B12_026_falha_tardia.txt"; then falha "B1b: 026 aplicada com o gatilho homonimo"; fi
grep -qF 'trigger "trg_agent_run_events_enforce_redaction_or_reject" for relation "agent_run_events" already exists' "$SAIDA/B12_026_falha_tardia.txt" \
    || { cat "$SAIDA/B12_026_falha_tardia.txt"; falha "B1b: recusa diferente da esperada"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B1b: migrador fora de 0|0|0"
consulta "$POP" nekkora "DROP TRIGGER trg_agent_run_events_enforce_redaction_or_reject ON public.agent_run_events" > /dev/null
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B13_dados.txt"
diff "$SAIDA/B07_dados_025.txt" "$SAIDA/B13_dados.txt" > /dev/null || falha "B1b: dados alterados"
lista "$POP" > "$SAIDA/B13_catalogo.txt"
diff "$SAIDA/B07_catalogo_025.txt" "$SAIDA/B13_catalogo.txt" > /dev/null || falha "B1b: catalogo alterado"

echo "   B2: 026"
modelo "$POP" 026 026 "$SAIDA/B20_026.txt" || { cat "$SAIDA/B20_026.txt"; falha "026 na populada"; }
grep -q "aplicada versao=026 elevada=SUPERUSER" "$SAIDA/B20_026.txt" || falha "B2: 026 deveria ser aplicada com a janela"
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B2: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=026" ] || falha "B2: marcador"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B21_dados_026.txt"
diff "$SAIDA/B07_dados_025.txt" "$SAIDA/B21_dados_026.txt" > "$SAIDA/B21_diff_dados.txt" || falha "B2: a 026 alterou dados anteriores (ver B21_diff_dados.txt)"
[ "$(consulta "$POP" nekkora "SELECT count(*) FILTER (WHERE purged_at IS NULL AND envelope IS NOT NULL AND purge_category = 3) || '|' || count(*) FROM agent_run_events")" = "3|3" ] \
    || falha "B2: eventos anteriores nao ficaram intactos e sem expurgo"
lista "$POP" > "$SAIDA/B24_catalogo_026.txt"
diff "$SAIDA/B07_catalogo_025.txt" "$SAIDA/B24_catalogo_026.txt" > "$SAIDA/B24_diff_catalogo.txt" || true
grep -E '^[<>]' "$SAIDA/B24_diff_catalogo.txt" | sed -E 's/\|md5=[0-9a-f]+/|md5=.../' | sort > "$SAIDA/B24_diff_resumo.txt"
if [ -f "$AQUI/esperado/B24_diff_esperado.txt" ]; then
    sort "$AQUI/esperado/B24_diff_esperado.txt" > "$SAIDA/_b.txt"
    diff "$SAIDA/_b.txt" "$SAIDA/B24_diff_resumo.txt" > "$SAIDA/B24_fora_do_previsto.txt" || { cat "$SAIDA/B24_fora_do_previsto.txt"; falha "B2: catalogo 025 -> 026 fora do previsto"; }
else
    falha "B2: esperado/B24_diff_esperado.txt ausente (resumo gerado em $SAIDA/B24_diff_resumo.txt para revisao)"
fi
diff "$SAIDA/A02_catalogo_026.txt" "$SAIDA/B24_catalogo_026.txt" > "$SAIDA/B25_diff_limpa.txt" || falha "B2: catalogo da atualizada difere da instalacao limpa (ver B25)"
invariantes "$POP" B26

echo "   B3: reaplicacao da 026"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B30_dados_antes.txt"
if psqlc "$POP" nekkora < "$M026" > "$SAIDA/B30_reaplica.txt" 2>&1; then falha "B3: 026 reaplicada"; fi
grep -qF "026: retencao dos eventos do Agent ja aplicada" "$SAIDA/B30_reaplica.txt" || { cat "$SAIDA/B30_reaplica.txt"; falha "B3: recusa diferente da pre-condicao"; }
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B30_dados_depois.txt"
diff "$SAIDA/B30_dados_antes.txt" "$SAIDA/B30_dados_depois.txt" > /dev/null || falha "B3: dados alterados"
lista "$POP" > "$SAIDA/B31_catalogo.txt"
diff "$SAIDA/B24_catalogo_026.txt" "$SAIDA/B31_catalogo.txt" > /dev/null || falha "B3: catalogo alterado"

# ================================================================== C. comportamento no banco (base limpa A)
echo ">> C: comportamento no banco ($LIMPA)"
massa_canal "$LIMPA"
HOJE="$(consulta "$LIMPA" nekkora "SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date")"
ANTIGO="$(consulta "$LIMPA" nekkora "SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date - 3")"

echo "   C1: barreira na ingestao"
passa_sql "$LIMPA" "C1_ingestao_insere" "$(como nekkora_ingestion "$O" "INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source,
    event_code, severity, category, step_id, observed_utc, received_at) VALUES $(evento "$RUN" 10 RUNNING | sed 's/, now())$/, now(), '"'"'2000-01-01T00:00:00Z'"'"')/'),
    $(evento "$RUN" 11 STEP_COMPLETED | sed 's/, now())$/, now(), now())/');")"
[ "$(consulta "$LIMPA" nekkora "SELECT count(*) FROM agent_run_events WHERE agent_run_id = '$RUN' AND sequence IN (10, 11)
      AND (received_at AT TIME ZONE 'UTC')::date = '$HOJE' AND received_at > now() - interval '10 minutes' AND purged_at IS NULL")" = 2 ] \
    || falha "C1: received_at nao foi atribuido pelo servidor (o enviado deveria ser ignorado)"
[ "$(consulta "$LIMPA" nekkora "SELECT status || '|' || (consolidated_at IS NULL) FROM organization_usage_daily WHERE organization_id = '$O' AND resource_code = 6 AND usage_date = '$HOJE'")" = "1|true" ] \
    || falha "C1: linha-barreira do recurso 6 ausente ou fora do estado de barreira"
recusa_sql "$LIMPA" "C1_nasce_expurgado" "evento do Agent nasce sem expurgo" "$(como nekkora_ingestion "$O" "INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256,
    envelope, source, event_code, severity, category, observed_utc, purged_at, purged_by_user_id, purge_run_id) VALUES ('$O', '$RUN', 12, repeat('e', 64),
    '{\"sequence\":12,\"event_code\":\"XY\"}', 1, 'XY', 1, 1, now(), now(), '$ADM', gen_random_uuid());")"
recusa_sql "$LIMPA" "C1_outra_org_barreira" "contexto organizacional ausente ou divergente" "$(como nekkora_ingestion "$O2" "SELECT public.assign_received_at_and_lock_barrier('$O', 6);")"

# eventos de um dia passado (fixture: recebidos ha 3 dias, com a politica de retencao de 1 dia vigente desde antes). O superusuario
# usa session_replication_role=replica SO para escrever o passado (gatilhos de barreira e de vigencia desligados nesta fixture).
psqlc "$LIMPA" nekkora > "$SAIDA/C20_fixture_passado.txt" 2>&1 <<SQL || { cat "$SAIDA/C20_fixture_passado.txt"; falha "fixture do passado"; }
BEGIN;
SET LOCAL session_replication_role = replica;
INSERT INTO organization_retention_policy_revisions (organization_id, category, revision_number, effective_from, retention_mode, retention_days, provisioned_by_user_id)
  VALUES ('$O', 3, 1, now() - interval '10 days', 1, 1, '$ADM');
INSERT INTO organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at)
  VALUES ('$O', 6, '$ANTIGO', 0, 0, 1, NULL);
INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source, event_code, severity, category, step_id, observed_utc, received_at)
  VALUES $(evento "$RUN" 1 RUNNING | sed "s/, now())\$/, now() - interval '3 days', ('$ANTIGO'::date + time '12:00') AT TIME ZONE 'UTC')/"),
         $(evento "$RUN" 2 STEP_COMPLETED | sed "s/, now())\$/, now() - interval '3 days', ('$ANTIGO'::date + time '12:00:01') AT TIME ZONE 'UTC')/"),
         $(evento "$RUN" 3 STEP_COMPLETED | sed "s/, now())\$/, now() - interval '3 days', ('$ANTIGO'::date + time '12:00:02') AT TIME ZONE 'UTC')/");
COMMIT;
SQL
passa_sql "$LIMPA" "C2_contadores" "$(como nekkora_ingestion "$O" "UPDATE agent_runs SET events_stored = 5, stored_through = 3, settled_through = 3, max_sequence = 11 WHERE agent_run_id = '$RUN';")"
ANTES_RUN="$(consulta "$LIMPA" nekkora "SELECT md5(row(r.*)::text) FROM agent_runs r WHERE agent_run_id = '$RUN'")"

echo "   C3: expurgo antes do fechamento (dia aberto): nada"
[ "$(consulta "$LIMPA" nekkora "SELECT public.purge_expired_records('$O', 3, '$ADM', 100)")" = 0 ] || falha "C3: expurgo com o dia aberto"

echo "   C2: fechamento e reconsolidacao do recurso 6"
recusa_sql "$LIMPA" "C2_fecha_hoje" "fechamento so processa dias estritamente passados" "BEGIN; SET LOCAL ROLE nekkora_retention_executor; SELECT public.close_usage_day('$O', 6, '$HOJE'); COMMIT;"
recusa_sql "$LIMPA" "C2_fecha_sem_privilegio" "permission denied for function close_usage_day" "BEGIN; SET LOCAL ROLE nekkora_ingestion; SELECT public.close_usage_day('$O', 6, '$ANTIGO'); COMMIT;"
passa_sql "$LIMPA" "C2_fecha_antigo" "BEGIN; SET LOCAL ROLE nekkora_retention_executor; SELECT public.close_usage_day('$O', 6, '$ANTIGO'); COMMIT;"
ESPERADO="$(consulta "$LIMPA" nekkora "SELECT count(*) || '|' || sum(octet_length(envelope::text)) FROM agent_run_events WHERE organization_id = '$O' AND (received_at AT TIME ZONE 'UTC')::date = '$ANTIGO'")"
[ "$(consulta "$LIMPA" nekkora "SELECT records_count || '|' || bytes_count || '|' || status FROM organization_usage_daily WHERE organization_id = '$O' AND resource_code = 6 AND usage_date = '$ANTIGO'")" = "$ESPERADO|2" ] \
    || falha "C2: fechamento nao contou os eventos do Agent do dia (esperado $ESPERADO|2)"
passa_sql "$LIMPA" "C2_reconsolida_hoje" "BEGIN; SET LOCAL ROLE nekkora_retention_executor; SELECT * FROM public.reconsolidate_usage_day('$O', 6, '$HOJE'); COMMIT;"
[ "$(consulta "$LIMPA" nekkora "SELECT records_count || '|' || status || '|' || (consolidated_at IS NOT NULL) FROM organization_usage_daily WHERE organization_id = '$O' AND resource_code = 6 AND usage_date = '$HOJE'")" = "2|1|true" ] \
    || falha "C2: reconsolidacao do dia aberto"
recusa_sql "$LIMPA" "C2_guard_categoria" "fechamento/expurgo nao pode misturar organizacao/categoria" "BEGIN; SET LOCAL ROLE nekkora_retention_executor;
    SELECT * FROM public.reconsolidate_usage_day('$O', 6, '$HOJE'); SELECT * FROM public.reconsolidate_usage_day('$O', 5, '$HOJE'); COMMIT;"

echo "   C4: redacao e a unica atualizacao"
como_dono() { printf "BEGIN; SET LOCAL ROLE nekkora_retention_owner; SELECT set_config('nekkora.fn_organization_id', '%s', true);\n%s\nCOMMIT;" "$O" "$1"; }
recusa_sql "$LIMPA" "C4_dono_forma_errada" "so a transicao de expurgo e aceita" "$(como_dono "UPDATE agent_run_events SET step_id = NULL WHERE agent_run_id = '$RUN' AND sequence = 2;")"
recusa_sql "$LIMPA" "C4_dono_muda_hash" "permission denied" "$(como_dono "UPDATE agent_run_events SET content_sha256 = repeat('f', 64) WHERE agent_run_id = '$RUN' AND sequence = 2;")"
recusa_sql "$LIMPA" "C4_dono_dia_aberto" "dia ainda nao fechado" "$(como_dono "UPDATE agent_run_events SET envelope = NULL, step_id = NULL, timestamp_utc = NULL, purged_at = now(),
    purged_by_user_id = '$ADM', purge_run_id = gen_random_uuid() WHERE agent_run_id = '$RUN' AND sequence = 10;")"
recusa_sql "$LIMPA" "C4_ingestao_atualiza" "permission denied" "$(como nekkora_ingestion "$O" "UPDATE agent_run_events SET envelope = NULL WHERE agent_run_id = '$RUN';")"
recusa_sql "$LIMPA" "C4_platform_atualiza" "permission denied" "$(como nekkora_platform "$O" "UPDATE agent_run_events SET envelope = NULL WHERE agent_run_id = '$RUN';")"
recusa_sql "$LIMPA" "C4_executor_expurga" "permission denied for function purge_expired_records" "BEGIN; SET LOCAL ROLE nekkora_retention_executor; SELECT public.purge_expired_records('$O', 3, '$ADM', 10); COMMIT;"

echo "   C3: expurgo da categoria 3"
SHA_ANTES="$(consulta "$LIMPA" nekkora "SELECT string_agg(sequence || ':' || content_sha256 || ':' || event_code || ':' || observed_utc || ':' || received_at, ',' ORDER BY sequence) FROM agent_run_events WHERE agent_run_id = '$RUN'")"
[ "$(consulta "$LIMPA" nekkora "SELECT public.purge_expired_records('$O', 3, '$ADM', 2)")" = 2 ] || falha "C3: lote limitado (2) nao respeitado"
[ "$(consulta "$LIMPA" nekkora "SELECT public.purge_expired_records('$O', 3, '$ADM', 100)")" = 1 ] || falha "C3: segundo lote deveria expurgar o restante elegivel (1)"
[ "$(consulta "$LIMPA" nekkora "SELECT public.purge_expired_records('$O', 3, '$ADM', 100)")" = 0 ] || falha "C3: expurgo repetido"
[ "$(consulta "$LIMPA" nekkora "SELECT string_agg(sequence || ':' || (purged_at IS NOT NULL) || ':' || (envelope IS NULL) || ':' || (step_id IS NULL), ',' ORDER BY sequence) FROM agent_run_events WHERE agent_run_id = '$RUN'")" \
    = "1:true:true:true,2:true:true:true,3:true:true:true,10:false:false:false,11:false:false:false" ] || falha "C3: expurgo fora do elegivel (so os 3 do dia fechado)"
[ "$(consulta "$LIMPA" nekkora "SELECT string_agg(sequence || ':' || content_sha256 || ':' || event_code || ':' || observed_utc || ':' || received_at, ',' ORDER BY sequence) FROM agent_run_events WHERE agent_run_id = '$RUN'")" = "$SHA_ANTES" ] \
    || falha "C3: identidade, content_sha256, classificacao ou instantes mudaram com o expurgo"
[ "$(consulta "$LIMPA" nekkora "SELECT md5(row(r.*)::text) FROM agent_runs r WHERE agent_run_id = '$RUN'")" = "$ANTES_RUN" ] || falha "C3: agent_runs mudou com o expurgo"
[ "$(consulta "$LIMPA" nekkora "SELECT string_agg(records_purged::text, ',' ORDER BY occurred_at) FROM organization_purge_runs WHERE organization_id = '$O' AND category = 3")" = "0,2,1,0" ] \
    || falha "C3: auditoria do expurgo em organization_purge_runs"
[ "$(consulta "$LIMPA" nekkora "SELECT count(DISTINCT purge_run_id) FROM agent_run_events WHERE agent_run_id = '$RUN' AND purged_at IS NOT NULL")" = 2 ] || falha "C3: lotes"
# reenvio do mesmo (run, sequence) depois do expurgo: nao duplica (a identidade ficou; o gateway compara o content_sha256)
passa_sql "$LIMPA" "C3_reenvio" "$(como nekkora_ingestion "$O" "INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source,
    event_code, severity, category, step_id, observed_utc) VALUES $(evento "$RUN" 2 STEP_COMPLETED) ON CONFLICT (agent_run_id, sequence) DO NOTHING;")"
[ "$(consulta "$LIMPA" nekkora "SELECT count(*) || '|' || bool_and(purged_at IS NOT NULL) FROM agent_run_events WHERE agent_run_id = '$RUN' AND sequence = 2")" = "1|true" ] \
    || falha "C3: reenvio depois do expurgo duplicou ou desfez a redacao"
recusa_sql "$LIMPA" "C3_desfaz_redacao" "so a transicao de expurgo e aceita" "$(como_dono "UPDATE agent_run_events SET envelope = '{\"sequence\":1,\"event_code\":\"RUNNING\"}', purged_at = NULL,
    purged_by_user_id = NULL, purge_run_id = NULL WHERE agent_run_id = '$RUN' AND sequence = 1;")"
recusa_sql "$LIMPA" "C3_categoria_invalida" "categoria invalida" "SELECT public.purge_expired_records('$O', 4, '$ADM', 10);"

echo "   C5: contraprovas das invariantes da 026"
awk '/^-- === 8\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M026" > "$SAIDA/C50_inv026.sql"
# a regra "nenhum evento nasce expurgado" vale so na aplicacao (o C3 acabou de expurgar): retirada da extracao
adapta "$SAIDA/C50_inv026.sql" "    IF EXISTS (SELECT 1 FROM public.agent_run_events WHERE purged_at IS NOT NULL) THEN
        RAISE EXCEPTION '026: evento expurgado na aplicacao';
    END IF;" "" > "$SAIDA/C51_inv026.sql"
contra() {   # $1 rotulo, $2 violacao, $3 trecho esperado
    local o rc=0
    o="$({ echo "BEGIN;"; echo "$2"; cat "$SAIDA/C51_inv026.sql"; echo "ROLLBACK;"; } | psqlc "$LIMPA" nekkora 2>&1)" || rc=$?
    echo "$1|rc=$rc|$(echo "$o" | grep -m1 -oE 'ERROR: .*' | cut -c1-160)" >> "$SAIDA/C5_contraprovas.txt"
    [ "$rc" != 0 ] && echo "$o" | grep -qF "$3" || falha "C5 $1: esperado '$3' (rc=$rc: $o)"
}
{ echo "BEGIN;"; cat "$SAIDA/C51_inv026.sql"; echo "ROLLBACK;"; } | psqlc "$LIMPA" nekkora > "$SAIDA/C5_controle.txt" 2>&1 || { cat "$SAIDA/C5_controle.txt"; falha "C5: controle"; }
contra purge_concedido "GRANT EXECUTE ON FUNCTION public.purge_expired_records(uuid, integer, uuid, integer) TO nekkora_retention_executor;" "EXECUTE de purge_expired_records concedido"
contra fechamento_extra "GRANT EXECUTE ON FUNCTION public.close_usage_day(uuid, integer, date) TO nekkora_ingestion;" "EXECUTE de fechamento divergente"
contra definer_gatilho "ALTER FUNCTION public.agent_run_event_received_at() SECURITY DEFINER;" "funcao de gatilho fora do padrao"
contra politica_extra "CREATE POLICY extra ON public.agent_run_events FOR SELECT TO nekkora_portal USING (true);" "politicas de agent_run_events divergentes"
contra update_platform "GRANT UPDATE (envelope) ON public.agent_run_events TO nekkora_platform;" "atualizacao ou exclusao possivel em agent_run_events"
contra dono_alem "GRANT UPDATE (content_sha256) ON public.agent_run_events TO nekkora_retention_owner;" "nekkora_retention_owner com privilegio alem da redacao"
contra gatilho "ALTER TABLE public.agent_run_events DISABLE TRIGGER trg_agent_run_events_received_at;" "gatilhos ligados em agent_run_events"
contra dono_funcao "ALTER FUNCTION public.close_usage_day(uuid, integer, date) OWNER TO migrador_nekkora;" "funcao de manutencao fora do padrao"

# ================================================================== fim
docker rm -f -v "$LIMPA" "$POP" > /dev/null
echo "OK: suite da 026 completa (saidas em $SAIDA)"
