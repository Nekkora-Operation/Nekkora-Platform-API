#!/usr/bin/env bash
# Suite de banco da 018_instantes_terminais_relogio.sql (candidata). Dois clusters PostgreSQL 16 DESCARTAVEIS e isolados
# (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal, o hml03
# nem o ambiente preservado.
#
# Modelo de aplicacao: o da 016 revisao 5 (db/bootstrap/aplicar_como_migrador.sh), SUPERUSER so em 011, 012, 013, 016,
# 017 e 018, BYPASSRLS so na 014, retirada conferida em 0|0|0.
#
#   A (limpa):    001-018 pelo entrypoint. Invariantes 012 (§15), 013 (§8), 014 (§8, adaptacao da 017), 016 (§19),
#                 017 (§12) e 018 (§4) DEPOIS da retirada. Marcador versao=018.
#   B (populada): 001-012 + massa (atualizacao-populada/p01), 013-015 + massa da era MFA (mfa-emergencia-016/p02), 016-017;
#                 instantaneo de dados (public e ops) e catalogo de public.
#     B1 - 018 SEM a janela de SUPERUSER: recusada por privilegio (42501), nada muda (dados, catalogo, migrador 0|0|0).
#     B2 - 018 com a janela: dados identicos; diff de catalogo EXATAMENTE as duas funcoes (corpo) e os dois SELECT
#          (created_at) do dono operacional; catalogo = instalacao limpa A; invariantes depois da retirada; marcador 018.
#     B3 - reaplicar a 018 (superusuario, transacao propria) e recusado pela pre-condicao (os quatro corpos); nada muda.
#     B4 - (antes de B1) cada uma das quatro funcoes com um trecho alterado FORA das expressoes da 018 (autorizacao,
#          step-up, lock, auditoria), aceito pela pre-condicao anterior: 018 com a janela recusada pela comparacao
#          integral (SHA-256 fixado); dados, funcoes, privilegios, marcador e migrador inalterados; corpo restaurado.
# O comportamento (recuo do relogio, controles negativos) e coberto pelos testes Java nas bases 001-018:
# RecuoDoRelogioIT (nekkora-ops) e RecuoDoRelogioIdentidadeIT/RecuoDoRelogioOrgIT (nekkora-platform).
#
# Uso: db/tests/relogio-018/run.sh  |  TESTE_PROJETO=nekkora-r018b db/tests/relogio-018/run.sh
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018}"
PROJETO="${TESTE_PROJETO:-nekkora-r018}"
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
for n in $(seq -w 1 18); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
M018="$SAIDA/mig/018_instantes_terminais_relogio.sql"
[ -f "$M018" ] || falha "018 ausente"
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
sha256sum "$SAIDA"/mig/0*.sql 2>/dev/null | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt" \
    || shasum -a 256 "$SAIDA"/mig/0*.sql | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt"

psqlc() { local c="$1" db="$2"; shift 2; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$db" "$@"; }
consulta() { psqlc "$1" "$2" -c "$3" < /dev/null; }
lista() { psqlc "$1" "$2" < "$LISTAGEM" | awk '/^(rel|relacl|col|colacl|con|idx|trg|pol|fn|fnacl|type|nsp|nspacl|ext|defacl|dbacl)\|/ { if (l != "") print l; l = $0; next } { l = l " " $0 } END { if (l != "") print l }'; }
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
invariantes() {   # $1 container, $2 nome; sempre DEPOIS da retirada
    { awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql"
      awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/013_portal_externo.sql"
      awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/014_mfa_sessoes.sql" > "$SAIDA/_inv014.sql"
      "$PY" - "$SAIDA/_inv014.sql" <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
a = "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1"
b = ("(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname "
     "AND p.policyname NOT IN ('ops_function_context', 'identity_function_context')) <> 1")
assert s.count(a) == 1, 'trecho da 014 a adaptar nao encontrado exatamente uma vez'
sys.stdout.write(s.replace(a, b))
PY
      awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql"
      awk '/^-- === 12\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/017_identidade_cadastro.sql"
      # 018 §4: depende da tabela temporaria da propria migration; aqui so os blocos que nao a usam (a partir do segundo DO)
      echo "CREATE TEMP TABLE n018_antes AS SELECT p.oid, p.proname, pg_get_userbyid(p.proowner) AS dono, p.prosecdef, p.proconfig,"
      echo "  p.proacl::text AS acl, p.provolatile, obj_description(p.oid, 'pg_proc') AS comentario FROM pg_proc p"
      echo "  WHERE p.oid IN ('public.ops_mfa_execute(uuid)'::regprocedure, 'public.identity_mfa_replace(text, uuid, text[])'::regprocedure,"
      echo "                  'public.ops_operator_revoke(uuid, text)'::regprocedure, 'public.ops_operator_disable(uuid)'::regprocedure);"
      awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/018_instantes_terminais_relogio.sql"
    } > "$SAIDA/${2}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${2}_invariantes.sql")" = 6 ] || falha "extracao dos invariantes ($2) falhou"
    estado_migrador "$1" > "$SAIDA/${2}_estado_migrador.txt"
    [ "$(cat "$SAIDA/${2}_estado_migrador.txt")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "invariantes ($2) fora do estado pos-retirada"
    { echo "BEGIN;"; cat "$SAIDA/${2}_invariantes.sql"; echo "ROLLBACK;"; } | psqlc "$1" nekkora > "$SAIDA/${2}_invariantes.txt" 2>&1 \
        || { cat "$SAIDA/${2}_invariantes.txt"; falha "invariantes ($2) falharam"; }
}
marcador() { consulta "$1" nekkora "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }

# ================================================================== A. instalacao limpa 001-018
echo ">> A: instalacao limpa 001-018 ($LIMPA)"
sobe "$LIMPA" 18 018
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" > "$SAIDA/ambiente.txt"
grep -q "fim    versao=018 SUPERUSER revogado, estado 0|0|0" "$SAIDA/entrypoint_$LIMPA.txt" || falha "janela da 018 sem retirada 0|0|0"
grep -q "invariantes_016_pos_retirada=OK" "$SAIDA/entrypoint_$LIMPA.txt" || falha "016 §19 pos-retirada ausente"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=018" ] || falha "marcador da instalacao limpa"
invariantes "$LIMPA" A01
lista "$LIMPA" nekkora > "$SAIDA/A02_catalogo_018.txt"

# ================================================================== B. base populada 017 -> 018
echo ">> B: base populada 001-017 -> 018 ($POP)"
sobe "$POP" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | shasum -a 256 2>/dev/null | cut -c1-64 || head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" 013 015 "$SAIDA/B02_013_015.txt" || { cat "$SAIDA/B02_013_015.txt"; falha "013-015 na populada"; }
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
modelo "$POP" 016 017 "$SAIDA/B04_016_017.txt" || { cat "$SAIDA/B04_016_017.txt"; falha "016-017 na populada"; }
psqlc "$POP" nekkora < "$AQUI/sql/c_instantaneo_gera.sql" > "$SAIDA/B05_instantaneo.sql"
[ "$(wc -l < "$SAIDA/B05_instantaneo.sql")" -ge 60 ] || falha "gerador de instantaneo incompleto"
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B05_dados_017.txt"
lista "$POP" nekkora > "$SAIDA/B05_catalogo_017.txt"
VIVOS="$(consulta "$POP" nekkora "SELECT (SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL) || '|' || (SELECT count(*) FROM user_mfa_factors WHERE disabled_at IS NULL) || '|' || (SELECT count(*) FROM user_mfa_recovery_codes WHERE used_at IS NULL AND invalidated_at IS NULL)")"
echo "$VIVOS" > "$SAIDA/B05_vivos_sessoes_fatores_codigos.txt"
[ "$(echo "$VIVOS" | cut -d'|' -f1)" -gt 0 ] && [ "$(echo "$VIVOS" | cut -d'|' -f2)" -gt 0 ] || falha "massa sem sessao ou fator ativo"

echo "   B4: corpo da 016/017 alterado FORA das expressoes da 018 (autorizacao, lock, auditoria)"
# Cada caso: substitui UMA funcao (superusuario, CREATE OR REPLACE: dono e ACL preservados) mudando um trecho que nao e
# o encerramento ajustado pela 018; confere que a pre-condicao anterior (trecho unico por funcao) ainda o aceitaria; aplica
# a 018 COM a janela e exige a recusa da pre-condicao integral; dados, funcoes, privilegios, marcador e migrador
# inalterados; restaura o corpo original e exige o catalogo da 017 de volta.
adultera() {   # $1 caso, $2 assinatura, $3 padrao da pre-condicao anterior, $4 trecho original, $5 substituto
    local def="$SAIDA/B4_$1_original.sql" adul="$SAIDA/B4_$1_adulterada.sql"
    consulta "$POP" nekkora "SELECT pg_get_functiondef('$2'::regprocedure)" > "$def"
    "$PY" - "$def" "$adul" "$4" "$5" <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
assert s.count(sys.argv[3]) == 1, 'trecho a adulterar nao encontrado exatamente uma vez'
open(sys.argv[2], 'w', encoding='utf-8').write(s.replace(sys.argv[3], sys.argv[4]) + ';\n')
PY
    psqlc "$POP" nekkora < "$adul" > /dev/null || falha "B4 $1: adulteracao nao aplicada"
    [ "$(consulta "$POP" nekkora "SELECT prosrc ~ '$3' FROM pg_proc WHERE oid = '$2'::regprocedure")" = t ] \
        || falha "B4 $1: a pre-condicao anterior ja recusaria (caso nao discrimina)"
    psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B4_$1_dados_antes.txt"
    lista "$POP" nekkora > "$SAIDA/B4_$1_catalogo_antes.txt"
    local marca; marca="$(marcador "$POP")"
    { diff "$SAIDA/B05_catalogo_017.txt" "$SAIDA/B4_$1_catalogo_antes.txt" || true; } | grep -c '^[<>] fn|' | grep -qx 2 || falha "B4 $1: adulteracao mudou mais que um corpo"
    if modelo "$POP" 018 018 "$SAIDA/B4_$1_018.txt"; then falha "B4 $1: 018 aplicada sobre corpo adulterado"; fi
    grep -qF "ERROR:  018: corpo diferente do da 016/017 em $2" "$SAIDA/B4_$1_018.txt" \
        || { cat "$SAIDA/B4_$1_018.txt"; falha "B4 $1: recusa diferente da pre-condicao integral"; }
    grep -q "fim    versao=018 SUPERUSER revogado, estado 0|0|0" "$SAIDA/B4_$1_018.txt" || falha "B4 $1: janela sem retirada 0|0|0"
    [ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B4 $1: migrador fora de 0|0|0"
    [ "$(marcador "$POP")" = "$marca" ] || falha "B4 $1: marcador mudou"
    psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B4_$1_dados_depois.txt"
    diff "$SAIDA/B05_dados_017.txt" "$SAIDA/B4_$1_dados_depois.txt" > /dev/null || falha "B4 $1: dados alterados"
    lista "$POP" nekkora > "$SAIDA/B4_$1_catalogo_depois.txt"
    diff "$SAIDA/B4_$1_catalogo_antes.txt" "$SAIDA/B4_$1_catalogo_depois.txt" > /dev/null \
        || falha "B4 $1: funcoes ou privilegios alterados pela 018 recusada"
    { echo "BEGIN;"; cat "$def"; echo ";"; echo "COMMIT;"; } | psqlc "$POP" nekkora > /dev/null || falha "B4 $1: restauracao"
    lista "$POP" nekkora > "$SAIDA/B4_$1_catalogo_restaurado.txt"
    diff "$SAIDA/B05_catalogo_017.txt" "$SAIDA/B4_$1_catalogo_restaurado.txt" > /dev/null || falha "B4 $1: restauracao incompleta"
    echo "$1 $2: recusada ($(grep -m1 'ERROR:' "$SAIDA/B4_$1_018.txt" | sed 's/^ERROR: *//')); dados, funcoes, privilegios, marcador e migrador inalterados" \
        >> "$SAIDA/B4_resumo.txt"
}
: > "$SAIDA/B4_resumo.txt"
adultera execucao_autorizacao 'public.ops_mfa_execute(uuid)' 'UPDATE public\.user_mfa_factors SET disabled_at = v_agora\s' \
    "    IF v_motivo IS NULL AND ops.tem_vinculo(v_op, v_org) THEN v_motivo := 'VINCULO_IMPEDITIVO'; END IF;
" ""
adultera troca_stepup 'public.identity_mfa_replace(text, uuid, text[])' 'UPDATE public\.identity_mfa_factors SET disabled_at = v_t WHERE factor_id = v_old;' \
    "    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'troca') THEN
        RETURN false;
    END IF;
" ""
adultera retirada_lock 'public.ops_operator_revoke(uuid, text)' 'UPDATE ops\.ops_operator_permissions SET revoked_at = v_agora, revoked_by = v_op' \
    "    PERFORM ops.travar_operadores(ARRAY[v_op], ARRAY[p_operator]);
" ""
adultera desativacao_auditoria 'public.ops_operator_disable(uuid)' 'UPDATE ops\.ops_operator_permissions SET revoked_at = v_agora, revoked_by = v_op' \
    "        PERFORM ops.registrar_cadastro('CERTIFICADO_REVOGADO', 'OK', NULL, 1::smallint, v_op, p_operator, NULL, v_cert);" "        NULL;"
[ "$(wc -l < "$SAIDA/B4_resumo.txt" | tr -d ' ')" = 4 ] || falha "B4: casos incompletos"

echo "   B1: 018 sem a janela de SUPERUSER"
if modelo "$POP" 018 018 "$SAIDA/B10_018_sem_elevacao.txt" "011 012 013 016 017"; then falha "018 aplicada sem elevacao"; fi
# 42501: a primeira recusa medida e a do parametro de configuracao da funcao (nekkora.fn_organization_id); sem ela, a
# substituicao exigiria ser o dono. As duas sao de privilegio; nenhuma outra falha e aceita
grep -qE "ERROR:  (permission denied to set parameter \"nekkora\.fn_organization_id\"|must be owner of function)" "$SAIDA/B10_018_sem_elevacao.txt" \
    || { cat "$SAIDA/B10_018_sem_elevacao.txt"; falha "B1: recusa diferente da esperada (privilegio)"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B1: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B11_dados.txt"
diff "$SAIDA/B05_dados_017.txt" "$SAIDA/B11_dados.txt" > /dev/null || falha "B1: dados alterados"
lista "$POP" nekkora > "$SAIDA/B11_catalogo.txt"
diff "$SAIDA/B05_catalogo_017.txt" "$SAIDA/B11_catalogo.txt" > /dev/null || falha "B1: catalogo alterado"

echo "   B2: 018 com a janela"
modelo "$POP" 018 018 "$SAIDA/B20_018.txt" || { cat "$SAIDA/B20_018.txt"; falha "018 na populada"; }
grep -q "fim    versao=018 SUPERUSER revogado, estado 0|0|0" "$SAIDA/B20_018.txt" || falha "B2: janela da 018 sem retirada 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=018" ] || falha "B2: marcador"
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B21_dados_018.txt"
diff "$SAIDA/B05_dados_017.txt" "$SAIDA/B21_dados_018.txt" > "$SAIDA/B21_diff_dados.txt" || falha "B2: a 018 alterou dados (ver B21_diff_dados.txt)"
lista "$POP" nekkora > "$SAIDA/B22_catalogo_018.txt"
diff "$SAIDA/B05_catalogo_017.txt" "$SAIDA/B22_catalogo_018.txt" > "$SAIDA/B22_diff_catalogo.txt" || true
grep -E '^[<>]' "$SAIDA/B22_diff_catalogo.txt" | sed -E 's/\|md5=[0-9a-f]+/|md5=.../' > "$SAIDA/B22_diff_resumo.txt"
cat > "$SAIDA/B22_diff_esperado.txt" <<'ESP'
< fn|identity_mfa_replace(text,uuid,text[])|owner=nekkora_identity_owner|secdef=t|vol=v|strict=f|lang=plpgsql|config={"search_path=pg_catalog, pg_temp"}|result=boolean|md5=...
< fn|ops_mfa_execute(uuid)|owner=nekkora_ops_mfa_owner|secdef=t|vol=v|strict=f|lang=plpgsql|config={"search_path=pg_catalog, pg_temp",nekkora.fn_organization_id=}|result=TABLE(r_resultado text, r_motivo text, r_solicitacao uuid, r_estado text)|md5=...
> colacl|user_mfa_factors|created_at|nekkora_ops_mfa_owner|SELECT|grant=f
> colacl|user_mfa_recovery_codes|created_at|nekkora_ops_mfa_owner|SELECT|grant=f
> fn|identity_mfa_replace(text,uuid,text[])|owner=nekkora_identity_owner|secdef=t|vol=v|strict=f|lang=plpgsql|config={"search_path=pg_catalog, pg_temp"}|result=boolean|md5=...
> fn|ops_mfa_execute(uuid)|owner=nekkora_ops_mfa_owner|secdef=t|vol=v|strict=f|lang=plpgsql|config={"search_path=pg_catalog, pg_temp",nekkora.fn_organization_id=}|result=TABLE(r_resultado text, r_motivo text, r_solicitacao uuid, r_estado text)|md5=...
< fn|ops_operator_disable(uuid)|owner=nekkora_ops_mfa_owner|secdef=t|vol=v|strict=f|lang=plpgsql|config={"search_path=pg_catalog, pg_temp"}|result=TABLE(r_resultado text, r_motivo text)|md5=...
< fn|ops_operator_revoke(uuid,text)|owner=nekkora_ops_mfa_owner|secdef=t|vol=v|strict=f|lang=plpgsql|config={"search_path=pg_catalog, pg_temp"}|result=TABLE(r_resultado text, r_motivo text)|md5=...
> fn|ops_operator_disable(uuid)|owner=nekkora_ops_mfa_owner|secdef=t|vol=v|strict=f|lang=plpgsql|config={"search_path=pg_catalog, pg_temp"}|result=TABLE(r_resultado text, r_motivo text)|md5=...
> fn|ops_operator_revoke(uuid,text)|owner=nekkora_ops_mfa_owner|secdef=t|vol=v|strict=f|lang=plpgsql|config={"search_path=pg_catalog, pg_temp"}|result=TABLE(r_resultado text, r_motivo text)|md5=...
ESP
sort "$SAIDA/B22_diff_resumo.txt" > "$SAIDA/_a.txt"; sort "$SAIDA/B22_diff_esperado.txt" > "$SAIDA/_b.txt"
diff "$SAIDA/_b.txt" "$SAIDA/_a.txt" > "$SAIDA/B22_fora_do_previsto.txt" || { cat "$SAIDA/B22_fora_do_previsto.txt"; falha "B2: catalogo 017 -> 018 fora do previsto"; }
[ "$(grep -c '^< fn' "$SAIDA/B22_diff_catalogo.txt")" = 4 ] && [ "$(grep '^< fn' "$SAIDA/B22_diff_catalogo.txt" | cut -c3- | sed 's/md5=.*//')" = "$(grep '^> fn' "$SAIDA/B22_diff_catalogo.txt" | cut -c3- | sed 's/md5=.*//')" ] \
    || falha "B2: atributos das funcoes mudaram (so o corpo pode mudar)"
diff "$SAIDA/A02_catalogo_018.txt" "$SAIDA/B22_catalogo_018.txt" > "$SAIDA/B23_diff_limpa.txt" || falha "B2: catalogo da atualizada difere da instalacao limpa (ver B23)"
invariantes "$POP" B24
VIVOS2="$(consulta "$POP" nekkora "SELECT (SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL) || '|' || (SELECT count(*) FROM user_mfa_factors WHERE disabled_at IS NULL) || '|' || (SELECT count(*) FROM user_mfa_recovery_codes WHERE used_at IS NULL AND invalidated_at IS NULL)")"
[ "$VIVOS2" = "$VIVOS" ] || falha "B2: sessoes, fatores ou codigos vivos mudaram"

echo "   B3: reaplicacao da 018"
if psqlc "$POP" nekkora < "$M018" > "$SAIDA/B30_reaplica.txt" 2>&1; then falha "B3: 018 reaplicada"; fi
grep -qF "018: corpo diferente do da 016/017 em public.identity_mfa_replace(text, uuid, text[]), public.ops_mfa_execute(uuid), public.ops_operator_disable(uuid), public.ops_operator_revoke(uuid, text)" "$SAIDA/B30_reaplica.txt" || { cat "$SAIDA/B30_reaplica.txt"; falha "B3: recusa diferente da pre-condicao"; }
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B31_dados.txt"
diff "$SAIDA/B21_dados_018.txt" "$SAIDA/B31_dados.txt" > /dev/null || falha "B3: dados alterados"
lista "$POP" nekkora > "$SAIDA/B31_catalogo.txt"
diff "$SAIDA/B22_catalogo_018.txt" "$SAIDA/B31_catalogo.txt" > /dev/null || falha "B3: catalogo alterado"

{
    echo "A: 18 migrations (001-018); janelas SUPERUSER com retirada 0|0|0: $(grep -c 'SUPERUSER revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt"); invariantes 012/013/014(adaptada)/016/017/018 DEPOIS da retirada ($(cat "$SAIDA/A01_estado_migrador.txt")): OK; marcador versao=018"
    echo "B: $(cat "$SAIDA/B01_massa_001_012.txt")"
    echo "B: $(cat "$SAIDA/B03_massa_mfa.txt")"
    echo "B: tabelas no instantaneo (public e ops): $(wc -l < "$SAIDA/B05_dados_017.txt" | tr -d ' '); vivos sessoes|fatores|codigos: $VIVOS"
    sed 's/^/B4: /' "$SAIDA/B4_resumo.txt"
    echo "B1: 018 sem SUPERUSER recusada: $(grep -m1 "ERROR:" "$SAIDA/B10_018_sem_elevacao.txt" | sed "s/^ERROR: *//"); dados e catalogo identicos; migrador 0|0|0"
    echo "B2: 018 com a janela: diff de dados vazio; catalogo 017->018 = exatamente $(wc -l < "$SAIDA/B22_diff_resumo.txt" | tr -d ' ') linhas previstas (4 corpos, 2 SELECT created_at); = instalacao limpa; invariantes DEPOIS da retirada ($(cat "$SAIDA/B24_estado_migrador.txt")): OK; vivos inalterados; marcador versao=018"
    echo "B3: reaplicacao recusada pela pre-condicao integral (os quatro corpos); nada muda"
} | tee "$SAIDA/resumo.txt"
rm -f "$SAIDA/_a.txt" "$SAIDA/_b.txt"
docker rm -f -v "$LIMPA" "$POP" > /dev/null
echo "saidas: $SAIDA"
