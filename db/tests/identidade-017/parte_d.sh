# parte_d.sh - carregado pelo run.sh (usa falha, psqlc, consulta, modelo, invariantes, lista, ELEVAR, IMAGEM, SAIDA, REPO, AQUI).
# D (R3): atualizacao de base populada COM A 016 -> 017. Massa da 016 pelos caminhos REAIS (operadores com certificado TLS
# de 15 min registrado pelo papel da CA, IdP simulado, contatos, solicitacoes, avisos pelo fluxo de BANCO sob nekkora_platform com resultado de entrega SIMULADO - sem
# remetente Java, transporte ou e-mail), com as duas fixtures
# temporais DECLARADAS da 016 (contato envelhecido 31 dias; solicitacao recuada 24 h + 1 min), as mesmas de OpsBanco e da
# homologacao. Antes da 017: S1 APROVADA (aguardando execucao) e S2 VERIFICADA. Depois da 017: os caminhos da 016 seguem.
POP16="${PROJETO}-pop016"
EMISSOR_IDP="https://idp.nekkora.test"
DN_CA="/CN=Nekkora Ops CA Teste"
PKI="$SAIDA/D_pki"
D_LOG="$SAIDA/D02_massa_016.txt"   # log da fase corrente (D07 depois da 017)
docker container inspect "$POP16" >/dev/null 2>&1 && falha "container $POP16 ja existe"
mkdir -p "$PKI/tls-src" "$PKI/etc" "$PKI/op"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$PKI/ca.key" -out "$PKI/tls-src/ca.crt" -subj "$DN_CA" -days 2 >/dev/null 2>&1
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$PKI/tls-src/server.key" -out "$PKI/server.csr" -subj "/CN=localhost" >/dev/null 2>&1
printf 'subjectAltName=IP:127.0.0.1,DNS:localhost\n' > "$PKI/san.ext"
openssl x509 -req -in "$PKI/server.csr" -CA "$PKI/tls-src/ca.crt" -CAkey "$PKI/ca.key" -set_serial 0x$(openssl rand -hex 16) -days 2 \
    -extfile "$PKI/san.ext" -out "$PKI/tls-src/server.crt" >/dev/null 2>&1 || falha "D: certificado do servidor"
cat > "$PKI/etc/pg_hba.conf" <<'HBA'
local     all  all                    trust
hostnossl all  all    0.0.0.0/0       reject
hostssl   all  /^op_  127.0.0.1/32    cert map=ops clientname=CN
hostssl   all  all    127.0.0.1/32    scram-sha-256
HBA
printf 'ops   "/^(op_[a-z0-9_]{2,40})$"   \\1\n' > "$PKI/etc/pg_ident.conf"

d_sobe() {
    docker create --name "$POP16" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
        -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE=012 \
        -e NEKKORA_ELEVAR="$ELEVAR" -e NEKKORA_MIGRACOES=/nekkora-migracoes "$IMAGEM" sh -c \
        "mkdir -p /var/lib/postgresql/tls && cp /tls-src/* /var/lib/postgresql/tls/ && chown -R postgres:postgres /var/lib/postgresql/tls \
         && chmod 600 /var/lib/postgresql/tls/server.key && exec docker-entrypoint.sh postgres -c cluster_name=$POP16 -c ssl=on \
         -c ssl_cert_file=/var/lib/postgresql/tls/server.crt -c ssl_key_file=/var/lib/postgresql/tls/server.key \
         -c ssl_ca_file=/var/lib/postgresql/tls/ca.crt -c hba_file=/etc/nekkora/pg_hba.conf -c ident_file=/etc/nekkora/pg_ident.conf" > /dev/null
    docker cp "$SAIDA/mig_016" "$POP16":/nekkora-migracoes > /dev/null
    docker cp "$PKI/tls-src" "$POP16":/tls-src > /dev/null
    docker cp "$PKI/etc" "$POP16":/etc/nekkora > /dev/null
    docker cp "$SAIDA/modelo.sh" "$POP16":/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh > /dev/null
    docker cp "$SAIDA/modelo.sh" "$POP16":/tmp/modelo.sh > /dev/null
    docker start "$POP16" > /dev/null
    for _ in $(seq 1 180); do
        [ "$(docker logs "$POP16" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
        docker ps -q -f "name=^$POP16\$" | grep -q . || break
        sleep 1
    done
    docker logs "$POP16" 2>&1 | grep -E "aplicar_como_migrador|ERROR|FATAL" > "$SAIDA/entrypoint_$POP16.txt" || true
    grep -qE "ERROR|FATAL|aplicar_como_migrador: FALHA" "$SAIDA/entrypoint_$POP16.txt" && falha "D: erro na aplicacao (ver entrypoint_$POP16.txt)"
    grep -q "aplicar_como_migrador: fim db=nekkora aplicadas=12 estado=0|0|0" "$SAIDA/entrypoint_$POP16.txt" || falha "D: 001-012 nao aplicadas"
    docker exec "$POP16" mkdir -p /pki
    docker cp "$PKI/tls-src/ca.crt" "$POP16":/pki/ca.crt > /dev/null
}
dq() { psqlc "$POP16" nekkora -c "$1" < /dev/null; }                                        # superusuario
dpapel() { docker exec -i "$POP16" psql -X -At -v ON_ERROR_STOP=1 -U "$1" -d nekkora -c "$2" < /dev/null; }   # servico, socket local
dop() {   # operador: TLS verify-ca com o certificado do proprio papel (pg_hba cert, CN = papel)
    docker exec -i -e PGSSLMODE=verify-ca -e PGSSLROOTCERT=/pki/ca.crt -e PGSSLCERT="/pki/$1.crt" -e PGSSLKEY="/pki/$1.key" "$POP16" \
        psql -X -At -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$1" -d nekkora -c "$2" < /dev/null
}
d_cert() {   # emite certificado de 15 min (CN = papel) e o registra pelo papel da CA, como EmissorCertificados
    local papel="$1" hex nb na r
    hex="$(openssl rand -hex 16)"
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$PKI/op/$papel.key" -out "$PKI/op/$papel.csr" -subj "/CN=$papel" >/dev/null 2>&1
    nb="$(date -u -v-5S +%Y%m%d%H%M%SZ 2>/dev/null || date -u -d '-5 seconds' +%Y%m%d%H%M%SZ)"
    na="$(date -u -v+895S +%Y%m%d%H%M%SZ 2>/dev/null || date -u -d '+895 seconds' +%Y%m%d%H%M%SZ)"
    openssl x509 -req -in "$PKI/op/$papel.csr" -CA "$PKI/tls-src/ca.crt" -CAkey "$PKI/ca.key" -set_serial "0x$hex" -not_before "$nb" -not_after "$na" \
        -out "$PKI/op/$papel.crt" >/dev/null 2>&1 || falha "D: certificado de $papel"
    docker cp "$PKI/op/$papel.crt" "$POP16":/pki/ > /dev/null; docker cp "$PKI/op/$papel.key" "$POP16":/pki/ > /dev/null
    docker exec "$POP16" chmod 600 "/pki/$papel.key"
    r="$(dpapel nekkora_ops_ca "SELECT r_resultado || ':' || coalesce(r_motivo, '') FROM public.ops_register_certificate('$EMISSOR_IDP', 'sub-$papel', '$papel',
        '$DN_CA', $("$PY" -c "print(int('$hex', 16))"), to_timestamp('$nb', 'YYYYMMDDHH24MISS\"Z\"') AT TIME ZONE 'UTC',
        to_timestamp('$na', 'YYYYMMDDHH24MISS\"Z\"') AT TIME ZONE 'UTC', now(), 'phr+mfa')")"
    [ "$r" = "OK:" ] || falha "D: registro do certificado de $papel: $r"
    echo "certificado $papel: OK" >> "$D_LOG"
}
d_ok() { case "$2" in OK*) echo "$1: $2" >> "$SAIDA/D02_massa_016.txt";; *) falha "D: $1 recusado: $2";; esac; }
d_org() {   # organizacao com ORG_ADMIN (bootstrap da 012), fator confirmado e estado de acesso vivo - fixture de OpsBanco
    local org admin memb fator
    org="$(dq "SELECT gen_random_uuid()")"; admin="$(dq "SELECT gen_random_uuid()")"; memb="$(dq "SELECT gen_random_uuid()")"; fator="$(dq "SELECT gen_random_uuid()")"
    dq "INSERT INTO organizations (organization_id, name, status) VALUES ('$org', 'Org da 016 $1', 1);
        INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name)
        VALUES ('$admin', '$org', 1, 'adm-$1@org016.test', now(), 2, 'Titular $1');
        INSERT INTO user_credentials (user_id, password_hash) VALUES ('$admin', 'hash-sintetico-$1');
        INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('$memb', '$org', '$admin', 1, 2)" > /dev/null
    dpapel nekkora_provisioning "SELECT provision_first_org_admin('$org', '$memb', '$admin', 'teste', gen_random_uuid())" > /dev/null
    dq "INSERT INTO user_mfa_factors (factor_id, organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, created_at, confirmed_at)
        VALUES ('$fator', '$org', '$admin', 1, gen_random_bytes(36), gen_random_bytes(12), 'k1', now() - interval '1 day', now() - interval '1 day');
        INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
        VALUES ('$org', '$admin', encode(gen_random_bytes(32), 'hex'), now() + interval '8 hours', now(), repeat('c', 43));
        INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at)
        VALUES ('$org', '$admin', encode(gen_random_bytes(32), 'hex'), 1, 'hash-sintetico', repeat('d', 43), now() + interval '4 minutes');
        INSERT INTO user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash)
        SELECT '$org', '$admin', '$fator', encode(gen_random_bytes(32), 'hex') FROM generate_series(1, 2)" > /dev/null
    echo "$org $admin"
}
d_contato() {   # contato pelo caminho real (CADASTRAR) e envelhecido 31 dias (fixture temporal declarada da 016)
    local org="$1" usuario="$2" r c
    r="$(dop op_cad "SELECT r_resultado || ' ' || coalesce(r_contato::text, coalesce(r_motivo, '')) FROM public.ops_contact_add('$org', 'Contato sintetico',
        2::smallint, 'c-$(openssl rand -hex 4)@contato.test', $usuario, 'CONTRATACAO', true)")"
    c="${r#OK }"; [ "${r%% *}" = OK ] || falha "D: contato recusado: $r"
    dq "BEGIN; SET LOCAL session_replication_role = replica;
        UPDATE ops.org_authorized_contacts SET created_at = created_at - interval '31 days', channel_set_at = channel_set_at - interval '31 days'
         WHERE contact_id = '$c'; COMMIT" > /dev/null
    echo "$c"
}
d_avisos() {   # Platform: emite, reivindica e entrega (ACEITA) os avisos pendentes da organizacao; o titular confirma
    dpapel nekkora_platform "DO \$\$
    DECLARE a RECORD; e RECORD; conf TEXT; cont TEXT; confirmado TEXT := 'nenhum'; n INTEGER := 0;
    BEGIN
        FOR a IN SELECT r_aviso, r_destinatario FROM public.ops_notice_pending(100) WHERE r_organizacao = '$1' LOOP
            conf := translate(rtrim(encode(gen_random_bytes(32), 'base64'), '='), '+/', '-_');
            cont := translate(rtrim(encode(gen_random_bytes(32), 'base64'), '='), '+/', '-_');
            SELECT * INTO e FROM public.ops_notice_issue(a.r_aviso, encode(sha256(convert_to(conf, 'UTF8')), 'hex'), encode(sha256(convert_to(cont, 'UTF8')), 'hex'));
            IF NOT e.r_emitido THEN RAISE EXCEPTION 'aviso nao emitido'; END IF;
            n := n + 1;
            PERFORM public.ops_notice_claim(a.r_aviso, gen_random_uuid());
            IF (SELECT r_resultado FROM public.ops_notice_delivery_result(a.r_aviso, 4::smallint, NULL)) <> 'APLICADO' THEN RAISE EXCEPTION 'entrega nao aplicada'; END IF;
            IF '$2' = 'confirmar' AND e.r_confirmar AND a.r_destinatario = '$3' THEN
                PERFORM set_config('nekkora.organization_id', '$1', true);
                confirmado := public.ops_notice_confirm('$1', encode(sha256(convert_to(conf, 'UTF8')), 'hex'));
            END IF;
        END LOOP;
        IF n = 0 THEN RAISE EXCEPTION 'nenhum aviso pendente para a organizacao'; END IF;
        IF '$2' = 'confirmar' AND confirmado <> 'ACEITO' THEN RAISE EXCEPTION 'confirmacao do titular: %', confirmado; END IF;
        RAISE NOTICE 'avisos: % emitidos (fluxo de banco, entrega simulada); confirmacao do titular: %', n, confirmado;
    END \$\$" > /dev/null 2>> "$D_LOG" || falha "D: avisos da Platform (ver $D_LOG)"
}
d_recuar() {   # fixture temporal declarada da 016 (a de OpsBanco.recuarSolicitacao e fixture_016_solicitacao.sql)
    local i="interval '24 hours 1 minute'"
    dq "BEGIN; SET LOCAL session_replication_role = replica;
        UPDATE ops.ops_mfa_recovery_requests SET opened_at = opened_at - $i, t_total = t_total - $i, verified_at = verified_at - $i,
            preapproved_at = preapproved_at - $i, notice_confirmed_at = notice_confirmed_at - $i, final_approved_at = final_approved_at - $i,
            executed_at = executed_at - $i, closed_at = closed_at - $i WHERE request_id = '$1';
        UPDATE ops.ops_mfa_notices SET created_at = created_at - $i, issued_at = issued_at - $i, state_at = state_at - $i,
            claimed_at = claimed_at - $i, accepted_at = accepted_at - $i WHERE request_id = '$1';
        UPDATE ops.ops_mfa_notice_tokens SET issued_at = issued_at - $i, valid_until = valid_until - $i, used_at = used_at - $i,
            invalidated_at = invalidated_at - $i WHERE request_id = '$1';
        UPDATE ops.ops_mfa_approval_attempts SET accepted_at = accepted_at - $i, approved_at = approved_at - $i WHERE request_id = '$1';
        COMMIT" > /dev/null
}

echo ">> D: base populada COM a 016 -> 017 ($POP16; operadores por certificado TLS)"
d_sobe
HASH_PRE="$(head -c 32 /dev/urandom | shasum -a 256 2>/dev/null | cut -c1-64)"
psqlc "$POP16" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/D01_massa.txt"
modelo "$POP16" nekkora 013 015 "$SAIDA/D01_013_015.txt"
psqlc "$POP16" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 >> "$SAIDA/D01_massa.txt"
modelo "$POP16" nekkora 016 016 "$SAIDA/D01_016.txt"
grep -q 'invariantes_016_pos_retirada=OK' "$SAIDA/D01_016.txt" || falha "D: sec. 19 da 016 pos-retirada"

# --- massa da 016 pelos caminhos reais
: > "$SAIDA/D02_massa_016.txt"
dq "CREATE ROLE op_cad LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    CREATE ROLE op_ver LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    CREATE ROLE op_apr LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    CREATE ROLE op_exe LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    GRANT nekkora_ops_mfa TO op_cad, op_ver, op_apr, op_exe" > /dev/null
dq "SELECT public.ops_bootstrap_operator('op_cad', '$EMISSOR_IDP', 'sub-op_cad')" > /dev/null
d_cert op_cad
for par in op_ver:VERIFICAR,CONSULTAR,CANCELAR op_apr:APROVAR,CONSULTAR op_exe:EXECUTAR,CONSULTAR; do
    papel="${par%%:*}"
    id="$(dop op_cad "SELECT r_resultado || ' ' || coalesce(r_operador::text, coalesce(r_motivo, '')) FROM public.ops_operator_create('$papel', '$EMISSOR_IDP', 'sub-$papel')")"
    d_ok "cadastro $papel" "$id"
    for ato in $(echo "${par#*:}" | tr ',' ' '); do
        d_ok "habilitacao $papel $ato" "$(dop op_cad "SELECT r_resultado || coalesce(' ' || r_motivo, '') FROM public.ops_operator_grant('${id#OK }', '$ato')")"
    done
    d_cert "$papel"
done
read -r ORG1 ADM1 <<< "$(d_org 1)"
read -r ORG2 ADM2 <<< "$(d_org 2)"
C11="$(d_contato "$ORG1" "'$ADM1'")"; C12="$(d_contato "$ORG1" NULL)"
C21="$(d_contato "$ORG2" "'$ADM2'")"; C22="$(d_contato "$ORG2" NULL)"
abre() {   # retorno registrado pelo verificador + abertura com ele + verificacao
    local ret s
    ret="$(dop op_ver "SELECT r_resultado || ' ' || coalesce(r_retorno::text, coalesce(r_motivo, '')) FROM public.ops_mfa_callback_register('$3')")"
    d_ok "retorno $1" "$ret"
    s="$(dop op_ver "SELECT r_resultado || ' ' || coalesce(r_solicitacao::text, coalesce(r_motivo, '')) FROM public.ops_mfa_open('$1', '$2', '$3', NULL, '${ret#OK }')")"
    d_ok "abertura $1" "$s"
    d_ok "verificacao $1" "$(dop op_ver "SELECT r_resultado || coalesce(' ' || r_motivo, '') || ' ' || r_estado FROM public.ops_mfa_verify('${s#OK }', true, '$4', true, 'NENHUM')")"
    echo "${s#OK }"
}
S1="$(abre "$ORG1" "$ADM1" "$C11" "$C12")"
d_ok "pre-aprovacao S1" "$(dop op_apr "SELECT r_resultado || coalesce(' ' || r_motivo, '') || ' ' || r_estado FROM public.ops_mfa_preapprove('$S1')")"
d_avisos "$ORG1" confirmar "$ADM1"
d_recuar "$S1"
d_ok "aprovacao final S1" "$(dop op_apr "SELECT r_resultado || coalesce(' ' || r_motivo, '') || ' ' || r_estado FROM public.ops_mfa_final_approve('$S1')")"
S2="$(abre "$ORG2" "$ADM2" "$C21" "$C22")"
ESTADOS_PRE="$(dq "SELECT string_agg(ops.estado_nome(state), ',' ORDER BY opened_at) FROM ops.ops_mfa_recovery_requests")"
echo "estados antes da 017: $ESTADOS_PRE" >> "$SAIDA/D02_massa_016.txt"

# --- instantaneo (public + ops, colunas anteriores a 017), 017, instantaneo de novo
psqlc "$POP16" nekkora < "$AQUI/sql/c_instantaneo_gera_ops.sql" > "$SAIDA/D03_instantaneo.sql"
psqlc "$POP16" nekkora < "$SAIDA/D03_instantaneo.sql" > "$SAIDA/D03_dados_antes.txt"
D_ATIVAS="$(dq "SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL")"
modelo "$POP16" nekkora 017 017 "$SAIDA/D04_aplica_017.txt"
psqlc "$POP16" nekkora < "$SAIDA/D03_instantaneo.sql" > "$SAIDA/D05_dados_depois.txt"
diff "$SAIDA/D03_dados_antes.txt" "$SAIDA/D05_dados_depois.txt" > "$SAIDA/D05_diff.txt" || falha "D: a 017 alterou dados de public ou ops (ver D05_diff.txt)"
[ "$(dq "SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL")" = "$D_ATIVAS" ] || falha "D: a 017 revogou sessoes"
invariantes "$POP16" D06 com016

# --- caminhos da 016 depois da 017
: > "$SAIDA/D07_caminhos_016.txt"; D_LOG="$SAIDA/D07_caminhos_016.txt"
for papel in op_ver op_apr op_exe; do d_cert "$papel"; done   # registro de certificado depois da 017
d_dep() { case "$2" in OK*) echo "$1: $2" >> "$SAIDA/D07_caminhos_016.txt";; *) falha "D (depois da 017): $1 recusado: $2";; esac; }
d_dep "execucao S1" "$(dop op_exe "SELECT r_resultado || coalesce(' ' || r_motivo, '') || ' ' || r_estado FROM public.ops_mfa_execute('$S1')")"
d_dep "consulta S1" "$(dop op_ver "SELECT r_resultado || ' ' || r_estado || ' reset=' || r_reset_registrado FROM public.ops_mfa_status('$S1')")"
grep -q "consulta S1: OK EXECUTADA reset=true" "$SAIDA/D07_caminhos_016.txt" || falha "D: S1 nao ficou EXECUTADA com reset registrado"
[ "$(dq "SELECT (SELECT count(*) FROM user_mfa_factors WHERE user_id = '$ADM1' AND disabled_at IS NULL)
    + (SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '$ADM1' AND used_at IS NULL AND invalidated_at IS NULL)
    + (SELECT count(*) FROM auth_challenges WHERE user_id = '$ADM1' AND consumed_at IS NULL AND invalidated_at IS NULL)
    + (SELECT count(*) FROM user_sessions WHERE user_id = '$ADM1' AND revoked_at IS NULL)")" = 0 ] || falha "D: efeitos do reset incompletos"
[ "$(dq "SELECT count(*) FROM mfa_events WHERE ops_request_id = '$S1' AND event_type = 4")" = 1 ] || falha "D: evento do reset ausente"
echo "efeitos do reset de S1 (fator, codigos, desafios, sessoes) e mfa_events tipo 4: conferidos" >> "$SAIDA/D07_caminhos_016.txt"
d_dep "pre-aprovacao S2" "$(dop op_apr "SELECT r_resultado || coalesce(' ' || r_motivo, '') || ' ' || r_estado FROM public.ops_mfa_preapprove('$S2')")"
d_avisos "$ORG2" entregar "$ADM2"
# o titular de S1, resetado pela 016, volta pela porta antiga (conta nao vinculada): fator novo e sessao (ramo 1 da 4.4)
cat "$ASSERTS" - <<SQL | docker exec -i "$POP16" psql -X -d nekkora -U nekkora_platform > "$SAIDA/D08_retorno_titular.txt" 2>&1 || falha "D: retorno do titular (ver D08)"
BEGIN;
SELECT set_config('nekkora.organization_id', '$ORG1', true);
INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id)
VALUES ('$ORG1', '$ADM1', 1, gen_random_bytes(36), gen_random_bytes(12), 'k1');
UPDATE user_mfa_factors SET confirmed_at = clock_timestamp(), last_used_step = 1 WHERE user_id = '$ADM1' AND disabled_at IS NULL;
INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
VALUES ('$ORG1', '$ADM1', encode(gen_random_bytes(32), 'hex'), now() + interval '1 hour', now(), repeat('e', 43));
SELECT pg_temp.afirma((SELECT count(*) FROM user_sessions WHERE user_id = '$ADM1' AND revoked_at IS NULL) = 1, 'titular de S1 de volta pela porta antiga');
COMMIT;
SQL
echo "titular de S1 de volta: fator novo e sessao pela porta antiga" >> "$SAIDA/D07_caminhos_016.txt"
ESTADOS_POS="$(dq "SELECT string_agg(ops.estado_nome(state), ',' ORDER BY opened_at) FROM ops.ops_mfa_recovery_requests")"
echo "estados depois: $ESTADOS_POS" >> "$SAIDA/D07_caminhos_016.txt"

# --- catalogo de public e ops = base limpa 001-017 no mesmo cluster; restauracao logica
consulta "$POP16" nekkora "CREATE DATABASE nekkora_ref17" > /dev/null
modelo "$POP16" nekkora_ref17 001 017 "$SAIDA/D09_ref_001_017.txt"
lista "$POP16" nekkora_ref17 > "$SAIDA/D09_catalogo_limpa.txt"; lista "$POP16" nekkora > "$SAIDA/D09_catalogo_atualizada.txt"
diff "$SAIDA/D09_catalogo_limpa.txt" "$SAIDA/D09_catalogo_atualizada.txt" > "$SAIDA/D09_diff_public.txt" || falha "D: catalogo de public difere da base limpa"
psqlc "$POP16" nekkora_ref17 < "$REPO/db/tests/mfa-emergencia-016/sql/c_listagem_ops.sql" > "$SAIDA/D09_ops_limpa.txt"
psqlc "$POP16" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/c_listagem_ops.sql" > "$SAIDA/D09_ops_atualizada.txt"
diff "$SAIDA/D09_ops_limpa.txt" "$SAIDA/D09_ops_atualizada.txt" > "$SAIDA/D09_diff_ops.txt" || falha "D: catalogo de ops difere da base limpa"
docker exec "$POP16" sh -c "psql -X -q -U nekkora -d postgres -c 'CREATE DATABASE restaurada_d' && pg_dump -U nekkora -Fc nekkora | pg_restore -U nekkora -d restaurada_d --exit-on-error" \
    > "$SAIDA/D10_restauracao.txt" 2>&1 || { cat "$SAIDA/D10_restauracao.txt"; falha "D: restauracao logica"; }
CONTA_D="SELECT string_agg(format('%s.%s=%s', n.nspname, c.relname, (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname), false, true, '')))[1]::text), ',' ORDER BY n.nspname, c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname IN ('public', 'ops') AND c.relkind = 'r'"
[ "$(consulta "$POP16" nekkora "$CONTA_D")" = "$(consulta "$POP16" restaurada_d "$CONTA_D")" ] || falha "D: contagens da restauracao divergem"
# chaves privadas da PKI descartavel nao ficam como evidencia (so certificados e registros)
find "$PKI" -name '*.key' -delete; rm -f "$PKI"/op/*.csr "$PKI"/server.csr
echo "   016 -> 017: dados de public e ops identicos, invariantes 012-017, caminhos da 016 depois (execucao, consulta, pre-aprovacao, avisos), catalogo = limpa, restauracao: OK"
D_RESUMO="D: base com a 016 ($(cat "$SAIDA/D01_massa.txt" | tr '\n' ' ')); massa da 016 pelos caminhos reais: $(grep -c ': OK' "$SAIDA/D02_massa_016.txt") atos aceitos; estados antes [$ESTADOS_PRE]; tabelas no instantaneo (public+ops): $(wc -l < "$SAIDA/D03_dados_antes.txt" | tr -d ' '), diff vazio
D: depois da 017: $(grep -c ': OK' "$SAIDA/D07_caminhos_016.txt") atos da 016 aceitos (3 registros de certificado, execucao com os efeitos do reset, consulta, pre-aprovacao); $(grep -o 'avisos: [0-9]* emitidos' "$SAIDA/D07_caminhos_016.txt") pelo fluxo de banco sob nekkora_platform (entrega simulada), titular de volta pela porta antiga; estados [$ESTADOS_POS]
D: invariantes 012/013/014/016/017 depois da retirada; catalogo de public e de ops = base limpa 001-017; restauracao logica: OK"
