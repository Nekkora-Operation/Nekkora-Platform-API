#!/usr/bin/env bash
# Suite de banco da 023_acessos_convites.sql (candidata). Dois clusters PostgreSQL 16 DESCARTAVEIS e isolados (containers
# proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal nem os ambientes de
# homologacao.
#
# Modelo de aplicacao: o da 016 revisao 5 (db/bootstrap/aplicar_como_migrador.sh), SUPERUSER em 011-013, 016-020, 022 e 023,
# BYPASSRLS so na 014, retirada conferida em 0|0|0; a sec. 19 da 016 roda de novo DEPOIS de tudo.
#
#   A (limpa):    001-023 pelo entrypoint. Invariantes 012, 013, 014 (adaptada), 016, 017 (adaptada: 44 tabelas de tenant e o
#                 aceite na lista da Platform), 019 e 020 (adaptadas: o aceite na lista), 021, 022 e 023 DEPOIS da retirada.
#   B (populada): 001-012 + massa, 013-015 + massa MFA, 016-022, mais a massa de ACESSO anterior a 023 (convites no modelo
#                 da 002: aberto, aceito e revogado; atribuicoes de organizacao e de workspace; concessoes pendente e ativa).
#     B0 - convites abertos DUPLICADOS para o mesmo e-mail: a 023 recusa pela pre-condicao, nada muda.
#     B1 - 023 SEM a janela: recusada por privilegio, nada muda.
#     B2 - 023 com a janela: dados de public e ops identicos (todas as colunas anteriores); linhas antigas validas no modelo
#          novo; diff de catalogo de public EXATAMENTE o previsto (esperado/B23_diff_esperado.txt); catalogo = instalacao
#          limpa A; invariantes; marcador versao=023.
#     B3 - reaplicar a 023 e recusado pela pre-condicao; nada muda.
#   C (comportamento no banco, papeis reais):
#     C1 - convites pela Platform: emissor sem org.manage, papel sem autoridade, prazo acima de 7 dias, convite externo ->
#          recusados; emissao autorizada passa; aceite fora da funcao recusado; aceito congelado; sem DELETE nem TRUNCATE;
#          duplicado aberto recusado pelo indice;
#     C2 - papeis pela Platform: autoatribuicao, autor sem autoridade, WORKSPACE_ADMIN fora do proprio workspace ou com papel
#          de administracao, revogacao sem autoridade -> 42501; WORKSPACE_ADMIN no proprio workspace e ORG_ADMIN passam;
#          superusuario nao e barrado; ultimo ORG_ADMIN continua protegido (P0001);
#     C3 - concessoes: aprovacao, revisao e revogacao por quem nao tem external_access.manage -> 42501; pendente revogada
#          sem aprovacao passa (correcao da 003); ATIVA sem aprovacao continua recusada;
#     C4 - auditoria: Platform sem UPDATE, DELETE e TRUNCATE; RLS: so a organizacao do contexto;
#     C5 - identity_accept_invitation: so a Platform executa (Portal, provisionamento e identidade direta: 42501); o aceite
#          completo (caminhos e recusas) e coberto pelos testes Java (AccessInvitationsIT);
#     C6 - contraprovas das invariantes da 023 (cada uma em BEGIN ... ROLLBACK): controle passa; recusam UPDATE na auditoria,
#          EXECUTE do aceite para outro papel, gatilho desligado e funcao de autoridade SECURITY DEFINER.
#
# Uso: db/tests/acessos-023/run.sh   (Windows/Git Bash: ver README da suite)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
INSTANTANEO="$REPO/db/tests/relogio-018/sql/c_instantaneo_gera.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018 019 020 022 023}"
PROJETO="${TESTE_PROJETO:-nekkora-a023}"
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
for n in $(seq -w 1 23); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
M023="$SAIDA/mig/023_acessos_convites.sql"
[ -f "$M023" ] || falha "023 ausente"
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
invariantes() {   # $1 container, $2 nome; sempre DEPOIS da retirada
    awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/014_mfa_sessoes.sql" > "$SAIDA/_inv014.sql"
    awk '/^-- === 12\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/017_identidade_cadastro.sql" > "$SAIDA/_inv017.sql"
    awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/019_identidade_regeneracao_codigos.sql" > "$SAIDA/_inv019.sql"
    awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/020_identidade_recuperacao_senha.sql" > "$SAIDA/_inv020.sql"
    # 017: as adaptacoes ja usadas desde a 020 (lista com a recuperacao e a regeneracao) e as da 023 (44 tabelas; o aceite)
    adapta "$SAIDA/_inv017.sql" "        'identity_password_change(text,text,text), '" \
        "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '
        'identity_recovery_codes_regenerate(text,text[]), '" > "$SAIDA/_inv017b.sql"
    adapta "$SAIDA/_inv017b.sql" "IF v_n <> 43 THEN RAISE EXCEPTION 'invariante 1: % tabelas com organization_id (esperadas 43)', v_n; END IF;" \
        "IF v_n <> 44 THEN RAISE EXCEPTION 'invariante 1: % tabelas com organization_id (esperadas 44: 43 + access_events da 023)', v_n; END IF;" > "$SAIDA/_inv017c.sql"
    adapta "$SAIDA/_inv019.sql" "        'identity_password_change(text,text,text), '" \
        "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '" > "$SAIDA/_inv019b.sql"
    adapta "$SAIDA/_inv019b.sql" "'CHECK (((event_type >= 1) AND (event_type <= 15)))'" "'CHECK (((event_type >= 1) AND (event_type <= 17)))'" > "$SAIDA/_inv019c.sql"
    { awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql"
      awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/013_portal_externo.sql"
      adapta "$SAIDA/_inv014.sql" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname AND p.policyname NOT IN ('ops_function_context', 'identity_function_context')) <> 1"
      awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql"
      adapta "$SAIDA/_inv017c.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS"
      adapta "$SAIDA/_inv019c.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS"
      adapta "$SAIDA/_inv020.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS"
      awk '/^-- === 5\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/021_correio_fila.sql"
      awk '/^-- === 10\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/022_ativacao_organizacoes.sql"
      awk '/^-- === 11\. /{f=1} f && /^COMMIT;/{exit} f' "$M023"
    } > "$SAIDA/${2}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${2}_invariantes.sql")" = 10 ] || falha "extracao dos invariantes ($2) falhou"
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
# como a Platform: papel real e contexto da organizacao
plat() { printf "SET ROLE nekkora_platform; SELECT set_config('nekkora.organization_id', '%s', false);\n%s" "$1" "$2"; }

# ================================================================== A. instalacao limpa 001-023
echo ">> A: instalacao limpa 001-023 ($LIMPA)"
sobe "$LIMPA" 23 023
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" > "$SAIDA/ambiente.txt"
grep -q "aplicada versao=023 elevada=SUPERUSER" "$SAIDA/entrypoint_$LIMPA.txt" || falha "023 sem a janela de SUPERUSER"
grep -q "invariantes_016_pos_retirada=OK" "$SAIDA/entrypoint_$LIMPA.txt" || falha "016 §19 pos-retirada ausente (depois da 023)"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=023" ] || falha "marcador da instalacao limpa"
invariantes "$LIMPA" A01
lista "$LIMPA" > "$SAIDA/A02_catalogo_023.txt"

# ================================================================== B. base populada 022 -> 023
echo ">> B: base populada 001-022 -> 023 ($POP)"
sobe "$POP" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" 013 015 "$SAIDA/B02_013_015.txt" || { cat "$SAIDA/B02_013_015.txt"; falha "013-015 na populada"; }
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
modelo "$POP" 016 022 "$SAIDA/B04_016_022.txt" "011 012 013 016 017 018 019 020 022" || { cat "$SAIDA/B04_016_022.txt"; falha "016-022 na populada"; }
consulta "$POP" nekkora "CREATE ROLE backup_nekkora LOGIN BYPASSRLS; GRANT pg_read_all_data TO backup_nekkora" > /dev/null
# massa de ACESSO anterior a 023 (superusuario, no modelo da 002/003): organizacao com ORG_ADMIN pelo provisionamento real,
# um membro, workspace com cliente, conta externa; convites aberto, aceito e revogado; papeis de organizacao e de workspace;
# concessoes pendente e ativa
O="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"; ADM="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
ADMM="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"; MEM="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
MEMM="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"; CLI="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
WS="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"; EXT="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
CM="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"; WS2="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
psqlc "$POP" nekkora > "$SAIDA/B05_massa_acesso.txt" 2>&1 <<SQL || { cat "$SAIDA/B05_massa_acesso.txt"; falha "massa de acesso"; }
BEGIN;
SELECT set_config('nekkora.organization_id', '$O', true);
INSERT INTO organizations (organization_id, name, status) VALUES ('$O', 'Org acessos', 1);
INSERT INTO clients (client_id, organization_id, name, status) VALUES ('$CLI', '$O', 'Cliente', 1);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES ('$WS', '$O', '$CLI', 'WS cliente', 1), ('$WS2', '$O', NULL, 'WS interno', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
  ('$ADM', '$O', 1, 'adm-acessos@o.test', now(), 2, 'Admin'), ('$MEM', '$O', 1, 'membro-acessos@o.test', now(), 2, 'Membro'),
  ('$EXT', '$O', 2, 'ext-acessos@c.test', now(), 2, 'Externo');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('$ADMM', '$O', '$ADM', 1, 2), ('$MEMM', '$O', '$MEM', 1, 2);
INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status) VALUES ('$CM', '$O', '$CLI', '$EXT', 2, 2);
COMMIT;
SET ROLE nekkora_provisioning;
SELECT provision_first_org_admin('$O', '$ADMM', '$ADM', 'massa-023', gen_random_uuid()) IS NOT NULL;
RESET ROLE;
BEGIN;
SELECT set_config('nekkora.organization_id', '$O', true);
INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id)
SELECT '$O', '$WS2', '$MEMM', role_id, 2, 1, '$ADMM' FROM roles WHERE code = 'OPERATOR';
INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at) VALUES
  ('$O', 1, 'aberto@o.test', '$ADM', md5('a1') || md5('a2'), now() + interval '20 days');
INSERT INTO identity_invitations (organization_id, invited_account_type, client_id, email_normalized, issued_by, token_hash, expires_at, revoked_at) VALUES
  ('$O', 2, '$CLI', 'revogado@c.test', '$ADM', md5('c1') || md5('c2'), now() + interval '1 day', now());
INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, accepted_at, accepted_user_id) VALUES
  ('$O', 1, 'membro-acessos@o.test', '$ADM', md5('d1') || md5('d2'), now() + interval '1 day', now(), '$MEM');
INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, starts_at)
VALUES ('$O', '$CLI', '$CM', '$WS', 1, now());
COMMIT;
SQL
grep -q "^t$" "$SAIDA/B05_massa_acesso.txt" || falha "massa: bootstrap do ORG_ADMIN"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM identity_invitations")" = 3 ] || falha "massa: 3 convites antigos (aberto, revogado externo, aceito)"

echo "   B0: convites abertos duplicados"
consulta "$POP" nekkora "INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at)
    VALUES ('$O', 1, 'aberto@o.test', '$ADM', md5('e1') || md5('e2'), now() + interval '20 days')" > /dev/null
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B06_instantaneo.sql"
psqlc "$POP" nekkora < "$SAIDA/B06_instantaneo.sql" > "$SAIDA/B06_dados_022_dup.txt"
if modelo "$POP" 023 023 "$SAIDA/B00_023_duplicados.txt"; then falha "B0: 023 aplicada com convites duplicados"; fi
grep -qF "023: ha convites abertos duplicados" "$SAIDA/B00_023_duplicados.txt" || { cat "$SAIDA/B00_023_duplicados.txt"; falha "B0: recusa diferente da pre-condicao"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B0: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B06_instantaneo.sql" > "$SAIDA/B01b_dados.txt"
diff "$SAIDA/B06_dados_022_dup.txt" "$SAIDA/B01b_dados.txt" > /dev/null || falha "B0: dados alterados"
consulta "$POP" nekkora "UPDATE identity_invitations SET revoked_at = now() WHERE token_hash = md5('e1') || md5('e2')" > /dev/null

psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B07_instantaneo.sql"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B07_dados_022.txt"
lista "$POP" > "$SAIDA/B07_catalogo_022.txt"

echo "   B1: 023 sem a janela"
if modelo "$POP" 023 023 "$SAIDA/B10_023_sem_janela.txt" "011 012 013 016 017 018 019 020 022"; then falha "B1: 023 aplicada sem elevacao"; fi
grep -qiE "permission denied|must be able to SET ROLE|must be owner|must be member" "$SAIDA/B10_023_sem_janela.txt" || { cat "$SAIDA/B10_023_sem_janela.txt"; falha "B1: recusa diferente da de privilegio"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B1: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B11_dados.txt"
diff "$SAIDA/B07_dados_022.txt" "$SAIDA/B11_dados.txt" > /dev/null || falha "B1: dados alterados"
lista "$POP" > "$SAIDA/B11_catalogo.txt"
diff "$SAIDA/B07_catalogo_022.txt" "$SAIDA/B11_catalogo.txt" > /dev/null || falha "B1: catalogo alterado"

echo "   B2: 023 com a janela"
modelo "$POP" 023 023 "$SAIDA/B20_023.txt" || { cat "$SAIDA/B20_023.txt"; falha "023 na populada"; }
grep -q "aplicada versao=023 elevada=SUPERUSER" "$SAIDA/B20_023.txt" || falha "B2: 023 sem janela"
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B2: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=023" ] || falha "B2: marcador"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B21_dados_023.txt"
diff "$SAIDA/B07_dados_022.txt" "$SAIDA/B21_dados_023.txt" > "$SAIDA/B21_diff_dados.txt" || falha "B2: a 023 alterou dados de public/ops (ver B21_diff_dados.txt)"
consulta "$POP" nekkora "SELECT email_normalized || '|' || (issued_by_membership_id IS NULL) || '|' || (role_id IS NULL) || '|' || coalesce(revoke_reason::text, '-')
    FROM identity_invitations ORDER BY email_normalized, created_at" > "$SAIDA/B22_convites_antigos.txt"
[ "$(grep -c '|true|true|-$' "$SAIDA/B22_convites_antigos.txt")" = "$(wc -l < "$SAIDA/B22_convites_antigos.txt" | tr -d ' ')" ] \
    || { cat "$SAIDA/B22_convites_antigos.txt"; falha "B2: convites antigos sem as colunas novas nulas"; }
lista "$POP" > "$SAIDA/B23_catalogo_023.txt"
diff "$SAIDA/B07_catalogo_022.txt" "$SAIDA/B23_catalogo_023.txt" > "$SAIDA/B23_diff_catalogo.txt" || true
grep -E '^[<>]' "$SAIDA/B23_diff_catalogo.txt" | sed -E 's/\|md5=[0-9a-f]+/|md5=.../' | sort > "$SAIDA/B23_diff_resumo.txt"
if [ -f "$AQUI/esperado/B23_diff_esperado.txt" ]; then
    sort "$AQUI/esperado/B23_diff_esperado.txt" > "$SAIDA/_b.txt"
    diff "$SAIDA/_b.txt" "$SAIDA/B23_diff_resumo.txt" > "$SAIDA/B23_fora_do_previsto.txt" || { cat "$SAIDA/B23_fora_do_previsto.txt"; falha "B2: catalogo 022 -> 023 fora do previsto"; }
else
    falha "B2: esperado/B23_diff_esperado.txt ausente (resumo gerado em $SAIDA/B23_diff_resumo.txt para revisao)"
fi
diff "$SAIDA/A02_catalogo_023.txt" "$SAIDA/B23_catalogo_023.txt" > "$SAIDA/B24_diff_limpa.txt" || falha "B2: catalogo da atualizada difere da instalacao limpa (ver B24)"
invariantes "$POP" B26

echo "   B3: reaplicacao da 023"
if psqlc "$POP" nekkora < "$M023" > "$SAIDA/B30_reaplica.txt" 2>&1; then falha "B3: 023 reaplicada"; fi
grep -qF "023: objetos dos convites ja existem" "$SAIDA/B30_reaplica.txt" || { cat "$SAIDA/B30_reaplica.txt"; falha "B3: recusa diferente da pre-condicao"; }
lista "$POP" > "$SAIDA/B31_catalogo.txt"
diff "$SAIDA/B23_catalogo_023.txt" "$SAIDA/B31_catalogo.txt" > /dev/null || falha "B3: catalogo alterado"

# ================================================================== C. comportamento no banco (papeis reais)
echo ">> C: comportamento ($POP)"
: > "$SAIDA/C_recusas.txt"; : > "$SAIDA/C_passam.txt"
VIEWER="$(consulta "$POP" nekkora "SELECT role_id FROM roles WHERE code = 'VIEWER'")"
OPERATOR="$(consulta "$POP" nekkora "SELECT role_id FROM roles WHERE code = 'OPERATOR'")"
WSADMIN="$(consulta "$POP" nekkora "SELECT role_id FROM roles WHERE code = 'WORKSPACE_ADMIN'")"
ORGADMIN="$(consulta "$POP" nekkora "SELECT role_id FROM roles WHERE code = 'ORG_ADMIN'")"
TOK() { head -c 32 /dev/urandom | sha256sum | cut -c1-64; }
INV="INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, issued_by_membership_id, role_id, role_scope, workspace_id)"

# C1 convites
recusa_sql "$POP" "C1 emissor sem org.manage" "emissor sem autoridade para o convite" "$(plat "$O" "$INV VALUES ('$O', 1, 'c1a@o.test', '$MEM', '$(TOK)', now() + interval '1 day', '$MEMM', NULL, NULL, NULL);")"
recusa_sql "$POP" "C1 emissor de outra conta" "emissor sem autoridade para o convite" "$(plat "$O" "$INV VALUES ('$O', 1, 'c1b@o.test', '$MEM', '$(TOK)', now() + interval '1 day', '$ADMM', NULL, NULL, NULL);")"
recusa_sql "$POP" "C1 prazo acima de 7 dias" "prazo do convite acima de 7 dias" "$(plat "$O" "$INV VALUES ('$O', 1, 'c1c@o.test', '$ADM', '$(TOK)', now() + interval '8 days', '$ADMM', NULL, NULL, NULL);")"
recusa_sql "$POP" "C1 convite externo" "convite de conta externa fora do modelo atual" "$(plat "$O" "INSERT INTO identity_invitations (organization_id, invited_account_type, client_id, email_normalized, issued_by, token_hash, expires_at, issued_by_membership_id) VALUES ('$O', 2, '$CLI', 'c1d@c.test', '$ADM', '$(TOK)', now() + interval '1 day', '$ADMM');")"
recusa_sql "$POP" "C1 papel de workspace sem workspace" "identity_invitations_role_check" "$(plat "$O" "$INV VALUES ('$O', 1, 'c1e@o.test', '$ADM', '$(TOK)', now() + interval '1 day', '$ADMM', '$VIEWER', 2, NULL);")"
ACEITAVEL="$(TOK)"
passa_sql "$POP" "C1 emissao autorizada com papel de workspace" "$(plat "$O" "$INV VALUES ('$O', 1, 'c1ok@o.test', '$ADM', '$ACEITAVEL', now() + interval '7 days', '$ADMM', '$VIEWER', 2, '$WS2');")"
recusa_sql "$POP" "C1 duplicado aberto" "ux_identity_invitations_open" "$(plat "$O" "$INV VALUES ('$O', 1, 'c1ok@o.test', '$ADM', '$(TOK)', now() + interval '1 day', '$ADMM', NULL, NULL, NULL);")"
recusa_sql "$POP" "C1 aceite fora da funcao" "aceite de convite so pela funcao de aceite" "$(plat "$O" "UPDATE identity_invitations SET accepted_at = now(), accepted_user_id = '$MEM' WHERE token_hash = '$ACEITAVEL';")"
recusa_sql "$POP" "C1 revogacao sem autoridade" "revogacao de convite sem autoridade" "$(plat "$O" "UPDATE identity_invitations SET revoked_at = now(), revoke_reason = 1, revoked_by_membership_id = '$MEMM' WHERE token_hash = '$ACEITAVEL';")"
recusa_sql "$POP" "C1 origem imutavel" "origem do convite e imutavel" "$(plat "$O" "UPDATE identity_invitations SET email_normalized = 'outro@o.test', revoked_at = now(), revoke_reason = 1, revoked_by_membership_id = '$ADMM' WHERE token_hash = '$ACEITAVEL';")"
passa_sql "$POP" "C1 revogacao autorizada" "$(plat "$O" "UPDATE identity_invitations SET revoked_at = now(), revoke_reason = 1, revoked_by_membership_id = '$ADMM' WHERE token_hash = '$ACEITAVEL';")"
recusa_sql "$POP" "C1 revogado congelado" "convite aceito ou revogado e imutavel" "$(plat "$O" "UPDATE identity_invitations SET revoke_reason = 2 WHERE token_hash = '$ACEITAVEL';")"
recusa_sql "$POP" "C1 DELETE" "exclusao nao permitida em identity_invitations" "DELETE FROM identity_invitations WHERE token_hash = '$ACEITAVEL';"
recusa_sql "$POP" "C1 TRUNCATE (com a auditoria que referencia os convites)" "truncate nao permitido em" "TRUNCATE identity_invitations CASCADE;"

# C2 papeis
WSA="INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id)"
recusa_sql "$POP" "C2 autoatribuicao" "autoatribuicao de papel recusada" "$(plat "$O" "$WSA VALUES ('$O', '$WS2', '$MEMM', '$VIEWER', 2, 1, '$MEMM');")"
recusa_sql "$POP" "C2 OPERATOR sem autoridade de atribuir" "atribuicao de papel sem autoridade" "$(plat "$O" "$WSA VALUES ('$O', '$WS2', '$ADMM', '$VIEWER', 2, 1, '$MEMM');")"
# WORKSPACE_ADMIN do membro em WS (cliente), conferido no WS2 e com papel de administracao
consulta "$POP" nekkora "BEGIN; SELECT set_config('nekkora.organization_id', '$O', true); $WSA VALUES ('$O', '$WS', '$MEMM', '$WSADMIN', 2, 1, '$ADMM'); COMMIT;" > /dev/null
OUTRO="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"; OUTROM="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
consulta "$POP" nekkora "BEGIN; SELECT set_config('nekkora.organization_id', '$O', true);
    INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('$OUTRO', '$O', 1, 'outro-acessos@o.test', now(), 2, 'Outro');
    INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('$OUTROM', '$O', '$OUTRO', 1, 2); COMMIT;" > /dev/null
passa_sql "$POP" "C2 WORKSPACE_ADMIN atribui VIEWER no proprio workspace" "$(plat "$O" "$WSA VALUES ('$O', '$WS', '$OUTROM', '$VIEWER', 2, 1, '$MEMM');")"
recusa_sql "$POP" "C2 WORKSPACE_ADMIN fora do proprio workspace" "atribuicao de papel sem autoridade" "$(plat "$O" "$WSA VALUES ('$O', '$WS2', '$OUTROM', '$VIEWER', 2, 1, '$MEMM');")"
recusa_sql "$POP" "C2 WORKSPACE_ADMIN atribui WORKSPACE_ADMIN" "atribuicao de papel sem autoridade" "$(plat "$O" "$WSA VALUES ('$O', '$WS', '$OUTROM', '$WSADMIN', 2, 1, '$MEMM');")"
recusa_sql "$POP" "C2 WORKSPACE_ADMIN atribui ORG_ADMIN" "atribuicao de papel sem autoridade" "$(plat "$O" "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) VALUES ('$O', '$OUTROM', '$ORGADMIN', 1, 1, '$MEMM');")"
recusa_sql "$POP" "C2 revogacao sem autoridade" "revogacao de papel sem autoridade" "$(plat "$O" "UPDATE workspace_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '$OUTROM' WHERE membership_id = '$MEMM' AND workspace_id = '$WS2' AND status = 1;")"
passa_sql "$POP" "C2 ORG_ADMIN atribui ORG_ADMIN" "$(plat "$O" "BEGIN; INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) VALUES ('$O', '$OUTROM', '$ORGADMIN', 1, 1, '$ADMM'); ROLLBACK;")"
passa_sql "$POP" "C2 superusuario nao e barrado" "BEGIN; SELECT set_config('nekkora.organization_id', '$O', true); $WSA VALUES ('$O', '$WS2', '$OUTROM', '$VIEWER', 2, 1, '$OUTROM'); ROLLBACK;"
recusa_sql "$POP" "C2 ultimo ORG_ADMIN (003)" "ficaria sem ORG_ADMIN efetivo" "$(plat "$O" "UPDATE organization_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '$ADMM' WHERE membership_id = '$ADMM' AND status = 1;")"

# C3 concessoes
G="$(consulta "$POP" nekkora "SELECT grant_id FROM client_workspace_grants WHERE organization_id = '$O' AND status = 1")"
recusa_sql "$POP" "C3 aprovacao sem external_access.manage" "ato sobre concessao externa sem autoridade" "$(plat "$O" "INSERT INTO client_workspace_grant_approvals (organization_id, grant_id, approval_type, approved_by_membership_id, new_starts_at) VALUES ('$O', '$G', 1, '$MEMM', now());")"
recusa_sql "$POP" "C3 revisao sem external_access.manage" "ato sobre concessao externa sem autoridade" "$(plat "$O" "INSERT INTO client_workspace_grant_reviews (organization_id, grant_id, reviewed_by_membership_id, decision) VALUES ('$O', '$G', '$MEMM', 1);")"
recusa_sql "$POP" "C3 aprovador sem permissao na concessao" "ato sobre concessao externa sem autoridade" "$(plat "$O" "UPDATE client_workspace_grants SET status = 2, approved_by_membership_id = '$MEMM', approved_at = now(), next_review_at = now() + interval '30 days' WHERE grant_id = '$G';")"
recusa_sql "$POP" "C3 ATIVA sem aprovador pela Platform (gatilho)" "ato sobre concessao externa sem autoridade" "$(plat "$O" "UPDATE client_workspace_grants SET status = 2, next_review_at = now() + interval '30 days' WHERE grant_id = '$G';")"
recusa_sql "$POP" "C3 ATIVA sem aprovacao, superusuario (restricao da 003 continua)" "client_workspace_grants_approval_check" "BEGIN; SELECT set_config('nekkora.organization_id', '$O', true); UPDATE client_workspace_grants SET status = 2, next_review_at = now() + interval '30 days' WHERE grant_id = '$G'; ROLLBACK;"
recusa_sql "$POP" "C3 revogacao sem permissao" "ato sobre concessao externa sem autoridade" "$(plat "$O" "UPDATE client_workspace_grants SET status = 4, revoked_at = now(), revoked_by_membership_id = '$MEMM' WHERE grant_id = '$G';")"
# reativacao SUSPENSA -> ATIVA: o aprovador gravado tem de ter a permissao, mesmo sem mudar (fixtures do superusuario: duas
# concessoes suspensas em workspaces novos do cliente, uma aprovada por quem nao tem a permissao e outra pelo ORG_ADMIN)
WS3="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"; WS4="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
G3="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"; G4="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
psqlc "$POP" nekkora > "$SAIDA/C30_suspensas.txt" 2>&1 <<SQL || { cat "$SAIDA/C30_suspensas.txt"; falha "C3: fixture das suspensas"; }
BEGIN;
SELECT set_config('nekkora.organization_id', '$O', true);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES ('$WS3', '$O', '$CLI', 'WS 3', 1), ('$WS4', '$O', '$CLI', 'WS 4', 1);
INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, workspace_id, status, approved_by_membership_id, approved_at, starts_at, next_review_at)
VALUES ('$G3', '$O', '$CLI', '$CM', '$WS3', 3, '$MEMM', now(), now(), now() + interval '30 days'),
       ('$G4', '$O', '$CLI', '$CM', '$WS4', 3, '$ADMM', now(), now(), now() + interval '30 days');
COMMIT;
SQL
recusa_sql "$POP" "C3 reativacao com aprovador gravado sem permissao" "ato sobre concessao externa sem autoridade" "$(plat "$O" "UPDATE client_workspace_grants SET status = 2 WHERE grant_id = '$G3';")"
passa_sql "$POP" "C3 reativacao com aprovador gravado ORG_ADMIN" "$(plat "$O" "UPDATE client_workspace_grants SET status = 2 WHERE grant_id = '$G4';")"
passa_sql "$POP" "C3 pendente revogada sem aprovacao (correcao da 003)" "$(plat "$O" "UPDATE client_workspace_grants SET status = 4, revoked_at = greatest(now(), created_at), revoked_by_membership_id = '$ADMM' WHERE grant_id = '$G';")"

# C4 auditoria
passa_sql "$POP" "C4 Platform grava auditoria" "$(plat "$O" "INSERT INTO access_events (organization_id, event_type, actor_membership_id, actor_user_id, target_user_id, correlation_id) VALUES ('$O', 8, '$ADMM', '$ADM', '$OUTRO', gen_random_uuid());")"
recusa_sql "$POP" "C4 UPDATE na auditoria" "permission denied for table access_events" "$(plat "$O" "UPDATE access_events SET event_type = 2;")"
recusa_sql "$POP" "C4 DELETE na auditoria" "permission denied for table access_events" "$(plat "$O" "DELETE FROM access_events;")"
recusa_sql "$POP" "C4 TRUNCATE na auditoria" "permission denied for table access_events" "$(plat "$O" "TRUNCATE access_events;")"
recusa_sql "$POP" "C4 superusuario tambem nao altera" "atualizacao nao permitida em access_events" "UPDATE access_events SET event_type = 2;"
OUTRA_ORG="$(consulta "$POP" nekkora "SELECT organization_id FROM organizations WHERE organization_id <> '$O' ORDER BY organization_id LIMIT 1")"
[ "$(plat "$OUTRA_ORG" "SELECT count(*) FROM access_events;" | psqlc "$POP" nekkora | tail -1)" = 0 ] || falha "C4: RLS da auditoria"
[ "$(plat "$O" "SELECT count(*) FROM access_events;" | psqlc "$POP" nekkora | tail -1)" = 1 ] || falha "C4: leitura na propria organizacao"
recusa_sql "$POP" "C4 gravar em outra organizacao" "row-level security" "$(plat "$O" "INSERT INTO access_events (organization_id, event_type, actor_membership_id, actor_user_id, correlation_id) VALUES ('$OUTRA_ORG', 8, '$ADMM', '$ADM', gen_random_uuid());")"

# C5 aceite: so a Platform executa
for p in nekkora_portal nekkora_provisioning nekkora_ingestion; do
    recusa_sql "$POP" "C5 $p nao executa o aceite" "permission denied for function identity_accept_invitation" "SET ROLE $p; SELECT public.identity_accept_invitation('x', 'y', '$O');"
done
[ "$(printf "SET ROLE nekkora_platform;
SELECT public.identity_accept_invitation('sessao-invalida', 'token-invalido', '%s');
" "$O" | psqlc "$POP" nekkora | tail -1)" = INVALIDO ]     || falha "C5: a Platform executa o aceite e a sessao invalida volta INVALIDO"
echo "C5 Platform executa o aceite (sessao invalida -> INVALIDO, sem efeito)|rc=0" >> "$SAIDA/C_passam.txt"

echo "   C6: contraprovas das invariantes da 023"
awk '/^-- === 11\. /{f=1} f && /^COMMIT;/{exit} f' "$M023" > "$SAIDA/C60_inv023.sql"
[ "$(grep -c '^DO \$\$' "$SAIDA/C60_inv023.sql")" = 1 ] || falha "C6: extracao da sec. 11 da 023"
: > "$SAIDA/C61_contraprovas.txt"
c6() {   # $1 rotulo, $2 alteracao (vazia = controle), $3 trecho da recusa (vazio = deve passar)
    local o rc=0 m
    o="$({ echo "BEGIN;"; printf '%s\n' "$2"; cat "$SAIDA/C60_inv023.sql"; echo "ROLLBACK;"; } | psqlc "$POP" nekkora 2>&1)" || rc=$?
    m="$(echo "$o" | grep -m1 -o '023: .*' || echo '-')"
    echo "$1|rc=$rc|$m" >> "$SAIDA/C61_contraprovas.txt"
    if [ -z "$3" ]; then [ "$rc" = 0 ] || falha "C6 $1: controle recusado ($o)"; return 0; fi
    [ "$rc" != 0 ] && [[ "$m" == *"$3"* ]] || falha "C6 $1: esperada a recusa '$3' (rc=$rc: $o)"
}
c6 controle "" ""
c6 update_na_auditoria "GRANT UPDATE ON access_events TO nekkora_platform;" "023: alteracao possivel em access_events: nekkora_platform"
c6 execute_do_aceite "GRANT EXECUTE ON FUNCTION identity_accept_invitation(text,text,uuid) TO nekkora_portal;" "023: EXECUTE do aceite divergente"
c6 gatilho_desligado "ALTER TABLE organization_role_assignments DISABLE TRIGGER trg_org_role_assignments_authority;" "023: gatilhos de acesso ausentes ou desligados"
c6 autoridade_security_definer "ALTER FUNCTION access_can_manage_role(uuid,uuid,uuid,uuid) SECURITY DEFINER;" "023: funcao de autoridade fora do padrao"
c6 politica_extra "CREATE POLICY extra ON access_events FOR SELECT TO nekkora_portal USING (true);" "023: politicas de access_events divergentes"
c6 platform_liga_identidade "GRANT INSERT ON identity_users TO nekkora_platform;" "023: Platform com escrita em identity_users"
{
    echo "A: 23 migrations (001-023); janelas SUPERUSER com retirada 0|0|0: $(grep -c 'SUPERUSER revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt") (023 com janela); sec. 19 da 016 depois de tudo: OK; invariantes 012/013/014(adaptada)/016/017(adaptada)/019(adaptada)/020(adaptada)/021/022/023 DEPOIS da retirada ($(cat "$SAIDA/A01_estado_migrador.txt")): OK; marcador versao=023"
    echo "B: $(cat "$SAIDA/B01_massa_001_012.txt"); massa de acesso: $(consulta "$POP" nekkora "SELECT count(*) FROM identity_invitations WHERE issued_by_membership_id IS NULL") convites antigos, papeis de organizacao e de workspace, concessoes"
    echo "B0: convites abertos duplicados -> 023 recusada pela pre-condicao, dados inalterados"
    echo "B1: 023 sem janela recusada por privilegio; dados e catalogo inalterados"
    echo "B2: dados de public/ops identicos (colunas anteriores); convites antigos validos com as colunas novas nulas; catalogo 022->023 = $(wc -l < "$SAIDA/B23_diff_resumo.txt" | tr -d ' ') linhas previstas; = instalacao limpa; invariantes ($(cat "$SAIDA/B26_estado_migrador.txt")): OK"
    echo "B3: reaplicacao recusada pela pre-condicao"
    echo "C: $(grep -c . "$SAIDA/C_recusas.txt") recusas e $(grep -c . "$SAIDA/C_passam.txt") controles positivos conferidos (convites, papeis, concessoes, auditoria, aceite)"
    echo "C6: $(grep -c . "$SAIDA/C61_contraprovas.txt") casos das invariantes da 023 (controle e 6 contraprovas): todos como esperado"
} | tee "$SAIDA/resumo.txt"
rm -f "$SAIDA/_b.txt"
docker rm -f -v "$LIMPA" "$POP" > /dev/null
echo "saidas: $SAIDA"
