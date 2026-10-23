#!/usr/bin/env bash
# Suite de banco da 022_ativacao_organizacoes.sql (candidata). Dois clusters PostgreSQL 16 DESCARTAVEIS e isolados
# (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal nem os
# ambientes de homologacao.
#
# Modelo de aplicacao: o da 016 revisao 5 (db/bootstrap/aplicar_como_migrador.sh), SUPERUSER em 011-013, 016-020 e 022,
# BYPASSRLS so na 014, retirada conferida em 0|0|0; a sec. 19 da 016 roda de novo DEPOIS de tudo (o entrypoint a reexecuta).
#
#   A (limpa):    001-022 pelo entrypoint. Invariantes 012 (§15), 013 (§8), 014 (§8, adaptada como na 017), 016 (§19, SEM
#                 adaptacao: a 022 nao toca o inventario de ops nem a matriz de public.ops_*), 017 (§12) e 019 (§4) com as
#                 adaptacoes da 020, 020 (§4), 021 (§5) e 022 (§10) DEPOIS da retirada. Marcador versao=022.
#   B (populada): 001-012 + massa (atualizacao-populada/p01: 3 organizacoes com limites UNLIMITED), 013-015 + massa da era
#                 MFA, 016-021, mais duas organizacoes de controle (limites 1-3 FINITE 0; e sem nenhuma revisao) e a
#                 identidade de backup da topologia (backup_nekkora em pg_read_all_data).
#     B1 - 022 SEM a janela: recusada por privilegio, nada muda.
#     B2 - 022 com a janela: dados de public e ops identicos; classificacao: as 3 da massa ATIVADA (origem 2), as 2 de
#          controle AGUARDANDO_ATIVACAO; diff de catalogo de public EXATAMENTE o previsto (esperado/B22_diff_esperado.txt);
#          catalogos de public e de ops_activation = instalacao limpa A; invariantes; marcador versao=022.
#     B3 - reaplicar a 022 e recusado pela pre-condicao; nada muda.
#   C (comportamento no banco, sem certificado de cliente):
#     C1 - organizacao criada depois da 022 (provision_organization, papel real nekkora_provisioning) nasce AGUARDANDO_ATIVACAO;
#     C2 - capacidade de execucao barrada enquanto aguarda: provision_resource_limit pelo papel de provisionamento com
#          recurso 1-3 diferente de FINITE 0 -> 42501; FINITE 0 e os recursos 4-5 passam; o superusuario nao e barrado;
#     C3 - estado protegido: UPDATE direto (mesmo do superusuario), DELETE e TRUNCATE recusados;
#     C4 - ato chamado sem certificado (op_* membro do grupo): RECUSA SEM_CERTIFICADO auditada; nada muda;
#     C5 - quem nao e do grupo (Platform, provisionamento, op_* fora do grupo) nao executa o ato (42501);
#     C6 - Platform: org_activation_states devolve so o estado; nenhuma leitura das tabelas de ops_activation.
# O ato autorizado (certificado registrado, responsavel provado, repeticao, concorrencia, recusas) e coberto pelos testes
# Java de nekkora-ops (OrgActivationIT), com operadores reais.
#
# Uso: db/tests/ativacao-022/run.sh   (Windows/Git Bash: ver README da suite)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
INSTANTANEO="$REPO/db/tests/relogio-018/sql/c_instantaneo_gera.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018 019 020 022}"
PROJETO="${TESTE_PROJETO:-nekkora-a022}"
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
for n in $(seq -w 1 22); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
M022="$SAIDA/mig/022_ativacao_organizacoes.sql"
[ -f "$M022" ] || falha "022 ausente"
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
sed "s/n.nspname = 'public'/n.nspname = 'ops_activation'/g; s/schemaname = 'public'/schemaname = 'ops_activation'/g" "$LISTAGEM" > "$SAIDA/listagem_ops_activation.sql"
grep -q "ops_activation" "$SAIDA/listagem_ops_activation.sql" || falha "listagem de ops_activation nao gerada"
sha256sum "$SAIDA"/mig/0*.sql 2>/dev/null | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt" \
    || shasum -a 256 "$SAIDA"/mig/0*.sql | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt"

psqlc() { local c="$1" db="$2"; shift 2; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$db" "$@"; }
consulta() { psqlc "$1" "$2" -c "$3" < /dev/null; }
lista_de() { psqlc "$1" nekkora < "$2" | awk '/^(rel|relacl|col|colacl|con|idx|trg|pol|fn|fnacl|type|nsp|nspacl|ext|defacl|dbacl)\|/ { if (l != "") print l; l = $0; next } { l = l " " $0 } END { if (l != "") print l }'; }
lista() { lista_de "$1" "$LISTAGEM"; }
lista_ops() { lista_de "$1" "$SAIDA/listagem_ops_activation.sql"; }
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
invariantes() {   # $1 container, $2 nome; sempre DEPOIS da retirada
    awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/014_mfa_sessoes.sql" > "$SAIDA/_inv014.sql"
    awk '/^-- === 12\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/017_identidade_cadastro.sql" > "$SAIDA/_inv017.sql"
    awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/019_identidade_regeneracao_codigos.sql" > "$SAIDA/_inv019.sql"
    { awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql"
      awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/013_portal_externo.sql"
      adapta "$SAIDA/_inv014.sql" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname AND p.policyname NOT IN ('ops_function_context', 'identity_function_context')) <> 1"
      awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql"
      adapta "$SAIDA/_inv017.sql" "        'identity_password_change(text,text,text), '" \
          "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '
        'identity_recovery_codes_regenerate(text,text[]), '"
      adapta "$SAIDA/_inv019.sql" "        'identity_password_change(text,text,text), '" \
          "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '" > "$SAIDA/_inv019b.sql"
      adapta "$SAIDA/_inv019b.sql" "'CHECK (((event_type >= 1) AND (event_type <= 15)))'" "'CHECK (((event_type >= 1) AND (event_type <= 17)))'"
      awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/020_identidade_recuperacao_senha.sql"
      awk '/^-- === 5\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/021_correio_fila.sql"
      awk '/^-- === 10\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/022_ativacao_organizacoes.sql"
    } > "$SAIDA/${2}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${2}_invariantes.sql")" = 9 ] || falha "extracao dos invariantes ($2) falhou"
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

# ================================================================== A. instalacao limpa 001-022
echo ">> A: instalacao limpa 001-022 ($LIMPA)"
sobe "$LIMPA" 22 022
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" > "$SAIDA/ambiente.txt"
grep -q "aplicada versao=022 elevada=SUPERUSER" "$SAIDA/entrypoint_$LIMPA.txt" || falha "022 sem a janela de SUPERUSER"
grep -q "invariantes_016_pos_retirada=OK" "$SAIDA/entrypoint_$LIMPA.txt" || falha "016 §19 pos-retirada ausente (depois da 022)"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=022" ] || falha "marcador da instalacao limpa"
invariantes "$LIMPA" A01
lista "$LIMPA" > "$SAIDA/A02_catalogo_022.txt"
lista_ops "$LIMPA" > "$SAIDA/A03_catalogo_ops_activation.txt"
[ "$(consulta "$LIMPA" nekkora "SELECT count(*) FROM ops_activation.organization_states")" = 0 ] || falha "A: estados sem organizacao"

# ================================================================== B. base populada 021 -> 022
echo ">> B: base populada 001-021 -> 022 ($POP)"
sobe "$POP" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" 013 015 "$SAIDA/B02_013_015.txt" || { cat "$SAIDA/B02_013_015.txt"; falha "013-015 na populada"; }
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
modelo "$POP" 016 021 "$SAIDA/B04_016_021.txt" || { cat "$SAIDA/B04_016_021.txt"; falha "016-021 na populada"; }
# identidade de backup da topologia (pg_read_all_data), ANTES da 022: as invariantes nao podem contar essa heranca
consulta "$POP" nekkora "CREATE ROLE backup_nekkora LOGIN BYPASSRLS; GRANT pg_read_all_data TO backup_nekkora" > /dev/null
# organizacoes de controle: zero (1-3 FINITE 0, 4-5 UNLIMITED) e sem nenhuma revisao
ZERO="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"; SEM="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
RESP="$(consulta "$POP" nekkora "SELECT user_id FROM users ORDER BY user_id LIMIT 1")"
psqlc "$POP" nekkora > /dev/null <<SQL
BEGIN;
INSERT INTO organizations (organization_id, name, status) VALUES ('$ZERO', 'Controle zero', 1), ('$SEM', 'Controle sem revisao', 1);
INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, provisioned_by_user_id)
SELECT '$ZERO', c, 1, now(), CASE WHEN c <= 3 THEN 1 ELSE 2 END, CASE WHEN c <= 3 THEN 0 END, '$RESP' FROM generate_series(1, 5) c;
COMMIT;
SQL
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B05_instantaneo.sql"
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B05_dados_021.txt"
lista "$POP" > "$SAIDA/B05_catalogo_021.txt"

echo "   B1: 022 sem a janela"
if modelo "$POP" 022 022 "$SAIDA/B10_022_sem_janela.txt" "011 012 013 016 017 018 019 020"; then falha "B1: 022 aplicada sem elevacao"; fi
grep -qiE "permission denied|must be able to SET ROLE|must be owner" "$SAIDA/B10_022_sem_janela.txt" || { cat "$SAIDA/B10_022_sem_janela.txt"; falha "B1: recusa diferente da de privilegio"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B1: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B11_dados.txt"
diff "$SAIDA/B05_dados_021.txt" "$SAIDA/B11_dados.txt" > /dev/null || falha "B1: dados alterados"
lista "$POP" > "$SAIDA/B11_catalogo.txt"
diff "$SAIDA/B05_catalogo_021.txt" "$SAIDA/B11_catalogo.txt" > /dev/null || falha "B1: catalogo alterado"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM pg_namespace WHERE nspname = 'ops_activation'")" = 0 ] || falha "B1: schema criado"

echo "   B2: 022 com a janela"
modelo "$POP" 022 022 "$SAIDA/B20_022.txt" || { cat "$SAIDA/B20_022.txt"; falha "022 na populada"; }
grep -q "aplicada versao=022 elevada=SUPERUSER" "$SAIDA/B20_022.txt" || falha "B2: 022 sem janela"
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B2: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=022" ] || falha "B2: marcador"
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B21_dados_022.txt"
diff "$SAIDA/B05_dados_021.txt" "$SAIDA/B21_dados_022.txt" > "$SAIDA/B21_diff_dados.txt" || falha "B2: a 022 alterou dados de public/ops (ver B21_diff_dados.txt)"
consulta "$POP" nekkora "SELECT o.name || '|' || s.state || '|' || s.origin || '|' || (s.activated_at IS NOT NULL) FROM ops_activation.organization_states s
    JOIN organizations o USING (organization_id) ORDER BY o.name" > "$SAIDA/B22_classificacao.txt"
[ "$(grep -c '|2|2|true$' "$SAIDA/B22_classificacao.txt")" = 3 ] && grep -qx 'Controle zero|1|2|false' "$SAIDA/B22_classificacao.txt" \
    && grep -qx 'Controle sem revisao|1|2|false' "$SAIDA/B22_classificacao.txt" && [ "$(wc -l < "$SAIDA/B22_classificacao.txt" | tr -d ' ')" = 5 ] \
    || { cat "$SAIDA/B22_classificacao.txt"; falha "B2: classificacao das organizacoes existentes"; }
lista "$POP" > "$SAIDA/B23_catalogo_022.txt"
diff "$SAIDA/B05_catalogo_021.txt" "$SAIDA/B23_catalogo_022.txt" > "$SAIDA/B23_diff_catalogo.txt" || true
grep -E '^[<>]' "$SAIDA/B23_diff_catalogo.txt" | sed -E 's/\|md5=[0-9a-f]+/|md5=.../' | sort > "$SAIDA/B23_diff_resumo.txt"
if [ -f "$AQUI/esperado/B23_diff_esperado.txt" ]; then
    sort "$AQUI/esperado/B23_diff_esperado.txt" > "$SAIDA/_b.txt"
    diff "$SAIDA/_b.txt" "$SAIDA/B23_diff_resumo.txt" > "$SAIDA/B23_fora_do_previsto.txt" || { cat "$SAIDA/B23_fora_do_previsto.txt"; falha "B2: catalogo 021 -> 022 fora do previsto"; }
else
    falha "B2: esperado/B23_diff_esperado.txt ausente (resumo gerado em $SAIDA/B23_diff_resumo.txt para revisao)"
fi
diff "$SAIDA/A02_catalogo_022.txt" "$SAIDA/B23_catalogo_022.txt" > "$SAIDA/B24_diff_limpa.txt" || falha "B2: catalogo de public da atualizada difere da instalacao limpa (ver B24)"
lista_ops "$POP" > "$SAIDA/B25_catalogo_ops_activation.txt"
diff "$SAIDA/A03_catalogo_ops_activation.txt" "$SAIDA/B25_catalogo_ops_activation.txt" > "$SAIDA/B25_diff_limpa.txt" || falha "B2: catalogo de ops_activation difere da instalacao limpa (ver B25)"
invariantes "$POP" B26

echo "   B3: reaplicacao da 022"
if psqlc "$POP" nekkora < "$M022" > "$SAIDA/B30_reaplica.txt" 2>&1; then falha "B3: 022 reaplicada"; fi
grep -qF "022: objetos da ativacao ja existem" "$SAIDA/B30_reaplica.txt" || { cat "$SAIDA/B30_reaplica.txt"; falha "B3: recusa diferente da pre-condicao"; }
lista "$POP" > "$SAIDA/B31_catalogo.txt"
diff "$SAIDA/B23_catalogo_022.txt" "$SAIDA/B31_catalogo.txt" > /dev/null || falha "B3: catalogo alterado"

# ================================================================== C. comportamento no banco (sem certificado)
echo ">> C: comportamento ($POP)"
: > "$SAIDA/C_recusas.txt"
NOVA="$(consulta "$POP" nekkora "SELECT gen_random_uuid()")"
psqlc "$POP" nekkora > "$SAIDA/C10_provision_organization.txt" 2>&1 <<SQL || { cat "$SAIDA/C10_provision_organization.txt"; falha "C1: provision_organization"; }
SET ROLE nekkora_provisioning;
SELECT provision_organization('$NOVA', 'Criada depois da 022', NULL, '$RESP',
    ARRAY['(1,1,0)', '(2,1,0)', '(3,1,0)', '(4,2,)', '(5,2,)']::resource_limit_input[]);
SQL
[ "$(consulta "$POP" nekkora "SELECT state || '|' || origin FROM ops_activation.organization_states WHERE organization_id = '$NOVA'")" = "1|1" ] \
    || falha "C1: organizacao nova sem AGUARDANDO_ATIVACAO/origem 1"
recusa_sql "$POP" "C2 provisionamento com WORKERS > 0 aguardando ativacao" "capacidade de execucao (recurso 1) so pela ativacao autorizada" \
    "SET ROLE nekkora_provisioning; SELECT provision_resource_limit('$NOVA', 1, 1::smallint, 5, '$RESP');"
recusa_sql "$POP" "C2 provisionamento com CONCURRENT UNLIMITED aguardando ativacao" "capacidade de execucao (recurso 2) so pela ativacao autorizada" \
    "SET ROLE nekkora_provisioning; SELECT provision_resource_limit('$NOVA', 2, 2::smallint, NULL, '$RESP');"
psqlc "$POP" nekkora > "$SAIDA/C21_permitidos.txt" 2>&1 <<SQL || { cat "$SAIDA/C21_permitidos.txt"; falha "C2: FINITE 0 e recursos 4-5 deveriam passar"; }
SET ROLE nekkora_provisioning;
SELECT provision_resource_limit('$NOVA', 3, 1::smallint, 0, '$RESP') IS NOT NULL;
SELECT provision_resource_limit('$NOVA', 4, 1::smallint, 1000, '$RESP') IS NOT NULL;
SQL
psqlc "$POP" nekkora > "$SAIDA/C22_superusuario.txt" 2>&1 <<SQL || { cat "$SAIDA/C22_superusuario.txt"; falha "C2: superusuario barrado"; }
BEGIN;
INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, provisioned_by_user_id)
VALUES ('$NOVA', 1, 99, now(), 1, 7, '$RESP');
ROLLBACK;
SQL
recusa_sql "$POP" "C3 UPDATE direto do estado pelo superusuario" "ativacao so pelo ato autorizado" \
    "UPDATE ops_activation.organization_states SET state = 2, activated_at = now(), activation_event_id = gen_random_uuid() WHERE organization_id = '$NOVA';"
recusa_sql "$POP" "C3 DELETE do estado" "estado de ativacao nao e apagado" "DELETE FROM ops_activation.organization_states WHERE organization_id = '$NOVA';"
recusa_sql "$POP" "C3 TRUNCATE do estado" "truncate nao permitido em organization_states" "TRUNCATE ops_activation.organization_states;"
recusa_sql "$POP" "C3 volta de ATIVADA" "organizacao ja ativada nao muda de estado" \
    "UPDATE ops_activation.organization_states SET state = 1, activated_at = NULL WHERE state = 2 AND origin = 2;"
consulta "$POP" nekkora "CREATE ROLE op_c4_teste LOGIN; GRANT nekkora_org_activation TO op_c4_teste; CREATE ROLE op_c5_fora LOGIN; GRANT nekkora_ops_mfa TO op_c5_fora" > /dev/null
psqlc "$POP" nekkora > "$SAIDA/C40_sem_certificado.txt" 2>&1 <<SQL || { cat "$SAIDA/C40_sem_certificado.txt"; falha "C4: chamada do ato"; }
SET ROLE op_c4_teste;
SELECT r_resultado || '|' || r_motivo FROM public.org_activation_activate('$NOVA', '$RESP', 'teste sem certificado', 1, 1, 1, NULL, NULL);
SQL
grep -qx 'RECUSA|SEM_CERTIFICADO' "$SAIDA/C40_sem_certificado.txt" || { cat "$SAIDA/C40_sem_certificado.txt"; falha "C4: recusa diferente de SEM_CERTIFICADO"; }
[ "$(consulta "$POP" nekkora "SELECT result || '|' || reason_code || '|' || session_role FROM ops_activation.activation_events WHERE organization_id = '$NOVA'")" = "RECUSA|SEM_CERTIFICADO|nekkora" ] \
    || falha "C4: recusa nao auditada"
[ "$(consulta "$POP" nekkora "SELECT state FROM ops_activation.organization_states WHERE organization_id = '$NOVA'")" = 1 ] || falha "C4: estado mudou"
for p in nekkora_platform nekkora_provisioning op_c5_fora; do
    recusa_sql "$POP" "C5 $p nao executa o ato" "permission denied for function org_activation_activate" \
        "SET ROLE $p; SELECT * FROM public.org_activation_activate('$NOVA', '$RESP', 'x', 1, 1, 1, NULL, NULL);"
done
[ "$(printf "SET ROLE nekkora_platform;\nSELECT organization_id || '|' || activation_state FROM public.org_activation_states(ARRAY['$NOVA', '$ZERO']::uuid[]) ORDER BY 1;\n" | psqlc "$POP" nekkora | grep -v '^SET$' | sort | tr '\n' ' ')" \
    = "$(printf '%s|1\n%s|1\n' "$NOVA" "$ZERO" | sort | tr '\n' ' ')" ] || falha "C6: estados pela Platform"
recusa_sql "$POP" "C6 Platform le a tabela de estados" "permission denied for schema ops_activation" "SET ROLE nekkora_platform; SELECT * FROM ops_activation.organization_states;"
recusa_sql "$POP" "C6 Platform le a auditoria" "permission denied for schema ops_activation" "SET ROLE nekkora_platform; SELECT * FROM ops_activation.activation_events;"
consulta "$POP" nekkora "DROP ROLE op_c4_teste; DROP ROLE op_c5_fora" > /dev/null 2>&1 || consulta "$POP" nekkora "REVOKE nekkora_org_activation FROM op_c4_teste; REVOKE nekkora_ops_mfa FROM op_c5_fora" > /dev/null
{
    echo "A: 22 migrations (001-022); janelas SUPERUSER com retirada 0|0|0: $(grep -c 'SUPERUSER revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt") (022 com janela); sec. 19 da 016 depois de tudo: OK; invariantes 012/013/014(adaptada)/016/017(adaptada)/019(adaptada)/020/021/022 DEPOIS da retirada ($(cat "$SAIDA/A01_estado_migrador.txt")): OK; marcador versao=022"
    echo "B: $(cat "$SAIDA/B01_massa_001_012.txt")"
    echo "B1: 022 sem janela recusada por privilegio; dados, catalogo e schema inalterados"
    echo "B2: dados de public/ops identicos; classificacao: $(tr '\n' ';' < "$SAIDA/B22_classificacao.txt"); catalogo de public 021->022 = $(wc -l < "$SAIDA/B23_diff_resumo.txt" | tr -d ' ') linhas previstas; public e ops_activation = instalacao limpa; invariantes ($(cat "$SAIDA/B26_estado_migrador.txt")): OK"
    echo "B3: reaplicacao recusada pela pre-condicao"
    echo "C: $(grep -c . "$SAIDA/C_recusas.txt") recusas conferidas (capacidade, estado protegido, chamadores fora do grupo, Platform sem leitura direta); C1 nova AGUARDANDO; C4 SEM_CERTIFICADO auditada; C6 estados pela Platform"
} | tee "$SAIDA/resumo.txt"
rm -f "$SAIDA/_b.txt"
docker rm -f -v "$LIMPA" "$POP" > /dev/null
echo "saidas: $SAIDA"
