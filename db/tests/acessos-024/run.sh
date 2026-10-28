#!/usr/bin/env bash
# Suite de banco da 024_acessos_decisoes_reset_vinculado.sql (candidata). Dois clusters PostgreSQL 16 DESCARTAVEIS e isolados
# (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal nem os
# ambientes de homologacao.
#
# Modelo de aplicacao: o da 016 revisao 5 (db/bootstrap/aplicar_como_migrador.sh), SUPERUSER em 011-013, 016-020, 022, 023 e
# 024, BYPASSRLS so na 014, retirada conferida em 0|0|0; a sec. 19 da 016 roda de novo DEPOIS de tudo.
#
#   A (limpa):    001-024 pelo entrypoint. Invariantes 012, 013, 014 (adaptada), 016, 017/019/020 (adaptadas: o aceite e o
#                 fechamento por perda de autoridade na lista da Platform; 44 tabelas de tenant), 021, 022, 023 e 024 DEPOIS da
#                 retirada.
#   B (populada): 001-012 + massa, 013-015 + massa MFA, 016-022, 023, as restricoes NOT BETWEEN da 023 na forma em que um
#                 dump as restaura (base restaurada), mais a massa de ACESSO no modelo da 023 (convites
#                 aberto valido, aberto de emissor SEM autoridade, aceito e revogado; concessoes pendente, ativa e suspensa
#                 anteriores a 024; eventos de acesso).
#     B0 - 024 sobre a base em 022 (sem a 023): recusada pela pre-condicao, nada muda.
#     B1 - 024 SEM a janela: recusada por privilegio, nada muda.
#     B1b - 024 com a janela, falhando DEPOIS do fechamento dos convites (gatilho homonimo criado por fora): o fechamento
#          volta com a transacao; nada muda.
#     B2 - 024 com a janela: dados de public e ops identicos (todas as colunas anteriores) EXCETO o previsto: so os convites
#          abertos de emissor sem autoridade ficam revogados com o motivo 4 (revoked_at e revoke_reason; mais nada);
#          linhas antigas validas no modelo novo (colunas novas nulas); diff de catalogo de public EXATAMENTE o previsto
#          (esperado/B24_diff_esperado.txt); catalogo = instalacao limpa A; invariantes; marcador versao=024.
#     B3 - reaplicar a 024 e recusado pela pre-condicao; nada muda.
#   C (comportamento no banco, papeis reais):
#     C1 - D-CONV-4: papel do emissor revogado pela Platform -> convite aberto revogado no ato (motivo 4); papel devolvido
#          -> o convite continua revogado; motivo 4 com o emissor ainda autorizado ou com revogador -> 42501;
#     C2 - D-GRANT-1: concessao sem solicitante, sem a proxima revisao pedida, ja ativa, ou com solicitante sem a permissao
#          -> 42501; aprovacao INITIAL pelo solicitante -> 42501; concessao anterior a 024 (sem solicitante) nao e aprovada;
#          aprovacao por outra pessoa com os valores pedidos passa;
#     C3 - D-GRANT-1a: valores pedidos da pendente imutaveis; extensao sem pedido (revisao, prazo, remocao do prazo, inicio,
#          reativacao) -> 42501; reducao passa; pedido que muda a validade -> 42501; RENEWAL pelo solicitante, com outro
#          valor, ou aplicacao de outro valor -> 42501; RENEWAL de outra pessoa com os valores pedidos passa e consome o
#          pedido; reducao descarta o pedido pendente;
#     C4 - reset vinculado: ops.alvo_inelegivel devolve CONTA_VINCULADA para conta vinculada (controle: conta nao vinculada
#          nao); identity_user_is_linked so pelo dono da 016 (Platform, Portal e provisionamento: 42501); o dono da 016
#          continua sem SELECT em identity_users;
#     C5 - contraprovas das invariantes da 024 (cada uma em BEGIN ... ROLLBACK): controle passa; recusam EXECUTE extra nas
#          duas funcoes novas, funcao de identidade SECURITY INVOKER, SELECT do dono da 016 em identity_users, dono trocado
#          numa funcao da 016, gatilho desligado e coluna do pedido de renovacao removida.
#
# A execucao do reset (abertura, execucao e as corridas com a vinculacao real) e as rotas sao cobertas pelos testes Java:
# ResetContaVinculadaIT (nekkora-ops), GrantLifecycleIT e AccessInvitationsIT (nekkora-platform).
#
# Uso: db/tests/acessos-024/run.sh   (Windows/Git Bash: ver README da suite)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
INSTANTANEO="$REPO/db/tests/relogio-018/sql/c_instantaneo_gera.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018 019 020 022 023 024}"
ELEVAR_SEM_024="011 012 013 016 017 018 019 020 022 023"
PROJETO="${TESTE_PROJETO:-nekkora-a024}"
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
for n in $(seq -w 1 24); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
M023="$SAIDA/mig/023_acessos_convites.sql"
M024="$SAIDA/mig/024_acessos_decisoes_reset_vinculado.sql"
[ -f "$M024" ] || falha "024 ausente"
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
# lista das funcoes do dono da identidade que a Platform executa: o aceite (023) e o fechamento por perda de autoridade (024)
ACEITE_ANTES="'identity_account_is_linked(uuid,uuid), identity_challenge_lookup(text), '"
ACEITE_DEPOIS="'identity_accept_invitation(text,text,uuid), identity_account_is_linked(uuid,uuid), identity_challenge_lookup(text), '"
FECHA_ANTES="        'identity_link_account(text,text,uuid), '"
FECHA_DEPOIS="        'identity_invitations_close_unauthorized(uuid,uuid[]), '
        'identity_link_account(text,text,uuid), '"
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
    adapta "$SAIDA/_inv017c.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS" > "$SAIDA/_inv017d.sql"
    adapta "$SAIDA/_inv019c.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS" > "$SAIDA/_inv019d.sql"
    adapta "$SAIDA/_inv020.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS" > "$SAIDA/_inv020d.sql"
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
      awk '/^-- === 11\. /{f=1} f && /^COMMIT;/{exit} f' "$M023"
      awk '/^-- === 6\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M024"
    } > "$SAIDA/${2}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${2}_invariantes.sql")" = 11 ] || falha "extracao dos invariantes ($2) falhou"
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
# como a Platform: papel real e contexto da organizacao, numa transacao (BEGIN ... COMMIT)
plat() { printf "BEGIN; SET LOCAL ROLE nekkora_platform; SELECT set_config('nekkora.organization_id', '%s', true);\n%s\nCOMMIT;" "$1" "$2"; }

# ================================================================== A. instalacao limpa 001-024
echo ">> A: instalacao limpa 001-024 ($LIMPA)"
sobe "$LIMPA" 24 024
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" > "$SAIDA/ambiente.txt"
grep -q "aplicada versao=024 elevada=SUPERUSER" "$SAIDA/entrypoint_$LIMPA.txt" || falha "024 sem a janela de SUPERUSER"
grep -q "invariantes_016_pos_retirada=OK" "$SAIDA/entrypoint_$LIMPA.txt" || falha "016 §19 pos-retirada ausente (depois da 024)"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=024" ] || falha "marcador da instalacao limpa"
invariantes "$LIMPA" A01
lista "$LIMPA" > "$SAIDA/A02_catalogo_024.txt"

# ================================================================== B. base populada 023 -> 024
echo ">> B: base populada 001-023 -> 024 ($POP)"
sobe "$POP" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" 013 015 "$SAIDA/B02_013_015.txt" || { cat "$SAIDA/B02_013_015.txt"; falha "013-015 na populada"; }
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
modelo "$POP" 016 022 "$SAIDA/B04_016_022.txt" "011 012 013 016 017 018 019 020 022" || { cat "$SAIDA/B04_016_022.txt"; falha "016-022 na populada"; }
consulta "$POP" nekkora "CREATE ROLE backup_nekkora LOGIN BYPASSRLS; GRANT pg_read_all_data TO backup_nekkora" > /dev/null

echo "   B0: 024 sem a 023"
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B00_instantaneo.sql"
psqlc "$POP" nekkora < "$SAIDA/B00_instantaneo.sql" > "$SAIDA/B00_dados_022.txt"
if modelo "$POP" 024 024 "$SAIDA/B00_024_sem_023.txt"; then falha "B0: 024 aplicada sem a 023"; fi
grep -qF "024 exige a 023 aplicada" "$SAIDA/B00_024_sem_023.txt" || { cat "$SAIDA/B00_024_sem_023.txt"; falha "B0: recusa diferente da pre-condicao"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B0: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B00_instantaneo.sql" > "$SAIDA/B00b_dados.txt"
diff "$SAIDA/B00_dados_022.txt" "$SAIDA/B00b_dados.txt" > /dev/null || falha "B0: dados alterados"

modelo "$POP" 023 023 "$SAIDA/B04b_023.txt" "$ELEVAR_SEM_024" || { cat "$SAIDA/B04b_023.txt"; falha "023 na populada"; }
grep -q "aplicada versao=023 elevada=SUPERUSER" "$SAIDA/B04b_023.txt" || falha "023 sem janela na populada"

# massa de ACESSO no modelo da 023 (superusuario; o bootstrap do ORG_ADMIN pelo provisionamento real): organizacao com dois
# ORG_ADMIN (ADM e EMI), um membro sem papel, cliente, workspaces, conta externa; convites abertos (um de ADM, valido, e um
# de EMI, que perde o papel ANTES da 024: inaceitavel), aceito e revogado; concessoes pendente, ativa e suspensa sem
# solicitante (anteriores a 024); eventos de acesso
uuid() { consulta "$POP" nekkora "SELECT gen_random_uuid()"; }
O="$(uuid)"; ADM="$(uuid)"; ADMM="$(uuid)"; EMI="$(uuid)"; EMIM="$(uuid)"; MEM="$(uuid)"; MEMM="$(uuid)"
CLI="$(uuid)"; WS="$(uuid)"; WS2="$(uuid)"; WS3="$(uuid)"; WS4="$(uuid)"; EXT="$(uuid)"; CM="$(uuid)"
GP="$(uuid)"; GA="$(uuid)"; GS="$(uuid)"
psqlc "$POP" nekkora > "$SAIDA/B05_massa_acesso.txt" 2>&1 <<SQL || { cat "$SAIDA/B05_massa_acesso.txt"; falha "massa de acesso"; }
BEGIN;
SELECT set_config('nekkora.organization_id', '$O', true);
INSERT INTO organizations (organization_id, name, status) VALUES ('$O', 'Org acessos 024', 1);
INSERT INTO clients (client_id, organization_id, name, status) VALUES ('$CLI', '$O', 'Cliente', 1);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES
  ('$WS', '$O', '$CLI', 'WS cliente', 1), ('$WS2', '$O', NULL, 'WS interno', 1), ('$WS3', '$O', '$CLI', 'WS 3', 1), ('$WS4', '$O', '$CLI', 'WS 4', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
  ('$ADM', '$O', 1, 'adm-a024@o.test', now(), 2, 'Admin'), ('$EMI', '$O', 1, 'emi-a024@o.test', now(), 2, 'Emissor'),
  ('$MEM', '$O', 1, 'membro-a024@o.test', now(), 2, 'Membro'), ('$EXT', '$O', 2, 'ext-a024@c.test', now(), 2, 'Externo');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES
  ('$ADMM', '$O', '$ADM', 1, 2), ('$EMIM', '$O', '$EMI', 1, 2), ('$MEMM', '$O', '$MEM', 1, 2);
INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status) VALUES ('$CM', '$O', '$CLI', '$EXT', 2, 2);
COMMIT;
SET ROLE nekkora_provisioning;
SELECT provision_first_org_admin('$O', '$ADMM', '$ADM', 'massa-024', gen_random_uuid()) IS NOT NULL;
RESET ROLE;
BEGIN;
SELECT set_config('nekkora.organization_id', '$O', true);
INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
SELECT '$O', '$EMIM', role_id, 1, 1, '$ADMM' FROM roles WHERE code = 'ORG_ADMIN';
INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, issued_by_membership_id) VALUES
  ('$O', 1, 'aberto-adm@o.test', '$ADM', md5('a1') || md5('a2'), now() + interval '6 days', '$ADMM'),
  ('$O', 1, 'aberto-emi@o.test', '$EMI', md5('b1') || md5('b2'), now() + interval '6 days', '$EMIM'),
  ('$O', 1, 'aberto-emi-2@o.test', '$EMI', md5('f1') || md5('f2'), now() + interval '6 days', '$EMIM');
INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, issued_by_membership_id, revoked_at, revoke_reason, revoked_by_membership_id) VALUES
  ('$O', 1, 'revogado@o.test', '$ADM', md5('c1') || md5('c2'), now() + interval '1 day', '$ADMM', now(), 1, '$ADMM');
INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, accepted_at, accepted_user_id) VALUES
  ('$O', 1, 'membro-a024@o.test', '$ADM', md5('d1') || md5('d2'), now() + interval '1 day', now(), '$MEM');
INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, workspace_id, status, starts_at, next_review_at)
VALUES ('$GP', '$O', '$CLI', '$CM', '$WS', 1, now(), now() + interval '30 days');
INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, workspace_id, status, approved_by_membership_id, approved_at, starts_at, next_review_at)
VALUES ('$GA', '$O', '$CLI', '$CM', '$WS3', 2, '$ADMM', now(), now(), now() + interval '30 days'),
       ('$GS', '$O', '$CLI', '$CM', '$WS4', 3, '$ADMM', now(), now(), now() + interval '30 days');
INSERT INTO access_events (organization_id, event_type, actor_membership_id, actor_user_id, grant_id, correlation_id)
VALUES ('$O', 9, '$ADMM', '$ADM', '$GA', gen_random_uuid()), ('$O', 13, '$ADMM', '$ADM', '$GS', gen_random_uuid());
COMMIT;
-- EMI perde o ORG_ADMIN ANTES da 024 (como superusuario, revogador ADM): o convite aberto dele fica inaceitavel (023)
BEGIN;
SELECT set_config('nekkora.organization_id', '$O', true);
UPDATE organization_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '$ADMM' WHERE membership_id = '$EMIM' AND status = 1;
COMMIT;
SQL
grep -q "^t$" "$SAIDA/B05_massa_acesso.txt" || falha "massa: bootstrap do ORG_ADMIN"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM identity_invitations WHERE organization_id = '$O' AND accepted_at IS NULL AND revoked_at IS NULL")" = 3 ] \
    || falha "massa: 3 convites abertos (um valido, dois de emissor sem autoridade)"
# previstos: os convites abertos de emissor sem autoridade na base inteira (o mesmo criterio da 024)
consulta "$POP" nekkora "SELECT invitation_id FROM identity_invitations i WHERE i.accepted_at IS NULL AND i.revoked_at IS NULL
    AND i.issued_by_membership_id IS NOT NULL AND NOT access_can_invite(i.organization_id, i.issued_by_membership_id, i.role_id, i.workspace_id)
    ORDER BY 1" > "$SAIDA/B06_convites_a_fechar.txt"
[ "$(wc -l < "$SAIDA/B06_convites_a_fechar.txt" | tr -d ' ')" = 2 ] || { cat "$SAIDA/B06_convites_a_fechar.txt"; falha "massa: esperados 2 convites a fechar"; }

# base como RESTAURADA de um dump da 023: as duas restricoes "NOT BETWEEN ... OR ..." na forma achatada em que o pg_restore as
# rele (achado da R015 na homologacao da 024). A instalacao limpa (A) cobre a forma original; aqui, a restaurada
consulta "$POP" nekkora "ALTER TABLE access_events DROP CONSTRAINT access_events_check3,
    ADD CONSTRAINT access_events_check3 CHECK (event_type < 1 OR event_type > 4 OR invitation_id IS NOT NULL),
    DROP CONSTRAINT access_events_check4, ADD CONSTRAINT access_events_check4 CHECK (event_type < 9 OR event_type > 13 OR grant_id IS NOT NULL)" > /dev/null
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM pg_constraint WHERE conrelid = 'access_events'::regclass AND pg_get_constraintdef(oid) IN
    ('CHECK (((event_type < 1) OR (event_type > 4) OR (invitation_id IS NOT NULL)))', 'CHECK (((event_type < 9) OR (event_type > 13) OR (grant_id IS NOT NULL)))')")" = 2 ]     || falha "B: restricoes na forma restaurada"
CONV_SEM="SELECT invitation_id || '|' || md5(row(organization_id, invited_account_type, client_id, email_normalized, issued_by, token_hash, created_at,
    expires_at, accepted_at, accepted_user_id, accepted_identity_id, issued_by_membership_id, role_id, role_scope, workspace_id, revoked_by_membership_id,
    replaces_invitation_id)::text) FROM identity_invitations ORDER BY 1"
CONV_REV="SELECT invitation_id || '|' || coalesce(revoked_at::text, '-') || '|' || coalesce(revoke_reason::text, '-') FROM identity_invitations ORDER BY 1"
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B07_instantaneo.sql"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B07_dados_023.txt"
consulta "$POP" nekkora "$CONV_SEM" > "$SAIDA/B07_convites_sem_revogacao.txt"
consulta "$POP" nekkora "$CONV_REV" > "$SAIDA/B07_convites_revogacao.txt"
lista "$POP" > "$SAIDA/B07_catalogo_023.txt"

echo "   B1: 024 sem a janela"
if modelo "$POP" 024 024 "$SAIDA/B10_024_sem_janela.txt" "$ELEVAR_SEM_024"; then falha "B1: 024 aplicada sem elevacao"; fi
grep -qiE "permission denied|must be able to SET ROLE|must be owner|must be member" "$SAIDA/B10_024_sem_janela.txt" || { cat "$SAIDA/B10_024_sem_janela.txt"; falha "B1: recusa diferente da de privilegio"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B1: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B11_dados.txt"
diff "$SAIDA/B07_dados_023.txt" "$SAIDA/B11_dados.txt" > /dev/null || falha "B1: dados alterados"
lista "$POP" > "$SAIDA/B11_catalogo.txt"
diff "$SAIDA/B07_catalogo_023.txt" "$SAIDA/B11_catalogo.txt" > /dev/null || falha "B1: catalogo alterado"

echo "   B1b: 024 com a janela, falhando DEPOIS do fechamento dos convites"
# gatilho homonimo de um que a sec. 3 da 024 cria, criado por fora: a 024 roda a sec. 2 (o fechamento avisa 2) e falha
# no CREATE TRIGGER, na MESMA transacao. Esperado: nada muda (os 2 convites continuam abertos), retirada 0|0|0
consulta "$POP" nekkora "CREATE FUNCTION public.zz_b1b() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END';
    CREATE TRIGGER trg_client_workspace_grants_request BEFORE UPDATE ON public.client_workspace_grants FOR EACH ROW EXECUTE FUNCTION public.zz_b1b()" > /dev/null
if modelo "$POP" 024 024 "$SAIDA/B12_024_falha_tardia.txt"; then falha "B1b: 024 aplicada com o gatilho homonimo"; fi
grep -qF "024: convites abertos de emissor sem autoridade fechados na aplicacao: 2" "$SAIDA/B12_024_falha_tardia.txt" \
    || { cat "$SAIDA/B12_024_falha_tardia.txt"; falha "B1b: a falha deveria vir DEPOIS do fechamento"; }
grep -qF 'trigger "trg_client_workspace_grants_request" for relation "client_workspace_grants" already exists' "$SAIDA/B12_024_falha_tardia.txt" \
    || { cat "$SAIDA/B12_024_falha_tardia.txt"; falha "B1b: recusa diferente da esperada"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B1b: migrador fora de 0|0|0"
consulta "$POP" nekkora "DROP TRIGGER trg_client_workspace_grants_request ON public.client_workspace_grants; DROP FUNCTION public.zz_b1b()" > /dev/null
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B12_dados.txt"
diff "$SAIDA/B07_dados_023.txt" "$SAIDA/B12_dados.txt" > /dev/null || falha "B1b: dados alterados (o fechamento nao voltou com a transacao)"
consulta "$POP" nekkora "$CONV_REV" > "$SAIDA/B12_convites_revogacao.txt"
diff "$SAIDA/B07_convites_revogacao.txt" "$SAIDA/B12_convites_revogacao.txt" > /dev/null || falha "B1b: convites alterados"
lista "$POP" > "$SAIDA/B12_catalogo.txt"
diff "$SAIDA/B07_catalogo_023.txt" "$SAIDA/B12_catalogo.txt" > /dev/null || falha "B1b: catalogo alterado"

echo "   B2: 024 com a janela"
modelo "$POP" 024 024 "$SAIDA/B20_024.txt" || { cat "$SAIDA/B20_024.txt"; falha "024 na populada"; }
grep -q "aplicada versao=024 elevada=SUPERUSER" "$SAIDA/B20_024.txt" || falha "B2: 024 sem janela"
grep -qF "024: convites abertos de emissor sem autoridade fechados na aplicacao: 2" "$SAIDA/B20_024.txt" || { cat "$SAIDA/B20_024.txt"; falha "B2: aviso do fechamento"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B2: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=024" ] || falha "B2: marcador"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B21_dados_024.txt"
diff "$SAIDA/B07_dados_023.txt" "$SAIDA/B21_dados_024.txt" > "$SAIDA/B21_diff_dados.txt" || true
# unica tabela com dados alterados: identity_invitations; e nela so revoked_at e revoke_reason dos previstos
[ "$(grep -E '^[<>]' "$SAIDA/B21_diff_dados.txt" | cut -d'|' -f1 | sed 's/^[<>] //' | sort -u)" = "public.identity_invitations" ] \
    || { cat "$SAIDA/B21_diff_dados.txt"; falha "B2: dados alterados fora de identity_invitations"; }
consulta "$POP" nekkora "$CONV_SEM" > "$SAIDA/B22_convites_sem_revogacao.txt"
diff "$SAIDA/B07_convites_sem_revogacao.txt" "$SAIDA/B22_convites_sem_revogacao.txt" > /dev/null || falha "B2: colunas dos convites alem da revogacao alteradas"
consulta "$POP" nekkora "$CONV_REV" > "$SAIDA/B22_convites_revogacao.txt"
diff "$SAIDA/B07_convites_revogacao.txt" "$SAIDA/B22_convites_revogacao.txt" | grep '^>' | cut -d'|' -f1 | sed 's/^> //' | sort > "$SAIDA/B22_convites_fechados.txt" || true
diff "$SAIDA/B06_convites_a_fechar.txt" "$SAIDA/B22_convites_fechados.txt" > /dev/null || falha "B2: convites fechados diferentes dos previstos"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM identity_invitations WHERE invitation_id IN ($(sed "s/.*/'&'/" "$SAIDA/B06_convites_a_fechar.txt" | paste -sd,))
    AND revoke_reason = 4 AND revoked_at IS NOT NULL AND revoked_by_membership_id IS NULL")" = 2 ] || falha "B2: previstos sem o motivo 4"
consulta "$POP" nekkora "SELECT (SELECT count(*) FROM client_workspace_grants WHERE requested_by_membership_id IS NOT NULL OR renewal_requested_by_membership_id IS NOT NULL)
    || '|' || (SELECT count(*) FROM client_workspace_grant_approvals WHERE requested_by_membership_id IS NOT NULL)" > "$SAIDA/B23_colunas_novas.txt"
[ "$(cat "$SAIDA/B23_colunas_novas.txt")" = "0|0" ] || falha "B2: linhas antigas com colunas novas preenchidas"
lista "$POP" > "$SAIDA/B24_catalogo_024.txt"
diff "$SAIDA/B07_catalogo_023.txt" "$SAIDA/B24_catalogo_024.txt" > "$SAIDA/B24_diff_catalogo.txt" || true
grep -E '^[<>]' "$SAIDA/B24_diff_catalogo.txt" | sed -E 's/\|md5=[0-9a-f]+/|md5=.../' | sort > "$SAIDA/B24_diff_resumo.txt"
if [ -f "$AQUI/esperado/B24_diff_esperado.txt" ]; then
    sort "$AQUI/esperado/B24_diff_esperado.txt" > "$SAIDA/_b.txt"
    diff "$SAIDA/_b.txt" "$SAIDA/B24_diff_resumo.txt" > "$SAIDA/B24_fora_do_previsto.txt" || { cat "$SAIDA/B24_fora_do_previsto.txt"; falha "B2: catalogo 023 -> 024 fora do previsto"; }
else
    falha "B2: esperado/B24_diff_esperado.txt ausente (resumo gerado em $SAIDA/B24_diff_resumo.txt para revisao)"
fi
diff "$SAIDA/A02_catalogo_024.txt" "$SAIDA/B24_catalogo_024.txt" > "$SAIDA/B25_diff_limpa.txt" || falha "B2: catalogo da atualizada difere da instalacao limpa (ver B25)"
invariantes "$POP" B26

echo "   B3: reaplicacao da 024"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B30_dados_antes.txt"
if psqlc "$POP" nekkora < "$M024" > "$SAIDA/B30_reaplica.txt" 2>&1; then falha "B3: 024 reaplicada"; fi
grep -qF "024: objetos ja existem" "$SAIDA/B30_reaplica.txt" || { cat "$SAIDA/B30_reaplica.txt"; falha "B3: recusa diferente da pre-condicao"; }
lista "$POP" > "$SAIDA/B31_catalogo.txt"
diff "$SAIDA/B24_catalogo_024.txt" "$SAIDA/B31_catalogo.txt" > /dev/null || falha "B3: catalogo alterado"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B31_dados.txt"
diff "$SAIDA/B30_dados_antes.txt" "$SAIDA/B31_dados.txt" > /dev/null || falha "B3: dados alterados"

# ================================================================== C. comportamento no banco (papeis reais)
echo ">> C: comportamento ($POP)"
: > "$SAIDA/C_recusas.txt"; : > "$SAIDA/C_passam.txt"
ORGADMIN="$(consulta "$POP" nekkora "SELECT role_id FROM roles WHERE code = 'ORG_ADMIN'")"
VIEWER="$(consulta "$POP" nekkora "SELECT role_id FROM roles WHERE code = 'VIEWER'")"
TOK() { head -c 32 /dev/urandom | sha256sum | cut -c1-64; }
INV="INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, issued_by_membership_id, role_id, role_scope, workspace_id)"

# C1 D-CONV-4: EMI recebe de novo o ORG_ADMIN, emite, perde o papel (Platform) -> convite revogado no ato; papel devolvido nao ressuscita
passa_sql "$POP" "C1 ORG_ADMIN devolvido ao emissor" "$(plat "$O" "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) VALUES ('$O', '$EMIM', '$ORGADMIN', 1, 1, '$ADMM');")"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM identity_invitations WHERE invitation_id IN ($(sed "s/.*/'&'/" "$SAIDA/B06_convites_a_fechar.txt" | paste -sd,)) AND revoked_at IS NOT NULL")" = 2 ] \
    || falha "C1: convites fechados na aplicacao voltaram a abrir com o papel devolvido"
C1TOK="$(TOK)"
passa_sql "$POP" "C1 emissor autorizado emite" "$(plat "$O" "$INV VALUES ('$O', 1, 'c1-a024@o.test', '$EMI', '$C1TOK', now() + interval '7 days', '$EMIM', '$VIEWER', 2, '$WS2');")"
recusa_sql "$POP" "C1 motivo 4 com o emissor ainda autorizado" "emissor do convite ainda tem autoridade" "$(plat "$O" "UPDATE identity_invitations SET revoked_at = now(), revoke_reason = 4 WHERE token_hash = '$C1TOK';")"
recusa_sql "$POP" "C1 motivo 4 com revogador" "emissor do convite ainda tem autoridade" "$(plat "$O" "UPDATE identity_invitations SET revoked_at = now(), revoke_reason = 4, revoked_by_membership_id = '$ADMM' WHERE token_hash = '$C1TOK';")"
passa_sql "$POP" "C1 papel do emissor revogado pela Platform" "$(plat "$O" "UPDATE organization_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '$ADMM' WHERE membership_id = '$EMIM' AND status = 1;")"
[ "$(consulta "$POP" nekkora "SELECT coalesce(revoke_reason::text, '-') || '|' || (revoked_by_membership_id IS NULL) FROM identity_invitations WHERE token_hash = '$C1TOK'")" = "4|true" ] \
    || falha "C1: convite nao revogado no ato da perda (motivo 4)"
echo "C1 convite revogado no ato da perda do papel (motivo 4, sem revogador)|rc=0" >> "$SAIDA/C_passam.txt"
passa_sql "$POP" "C1 ORG_ADMIN devolvido de novo" "$(plat "$O" "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) VALUES ('$O', '$EMIM', '$ORGADMIN', 1, 1, '$ADMM');")"
[ "$(consulta "$POP" nekkora "SELECT revoke_reason FROM identity_invitations WHERE token_hash = '$C1TOK'")" = 4 ] || falha "C1: convite ressuscitou"
recusa_sql "$POP" "C1 revogado continua congelado" "convite aceito ou revogado e imutavel" "$(plat "$O" "UPDATE identity_invitations SET revoked_at = NULL, revoke_reason = NULL WHERE token_hash = '$C1TOK';")"
recusa_sql "$POP" "C1 fechamento direto por outro papel" "permission denied for function identity_invitations_close_unauthorized" "SET ROLE nekkora_portal; SELECT public.identity_invitations_close_unauthorized('$O', ARRAY['$EMIM']::uuid[]);"

# C2 D-GRANT-1: segunda pessoa
GINS="INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, starts_at"
WS5="$(uuid)"; WS6="$(uuid)"
consulta "$POP" nekkora "BEGIN; SELECT set_config('nekkora.organization_id', '$O', true); INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES ('$WS5', '$O', '$CLI', 'WS 5', 1), ('$WS6', '$O', '$CLI', 'WS 6', 1); COMMIT;" > /dev/null
recusa_sql "$POP" "C2 concessao sem solicitante" "solicitacao de concessao externa sem autoridade" "$(plat "$O" "$GINS, next_review_at) VALUES ('$O', '$CLI', '$CM', '$WS5', 1, now(), now() + interval '30 days');")"
recusa_sql "$POP" "C2 concessao sem a proxima revisao pedida" "concessao nasce com a proxima revisao pedida" "$(plat "$O" "$GINS, requested_by_membership_id) VALUES ('$O', '$CLI', '$CM', '$WS5', 1, now(), '$ADMM');")"
recusa_sql "$POP" "C2 concessao ja ativa" "concessao nasce pendente, sem aprovacao" "$(plat "$O" "$GINS, approved_by_membership_id, approved_at, next_review_at, requested_by_membership_id) VALUES ('$O', '$CLI', '$CM', '$WS5', 2, now(), '$ADMM', now(), now() + interval '30 days', '$ADMM');")"
recusa_sql "$POP" "C2 solicitante sem a permissao" "solicitacao de concessao externa sem autoridade" "$(plat "$O" "$GINS, next_review_at, requested_by_membership_id) VALUES ('$O', '$CLI', '$CM', '$WS5', 1, now(), now() + interval '30 days', '$MEMM');")"
G5="$(uuid)"
passa_sql "$POP" "C2 solicitacao autorizada (ADM)" "$(plat "$O" "INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, workspace_id, status, starts_at, next_review_at, requested_by_membership_id) VALUES ('$G5', '$O', '$CLI', '$CM', '$WS5', 1, now(), now() + interval '30 days', '$ADMM');")"
APR1="INSERT INTO client_workspace_grant_approvals (organization_id, grant_id, approval_type, approved_by_membership_id, requested_by_membership_id, new_starts_at, new_expires_at, new_next_review_at) SELECT organization_id, grant_id, 1, '%s', requested_by_membership_id, starts_at, expires_at, %s FROM client_workspace_grants WHERE grant_id = '%s';"
ATIVA1="UPDATE client_workspace_grants SET status = 2, approved_by_membership_id = '%s', approved_at = now() WHERE grant_id = '%s';"
recusa_sql "$POP" "C2 INITIAL pelo solicitante" "aprovacao inicial exige pessoa diferente do solicitante" "$(plat "$O" "$(printf "$APR1" "$ADMM" next_review_at "$G5")")"
recusa_sql "$POP" "C2 PENDENTE -> ATIVA pelo solicitante" "aprovacao de concessao externa exige pessoa diferente do solicitante" "$(plat "$O" "$(printf "$ATIVA1" "$ADMM" "$G5")")"
recusa_sql "$POP" "C2 INITIAL com outro valor" "exatamente os valores pedidos" "$(plat "$O" "$(printf "$APR1" "$EMIM" "next_review_at + interval '1 day'" "$G5")")"
recusa_sql "$POP" "C2 concessao anterior a 024 (sem solicitante) nao e aprovada" "aprovacao de concessao externa exige pessoa diferente do solicitante" "$(plat "$O" "$(printf "$ATIVA1" "$EMIM" "$GP")")"
passa_sql "$POP" "C2 aprovacao por outra pessoa com os valores pedidos" "$(plat "$O" "$(printf "$APR1" "$EMIM" next_review_at "$G5") $(printf "$ATIVA1" "$EMIM" "$G5")")"
[ "$(consulta "$POP" nekkora "SELECT status FROM client_workspace_grants WHERE grant_id = '$G5'")" = 2 ] || falha "C2: aprovacao nao ativou"

# C3 D-GRANT-1a: renovacao e KEEP
W=" WHERE grant_id = '$G5'"
G6="$(uuid)"
passa_sql "$POP" "C3 outra pendente (ADM)" "$(plat "$O" "INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, workspace_id, status, starts_at, next_review_at, requested_by_membership_id) VALUES ('$G6', '$O', '$CLI', '$CM', '$WS6', 1, now(), now() + interval '30 days', '$ADMM');")"
recusa_sql "$POP" "C3 valor pedido da pendente" "valores solicitados da concessao sao imutaveis" "$(plat "$O" "UPDATE client_workspace_grants SET next_review_at = next_review_at + interval '1 day' WHERE grant_id = '$G6';")"
recusa_sql "$POP" "C3 estender a revisao sem pedido" "extensao da concessao exige pedido de renovacao" "$(plat "$O" "UPDATE client_workspace_grants SET next_review_at = next_review_at + interval '1 day'$W;")"
passa_sql "$POP" "C3 prazo novo (reducao: sem prazo -> com prazo)" "$(plat "$O" "UPDATE client_workspace_grants SET expires_at = now() + interval '400 days'$W;")"
recusa_sql "$POP" "C3 remover o prazo" "extensao da concessao exige pedido de renovacao" "$(plat "$O" "UPDATE client_workspace_grants SET expires_at = NULL$W;")"
recusa_sql "$POP" "C3 estender o prazo" "extensao da concessao exige pedido de renovacao" "$(plat "$O" "UPDATE client_workspace_grants SET expires_at = expires_at + interval '1 day'$W;")"
recusa_sql "$POP" "C3 antecipar o inicio" "extensao da concessao exige pedido de renovacao" "$(plat "$O" "UPDATE client_workspace_grants SET starts_at = starts_at - interval '1 day'$W;")"
recusa_sql "$POP" "C3 renovar sem pedido" "extensao da concessao exige pedido de renovacao" "$(plat "$O" "UPDATE client_workspace_grants SET approved_by_membership_id = '$EMIM', approved_at = clock_timestamp(), next_review_at = next_review_at + interval '1 day'$W;")"
passa_sql "$POP" "C3 reducao da revisao (KEEP mais cedo)" "$(plat "$O" "UPDATE client_workspace_grants SET next_review_at = next_review_at - interval '1 day'$W;")"
PEDIDO="UPDATE client_workspace_grants SET renewal_requested_by_membership_id = '%s', renewal_requested_at = now(), renewal_next_review_at = now() + interval '60 days', renewal_justification = 'renovacao'"
recusa_sql "$POP" "C3 pedido que muda a validade" "pedido de renovacao nao muda a validade" "$(plat "$O" "$(printf "$PEDIDO" "$ADMM"), next_review_at = next_review_at - interval '1 hour'$W;")"
recusa_sql "$POP" "C3 pedido por quem nao tem a permissao" "pedido de renovacao sem autoridade" "$(plat "$O" "$(printf "$PEDIDO" "$MEMM")$W;")"
passa_sql "$POP" "C3 pedido (ADM)" "$(plat "$O" "$(printf "$PEDIDO" "$ADMM")$W;")"
APR2="INSERT INTO client_workspace_grant_approvals (organization_id, grant_id, approval_type, approved_by_membership_id, requested_by_membership_id, previous_starts_at, new_starts_at, previous_expires_at, new_expires_at, previous_next_review_at, new_next_review_at) SELECT organization_id, grant_id, 2, '%s', renewal_requested_by_membership_id, starts_at, starts_at, expires_at, renewal_expires_at, next_review_at, %s FROM client_workspace_grants$W;"
APLICA="UPDATE client_workspace_grants SET next_review_at = %s, expires_at = renewal_expires_at, status = 2, approved_by_membership_id = '%s', approved_at = (SELECT max(approved_at) FROM client_workspace_grant_approvals$W)$W;"
recusa_sql "$POP" "C3 RENEWAL pelo solicitante" "renovacao exige pedido pendente de outra pessoa" "$(plat "$O" "$(printf "$APR2" "$ADMM" renewal_next_review_at)")"
recusa_sql "$POP" "C3 RENEWAL com outro valor" "renovacao exige pedido pendente de outra pessoa" "$(plat "$O" "$(printf "$APR2" "$EMIM" "renewal_next_review_at + interval '1 day'")")"
recusa_sql "$POP" "C3 aplicar outro valor que o aprovado" "extensao da concessao exige pedido de renovacao" "$(plat "$O" "$(printf "$APR2" "$EMIM" renewal_next_review_at) $(printf "$APLICA" "renewal_next_review_at + interval '9 days'" "$EMIM")")"
passa_sql "$POP" "C3 RENEWAL de outra pessoa com os valores pedidos" "$(plat "$O" "$(printf "$APR2" "$EMIM" renewal_next_review_at) $(printf "$APLICA" renewal_next_review_at "$EMIM")")"
[ "$(consulta "$POP" nekkora "SELECT status || '|' || (renewal_requested_by_membership_id IS NULL) || '|' || (expires_at IS NULL) FROM client_workspace_grants$W")" = "2|true|true" ] \
    || falha "C3: renovacao nao aplicou os valores pedidos ou nao consumiu o pedido"
recusa_sql "$POP" "C3 o pedido nao serve duas vezes" "renovacao exige pedido pendente de outra pessoa" "$(plat "$O" "$(printf "$APR2" "$EMIM" "now() + interval '60 days'")")"
passa_sql "$POP" "C3 pedido novo (ADM)" "$(plat "$O" "$(printf "$PEDIDO" "$ADMM")$W;")"
passa_sql "$POP" "C3 suspensao (reducao) descarta o pedido" "$(plat "$O" "UPDATE client_workspace_grants SET status = 3$W;")"
[ "$(consulta "$POP" nekkora "SELECT renewal_requested_by_membership_id IS NULL FROM client_workspace_grants$W")" = t ] || falha "C3: suspensao nao descartou o pedido"
recusa_sql "$POP" "C3 reativar SUSPENSA sem pedido" "extensao da concessao exige pedido de renovacao" "$(plat "$O" "UPDATE client_workspace_grants SET status = 2$W;")"
recusa_sql "$POP" "C3 reativar concessao anterior a 024 sem pedido" "extensao da concessao exige pedido de renovacao" "$(plat "$O" "UPDATE client_workspace_grants SET status = 2 WHERE grant_id = '$GS';")"

# C4 reset vinculado: elegibilidade e privilegios
U1="$(uuid)"; U2="$(uuid)"; I1="$(uuid)"
psqlc "$POP" nekkora > "$SAIDA/C40_vinculo.txt" 2>&1 <<SQL || { cat "$SAIDA/C40_vinculo.txt"; falha "C4: fixture da conta vinculada"; }
BEGIN;
SELECT set_config('nekkora.organization_id', '$O', true);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
  ('$U1', '$O', 1, 'vinc-a024@o.test', now(), 2, 'Vinculada'), ('$U2', '$O', 1, 'nvinc-a024@o.test', now(), 2, 'Nao vinculada');
INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES ('$O', '$U1', 1, 2), ('$O', '$U2', 1, 2);
INSERT INTO identities (identity_id, email, display_name, email_verified_at, status) VALUES ('$I1', 'vinc-a024@o.test', 'Identidade', now(), 2);
INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('$I1', '$U1', 'vinculacao');
COMMIT;
SQL
[ "$(consulta "$POP" nekkora "SELECT ops.alvo_inelegivel('$O', '$U1')")" = CONTA_VINCULADA ] || falha "C4: conta vinculada sem CONTA_VINCULADA"
[ "$(consulta "$POP" nekkora "SELECT coalesce(ops.alvo_inelegivel('$O', '$U2'), '-')")" = NAO_ORG_ADMIN ] || falha "C4: controle (nao vinculada) diferente de NAO_ORG_ADMIN"
echo "C4 alvo vinculado -> CONTA_VINCULADA; controle nao vinculado -> NAO_ORG_ADMIN|rc=0" >> "$SAIDA/C_passam.txt"
[ "$(printf "SET ROLE nekkora_ops_mfa_owner;\nSELECT public.identity_user_is_linked('%s')::text || '|' || public.identity_user_is_linked('%s')::text;\n" "$U1" "$U2" | psqlc "$POP" nekkora | tail -1)" = "true|false" ] \
    || falha "C4: o dono da 016 consulta a ligacao"
echo "C4 dono da 016 consulta a ligacao (true|false)|rc=0" >> "$SAIDA/C_passam.txt"
for p in nekkora_platform nekkora_portal nekkora_provisioning; do
    recusa_sql "$POP" "C4 $p nao consulta a ligacao" "permission denied for function identity_user_is_linked" "SET ROLE $p; SELECT public.identity_user_is_linked('$U1');"
done
recusa_sql "$POP" "C4 dono da 016 sem SELECT em identity_users" "permission denied for table identity_users" "SET ROLE nekkora_ops_mfa_owner; SELECT count(*) FROM public.identity_users;"

echo "   C5: contraprovas das invariantes da 024"
awk '/^-- === 6\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M024" > "$SAIDA/C50_inv024.sql"
[ "$(grep -c '^DO \$\$' "$SAIDA/C50_inv024.sql")" = 1 ] || falha "C5: extracao da sec. 6 da 024"
: > "$SAIDA/C51_contraprovas.txt"
c5() {   # $1 rotulo, $2 alteracao (vazia = controle), $3 trecho da recusa (vazio = deve passar)
    local o rc=0 m
    o="$({ echo "BEGIN;"; printf '%s\n' "$2"; cat "$SAIDA/C50_inv024.sql"; echo "ROLLBACK;"; } | psqlc "$POP" nekkora 2>&1)" || rc=$?
    m="$(echo "$o" | grep -m1 -o '024: .*' || echo '-')"
    echo "$1|rc=$rc|$m" >> "$SAIDA/C51_contraprovas.txt"
    if [ -z "$3" ]; then [ "$rc" = 0 ] || falha "C5 $1: controle recusado ($o)"; return 0; fi
    [ "$rc" != 0 ] && [[ "$m" == *"$3"* ]] || falha "C5 $1: esperada a recusa '$3' (rc=$rc: $o)"
}
c5 controle "" ""
c5 execute_ligacao_platform "GRANT EXECUTE ON FUNCTION identity_user_is_linked(uuid) TO nekkora_platform;" "024: EXECUTE da consulta de ligacao divergente"
c5 execute_fechamento_portal "GRANT EXECUTE ON FUNCTION identity_invitations_close_unauthorized(uuid,uuid[]) TO nekkora_portal;" "024: EXECUTE do fechamento divergente"
c5 identidade_security_invoker "ALTER FUNCTION identity_user_is_linked(uuid) SECURITY INVOKER;" "024: funcao de identidade fora do padrao"
c5 dono_016_le_identity_users "GRANT SELECT ON identity_users TO nekkora_ops_mfa_owner;" "024: dono da 016 com privilegio em identity_users"
c5 dono_funcao_016_trocado "ALTER FUNCTION ops.alvo_inelegivel(uuid,uuid) OWNER TO migrador_nekkora;" "024: dono das funcoes da 016 alterado"
c5 gatilho_desligado "ALTER TABLE client_workspace_grants DISABLE TRIGGER trg_client_workspace_grants_request;" "024: gatilhos ausentes ou desligados"
c5 coluna_do_pedido_removida "ALTER TABLE client_workspace_grants DROP COLUMN renewal_justification;" "024: colunas ou restricoes da regra de renovacao ausentes"
{
    echo "A: 24 migrations (001-024); janelas SUPERUSER com retirada 0|0|0: $(grep -c 'SUPERUSER revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt") (024 com janela); sec. 19 da 016 depois de tudo: OK; invariantes 012/013/014(adaptada)/016/017/019/020(adaptadas)/021/022/023/024 DEPOIS da retirada ($(cat "$SAIDA/A01_estado_migrador.txt")): OK; marcador versao=024"
    echo "B: $(cat "$SAIDA/B01_massa_001_012.txt"); massa de acesso no modelo da 023: 5 convites (3 abertos: 1 valido e 2 de emissor sem autoridade; 1 revogado; 1 aceito), concessoes pendente, ativa e suspensa anteriores a 024, eventos"
    echo "B0: 024 sobre a base em 022 -> recusada pela pre-condicao, dados inalterados"
    echo "B1: 024 sem janela recusada por privilegio; dados e catalogo inalterados"
    echo "B1b: 024 com a janela falhando DEPOIS do fechamento (aviso de 2 convites, depois gatilho homonimo): mesma transacao, os 2 convites continuam abertos; dados e catalogo inalterados; 0|0|0"
    echo "B2: dados de public/ops identicos exceto identity_invitations; nela so revoked_at e revoke_reason dos 2 convites previstos (motivo 4, sem revogador); linhas antigas com as colunas novas nulas; catalogo 023->024 = $(wc -l < "$SAIDA/B24_diff_resumo.txt" | tr -d ' ') linhas previstas; = instalacao limpa; invariantes ($(cat "$SAIDA/B26_estado_migrador.txt")): OK"
    echo "B3: reaplicacao recusada pela pre-condicao; dados e catalogo inalterados"
    echo "C: $(grep -c . "$SAIDA/C_recusas.txt") recusas e $(grep -c . "$SAIDA/C_passam.txt") controles positivos conferidos (D-CONV-4, D-GRANT-1, D-GRANT-1a, reset vinculado)"
    echo "C5: $(grep -c . "$SAIDA/C51_contraprovas.txt") casos das invariantes da 024 (controle e 7 contraprovas): todos como esperado"
} | tee "$SAIDA/resumo.txt"
rm -f "$SAIDA/_b.txt"
docker rm -f -v "$LIMPA" "$POP" > /dev/null
echo "saidas: $SAIDA"
