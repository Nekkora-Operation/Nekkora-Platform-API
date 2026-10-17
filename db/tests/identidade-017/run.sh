#!/usr/bin/env bash
# Suite de banco da 017_identidade_cadastro.sql (candidata). Tres clusters PostgreSQL 16 DESCARTAVEIS e isolados
# (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal, o hml03
# nem o ambiente preservado.
#
# MODELO SUPORTADO (o da 016 revisao 5): toda base e montada pela identidade de implantacao migrador_nekkora, dona do
# banco, com SUPERUSER so nas versoes elevadas (011, 012, 013, 016, 017) e BYPASSRLS so na 014, com retirada conferida.
#
#   A (limpa):    001-015 + 017. Catalogo de public x 001-015 no mesmo cluster (so acrescimos previstos; as unicas
#                 remocoes sao as versoes antigas das duas funcoes de user_sessions substituidas); invariantes da
#                 012 (§15), 013 (§8), 014 (§8, adaptacao unica: politica identity_function_context) e 017 (§12) DEPOIS da
#                 retirada. Em seguida, com os papeis reais: grupos A, B, C, C', D, E (depois de CADA cenario), G, H, I, J,
#                 K, L e M do desenho (secao 10.1).
#   B (populada): 001-012 + massa (atualizacao-populada/p01), 013-015 + massa da era MFA (mfa-emergencia-016/p02),
#                 instantaneo, 017, instantaneo de novo (identico nas colunas anteriores; nenhuma sessao revogada),
#                 catalogo = instalacao limpa, invariantes, conta atual ainda abre sessao (ramo 1) e restauracao logica.
#   C (compat):   001-017, COM a 016 (ordem de aplicacao em base persistente ainda a decidir): instalacao e invariantes
#                 012/013/014/016/017.
#
# Arquivos vao para o container por "docker cp" (nao por bind mount): no Docker Desktop para macOS o entrypoint tratou o
# script montado como executavel e falhou com "bad interpreter"; a copia preserva o modo 644 e o script e carregado.
#
# Uso: db/tests/identidade-017/run.sh  |  TESTE_PROJETO=nekkora-id017b db/tests/identidade-017/run.sh
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
ASSERTS="$REPO/db/tests/seguranca-012/sql/_asserts.inc.sql"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017}"
PROJETO="${TESTE_PROJETO:-nekkora-id017}"
LIMPA="${PROJETO}-limpa"
POP="${PROJETO}-populada"
COMPAT="${PROJETO}-compat"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine
O1=17000000-0000-0000-0000-000000000001
PY=python3; "$PY" -c 1 >/dev/null 2>&1 || PY=python

falha() {
    echo "ERRO: $*" >&2
    echo "containers mantidos para inspecao. Remocao: docker rm -f -v $LIMPA $POP $COMPAT ${PROJETO}-pop016" >&2
    exit 1
}
case "$PROJETO" in nekkora-platform|nekkora|*hml03*) falha "projeto '$PROJETO' reservado";; esac
for c in "$LIMPA" "$POP" "$COMPAT"; do
    [ "$c" != "nekkora-postgres" ] || falha "container da stack principal"
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done

mkdir -p "$SAIDA/mig_alvo" "$SAIDA/mig_016"
for n in $(seq -w 1 17); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig_016/"
    [ "$n" = 16 ] || cp "${f[0]}" "$SAIDA/mig_alvo/"
done
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"

psqlc() { local c="$1" db="$2"; shift 2; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$db" "$@"; }
consulta() { psqlc "$1" "$2" -c "$3" < /dev/null; }
com_inc() { cat "$ASSERTS" "$AQUI/sql/_fixture.inc.sql" "$@"; }
# listagem de catalogo com um fato por linha: o texto das politicas multilinha (qual/with_check) e juntado a linha do fato
lista() { psqlc "$1" "$2" < "$LISTAGEM" | awk '/^(rel|relacl|col|colacl|con|idx|trg|pol|fn|fnacl|type|nsp|nspacl|ext|defacl|dbacl)\|/ { if (l != "") print l; l = $0; next } { l = l " " $0 } END { if (l != "") print l }'; }
sobe() {   # $1 container, $2 diretorio de migrations, $3 quantidade esperada, $4 ultima versao aplicada no entrypoint
    docker create --name "$1" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
        -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE="$4" \
        -e NEKKORA_ELEVAR="$ELEVAR" -e NEKKORA_MIGRACOES=/nekkora-migracoes \
        "$IMAGEM" postgres -c cluster_name="$1" > /dev/null
    docker cp "$2" "$1":/nekkora-migracoes > /dev/null
    docker cp "$SAIDA/modelo.sh" "$1":/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh > /dev/null
    docker cp "$SAIDA/modelo.sh" "$1":/tmp/modelo.sh > /dev/null
    docker cp "$AQUI/sql/e_check.sql" "$1":/tmp/e_check.sql > /dev/null
    docker cp "$AQUI/sql/e.sh" "$1":/tmp/e.sh > /dev/null
    docker start "$1" > /dev/null
    for _ in $(seq 1 180); do
        [ "$(docker logs "$1" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
        docker ps -q -f "name=^$1\$" | grep -q . || break
        sleep 1
    done
    docker logs "$1" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|sourcing|aplicar_como_migrador|ERROR|FATAL|init process complete" > "$SAIDA/entrypoint_$1.txt" || true
    grep -qE "ERROR|FATAL|aplicar_como_migrador: FALHA" "$SAIDA/entrypoint_$1.txt" && falha "erro na aplicacao das migrations em $1 (ver entrypoint_$1.txt)"
    [ "$(grep -c 'aplicar_como_migrador: aplicada versao=' "$SAIDA/entrypoint_$1.txt")" = "$3" ] || falha "esperadas $3 migrations em $1"
    grep -q "aplicar_como_migrador: fim db=nekkora aplicadas=$3 estado=0|0|0" "$SAIDA/entrypoint_$1.txt" || falha "migrador nao terminou 0|0|0 em $1"
    [ "$(consulta "$1" nekkora 'SHOW cluster_name')" = "$1" ] || falha "cluster inesperado em $1"
}
modelo() {   # $1 container, $2 banco, $3 desde, $4 ate, $5 arquivo de saida
    docker exec -e NEKKORA_DB="$2" -e NEKKORA_DESDE="$3" -e NEKKORA_ATE="$4" -e NEKKORA_ELEVAR="$ELEVAR" \
        -e NEKKORA_MIGRACOES=/nekkora-migracoes "$1" sh /tmp/modelo.sh > "$5" 2>&1 || { cat "$5"; falha "aplicacao $3-$4 em $1/$2"; }
    grep -q "aplicar_como_migrador: fim db=$2 .* estado=0|0|0" "$5" || falha "migrador nao terminou 0|0|0 ($1/$2 $3-$4)"
}
estado_migrador() {
    consulta "$1" nekkora "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')
        || ' dono_do_banco=' || pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()"
}
invariantes() {   # $1 container, $2 nome, $3 "com016" para incluir a 016; sempre DEPOIS da retirada
    local alvo="$1" nome="$2" com016="${3:-}" esperado=4
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
      if [ "$com016" = com016 ]; then
          awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql"; esperado=5
      fi
      awk '/^-- === 12\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/017_identidade_cadastro.sql"
    } > "$SAIDA/${nome}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${nome}_invariantes.sql")" = "$esperado" ] || falha "extracao dos invariantes ($nome) falhou"
    estado_migrador "$alvo" > "$SAIDA/${nome}_estado_migrador.txt"
    [ "$(cat "$SAIDA/${nome}_estado_migrador.txt")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "invariantes ($nome) fora do estado pos-retirada"
    psqlc "$alvo" nekkora < "$SAIDA/${nome}_invariantes.sql" > "$SAIDA/${nome}_invariantes.txt" 2>&1 || { cat "$SAIDA/${nome}_invariantes.txt"; falha "invariantes ($nome) falharam"; }
}
roda() {   # $1 container, $2 papel, $3 arquivo (em sql/), $4 nome da saida; confere o marcador de E depois
    local c="$1" papel="$2" arq="$3" nome="${4:-$(basename "$3" .sql)}"
    echo "   $nome ($papel)"
    com_inc "$AQUI/sql/$arq" | docker exec -i "$c" psql -X -d nekkora -U "$papel" > "$SAIDA/$nome.txt" 2>&1 || { tail -20 "$SAIDA/$nome.txt"; falha "$nome falhou (ver $nome.txt)"; }
    docker exec "$c" sh -c 'test ! -e /tmp/E_FALHOU' || { docker exec "$c" cat /tmp/e.log | tail -5; falha "invariante E violado depois de $nome"; }
}
e_check() { docker exec "$LIMPA" sh /tmp/e.sh "$1" >> "$SAIDA/C_e.txt"; docker exec "$LIMPA" sh -c 'test ! -e /tmp/E_FALHOU' || falha "invariante E violado em $1"; }
verifica() {   # $1 rotulo, $2 condicao SQL (superusuario) que precisa ser verdadeira
    local r; r="$(consulta "$LIMPA" nekkora "SELECT ($2)::text")" || falha "verificacao $1 nao executou"
    [ "$r" = true ] || falha "verificacao $1: $2"
    echo "$1: verificado" >> "$SAIDA/C_verificacoes.txt"
}
# --- concorrencia: duas transacoes de nekkora_platform (t_conc.sql), observador de lock e verificacao do estado final
T_PIDS=()
transacao() {   # $1 rotulo, $2 atraso, $3 segura, $4 iso, $5 acao
    com_inc "$AQUI/sql/t_conc.sql" | docker exec -i -e PGAPPNAME="$1" "$LIMPA" psql -X -d nekkora -U nekkora_platform -v ON_ERROR_STOP=1 \
        -v rotulo="$1" -v atraso="$2" -v segura="$3" -v iso="$4" -v acao="$5" > "$SAIDA/conc_$1.txt" 2>&1 &
    T_PIDS+=("$!")
}
espera_lock() {   # $1 application_name que precisa ser visto ESPERANDO lock (ate ~8 s)
    local r
    for _ in $(seq 1 40); do
        r="$(consulta "$LIMPA" nekkora "SELECT coalesce(string_agg(application_name || ':' || coalesce(wait_event_type, '-') || ':' || coalesce(wait_event, '-'), ','), 'nada')
                FROM pg_stat_activity WHERE application_name LIKE '${1%-T*}-T%'" 2>&1)"
        echo "$(date +%T) $r" >> "$SAIDA/C_observador_leituras.txt"
        case ",$r," in *",$1:Lock:"*) echo "$1: observado esperando lock ($r)" >> "$SAIDA/C_observador.txt"; return 0;; esac
        sleep 0.2
    done
    falha "observador: $1 nao foi visto esperando lock (ver C_observador_leituras.txt)"
}
fim_transacoes() {
    local p rc=0
    for p in "${T_PIDS[@]}"; do wait "$p" || rc=1; done
    T_PIDS=()
    [ "$rc" = 0 ] || { tail -5 "$SAIDA"/conc_*.txt; falha "transacao concorrente falhou (ver conc_*.txt)"; }
}
conc() {   # $1 nome, $2 acao T1 (segura $6 s, padrao 4), $3 acao T2 (comeca 1 s depois), $4 quem espera (T1|T2), $5 iso de T2
    transacao "$1-T1" 0 "${6:-4}" "READ COMMITTED" "$2"
    transacao "$1-T2" 1 0 "${5:-READ COMMITTED}" "$3"
    espera_lock "$1-$4"
    fim_transacoes
}
seq_tx() { transacao "$1" 0 0 "${3:-READ COMMITTED}" "$2"; fim_transacoes; }
conta() { echo "pg_temp.conta('$1', '$O1')"; }
vincula() { echo "SELECT pg_temp.step_up('sessao/$1'); SELECT pg_temp.afirma(pg_temp.vincula(pg_temp.identidade('$1'), 'sessao/$1', $(conta "$1"), '$O1', 'conc/$1'), 'vinculou $1');"; }
escrita() { echo "SELECT pg_temp.espera_erro((pg_temp.escritas($(conta "$1"), '$2'))[$3], '${4:-23514}', '${5:-conta vinculada}');"; }

{
    echo "data_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit=$(cd "$REPO" && git rev-parse HEAD 2>/dev/null || echo 'sem git nesta arvore')"
    echo "versoes_elevadas=$ELEVAR versoes_bypassrls=014 (padrao do script)"
    echo "containers=$LIMPA $POP $COMPAT (rede none, sem porta, sem volume nomeado) imagem=$IMAGEM"
    echo "migracoes:"; (cd "$SAIDA/mig_016" && shasum -a 256 0*.sql 2>/dev/null || sha256sum 0*.sql) | sed 's/^/  /'
} > "$SAIDA/ambiente.txt"

if [ "${TESTE_SO_D:-0}" != 1 ]; then
# ================================================================== A. instalacao limpa 001-015 + 017
echo ">> A: instalacao limpa 001-015 + 017 ($LIMPA)"
sobe "$LIMPA" "$SAIDA/mig_alvo" 16 017
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" >> "$SAIDA/ambiente.txt"
consulta "$LIMPA" nekkora "CREATE DATABASE nekkora_ref" > /dev/null
modelo "$LIMPA" nekkora_ref 001 015 "$SAIDA/A01_ref_001_015.txt"
lista "$LIMPA" nekkora_ref > "$SAIDA/A01_catalogo_001_015.txt"
lista "$LIMPA" nekkora > "$SAIDA/A01_catalogo_017.txt"
diff "$SAIDA/A01_catalogo_001_015.txt" "$SAIDA/A01_catalogo_017.txt" > "$SAIDA/A01_diff_catalogo.txt" || true
REMOCAO_PREVISTA='^< fn\|(enforce_user_session_mfa_on_insert|prevent_user_session_reactivation)\(\)\|'
ACRESCIMO_PREVISTO='identit|refuse_l|enforce_user_session_mfa_on_insert|prevent_user_session_reactivation|prevent_local_token_change|prevent_user_credential_reassignment|trg_(password_reset|email_verification)_tokens_prevent_change|trg_user_credentials_prevent_reassignment'
{
    echo "removidas (<): $(grep -c '^<' "$SAIDA/A01_diff_catalogo.txt" || true)"
    echo "removidas fora do previsto: $(grep '^<' "$SAIDA/A01_diff_catalogo.txt" | grep -vcE "$REMOCAO_PREVISTA" || true)"
    echo "acrescentadas (>): $(grep -c '^>' "$SAIDA/A01_diff_catalogo.txt" || true)"
    echo "acrescentadas fora do previsto: $(grep '^>' "$SAIDA/A01_diff_catalogo.txt" | grep -vcE "$ACRESCIMO_PREVISTO" || true)"
    echo "por tipo:"; grep '^>' "$SAIDA/A01_diff_catalogo.txt" | cut -c3- | cut -d'|' -f1 | sort | uniq -c
} > "$SAIDA/A01_diff_resumo.txt"
[ "$(grep '^<' "$SAIDA/A01_diff_catalogo.txt" | grep -cE "$REMOCAO_PREVISTA" || true)" = 2 ] || falha "remocoes previstas (duas funcoes substituidas) ausentes"
[ "$(grep '^<' "$SAIDA/A01_diff_catalogo.txt" | grep -vcE "$REMOCAO_PREVISTA" || true)" = 0 ] || falha "a 017 alterou/removeu objeto de 001-015 fora do previsto"
[ "$(grep '^>' "$SAIDA/A01_diff_catalogo.txt" | grep -vcE "$ACRESCIMO_PREVISTO" || true)" = 0 ] || falha "a 017 acrescentou objeto fora do previsto"
[ "$(grep -c '^> pol|.*|identity_function_context|' "$SAIDA/A01_diff_catalogo.txt")" = 11 ] || falha "esperadas 11 politicas identity_function_context"
[ "$(grep -c '^> rel|identit[a-z_]*|r|' "$SAIDA/A01_diff_catalogo.txt")" = 10 ] || falha "esperadas 10 tabelas novas"
invariantes "$LIMPA" A02
echo "   catalogo e invariantes: OK"

echo ">> A: comportamento com os papeis reais"
psqlc "$LIMPA" nekkora -v org_id=99999999-0000-0000-0000-000000000099 -v org_name='Nekkora Internal' \
    -v staff_user_id=99999999-0000-0000-0000-0000000000aa -v staff_email=staff@nekkora.internal -v staff_name='Nekkora Staff' \
    -v limites='{"(1,1,0)","(2,1,0)","(3,1,0)","(4,2,)","(5,2,)"}' < "$REPO/db/bootstrap/001_organizacao_interna_nekkora.sql" > "$SAIDA/f00_bootstrap.txt" 2>&1 \
    || falha "bootstrap falhou"
roda "$LIMPA" nekkora_provisioning f01_provisiona.sql
roda "$LIMPA" nekkora_platform     f02_fixture_platform.sql
roda "$LIMPA" nekkora              f03_fixture_superusuario.sql
roda "$LIMPA" nekkora_platform     f04_vinculo_paula.sql
roda "$LIMPA" nekkora_platform     t_a_sessao_org.sql
roda "$LIMPA" nekkora_platform     t_b_fechamento.sql
roda "$LIMPA" nekkora_platform     t_c2_contorno.sql
roda "$LIMPA" nekkora_platform     t_d_recente.sql
roda "$LIMPA" nekkora_platform     t_g_restrita.sql
roda "$LIMPA" nekkora_platform     t_h_fator.sql
roda "$LIMPA" nekkora              t_h_dono.sql
roda "$LIMPA" nekkora_platform     t_i_criacao.sql
roda "$LIMPA" nekkora              t_i_verifica.sql
roda "$LIMPA" nekkora_platform     t_j_contexto.sql
roda "$LIMPA" nekkora_platform     t_t_r5.sql
verifica T-E "(SELECT count(*) FROM identity_events e JOIN identities i ON i.identity_id = e.identity_id
    WHERE i.email = 't1@ex.test' AND e.event_type IN (13, 14)) = 2"

echo "   grupo L (autorizacao, sem efeito)"
roda "$LIMPA" nekkora_platform t_l_prep.sql
roda "$LIMPA" nekkora          t_l_prep_super.sql
psqlc "$LIMPA" nekkora < "$AQUI/sql/c_instantaneo_gera.sql" > "$SAIDA/L00_instantaneo.sql"
psqlc "$LIMPA" nekkora < "$SAIDA/L00_instantaneo.sql" > "$SAIDA/L01_antes.txt"
roda "$LIMPA" nekkora_platform t_l_autorizacao.sql
psqlc "$LIMPA" nekkora < "$SAIDA/L00_instantaneo.sql" > "$SAIDA/L02_depois.txt"
diff "$SAIDA/L01_antes.txt" "$SAIDA/L02_depois.txt" > "$SAIDA/L03_diff.txt" || falha "grupo L: chamada sem prova teve efeito (ver L03_diff.txt)"

echo "   grupo C (concorrencia com a vinculacao; T2 sem AuthService e sem lock da aplicacao)"
conc C-1 "$(vincula c1@ex.test)" "$(escrita c1@ex.test C-1 1)" T2
verifica C-1 "NOT EXISTS (SELECT 1 FROM auth_challenges c JOIN users u ON u.user_id = c.user_id WHERE u.email = 'c1@ex.test' AND c.consumed_at IS NULL AND c.invalidated_at IS NULL)"
e_check C-1
aceita() { echo "SELECT pg_temp.executa((pg_temp.escritas($(conta "$1"), '$2'))[$3]);"; }

seq_tx C-2a "$(aceita c2@ex.test C-2 1)"
seq_tx C-2b "$(vincula c2@ex.test)"
verifica C-2 "EXISTS (SELECT 1 FROM auth_challenges c JOIN users u ON u.user_id = c.user_id WHERE u.email = 'c2@ex.test' AND c.invalidated_at IS NOT NULL)
    AND NOT EXISTS (SELECT 1 FROM auth_challenges c JOIN users u ON u.user_id = c.user_id WHERE u.email = 'c2@ex.test' AND c.consumed_at IS NULL AND c.invalidated_at IS NULL)"
e_check C-2
seq_tx C-3a "SELECT pg_temp.sessao_org('$O1', $(conta c3@ex.test), NULL, 'C-3');"
seq_tx C-3b "$(vincula c3@ex.test)"
verifica C-3 "(SELECT revoked_at IS NOT NULL FROM user_sessions s JOIN users u ON u.user_id = s.user_id WHERE u.email = 'c3@ex.test')"
e_check C-3
conc C-4 "$(vincula c4@ex.test)" "SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, NULL, ''C-4'')', '$O1', $(conta c4@ex.test)), '23514', 'fator de MFA confirmado');" T2
verifica C-4 "NOT EXISTS (SELECT 1 FROM user_sessions s JOIN users u ON u.user_id = s.user_id WHERE u.email = 'c4@ex.test')"
e_check C-4
conc C-5 "$(aceita c5@ex.test C-5 1)" "$(vincula c5@ex.test)" T2
verifica C-5 "NOT EXISTS (SELECT 1 FROM auth_challenges c JOIN users u ON u.user_id = c.user_id WHERE u.email = 'c5@ex.test' AND c.consumed_at IS NULL AND c.invalidated_at IS NULL)
    AND EXISTS (SELECT 1 FROM auth_challenges c JOIN users u ON u.user_id = c.user_id WHERE u.email = 'c5@ex.test')"
e_check C-5
seq_tx C-6p "SELECT pg_temp.step_up('sessao/c6@ex.test');
    SELECT pg_temp.nova_sessao_id(pg_temp.identidade('c6@ex.test'), 'C-6/b', true);
    SELECT pg_temp.desafio_vinculo(pg_temp.identidade('c6@ex.test'), $(conta c6@ex.test), '$O1', 'C-6/a');
    SELECT pg_temp.desafio_vinculo(pg_temp.identidade('c6@ex.test'), $(conta c6@ex.test), '$O1', 'C-6/b');"
conc C-6 "SELECT pg_temp.afirma(identity_link_account(pg_temp.tok('sessao/c6@ex.test'), pg_temp.tok('C-6/a'), '$O1'), 'C-6 T1 vinculou');" \
         "SELECT pg_temp.afirma(NOT identity_link_account(pg_temp.tok('C-6/b'), pg_temp.tok('C-6/b'), '$O1'), 'C-6 T2 recusada sob o lock');" T2
verifica C-6 "(SELECT count(*) FROM identity_users iu JOIN users u ON u.user_id = iu.user_id WHERE u.email = 'c6@ex.test') = 1"
e_check C-6
seq_tx C-7p "SELECT pg_temp.step_up('sessao/c7@ex.test');
    SELECT pg_temp.nova_sessao_id(pg_temp.identidade('c7@ex.test'), 'C-7/b', true);
    SELECT pg_temp.desafio_vinculo(pg_temp.identidade('c7@ex.test'), $(conta c7@ex.test), '$O1', 'C-7/x');"
conc C-7 "SELECT pg_temp.afirma(identity_link_account(pg_temp.tok('sessao/c7@ex.test'), pg_temp.tok('C-7/x'), '$O1'), 'C-7 T1 consumiu');" \
         "SELECT pg_temp.afirma(NOT identity_link_account(pg_temp.tok('C-7/b'), pg_temp.tok('C-7/x'), '$O1'), 'C-7 T2 sem efeito');" T2
verifica C-7 "(SELECT count(*) FROM identity_link_challenges WHERE token_hash = encode(sha256(convert_to(left(translate(encode(sha256(convert_to('C-7/x', 'UTF8')), 'base64'), '+/', '-_'), 42) || 'A', 'UTF8')), 'hex') AND consumed_at IS NOT NULL) = 1
    AND (SELECT count(*) FROM identity_users iu JOIN users u ON u.user_id = iu.user_id WHERE u.email = 'c7@ex.test') = 1"
e_check C-7
conc C-8 "$(vincula c8@ex.test)" "$(escrita c8@ex.test C-8 7)" T2
verifica C-8 "(SELECT uc.password_hash FROM user_credentials uc JOIN users u ON u.user_id = uc.user_id WHERE u.email = 'c8@ex.test') = '\$argon2id\$conta\$c8@ex.test'"
e_check C-8
transacao C-9-T2 0 0 "REPEATABLE READ" "SELECT pg_temp.ctx('$O1'); SELECT count(*) FROM users; SELECT pg_sleep(3); $(escrita c9@ex.test C-9 2 40001 'could not serialize')"
transacao C-9-T1 1 0 "READ COMMITTED" "$(vincula c9@ex.test)"
fim_transacoes
verifica C-9 "NOT EXISTS (SELECT 1 FROM user_mfa_factors f JOIN users u ON u.user_id = f.user_id WHERE u.email = 'c9@ex.test' AND f.disabled_at IS NULL)"
e_check C-9
seq_tx C-10 "SELECT pg_temp.espera_erro(format('SELECT pg_temp.vincula(%L, %L, %L, %L, %L)', pg_temp.identidade('c10@ex.test'), 'sessao/c10@ex.test', $(conta c10@ex.test), '$O1', 'conc/c10'), '25N01', 'read committed');" "REPEATABLE READ"
verifica C-10 "NOT EXISTS (SELECT 1 FROM identity_users iu JOIN users u ON u.user_id = iu.user_id WHERE u.email = 'c10@ex.test')"
e_check C-10
for par in 2:c11 4:c12 5:c13 6:c14; do
    conc "C-11-${par#*:}" "$(vincula "${par#*:}@ex.test")" "$(escrita "${par#*:}@ex.test" "C-11" "${par%%:*}")" T2
    e_check "C-11-${par#*:}"
done

echo "   grupo K (concorrencia pela mesma identidade)"
seq_tx K-1p "SELECT pg_temp.nova_identidade('k1@ex.test', false); SELECT pg_temp.desafio_cadastro(pg_temp.identidade('k1@ex.test'), 'K-1/cad');
    SELECT identity_mfa_enroll_pending(pg_temp.tok('K-1/cad'), NULL, decode(repeat('61', 36), 'hex'), decode(repeat('06', 12), 'hex'), 'k1');"
conc K-1 "SELECT pg_temp.afirma(pg_temp.cf(pg_temp.tok('K-1/cad'), pg_temp.pendente_de('k1@ex.test'), pg_temp.codigos('K-1/a'), 'K-1/a/s') = 'SESSAO_CRIADA', 'K-1 T1 confirmou');" \
         "SELECT pg_temp.afirma(pg_temp.cf(pg_temp.tok('K-1/cad'), pg_temp.pendente_de('k1@ex.test'), pg_temp.codigos('K-1/b'), 'K-1/b/s') <> 'SESSAO_CRIADA', 'K-1 T2 recusada');" T2
KF="(SELECT identity_id FROM identities WHERE email = 'kX@ex.test')"
verifica K-1 "(SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${KF/kX/k1} AND confirmed_at IS NOT NULL) = 1
    AND (SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = ${KF/kX/k1}) = 8
    AND EXISTS (SELECT 1 FROM identity_mfa_recovery_codes WHERE identity_id = ${KF/kX/k1} AND code_hash = encode(sha256(convert_to('K-1/a/1', 'UTF8')), 'hex'))"
e_check K-1
seq_tx K-2p "SELECT pg_temp.nova_identidade('k2@ex.test'); SELECT pg_temp.nova_sessao_id(pg_temp.identidade('k2@ex.test'), 'K-2/a', true);
    SELECT pg_temp.nova_sessao_id(pg_temp.identidade('k2@ex.test'), 'K-2/b', true);
    SELECT identity_mfa_enroll_pending(NULL, pg_temp.tok('K-2/a'), decode(repeat('62', 36), 'hex'), decode(repeat('06', 12), 'hex'), 'k1');"
conc K-2 "SELECT pg_temp.afirma(identity_mfa_replace(pg_temp.tok('K-2/a'), pg_temp.pendente_de('k2@ex.test'), pg_temp.codigos('K-2/a')), 'K-2 T1 trocou');" \
         "SELECT pg_temp.afirma(NOT identity_mfa_replace(pg_temp.tok('K-2/b'), pg_temp.pendente_de('k2@ex.test'), pg_temp.codigos('K-2/b')), 'K-2 T2 recusada');" T2
verifica K-2 "(SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${KF/kX/k2} AND confirmed_at IS NOT NULL AND disabled_at IS NULL) = 1
    AND (SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = ${KF/kX/k2} AND used_at IS NULL AND invalidated_at IS NULL) = 8
    AND EXISTS (SELECT 1 FROM identity_mfa_recovery_codes WHERE identity_id = ${KF/kX/k2} AND invalidated_at IS NULL AND code_hash = encode(sha256(convert_to('K-2/a/1', 'UTF8')), 'hex'))"
e_check K-2
seq_tx K-3p "SELECT pg_temp.nova_identidade('k3@ex.test'); SELECT pg_temp.nova_sessao_id(pg_temp.identidade('k3@ex.test'), 'K-3/a', true);
    SELECT pg_temp.nova_sessao_id(pg_temp.identidade('k3@ex.test'), 'K-3/b', true);
    SELECT identity_mfa_enroll_pending(NULL, pg_temp.tok('K-3/a'), decode(repeat('63', 36), 'hex'), decode(repeat('06', 12), 'hex'), 'k1');"
conc K-3 "SELECT pg_temp.afirma(identity_mfa_replace(pg_temp.tok('K-3/a'), pg_temp.pendente_de('k3@ex.test'), pg_temp.codigos('K-3/a')), 'K-3 T1 trocou');" \
         "SELECT pg_temp.afirma(identity_mfa_enroll_pending(NULL, pg_temp.tok('K-3/b'), decode(repeat('64', 36), 'hex'), decode(repeat('06', 12), 'hex'), 'k1') IS NULL, 'K-3 T2: sessao revogada pela troca nao cadastra');" T2
verifica K-3 "(SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${KF/kX/k3} AND confirmed_at IS NOT NULL AND disabled_at IS NULL) = 1
    AND (SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${KF/kX/k3} AND confirmed_at IS NULL AND disabled_at IS NULL) = 0"
e_check K-3
seq_tx K-4p "SELECT pg_temp.nova_identidade('k4@ex.test'); SELECT pg_temp.nova_sessao_id(pg_temp.identidade('k4@ex.test'), 'K-4/a', true);
    SELECT pg_temp.nova_sessao_id(pg_temp.identidade('k4@ex.test'), 'K-4/b', true);
    SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('K-4/a'), 'K-4 #' || i, NULL) IS NOT NULL, 'K-4 prep ' || i) FROM generate_series(1, 9) i;"
conc K-4 "SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('K-4/a'), 'K-4 #10', NULL) IS NOT NULL, 'K-4 T1 criou a decima');" \
         "SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('K-4/b'), 'K-4 #11', NULL) IS NULL, 'K-4 T2 recusada no teto');" T2
verifica K-4 "(SELECT count(*) FROM identity_users WHERE identity_id = ${KF/kX/k4} AND link_channel = 'cadastro_web') = 10"
e_check K-4
seq_tx K-5p "SELECT pg_temp.nova_conta('$O1', 'k5@ex.test'); SELECT pg_temp.nova_identidade('k5@ex.test');
    SELECT pg_temp.nova_sessao_id(pg_temp.identidade('k5@ex.test'), 'K-5/a', true); SELECT pg_temp.nova_sessao_id(pg_temp.identidade('k5@ex.test'), 'K-5/b', true);
    SELECT pg_temp.desafio_vinculo(pg_temp.identidade('k5@ex.test'), $(conta k5@ex.test), '$O1', 'K-5/link');"
conc K-5 "SELECT pg_temp.afirma(identity_link_account(pg_temp.tok('K-5/a'), pg_temp.tok('K-5/link'), '$O1'), 'K-5 T1 vinculou');" \
         "SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('K-5/b'), 'K-5 org', NULL) IS NOT NULL, 'K-5 T2 criou sem impasse');" T2
verifica K-5 "(SELECT string_agg(link_channel, ',' ORDER BY link_channel) FROM identity_users WHERE identity_id = ${KF/kX/k5}) = 'cadastro_web,vinculacao'"
e_check K-5
CODIGO_K6="encode(sha256(convert_to('codigos/' || pg_temp.identidade('k6@ex.test') || '/1', 'UTF8')), 'hex')"
seq_tx K-6p "SELECT pg_temp.nova_identidade('k6@ex.test'); SELECT pg_temp.desafio(pg_temp.identidade('k6@ex.test'), 'K-6/a', 1::SMALLINT);
    SELECT pg_temp.desafio(pg_temp.identidade('k6@ex.test'), 'K-6/b', 1::SMALLINT);"
conc K-6 "SELECT pg_temp.afirma((SELECT o_status FROM identity_login_complete(pg_temp.tok('K-6/a'), 'recuperacao', NULL, $CODIGO_K6, pg_temp.h('K-6/sa'), pg_temp.csrf('K-6/sa'), now() + interval '1 hour')) = 'SESSAO_CRIADA', 'K-6 T1 usou o codigo');" \
         "SELECT pg_temp.afirma((SELECT o_status FROM identity_login_complete(pg_temp.tok('K-6/b'), 'recuperacao', NULL, $CODIGO_K6, pg_temp.h('K-6/sb'), pg_temp.csrf('K-6/sb'), now() + interval '1 hour')) <> 'SESSAO_CRIADA', 'K-6 T2 nao reutiliza o codigo');" T2
verifica K-6 "(SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = ${KF/kX/k6} AND used_at IS NOT NULL) = 1
    AND (SELECT count(*) FROM identity_sessions WHERE identity_id = ${KF/kX/k6} AND recovery_used_at IS NOT NULL) = 1"
e_check K-6
echo "   grupo N (R1: excecao terminal estrita, imutabilidade dos tokens e da credencial, controles positivos)"
roda "$LIMPA" nekkora_platform t_n_imutabilidade.sql
echo "   grupo S (R3: conclusao do login, credencial vigente, re-hash, atomicidade, troca de senha)"
roda "$LIMPA" nekkora_platform t_s_senha_login.sql
roda "$LIMPA" nekkora          t_s_superusuario.sql

echo "   grupo R (R1: desafio valido antes da espera, invalidado ou expirado antes da liberacao)"
RID="(SELECT identity_id FROM identities WHERE email = 'rX@ex.test')"
trava() { echo "SELECT pg_temp.como(pg_temp.identidade('$1'));
    SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT 1 FROM identities WHERE identity_id = pg_temp.identidade('$1') FOR UPDATE) x) = 1, 'T1 travou a identidade');"; }
invalida() { echo "UPDATE identity_auth_challenges SET invalidated_at = now(), credential_hash = NULL WHERE token_hash = pg_temp.h('$1');"; }
desafio_curto() { echo "SELECT pg_temp.como(pg_temp.identidade('$1')); INSERT INTO identity_auth_challenges (identity_id, token_hash, purpose, credential_hash, csrf_token, expires_at)
    VALUES (pg_temp.identidade('$1'), pg_temp.h('$2'), ${4:-2}, pg_temp.hash_vigente(pg_temp.identidade('$1')), pg_temp.csrf('$2'), now() + interval '$3');"; }
# R-1: confirm_first, desafio invalidado durante a espera
seq_tx R-1p "SELECT pg_temp.nova_identidade('r1@ex.test', false); SELECT pg_temp.desafio_cadastro(pg_temp.identidade('r1@ex.test'), 'R-1/cad');
    SELECT identity_mfa_enroll_pending(pg_temp.tok('R-1/cad'), NULL, decode(repeat('71', 36), 'hex'), decode(repeat('07', 12), 'hex'), 'k1');"
conc R-1 "$(trava r1@ex.test) $(invalida R-1/cad)" \
         "SELECT pg_temp.afirma(pg_temp.cf(pg_temp.tok('R-1/cad'), pg_temp.pendente_de('r1@ex.test'), pg_temp.codigos('R-1'), 'R-1/s') <> 'SESSAO_CRIADA', 'R-1 desafio invalidado durante a espera nao autoriza');" T2
verifica R-1 "(SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${RID/rX/r1} AND confirmed_at IS NULL AND disabled_at IS NULL) = 1
    AND NOT EXISTS (SELECT 1 FROM identity_mfa_factors WHERE identity_id = ${RID/rX/r1} AND confirmed_at IS NOT NULL)
    AND NOT EXISTS (SELECT 1 FROM identity_mfa_recovery_codes WHERE identity_id = ${RID/rX/r1})"
e_check R-1
# R-2: confirm_first, desafio expirado durante a espera (vence em 4 s; T1 segura 6 s)
seq_tx R-2p "SELECT pg_temp.nova_identidade('r2@ex.test', false); $(desafio_curto r2@ex.test R-2/cad '4 seconds')
    SELECT identity_mfa_enroll_pending(pg_temp.tok('R-2/cad'), NULL, decode(repeat('72', 36), 'hex'), decode(repeat('07', 12), 'hex'), 'k1');"
conc R-2 "$(trava r2@ex.test)" \
         "SELECT pg_temp.afirma(pg_temp.cf(pg_temp.tok('R-2/cad'), pg_temp.pendente_de('r2@ex.test'), pg_temp.codigos('R-2'), 'R-2/s') <> 'SESSAO_CRIADA', 'R-2 desafio expirado durante a espera nao autoriza');" T2 "READ COMMITTED" 6
verifica R-2 "(SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${RID/rX/r2} AND confirmed_at IS NULL AND disabled_at IS NULL) = 1
    AND NOT EXISTS (SELECT 1 FROM identity_mfa_factors WHERE identity_id = ${RID/rX/r2} AND confirmed_at IS NOT NULL)
    AND NOT EXISTS (SELECT 1 FROM identity_mfa_recovery_codes WHERE identity_id = ${RID/rX/r2})
    AND (SELECT consumed_at IS NULL FROM identity_auth_challenges WHERE identity_id = ${RID/rX/r2})"
e_check R-2
# R-3: enroll_pending, desafio invalidado durante a espera
seq_tx R-3p "SELECT pg_temp.nova_identidade('r3@ex.test', false); SELECT pg_temp.desafio_cadastro(pg_temp.identidade('r3@ex.test'), 'R-3/cad');"
conc R-3 "$(trava r3@ex.test) $(invalida R-3/cad)" \
         "SELECT pg_temp.afirma(identity_mfa_enroll_pending(pg_temp.tok('R-3/cad'), NULL, decode(repeat('73', 36), 'hex'), decode(repeat('07', 12), 'hex'), 'k1') IS NULL, 'R-3 desafio invalidado durante a espera nao cadastra');" T2
verifica R-3 "NOT EXISTS (SELECT 1 FROM identity_mfa_factors WHERE identity_id = ${RID/rX/r3})"
e_check R-3
# R-4: enroll_pending, desafio expirado durante a espera
seq_tx R-4p "SELECT pg_temp.nova_identidade('r4@ex.test', false); $(desafio_curto r4@ex.test R-4/cad '4 seconds')"
conc R-4 "$(trava r4@ex.test)" \
         "SELECT pg_temp.afirma(identity_mfa_enroll_pending(pg_temp.tok('R-4/cad'), NULL, decode(repeat('74', 36), 'hex'), decode(repeat('07', 12), 'hex'), 'k1') IS NULL, 'R-4 desafio expirado durante a espera nao cadastra');" T2 "READ COMMITTED" 6
verifica R-4 "NOT EXISTS (SELECT 1 FROM identity_mfa_factors WHERE identity_id = ${RID/rX/r4})"
e_check R-4
echo "   grupo V (R2: a espera vem DEPOIS da primeira validacao; a prova vence durante ela)"
VID="(SELECT identity_id FROM identities WHERE email = 'vX@ex.test')"
sessao_curta() { echo "SELECT pg_temp.nova_sessao_id(pg_temp.identidade('$1'), '$2', true, false, interval '$3');"; }
trava_pendente() { echo "SELECT pg_temp.como(pg_temp.identidade('$1'));
    SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT 1 FROM identity_mfa_factors WHERE identity_id = pg_temp.identidade('$1') AND confirmed_at IS NULL AND disabled_at IS NULL FOR UPDATE) x) = 1, 'T1 travou o fator pendente');"; }
# V-1: confirm_first espera o lock do FATOR (identidade e desafio ja conferidos); o desafio vence durante a espera
seq_tx V-1p "SELECT pg_temp.nova_identidade('v1@ex.test', false); $(desafio_curto v1@ex.test V-1/cad '4 seconds')
    SELECT identity_mfa_enroll_pending(pg_temp.tok('V-1/cad'), NULL, decode(repeat('81', 36), 'hex'), decode(repeat('08', 12), 'hex'), 'k1');"
conc V-1 "$(trava_pendente v1@ex.test)" \
         "SELECT pg_temp.afirma(pg_temp.cf(pg_temp.tok('V-1/cad'), pg_temp.pendente_de('v1@ex.test'), pg_temp.codigos('V-1'), 'V-1/s') <> 'SESSAO_CRIADA', 'V-1 desafio vencido durante a espera pelo fator nao autoriza');" T2 "READ COMMITTED" 6
verifica V-1 "(SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${VID/vX/v1} AND confirmed_at IS NULL AND disabled_at IS NULL) = 1
    AND NOT EXISTS (SELECT 1 FROM identity_mfa_factors WHERE identity_id = ${VID/vX/v1} AND confirmed_at IS NOT NULL)
    AND NOT EXISTS (SELECT 1 FROM identity_mfa_recovery_codes WHERE identity_id = ${VID/vX/v1})
    AND (SELECT consumed_at IS NULL AND invalidated_at IS NULL FROM identity_auth_challenges WHERE identity_id = ${VID/vX/v1})"
e_check V-1
# V-2: enroll_pending (desafio) espera o lock do pendente anterior; o desafio vence durante a espera
seq_tx V-2p "SELECT pg_temp.nova_identidade('v2@ex.test', false); $(desafio_curto v2@ex.test V-2/cad '4 seconds')
    SELECT identity_mfa_enroll_pending(pg_temp.tok('V-2/cad'), NULL, decode(repeat('82', 36), 'hex'), decode(repeat('08', 12), 'hex'), 'k1');"
conc V-2 "$(trava_pendente v2@ex.test)" \
         "SELECT pg_temp.afirma(identity_mfa_enroll_pending(pg_temp.tok('V-2/cad'), NULL, decode(repeat('92', 36), 'hex'), decode(repeat('08', 12), 'hex'), 'k1') IS NULL, 'V-2 desafio vencido durante a espera pelo pendente nao cadastra');" T2 "READ COMMITTED" 6
verifica V-2 "(SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${VID/vX/v2}) = 1
    AND (SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${VID/vX/v2} AND confirmed_at IS NULL AND disabled_at IS NULL) = 1"
e_check V-2
# V-3: replace espera o lock do fator NOVO; a sessao (prova da DC-17.13) vence durante a espera
seq_tx V-3p "SELECT pg_temp.nova_identidade('v3@ex.test'); $(sessao_curta v3@ex.test V-3/s '4 seconds')
    SELECT identity_mfa_enroll_pending(NULL, pg_temp.tok('V-3/s'), decode(repeat('83', 36), 'hex'), decode(repeat('08', 12), 'hex'), 'k1');"
conc V-3 "$(trava_pendente v3@ex.test)" \
         "SELECT pg_temp.afirma(NOT identity_mfa_replace(pg_temp.tok('V-3/s'), pg_temp.pendente_de('v3@ex.test'), pg_temp.codigos('V-3')), 'V-3 sessao vencida durante a espera pelo fator novo nao troca');" T2 "READ COMMITTED" 6
verifica V-3 "(SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${VID/vX/v3} AND confirmed_at IS NOT NULL AND disabled_at IS NULL) = 1
    AND (SELECT count(*) FROM identity_mfa_factors WHERE identity_id = ${VID/vX/v3} AND confirmed_at IS NULL AND disabled_at IS NULL) = 1
    AND (SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = ${VID/vX/v3} AND used_at IS NULL AND invalidated_at IS NULL) = 8"
e_check V-3
# V-4: vinculacao espera o lock de uma linha do FECHAMENTO (sessao antiga da conta); o desafio de vinculacao vence
seq_tx V-4p "SELECT pg_temp.nova_conta('$O1', 'v4@ex.test'); SELECT pg_temp.nova_identidade('v4@ex.test');
    SELECT pg_temp.nova_sessao_id(pg_temp.identidade('v4@ex.test'), 'V-4/s', true);
    SELECT pg_temp.sessao_org('$O1', $(conta v4@ex.test), NULL, 'V-4/antiga');
    SELECT pg_temp.ctx('$O1'); SELECT pg_temp.como(pg_temp.identidade('v4@ex.test'));
    INSERT INTO identity_link_challenges (identity_id, user_id, token_hash, account_credential_hash, expires_at)
    VALUES (pg_temp.identidade('v4@ex.test'), $(conta v4@ex.test), pg_temp.h('V-4/link'),
            (SELECT password_hash FROM user_credentials WHERE user_id = $(conta v4@ex.test)), now() + interval '4 seconds');"
conc V-4 "SELECT pg_temp.ctx('$O1'); SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT 1 FROM user_sessions WHERE session_token_hash = pg_temp.h('V-4/antiga') FOR UPDATE) x) = 1, 'T1 travou a sessao antiga');" \
         "SELECT pg_temp.afirma(NOT identity_link_account(pg_temp.tok('V-4/s'), pg_temp.tok('V-4/link'), '$O1'), 'V-4 desafio vencido durante a espera pelo fechamento nao vincula');" T2 "READ COMMITTED" 6
verifica V-4 "NOT EXISTS (SELECT 1 FROM identity_users iu JOIN users u ON u.user_id = iu.user_id WHERE u.email = 'v4@ex.test')
    AND (SELECT consumed_at IS NULL FROM identity_link_challenges WHERE identity_id = ${VID/vX/v4})
    AND (SELECT revoked_at IS NULL FROM user_sessions s JOIN users u ON u.user_id = s.user_id WHERE u.email = 'v4@ex.test')
    AND (SELECT count(*) FROM user_mfa_factors f JOIN users u ON u.user_id = f.user_id WHERE u.email = 'v4@ex.test' AND f.disabled_at IS NULL) = 1"
e_check V-4
# V-5 (R3): identity_login_complete (recuperacao) espera o lock de OUTRA sessao (linha que T0 revogaria); o desafio vence
CODIGO_V5="encode(sha256(convert_to('codigos/' || pg_temp.identidade('v5@ex.test') || '/1', 'UTF8')), 'hex')"
seq_tx V-5p "SELECT pg_temp.nova_identidade('v5@ex.test'); SELECT pg_temp.nova_sessao_id(pg_temp.identidade('v5@ex.test'), 'V-5/outra');
    $(desafio_curto v5@ex.test V-5/login '4 seconds' 1)"
conc V-5 "SELECT pg_temp.como(pg_temp.identidade('v5@ex.test'));
    SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT 1 FROM identity_sessions WHERE session_token_hash = pg_temp.h('V-5/outra') FOR UPDATE) x) = 1, 'T1 travou a outra sessao');" \
         "SELECT pg_temp.afirma((SELECT o_status FROM identity_login_complete(pg_temp.tok('V-5/login'), 'recuperacao', NULL, $CODIGO_V5, pg_temp.h('V-5/s'), pg_temp.csrf('V-5/s'), now() + interval '1 hour')) = 'DESAFIO_INVALIDO', 'V-5 desafio vencido durante a espera nao gera sessao');" T2 "READ COMMITTED" 6
verifica V-5 "NOT EXISTS (SELECT 1 FROM identity_mfa_recovery_codes WHERE identity_id = ${VID/vX/v5} AND used_at IS NOT NULL)
    AND NOT EXISTS (SELECT 1 FROM identity_sessions WHERE identity_id = ${VID/vX/v5} AND recovery_used_at IS NOT NULL)
    AND (SELECT count(*) FROM identity_sessions WHERE identity_id = ${VID/vX/v5} AND revoked_at IS NULL) = 2"
e_check V-5
# V-6 (R3): identity_password_change espera o lock de um desafio PENDENTE (linha que invalidaria); a sessao vence
seq_tx V-6p "SELECT pg_temp.nova_identidade('v6@ex.test'); $(sessao_curta v6@ex.test V-6/s '4 seconds')
    SELECT pg_temp.desafio(pg_temp.identidade('v6@ex.test'), 'V-6/pendente', 1::SMALLINT);"
conc V-6 "SELECT pg_temp.como(pg_temp.identidade('v6@ex.test'));
    SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT 1 FROM identity_auth_challenges WHERE token_hash = pg_temp.h('V-6/pendente') FOR UPDATE) x) = 1, 'T1 travou o desafio pendente');" \
         "SELECT pg_temp.afirma(NOT identity_password_change(pg_temp.tok('V-6/s'), pg_temp.hash_vigente(pg_temp.identidade('v6@ex.test')), '\$argon2id\$v6-nova'), 'V-6 sessao vencida durante a espera nao troca a senha');" T2 "READ COMMITTED" 6
verifica V-6 "(SELECT password_hash FROM identity_credentials WHERE identity_id = ${VID/vX/v6}) = '\$argon2id\$id\$v6@ex.test'
    AND (SELECT count(*) FROM identity_auth_challenges WHERE identity_id = ${VID/vX/v6} AND purpose = 1 AND consumed_at IS NULL AND invalidated_at IS NULL) = 1"
e_check V-6
[ "$(grep -c 'observado esperando lock' "$SAIDA/C_observador.txt")" = 26 ] || falha "esperados 26 cenarios com espera de lock observada (C, K, R e V)"

echo "   grupo Q (R4: emissao da sessao de organizacao da conta vinculada; elegibilidade da conta, da organizacao e do vinculo no banco)"
O4=17000000-0000-0000-0000-000000000004
roda "$LIMPA" nekkora_provisioning t_q_prep_prov.sql
roda "$LIMPA" nekkora_platform     t_q_prep.sql
roda "$LIMPA" nekkora_platform     t_q_emissao.sql
qc() { echo "pg_temp.conta('$1', '$2')"; }                       # conta (email, org)
qu() { echo "(SELECT user_id FROM users WHERE email_normalized = '$1' AND organization_id = '$2')"; }   # idem, para o superusuario
qh() { echo "encode(sha256(convert_to(left(translate(encode(sha256(convert_to('$1', 'UTF8')), 'base64'), '+/', '-_'), 42) || 'A', 'UTF8')), 'hex')"; }
q_alvo() { echo "SELECT pg_temp.afirma(EXISTS (SELECT 1 FROM identity_org_session_target(pg_temp.tok('sessao/$1'), '$2')), 'resolucao inicial de $1');"; }
q_emite() { echo "SELECT pg_temp.afirma(pg_temp.sessao_org('$2', $(qc "$1" "$2"), pg_temp.sessao_id('sessao/$1'), '$3') IS NOT NULL, '$3: emissao aceita');"; }
q_recusa() { echo "SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, %L)', '$2', $(qc "$1" "$2"), pg_temp.sessao_id('sessao/$1'), '$3'), '23514', 'fator de MFA confirmado');"; }
# desativacoes como AdminService.suspend: organizacao FOR UPDATE -> escrita -> revogacao das sessoes da conta
q_suspende() { echo "SELECT pg_temp.ctx('$2'); SELECT 1 FROM organizations WHERE organization_id = '$2' FOR UPDATE;
    UPDATE users SET status = 3 WHERE user_id = $(qc "$1" "$2"); UPDATE user_sessions SET revoked_at = now() WHERE user_id = $(qc "$1" "$2") AND revoked_at IS NULL;"; }
q_desvincula() { echo "SELECT pg_temp.ctx('$2'); SELECT 1 FROM organizations WHERE organization_id = '$2' FOR UPDATE;
    UPDATE organization_memberships SET status = 3 WHERE user_id = $(qc "$1" "$2"); UPDATE user_sessions SET revoked_at = now() WHERE user_id = $(qc "$1" "$2") AND revoked_at IS NULL;"; }
q_org() { echo "SELECT pg_temp.ctx('$1'); UPDATE organizations SET status = $2 WHERE organization_id = '$1';"; }
# desativacao confirmada DURANTE a espera da emissao (T1 desativa e segura; T2 emite e espera a organizacao) -> recusa
seq_tx Q-C1r "$(q_alvo qv5@ex.test "$O1")"
conc Q-C1 "$(q_suspende qv5@ex.test "$O1")" "$(q_recusa qv5@ex.test "$O1" Q-C1)" T2
verifica Q-C1 "(SELECT status FROM users WHERE user_id = $(qu qv5@ex.test "$O1")) = 3 AND NOT EXISTS (SELECT 1 FROM user_sessions WHERE session_token_hash = $(qh Q-C1))"
e_check Q-C1
seq_tx Q-C2r "$(q_alvo qv6@ex.test "$O1")"
conc Q-C2 "$(q_desvincula qv6@ex.test "$O1")" "$(q_recusa qv6@ex.test "$O1" Q-C2)" T2
verifica Q-C2 "(SELECT status FROM organization_memberships WHERE user_id = $(qu qv6@ex.test "$O1")) = 3 AND NOT EXISTS (SELECT 1 FROM user_sessions WHERE session_token_hash = $(qh Q-C2))"
e_check Q-C2
seq_tx Q-C3r "$(q_alvo qo2@ex.test "$O4")"
conc Q-C3 "$(q_org "$O4" 2)" "$(q_recusa qo2@ex.test "$O4" Q-C3)" T2
verifica Q-C3 "(SELECT status FROM organizations WHERE organization_id = '$O4') = 2 AND NOT EXISTS (SELECT 1 FROM user_sessions WHERE session_token_hash = $(qh Q-C3))"
seq_tx Q-C3z "$(q_org "$O4" 1)"
e_check Q-C3
# emissao confirmada ANTES (T1 emite e segura; T2 desativa e espera a organizacao) -> a desativacao encontra a sessao
seq_tx Q-C4r "$(q_alvo qv7@ex.test "$O1")"
conc Q-C4 "$(q_emite qv7@ex.test "$O1" Q-C4)" "$(q_suspende qv7@ex.test "$O1")" T2
verifica Q-C4 "(SELECT status FROM users WHERE user_id = $(qu qv7@ex.test "$O1")) = 3
    AND (SELECT revoked_at IS NOT NULL FROM user_sessions WHERE session_token_hash = $(qh Q-C4))"
e_check Q-C4
seq_tx Q-C5r "$(q_alvo qv8@ex.test "$O1")"
conc Q-C5 "$(q_emite qv8@ex.test "$O1" Q-C5)" "$(q_desvincula qv8@ex.test "$O1")" T2
verifica Q-C5 "(SELECT status FROM organization_memberships WHERE user_id = $(qu qv8@ex.test "$O1")) = 3
    AND (SELECT revoked_at IS NOT NULL FROM user_sessions WHERE session_token_hash = $(qh Q-C5))"
e_check Q-C5
seq_tx Q-C6r "$(q_alvo qo3@ex.test "$O4")"
conc Q-C6 "$(q_emite qo3@ex.test "$O4" Q-C6)" "$(q_org "$O4" 2)" T2
verifica Q-C6 "(SELECT status FROM organizations WHERE organization_id = '$O4') = 2 AND EXISTS (SELECT 1 FROM user_sessions WHERE session_token_hash = $(qh Q-C6))"
seq_tx Q-C6z "$(q_org "$O4" 1)"
e_check Q-C6
[ "$(grep -c '^Q-C[1-6]-T2: observado esperando lock' "$SAIDA/C_observador.txt")" = 6 ] || falha "grupo Q: esperados 6 cenarios com a espera de T2 observada"
# Q-C7: ramo 1 sem lock novo - com a organizacao travada FOR UPDATE por T1, o INSERT da conta NAO vinculada nao espera
# (lock_timeout de 1 s; T1 segura 4 s). Q-C8, controle: a conta vinculada espera, e o mesmo lock_timeout a interrompe.
transacao Q-C7-T1 0 4 "READ COMMITTED" "SELECT pg_temp.ctx('$O1'); SELECT 1 FROM organizations WHERE organization_id = '$O1' FOR UPDATE;"
transacao Q-C7-T2 1 0 "READ COMMITTED" "SET LOCAL lock_timeout = '1s';
    SELECT pg_temp.afirma(pg_temp.sessao_org('$O1', $(qc qn2@ex.test "$O1"), NULL, 'Q-C7') IS NOT NULL, 'Q-C7 ramo 1 nao espera a organizacao');"
fim_transacoes
transacao Q-C8-T1 0 4 "READ COMMITTED" "SELECT pg_temp.ctx('$O1'); SELECT 1 FROM organizations WHERE organization_id = '$O1' FOR UPDATE;"
transacao Q-C8-T2 1 0 "READ COMMITTED" "SET LOCAL lock_timeout = '1s';
    SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, %L)', '$O1', $(qc qv9@ex.test "$O1"), pg_temp.sessao_id('sessao/qv9@ex.test'), 'Q-C8'), '55P03', 'lock timeout');"
fim_transacoes
e_check Q-C8
# Q-RR (R4b): isolamento. Em REPEATABLE READ o lock nao atualiza o snapshot: T1 fixa o snapshot com o vinculo ativo,
# T2 trava a organizacao, desativa o vinculo e confirma (so TRAVOU a organizacao e nao tocou a conta, entao o lock de T1
# nao provoca 40001), e T1 emite. A emissao vinculada exige READ COMMITTED no banco (25N01); o ramo 1 nao.
q_snapshot() { echo "SELECT pg_temp.ctx('$2'); SELECT pg_temp.afirma((SELECT status FROM organization_memberships WHERE user_id = $(qc "$1" "$2")) = 2, '$3 T1: snapshot com o vinculo ativo'); SELECT pg_sleep(3);"; }
q_recusa_iso() { echo "SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, %L)', '$2', $(qc "$1" "$2"), pg_temp.sessao_id('sessao/$1'), '$3'), '25N01', 'read committed');"; }
# Q-RR1: o cenario apontado, em REPEATABLE READ -> recusa por isolamento; estado final sem sessao
transacao Q-RR1-T1 0 0 "REPEATABLE READ" "$(q_snapshot qv15@ex.test "$O1" Q-RR1) $(q_recusa_iso qv15@ex.test "$O1" Q-RR1)"
transacao Q-RR1-T2 1 0 "READ COMMITTED" "$(q_desvincula qv15@ex.test "$O1")"
fim_transacoes
verifica Q-RR1 "(SELECT status FROM organization_memberships WHERE user_id = $(qu qv15@ex.test "$O1")) = 3 AND NOT EXISTS (SELECT 1 FROM user_sessions WHERE session_token_hash = $(qh Q-RR1))"
e_check Q-RR1
# Q-RR2 / Q-RR3: SERIALIZABLE e REPEATABLE READ sem nenhuma mudanca concorrente, conta elegivel -> recusa por isolamento
seq_tx Q-RR2 "$(q_recusa_iso qv16@ex.test "$O1" Q-RR2)" "SERIALIZABLE"
seq_tx Q-RR3 "$(q_recusa_iso qv17@ex.test "$O1" Q-RR3)" "REPEATABLE READ"
verifica Q-RR23 "NOT EXISTS (SELECT 1 FROM user_sessions WHERE session_token_hash IN ($(qh Q-RR2), $(qh Q-RR3)))"
# Q-RC1: o MESMO cenario em READ COMMITTED -> a reconferencia ve o vinculo desativado e recusa (23514)
transacao Q-RC1-T1 0 0 "READ COMMITTED" "$(q_snapshot qv18@ex.test "$O1" Q-RC1) $(q_recusa qv18@ex.test "$O1" Q-RC1)"
transacao Q-RC1-T2 1 0 "READ COMMITTED" "$(q_desvincula qv18@ex.test "$O1")"
fim_transacoes
verifica Q-RC1 "(SELECT status FROM organization_memberships WHERE user_id = $(qu qv18@ex.test "$O1")) = 3 AND NOT EXISTS (SELECT 1 FROM user_sessions WHERE session_token_hash = $(qh Q-RC1))"
# Q-RC2: controle positivo em READ COMMITTED, conta elegivel
seq_tx Q-RC2 "$(q_emite qv19@ex.test "$O1" Q-RC2)" "READ COMMITTED"
# Q-R1: ramo 1 mantido - conta NAO vinculada emite em REPEATABLE READ e em SERIALIZABLE, como antes
seq_tx Q-R1rr "SELECT pg_temp.afirma(pg_temp.sessao_org('$O1', $(qc qn2@ex.test "$O1"), NULL, 'Q-R1rr') IS NOT NULL, 'Q-R1 ramo 1 em REPEATABLE READ aceito');" "REPEATABLE READ"
seq_tx Q-R1sz "SELECT pg_temp.afirma(pg_temp.sessao_org('$O1', $(qc qn2@ex.test "$O1"), NULL, 'Q-R1sz') IS NOT NULL, 'Q-R1 ramo 1 em SERIALIZABLE aceito');" "SERIALIZABLE"
e_check Q-R1

echo "   grupo P (R2: RLS por identidade em leitura e escrita; credenciais sem SELECT; consultas por chave exata)"
roda "$LIMPA" nekkora_platform t_p_isolamento.sql

echo "   grupo M (privilegios efetivos)"
psqlc "$LIMPA" nekkora < "$AQUI/sql/m_privilegios.sql" > "$SAIDA/M01_real.txt"
diff "$AQUI/sql/m_esperado.txt" "$SAIDA/M01_real.txt" > "$SAIDA/M02_diff.txt" || falha "grupo M: privilegios efetivos divergem da matriz esperada (ver M02_diff.txt)"
echo "   mutacoes (a suite precisa detectar; depois, restauracao exata)"
M17="$REPO/db/migrations/017_identidade_cadastro.sql"
extrai_funcao() {   # $1 nome: a definicao da 017, como CREATE OR REPLACE (restaura o corpo exato)
    awk -v n="$1" 'index($0, "CREATE FUNCTION " n "(") == 1 || index($0, "CREATE FUNCTION public." n "(") == 1 || index($0, "CREATE OR REPLACE FUNCTION " n "(") == 1 {f=1} f {print} f && /^\$\$;$/ {exit}' "$M17" \
        | sed '1s/^CREATE FUNCTION/CREATE OR REPLACE FUNCTION/'
}
mutacao() {   # $1 nome, $2 funcao, $3 trecho (literal, presente exatamente uma vez), $4 substituto
    extrai_funcao "$2" > "$SAIDA/MUT_$1_original.sql"
    "$PY" - "$SAIDA/MUT_$1_original.sql" "$3" "${4:-}" > "$SAIDA/MUT_$1_mutada.sql" <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read(); t = sys.argv[2]
assert s.count(t) == 1, 'trecho da mutacao nao encontrado exatamente uma vez'
sys.stdout.write(s.replace(t, sys.argv[3]))
PY
    psqlc "$LIMPA" nekkora < "$SAIDA/MUT_$1_mutada.sql" > /dev/null || falha "mutacao $1 nao aplicou"
}
restaura() { psqlc "$LIMPA" nekkora < "$SAIDA/MUT_$1_original.sql" > /dev/null || falha "restauracao de $1 falhou"; echo "$1: restaurada" >> "$SAIDA/MUT_resumo.txt"; }
detectada() {   # $1 nome, $2 arquivo de saida que precisa conter a falha de assercao, $3 trecho (padrao: sucesso indevido)
    grep -q "FALHA DE ASSERCAO: ${3:-operacao deveria ter falhado}" "$2" || falha "MUTACAO $1 NAO FOI DETECTADA (ver $2)"
    echo "$1: DETECTADA ($(grep -m1 'FALHA DE ASSERCAO' "$2" | sed 's/.*FALHA DE ASSERCAO: //' | cut -c1-120))" >> "$SAIDA/MUT_resumo.txt"
}
# M1: a trigger de fechamento sem o lock da conta -> C-1 (vinculacao x escrita crua) deixa a escrita passar
mutacao M1 refuse_local_entry_for_linked_account "WHERE u.user_id = NEW.user_id FOR UPDATE;" "WHERE u.user_id = NEW.user_id;"
transacao M1-T1 0 4 "READ COMMITTED" "$(vincula c16@ex.test)"
transacao M1-T2 1 0 "READ COMMITTED" "$(escrita c16@ex.test M1 1)"
RC=0; for p in "${T_PIDS[@]}"; do wait "$p" || RC=$((RC + 1)); done; T_PIDS=()
[ "$RC" = 1 ] || falha "M1: esperada exatamente uma transacao reprovada (obtido $RC)"
detectada M1 "$SAIDA/conc_M1-T2.txt"
restaura M1
conc M1r "$(vincula c15@ex.test)" "$(escrita c15@ex.test M1r 1)" T2
e_check M1r
# M2: a auxiliar sem a recusa da sessao restrita -> G-1 deixa a sessao de organizacao nascer
seq_tx M2p "$(vincula c17@ex.test) SELECT pg_temp.nova_sessao_id(pg_temp.identidade('c17@ex.test'), 'M2/restrita', false, true);"
mutacao M2 identity_org_session_allowed "
                              AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)"
M2SQL="SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, ''M2'')', '$O1', $(conta c17@ex.test), pg_temp.sessao_id('M2/restrita')), '23514', 'fator de MFA confirmado');"
RC=0; com_inc "$AQUI/sql/t_conc.sql" | docker exec -i -e PGAPPNAME=M2 "$LIMPA" psql -X -d nekkora -U nekkora_platform -v ON_ERROR_STOP=1 \
    -v rotulo=M2 -v atraso=0 -v segura=0 -v iso="READ COMMITTED" -v acao="$M2SQL" > "$SAIDA/conc_M2.txt" 2>&1 || RC=$?
[ "$RC" != 0 ] || falha "M2: a transacao deveria ter reprovado"
detectada M2 "$SAIDA/conc_M2.txt"
restaura M2
seq_tx M2r "$M2SQL"
e_check M2r
# M3: SEM reconferencia do desafio depois do lock da identidade E sem a validade no consumo -> R-2 deixa passar
mutacao M3a identity_challenge_resolve "AND ch.consumed_at IS NULL AND ch.invalidated_at IS NULL
       AND ch.expires_at > pg_catalog.clock_timestamp()" "AND ch.consumed_at IS NULL AND ch.invalidated_at IS NULL"
mutacao M3b identity_mfa_confirm_first "AND invalidated_at IS NULL AND expires_at > v_t;" "AND invalidated_at IS NULL;"
seq_tx M3p "SELECT pg_temp.nova_identidade('r5@ex.test', false); $(desafio_curto r5@ex.test M3/cad '4 seconds')
    SELECT identity_mfa_enroll_pending(pg_temp.tok('M3/cad'), NULL, decode(repeat('75', 36), 'hex'), decode(repeat('07', 12), 'hex'), 'k1');"
transacao M3-T1 0 6 "READ COMMITTED" "$(trava r5@ex.test)"
transacao M3-T2 1 0 "READ COMMITTED" "SELECT pg_temp.afirma(pg_temp.cf(pg_temp.tok('M3/cad'), pg_temp.pendente_de('r5@ex.test'), pg_temp.codigos('M3'), 'M3/s') <> 'SESSAO_CRIADA', 'R-2 sob M3: desafio expirado durante a espera nao autoriza');"
RC=0; for p in "${T_PIDS[@]}"; do wait "$p" || RC=$((RC + 1)); done; T_PIDS=()
[ "$RC" = 1 ] || falha "M3: esperada exatamente uma transacao reprovada (obtido $RC)"
detectada M3 "$SAIDA/conc_M3-T2.txt" "R-2 sob M3"
restaura M3a; restaura M3b
verifica M3 "NOT EXISTS (SELECT 1 FROM identity_mfa_factors WHERE identity_id = ${RID/rX/r5} AND confirmed_at IS NOT NULL)"
e_check M3
# M4 / M5: corpos da R1 (hora medida antes de esperas posteriores) -> V-1 e V-4 deixam passar
mutacao_r1() {   # $1 nome, $2 funcao, $3 identidade/conta, $4 prep, $5 acao T1, $6 acao T2, $7 trecho da falha
    psqlc "$LIMPA" nekkora < "$AQUI/sql/mutacoes/r1_$2.sql" > /dev/null || falha "mutacao $1 nao aplicou"
    extrai_funcao "$2" > "$SAIDA/MUT_$1_original.sql"
    seq_tx "$1p" "$4"
    transacao "$1-T1" 0 6 "READ COMMITTED" "$5"
    transacao "$1-T2" 1 0 "READ COMMITTED" "$6"
    RC=0; for p in "${T_PIDS[@]}"; do wait "$p" || RC=$((RC + 1)); done; T_PIDS=()
    [ "$RC" = 1 ] || falha "$1: esperada exatamente uma transacao reprovada (obtido $RC)"
    detectada "$1" "$SAIDA/conc_$1-T2.txt" "$7"
    restaura "$1"
}
# M4: consumo SEM a validade medida depois do lock do fator -> V-1 deixa passar
mutacao M4 identity_mfa_confirm_first "AND invalidated_at IS NULL AND expires_at > v_t;" "AND invalidated_at IS NULL;"
seq_tx M4p "SELECT pg_temp.nova_identidade('w1@ex.test', false); $(desafio_curto w1@ex.test M4/cad '4 seconds')
    SELECT identity_mfa_enroll_pending(pg_temp.tok('M4/cad'), NULL, decode(repeat('a1', 36), 'hex'), decode(repeat('0a', 12), 'hex'), 'k1');"
transacao M4-T1 0 6 "READ COMMITTED" "$(trava_pendente w1@ex.test)"
transacao M4-T2 1 0 "READ COMMITTED" "SELECT pg_temp.afirma(pg_temp.cf(pg_temp.tok('M4/cad'), pg_temp.pendente_de('w1@ex.test'), pg_temp.codigos('M4'), 'M4/s') <> 'SESSAO_CRIADA', 'V-1 sob M4: desafio vencido nao autoriza');"
RC=0; for p in "${T_PIDS[@]}"; do wait "$p" || RC=$((RC + 1)); done; T_PIDS=()
[ "$RC" = 1 ] || falha "M4: esperada exatamente uma transacao reprovada (obtido $RC)"
detectada M4 "$SAIDA/conc_M4-T2.txt" "V-1 sob M4"
restaura M4
verifica M4 "NOT EXISTS (SELECT 1 FROM identity_mfa_factors WHERE identity_id = ${VID/vX/w1} AND confirmed_at IS NOT NULL)"
e_check M4
# M6: identity_login_complete SEM a conferencia da credencial vigente -> o desafio obsoleto (S-5) geraria sessao
mutacao M6 identity_login_complete "IF v_cred IS DISTINCT FROM c.credential_hash THEN" "IF false THEN"
seq_tx M6p "SELECT pg_temp.nova_identidade('m6@ex.test');
    SELECT pg_temp.desafio(pg_temp.identidade('m6@ex.test'), 'M6/a', 1::SMALLINT, '\$argon2id\$m6-rehash');
    SELECT pg_temp.desafio(pg_temp.identidade('m6@ex.test'), 'M6/b', 1::SMALLINT);
    SELECT pg_temp.afirma((SELECT o_status FROM identity_login_complete(pg_temp.tok('M6/a'), 'totp', 900, NULL, pg_temp.h('M6/sa'), pg_temp.csrf('M6/sa'), now() + interval '1 hour')) = 'SESSAO_CRIADA', 'M6 A conclui com re-hash');"
transacao M6v 0 0 "READ COMMITTED" "SELECT pg_temp.afirma((SELECT o_status FROM identity_login_complete(pg_temp.tok('M6/b'), 'totp', 901, NULL, pg_temp.h('M6/sb'), pg_temp.csrf('M6/sb'), now() + interval '1 hour')) = 'SENHA_OBSOLETA', 'S-5 sob M6: prova de senha obsoleta nao gera sessao');"
RC=0; for p in "${T_PIDS[@]}"; do wait "$p" || RC=$((RC + 1)); done; T_PIDS=()
[ "$RC" = 1 ] || falha "M6: a transacao deveria ter reprovado"
detectada M6 "$SAIDA/conc_M6v.txt" "S-5 sob M6"
restaura M6
e_check M6
# M7: identity_password_change SEM invalidar os desafios pendentes -> S-9 reprova
mutacao M7 identity_password_change "    UPDATE public.identity_auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
     WHERE identity_id = s.o_identity_id AND consumed_at IS NULL AND invalidated_at IS NULL;
" ""
transacao M7 0 0 "READ COMMITTED" "SELECT pg_temp.nova_identidade('m7@ex.test'); SELECT pg_temp.nova_sessao_id(pg_temp.identidade('m7@ex.test'), 'M7/a', true);
    SELECT pg_temp.desafio(pg_temp.identidade('m7@ex.test'), 'M7/pendente', 1::SMALLINT);
    SELECT pg_temp.afirma(identity_password_change(pg_temp.tok('M7/a'), pg_temp.hash_vigente(pg_temp.identidade('m7@ex.test')), '\$argon2id\$m7-nova'), 'M7 troca');
    SELECT pg_temp.afirma((SELECT invalidated_at IS NOT NULL FROM identity_auth_challenges WHERE token_hash = pg_temp.h('M7/pendente')), 'S-9 sob M7: desafio pendente invalidado na troca');"
RC=0; for p in "${T_PIDS[@]}"; do wait "$p" || RC=$((RC + 1)); done; T_PIDS=()
[ "$RC" = 1 ] || falha "M7: a transacao deveria ter reprovado"
detectada M7 "$SAIDA/conc_M7.txt" "S-9 sob M7"
restaura M7
mutacao_r1 M5 identity_link_account_impl w2 \
    "SELECT pg_temp.nova_conta('$O1', 'w2@ex.test'); SELECT pg_temp.nova_identidade('w2@ex.test');
     SELECT pg_temp.nova_sessao_id(pg_temp.identidade('w2@ex.test'), 'M5/s', true);
     SELECT pg_temp.sessao_org('$O1', $(conta w2@ex.test), NULL, 'M5/antiga');
     SELECT pg_temp.ctx('$O1'); SELECT pg_temp.como(pg_temp.identidade('w2@ex.test'));
     INSERT INTO identity_link_challenges (identity_id, user_id, token_hash, account_credential_hash, expires_at)
     VALUES (pg_temp.identidade('w2@ex.test'), $(conta w2@ex.test), pg_temp.h('M5/link'),
             (SELECT password_hash FROM user_credentials WHERE user_id = $(conta w2@ex.test)), now() + interval '4 seconds');" \
    "SELECT pg_temp.ctx('$O1'); SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT 1 FROM user_sessions WHERE session_token_hash = pg_temp.h('M5/antiga') FOR UPDATE) x) = 1, 'T1 travou a sessao antiga');" \
    "SELECT pg_temp.afirma(NOT identity_link_account(pg_temp.tok('M5/s'), pg_temp.tok('M5/link'), '$O1'), 'V-4 sob M5: desafio vencido nao vincula');" \
    "V-4 sob M5"
verifica M5 "NOT EXISTS (SELECT 1 FROM identity_users iu JOIN users u ON u.user_id = iu.user_id WHERE u.email = 'w2@ex.test')"
e_check M5
# M8 (R4): trigger SEM o lock da organizacao -> a desativacao do vinculo nao espera a emissao e a sessao escapa dela
mutacao M8 enforce_user_session_mfa_on_insert "        PERFORM 1 FROM public.organizations o WHERE o.organization_id = NEW.organization_id FOR SHARE;
" ""
transacao M8-T1 0 4 "READ COMMITTED" "$(q_emite qv10@ex.test "$O1" Q-M8)"
transacao M8-T2 1 0 "READ COMMITTED" "$(q_desvincula qv10@ex.test "$O1")"
fim_transacoes
r="$(consulta "$LIMPA" nekkora "SELECT ((SELECT status FROM organization_memberships WHERE user_id = $(qu qv10@ex.test "$O1")) = 3
    AND (SELECT revoked_at IS NULL FROM user_sessions WHERE session_token_hash = $(qh Q-M8)))::text")"
[ "$r" = true ] || falha "MUTACAO M8 NAO FOI DETECTADA (sessao ativa com vinculo inativo nao se formou)"
echo "M8: DETECTADA (sem o lock da organizacao, a sessao emitida durante a desativacao do vinculo ficou ativa)" >> "$SAIDA/MUT_resumo.txt"
restaura M8
conc M8r "$(q_emite qv11@ex.test "$O1" Q-M8r)" "$(q_desvincula qv11@ex.test "$O1")" T2
verifica M8r "(SELECT revoked_at IS NOT NULL FROM user_sessions WHERE session_token_hash = $(qh Q-M8r))"
# M9 (R4): auxiliar SEM a reconferencia da elegibilidade -> conta suspensa depois da resolucao recebe sessao
mutacao M9 identity_org_session_allowed "
               -- R4: elegibilidade da conta, da organizacao e do vinculo interno, lida DEPOIS dos locks da trigger
               -- (lock compartilhado da organizacao, depois lock exclusivo da conta); cada comando le o estado confirmado mais recente
               AND EXISTS (SELECT 1 FROM public.users u
                             JOIN public.organizations o ON o.organization_id = u.organization_id
                             JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
                            WHERE u.user_id = p_user_id AND u.organization_id = p_organization_id
                              AND u.account_type = 1 AND u.status = 2 AND u.email_verified_at IS NOT NULL
                              AND o.status = 1 AND m.status = 2)" ""
seq_tx M9p "$(q_alvo qv12@ex.test "$O1")"
seq_tx M9s "$(q_suspende qv12@ex.test "$O1")"
transacao M9 0 0 "READ COMMITTED" "$(q_recusa qv12@ex.test "$O1" Q-M9)"
RC=0; for p in "${T_PIDS[@]}"; do wait "$p" || RC=$((RC + 1)); done; T_PIDS=()
[ "$RC" = 1 ] || falha "M9: a transacao deveria ter reprovado"
detectada M9 "$SAIDA/conc_M9.txt"
restaura M9
seq_tx M9r "$(q_alvo qv13@ex.test "$O1")"
seq_tx M9rs "$(q_suspende qv13@ex.test "$O1")"
seq_tx M9rr "$(q_recusa qv13@ex.test "$O1" Q-M9r)"
e_check M9
# M10 (R4b): trigger SEM a exigencia de READ COMMITTED -> o cenario Q-RR1 emite com o snapshot antigo
mutacao M10 enforce_user_session_mfa_on_insert "        IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
            RAISE EXCEPTION 'sessao de organizacao vinculada exige transaction_isolation=read committed (atual=%)',
                pg_catalog.current_setting('transaction_isolation') USING ERRCODE = '25N01';
        END IF;
" ""
transacao M10-T1 0 0 "REPEATABLE READ" "$(q_snapshot qv20@ex.test "$O1" Q-M10) $(q_emite qv20@ex.test "$O1" Q-M10)"
transacao M10-T2 1 0 "READ COMMITTED" "$(q_desvincula qv20@ex.test "$O1")"
fim_transacoes
r="$(consulta "$LIMPA" nekkora "SELECT ((SELECT status FROM organization_memberships WHERE user_id = $(qu qv20@ex.test "$O1")) = 3
    AND (SELECT revoked_at IS NULL FROM user_sessions WHERE session_token_hash = $(qh Q-M10)))::text")"
[ "$r" = true ] || falha "MUTACAO M10 NAO FOI DETECTADA (sessao ativa com vinculo inativo nao se formou em REPEATABLE READ)"
echo "M10: DETECTADA (sem a exigencia de READ COMMITTED, REPEATABLE READ emitiu com o snapshot antigo: sessao ativa, vinculo inativo)" >> "$SAIDA/MUT_resumo.txt"
restaura M10
transacao M10r-T1 0 0 "REPEATABLE READ" "$(q_snapshot qv21@ex.test "$O1" Q-M10r) $(q_recusa_iso qv21@ex.test "$O1" Q-M10r)"
transacao M10r-T2 1 0 "READ COMMITTED" "$(q_desvincula qv21@ex.test "$O1")"
fim_transacoes
verifica M10r "NOT EXISTS (SELECT 1 FROM user_sessions WHERE session_token_hash = $(qh Q-M10r))"
e_check M10
# M11 (R5): a consulta de criacao SEM a recusa da sessao restrita -> T-5 passa a responder verdadeiro
mutacao M11 identity_org_creation_allowed "
           AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)"
M11SQL="SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/restrita')) IS FALSE, 'T-5 sob M11: sessao restrita');"
RC=0; com_inc "$AQUI/sql/t_conc.sql" | docker exec -i -e PGAPPNAME=M11 "$LIMPA" psql -X -d nekkora -U nekkora_platform -v ON_ERROR_STOP=1 \
    -v rotulo=M11 -v atraso=0 -v segura=0 -v iso="READ COMMITTED" -v acao="$M11SQL" > "$SAIDA/conc_M11.txt" 2>&1 || RC=$?
[ "$RC" != 0 ] || falha "M11: a transacao deveria ter reprovado"
detectada M11 "$SAIDA/conc_M11.txt" "T-5 sob M11"
restaura M11
seq_tx M11r "$M11SQL"
# M12 (R5): a consulta de criacao SEM o teto -> T-7 (10 organizacoes criadas) passa a responder verdadeiro
mutacao M12 identity_org_creation_allowed "AND iu.link_channel = 'cadastro_web') < 10" "AND iu.link_channel = 'cadastro_web') < 1000"
M12SQL="SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/teto')) IS FALSE, 'T-7 sob M12: teto atingido');"
RC=0; com_inc "$AQUI/sql/t_conc.sql" | docker exec -i -e PGAPPNAME=M12 "$LIMPA" psql -X -d nekkora -U nekkora_platform -v ON_ERROR_STOP=1 \
    -v rotulo=M12 -v atraso=0 -v segura=0 -v iso="READ COMMITTED" -v acao="$M12SQL" > "$SAIDA/conc_M12.txt" 2>&1 || RC=$?
[ "$RC" != 0 ] || falha "M12: a transacao deveria ter reprovado"
detectada M12 "$SAIDA/conc_M12.txt" "T-7 sob M12"
restaura M12
seq_tx M12r "$M12SQL"
lista "$LIMPA" nekkora > "$SAIDA/MUT_catalogo_restaurado.txt"
diff "$SAIDA/A01_catalogo_017.txt" "$SAIDA/MUT_catalogo_restaurado.txt" > "$SAIDA/MUT_diff_catalogo.txt" || falha "catalogo depois das mutacoes difere da instalacao"
[ "$(docker exec "$LIMPA" sh -c 'grep -c . /tmp/e.log')" -ge 60 ] || falha "menos cenarios com E conferido do que o esperado"
docker exec "$LIMPA" cat /tmp/e.log > "$SAIDA/E_por_cenario.txt"
grep -v '|0|0|0|0$' "$SAIDA/E_por_cenario.txt" && falha "algum cenario com E violado"
invariantes "$LIMPA" A99
echo "   comportamento: OK ($(wc -l < "$SAIDA/E_por_cenario.txt" | tr -d ' ') cenarios com E-1..E-3 conferidos)"

# ================================================================== B. atualizacao de base populada 015 -> 017
echo ">> B: base populada 001-015 -> 017 ($POP)"
sobe "$POP" "$SAIDA/mig_alvo" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | shasum -a 256 2>/dev/null | cut -c1-64 || head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" nekkora 013 015 "$SAIDA/B02_013_015.txt"
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
psqlc "$POP" nekkora < "$AQUI/sql/c_instantaneo_gera.sql" > "$SAIDA/B04_instantaneo.sql"
[ "$(wc -l < "$SAIDA/B04_instantaneo.sql")" -ge 45 ] || falha "gerador de instantaneo incompleto"
psqlc "$POP" nekkora < "$SAIDA/B04_instantaneo.sql" > "$SAIDA/B04_dados_antes.txt"
ATIVAS_ANTES="$(consulta "$POP" nekkora "SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL")"
[ "$ATIVAS_ANTES" -gt 0 ] || falha "massa sem sessao ativa"
modelo "$POP" nekkora 017 017 "$SAIDA/B05_aplica_017.txt"
psqlc "$POP" nekkora < "$SAIDA/B04_instantaneo.sql" > "$SAIDA/B06_dados_depois.txt"
diff "$SAIDA/B04_dados_antes.txt" "$SAIDA/B06_dados_depois.txt" > "$SAIDA/B06_diff_dados.txt" || falha "a 017 alterou dados existentes (ver B06_diff_dados.txt)"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL")" = "$ATIVAS_ANTES" ] || falha "a 017 revogou sessoes existentes"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM user_sessions WHERE identity_session_id IS NOT NULL")" = 0 ] || falha "coluna nova preenchida"
lista "$POP" nekkora > "$SAIDA/B07_catalogo_atualizada.txt"
diff "$SAIDA/A01_catalogo_017.txt" "$SAIDA/B07_catalogo_atualizada.txt" > "$SAIDA/B07_diff_catalogo_limpa.txt" || falha "catalogo da atualizada difere da instalacao limpa"
invariantes "$POP" B08
# conta atual com fator confirmado ainda abre sessao (ramo 1); conta atual sem fator continua recusada (014 intacta)
read -r B_ORG B_USER <<< "$(consulta "$POP" nekkora "SELECT u.organization_id || ' ' || u.user_id FROM users u JOIN user_mfa_factors f ON f.user_id = u.user_id
    AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL ORDER BY u.user_id LIMIT 1" | tr -d '\r')"
read -r B_ORG2 B_USER2 <<< "$(consulta "$POP" nekkora "SELECT u.organization_id || ' ' || u.user_id FROM users u WHERE NOT EXISTS (SELECT 1 FROM user_mfa_factors f
    WHERE f.user_id = u.user_id AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL) ORDER BY u.user_id LIMIT 1" | tr -d '\r')"
cat "$ASSERTS" - <<SQL | docker exec -i "$POP" psql -X -d nekkora -U nekkora_platform > "$SAIDA/B09_ramo1_populada.txt" 2>&1 || falha "conta atual na base populada (ver B09)"
BEGIN;
SELECT set_config('nekkora.organization_id', '$B_ORG', true);
INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
VALUES ('$B_ORG', '$B_USER', encode(sha256(gen_random_bytes(32)), 'hex'), now() + interval '1 hour', now(), repeat('a', 43));
SELECT set_config('nekkora.organization_id', '$B_ORG2', true);
SELECT pg_temp.espera_erro(format('INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES (%L, %L, %L, now() + interval ''1 hour'', now(), repeat(''a'', 43))',
    '$B_ORG2', '$B_USER2', encode(sha256(gen_random_bytes(32)), 'hex')), '23514', 'fator de MFA confirmado');
ROLLBACK;
SQL
docker exec "$POP" sh -c "psql -X -q -U nekkora -d postgres -c 'CREATE DATABASE restaurada_017' && pg_dump -U nekkora -Fc nekkora | pg_restore -U nekkora -d restaurada_017 --exit-on-error" \
    > "$SAIDA/B10_restauracao_logica.txt" 2>&1 || { cat "$SAIDA/B10_restauracao_logica.txt"; falha "restauracao logica da base atualizada"; }
CONTA="SELECT string_agg(format('%s=%s', c.relname, (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM public.%I', c.relname), false, true, '')))[1]::text), ',' ORDER BY c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'r'"
[ "$(consulta "$POP" nekkora "$CONTA")" = "$(consulta "$POP" restaurada_017 "$CONTA")" ] || falha "contagens da restauracao logica divergem"
echo "   dados identicos, sessoes preservadas, catalogo = limpa, invariantes, ramo 1 e restauracao logica: OK"

# ================================================================== C. compatibilidade com a 016
echo ">> C: 001-017 com a 016 ($COMPAT)"
sobe "$COMPAT" "$SAIDA/mig_016" 17 017
invariantes "$COMPAT" C01 com016
echo "   instalacao e invariantes 012/013/014/016/017: OK"
echo ">> C: controles positivos com a 016 presente (vinculacao e limpeza legitima do reset operacional)"
psqlc "$COMPAT" nekkora -v org_id=99999999-0000-0000-0000-000000000099 -v org_name='Nekkora Internal' \
    -v staff_user_id=99999999-0000-0000-0000-0000000000aa -v staff_email=staff@nekkora.internal -v staff_name='Nekkora Staff' \
    -v limites='{"(1,1,0)","(2,1,0)","(3,1,0)","(4,2,)","(5,2,)"}' < "$REPO/db/bootstrap/001_organizacao_interna_nekkora.sql" > "$SAIDA/C02_bootstrap.txt" 2>&1 \
    || falha "bootstrap (compat) falhou"
roda "$COMPAT" nekkora_provisioning f01_provisiona.sql C03_f01
roda "$COMPAT" nekkora_platform     f02_fixture_platform.sql C04_f02_vinculacao
roda "$COMPAT" nekkora_platform     t_r1_016_prep.sql C05_prep_016
awk '/-- 4\. efeitos \(os do reset administrativo\)/ {f=1; next} f && /^    INSERT INTO public\.mfa_events/ {exit} f' \
    "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql" > "$SAIDA/C06_bloco_016.sql"
[ "$(grep -c '^    UPDATE public\.' "$SAIDA/C06_bloco_016.sql")" = 4 ] || falha "extracao do bloco de efeitos da 016 falhou"
ALVO016="$(consulta "$COMPAT" nekkora "SELECT user_id FROM users WHERE email = 'r016@ex.test'")"
{ echo "SET ROLE nekkora_ops_mfa_owner;"
  echo "DO \$\$ DECLARE v_org UUID := '$O1'; v_target UUID := '$ALVO016'; v_agora TIMESTAMPTZ := clock_timestamp(); v_n INTEGER; BEGIN"
  echo "PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);"
  cat "$SAIDA/C06_bloco_016.sql"
  echo "END \$\$;"; echo "RESET ROLE;"; } > "$SAIDA/C06_executa_016.sql"
psqlc "$COMPAT" nekkora < "$SAIDA/C06_executa_016.sql" > "$SAIDA/C06_executa_016.txt" 2>&1 || { cat "$SAIDA/C06_executa_016.txt"; falha "a limpeza legitima da 016 foi recusada"; }
[ "$(consulta "$COMPAT" nekkora "SELECT (SELECT count(*) FROM auth_challenges WHERE user_id = '$ALVO016' AND consumed_at IS NULL AND invalidated_at IS NULL)
    + (SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '$ALVO016' AND used_at IS NULL AND invalidated_at IS NULL)
    + (SELECT count(*) FROM user_mfa_factors WHERE user_id = '$ALVO016' AND disabled_at IS NULL)
    + (SELECT count(*) FROM user_sessions WHERE user_id = '$ALVO016' AND revoked_at IS NULL)")" = 0 ] || falha "limpeza da 016 incompleta"
docker exec "$COMPAT" sh /tmp/e.sh C-016 >> "$SAIDA/C_e.txt"; docker exec "$COMPAT" sh -c 'test ! -e /tmp/E_FALHOU' || falha "E violado no compat"
echo "   vinculacao (fixture) e bloco de efeitos do reset da 016, como nekkora_ops_mfa_owner: OK"

fi   # TESTE_SO_D

# ================================================================== D. base populada COM a 016 -> 017 (R3)
D_RESUMO=""
. "$AQUI/parte_d.sh"

[ "${TESTE_SO_D:-0}" = 1 ] && { echo "$D_RESUMO" | tee "$SAIDA/resumo.txt"; docker rm -f -v "$POP16" > /dev/null; echo "saidas: $SAIDA"; exit 0; }
{
    echo "A: 16 migrations (001-015 + 017); diff de catalogo: $(sed -n 1p "$SAIDA/A01_diff_resumo.txt"); $(sed -n 3p "$SAIDA/A01_diff_resumo.txt"); fora do previsto: 0"
    echo "A: modelo do migrador: $(grep -c 'SUPERUSER revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt") janelas SUPERUSER (011, 012, 013, 017) e $(grep -c 'versao=014 BYPASSRLS revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt") BYPASSRLS, todas com retirada 0|0|0"
    echo "A: invariantes 012/013/014(adaptada)/017 DEPOIS da retirada ($(cat "$SAIDA/A02_estado_migrador.txt")), no inicio e no fim: OK"
    echo "A: grupos A, B, C' , D, G, H, I, J, L: OK; grupo L sem efeito ($(wc -l < "$SAIDA/L01_antes.txt" | tr -d ' ') tabelas identicas)"
    echo "A: concorrencia (grupos C, K, R, V e Q, mais M1r e M8r): $(grep -c . "$SAIDA/C_verificacoes.txt") verificacoes de estado final, $(grep -c . "$SAIDA/C_observador.txt") esperas de lock observadas"
    echo "A: E-1..E-3 e entrada local viva conferidos em $(wc -l < "$SAIDA/E_por_cenario.txt" | tr -d ' ') pontos, todos 0|0|0|0"
    echo "A: grupo M: $(wc -l < "$SAIDA/M01_real.txt" | tr -d ' ') privilegios efetivos = matriz esperada"
    echo "A: mutacoes: $(grep -c DETECTADA "$SAIDA/MUT_resumo.txt") de 12 detectadas (M1 lock, M2 sessao restrita, M3 reconferencia do desafio, M4 validade no consumo, M5 hora da vinculacao R1, M6 credencial vigente, M7 invalidacao na troca de senha, M8 lock da organizacao na emissao R4, M9 elegibilidade na emissao R4, M10 READ COMMITTED na emissao R4b, M11 sessao restrita na consulta de criacao R5, M12 teto na consulta de criacao R5), restauradas, catalogo final = instalacao"
    echo "A: R4: grupo Q (elegibilidade da conta, da organizacao e do vinculo na emissao; Q-0..Q-5 sequenciais, Q-C1..Q-C6 com a espera de T2 observada, Q-C7 ramo 1 sem espera, Q-C8 ramo 2 espera; R4b: Q-RR1..Q-RR3 recusa fora de READ COMMITTED, Q-RC1/Q-RC2 em READ COMMITTED, Q-R1 ramo 1 em qualquer isolamento) OK"
    echo "A: R3: grupo S (finalidade, credencial vigente, re-hash, atomicidade, troca de senha) e V-5/V-6 (espera nas funcoes novas) OK"
    echo "A: R2: grupo V (prova vencida durante espera posterior) e grupo P (RLS por identidade, credenciais sem SELECT, consultas exatas) OK"
    echo "A: R1: grupo N (imutabilidade estrita e controles positivos) e grupo R (desafio reconferido apos o lock) OK"
    echo "B: $(cat "$SAIDA/B01_massa_001_012.txt")"
    echo "B: $(cat "$SAIDA/B03_massa_mfa.txt")"
    echo "B: tabelas no instantaneo: $(wc -l < "$SAIDA/B04_dados_antes.txt" | tr -d ' '); diff de dados: vazio; sessoes ativas preservadas: $ATIVAS_ANTES"
    echo "B: catalogo identico a instalacao limpa; invariantes DEPOIS da retirada ($(cat "$SAIDA/B08_estado_migrador.txt")); ramo 1 na base populada; restauracao logica: OK"
    echo "C: 001-017 com a 016: 17 migrations, invariantes 012/013/014/016/017: OK"
    echo "C: controles positivos: vinculacao pela fixture e bloco de efeitos do reset da 016 (4 UPDATEs, papel nekkora_ops_mfa_owner) aceitos"
    echo "$D_RESUMO"
} > "$SAIDA/resumo.txt"
cat "$SAIDA/resumo.txt"
docker rm -f -v "$LIMPA" "$POP" "$COMPAT" "${PROJETO}-pop016" > /dev/null
echo "containers descartaveis removidos: $LIMPA $POP $COMPAT ${PROJETO}-pop016"
echo "saidas: $SAIDA"
