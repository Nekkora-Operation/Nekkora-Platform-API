#!/usr/bin/env bash
# P2 - medicao dos privilegios exigidos pela migration 016 (plano de homologacao da 016, cenario B1; decisao D-ELEV/P3).
#
#   ops/homologacao/testes/p2_privilegios.sh <dir_evidencia> <caso> [<caso> ...]
#
# Cada caso roda num cluster PostgreSQL 16 DESCARTAVEL proprio (container rotulado nekkora.p2=<caso>, --network none: sem
# rede, sem porta; autenticacao SCRAM sem trust; removido com o volume anonimo ao final). Papeis sao objetos do cluster:
# cluster novo por caso, nunca reaproveitado. Nada aqui toca ambiente existente (hml03, banco principal, nk016-smoke).
#
# Base 001-015 reproduzivel no MODELO da implantacao (implantacao/implantar.sh), aplicada por psql (NAO por Flyway: o
# caminho do Flyway e do ensaio): bootstrap REAL (sql/bootstrap.sql, migrador_nekkora NAO superusuario, dono do banco),
# migrations como migrador_nekkora, arquivo sem BEGIN com --single-transaction (Flyway executeInTransaction=true), com
# BEGIN proprio sem; elevacao SUPERUSER so nas versoes de implantacao/elevacao.txt com o sha256 exato, revogada depois
# de cada janela com o MESMO comando do runner e conferida (estado 0|0|0). Qualquer outra versao que falhe anula a base.
#
# Tentativa INTEGRAL da 016: os bytes originais (sha256 conferido DENTRO do container antes de cada tentativa), psql
# -v ON_ERROR_STOP=1 -v VERBOSITY=verbose (SQLSTATE e linha), como migrador_nekkora. DIAGNOSTICO (rotulado como SONDA, nao
# substitui a tentativa integral): os mesmos bytes com ON_ERROR_STOP=0 e ON_ERROR_ROLLBACK=on - cada instrucao recusada e
# desfeita isoladamente e a execucao segue, listando TODAS as recusas (inclusive posteriores e em cascata); o cluster e
# descartado em seguida.
#
# Casos: M1 M2 D1 D2 D3 M3a M3b M3c M3d M3e M3f M3g M4 (M4 inclui a retirada, M5). D* e M3d sao SONDAS. Senhas efemeras so em memoria e num diretorio
# temporario fora do repositorio (verificadores SCRAM), apagado no fim; nada de senha na evidencia.
set -uo pipefail
EV="${1:?dir_evidencia}"; shift
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
M016="$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql"
SHA016=25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61
IMG=postgres:16-alpine
export MSYS_NO_PATHCONV=1
mkdir -p "$EV"
REC="$EV/recursos_p2.txt"
senha() { python -c 'import secrets; print(secrets.token_urlsafe(24))' | tr -d '\r'; }
mixed() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
[ "$(sha256sum "$M016" | cut -c1-64)" = "$SHA016" ] || { echo "ERRO: 016 fora do sha256 aprovado" >&2; exit 1; }

# ------------------------------------------------------------------------------------------------ cluster
novo_cluster() {   # $1 caso -> define C, ADM, MIG
    local caso="$1" tmp
    C="nekkora-p2-${caso,,}-$(python -c 'import secrets; print(secrets.token_hex(3))' | tr -d '\r')"
    ADM="$(senha)"; MIG="$(senha)"
    docker run -d --name "$C" --label nekkora.p2="$caso" --network none -e POSTGRES_USER=admin_cluster -e POSTGRES_PASSWORD="$ADM" \
        -e POSTGRES_DB=postgres -e POSTGRES_INITDB_ARGS="--auth-local=scram-sha-256 --auth-host=scram-sha-256" "$IMG" > /dev/null || return 1
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ)|container|$(docker inspect -f '{{.Id}}' "$C")|$C rotulo nekkora.p2=$caso imagem $IMG ($(docker image inspect -f '{{.Id}}' $IMG))|criado" >> "$REC"
    for i in $(seq 1 90); do
        [ "$(docker logs "$C" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
        sleep 1
    done
    docker exec "$C" mkdir -p /p2
    docker cp "$(mixed "$REPO/db/migrations")/." "$C:/p2/mig" > /dev/null
    docker cp "$(mixed "$AQUI/sql/bootstrap.sql")" "$C:/p2/bootstrap.sql" > /dev/null
    [ "$(docker exec "$C" sha256sum /p2/mig/016_recuperacao_emergencial_mfa.sql | cut -c1-64)" = "$SHA016" ] || { echo "ERRO: copia da 016 divergente" >&2; return 1; }
    tmp="$(mktemp -d)"
    printf '%s' "$MIG" > "$tmp/m"; printf '%s' "$(senha)" > "$tmp/b"
    VM="$(python "$(mixed "$AQUI/lib/segredos.py")" scram "$(mixed "$tmp/m")" | tr -d '\r')"
    VB="$(python "$(mixed "$AQUI/lib/segredos.py")" scram "$(mixed "$tmp/b")" | tr -d '\r')"
    rm -rf "$tmp"
    adm postgres -v verif_migrador="$VM" -v verif_backup="$VB" -f /p2/bootstrap.sql > /dev/null || { echo "ERRO: bootstrap" >&2; return 1; }
}
fim_cluster() {
    [ -n "${C:-}" ] || return 0
    local id; id="$(docker inspect -f '{{.Id}}' "$C" 2>/dev/null)"
    docker rm -f -v "$C" > /dev/null 2>&1 && echo "$(date -u +%Y-%m-%dT%H:%M:%SZ)|container|$id|$C|removido com docker rm -f -v (inclui o volume anonimo)" >> "$REC"
    C=""
}
trap fim_cluster EXIT
adm() { local db="$1"; shift; docker exec -i -e PGPASSWORD="$ADM" "$C" psql -X -q -At -v ON_ERROR_STOP=1 -U admin_cluster -d "$db" "$@"; }
mig() { docker exec -i -e PGPASSWORD="$MIG" "$C" psql -X -q -At -U migrador_nekkora -d nekkora "$@"; }
REVOGA="ALTER ROLE migrador_nekkora NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS NOREPLICATION"   # o de implantar.sh
estado_migrador() {   # mesma consulta de lib/estado.sh: atributos|memberships|sessoes
    adm postgres -c "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' ||
        (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole OR roleid = 'migrador_nekkora'::regrole) || '|' ||
        (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')" | tr -d '\r'
}
atributos_migrador() {
    adm postgres -c "SELECT 'rolsuper=' || rolsuper || ' rolcreaterole=' || rolcreaterole || ' rolcreatedb=' || rolcreatedb || ' rolreplication=' || rolreplication
        || ' rolbypassrls=' || rolbypassrls || ' membro_de=' || coalesce((SELECT string_agg(pg_get_userbyid(roleid), ',') FROM pg_auth_members WHERE member = r.oid), '-')
        || ' administrado_por_ele=' || coalesce((SELECT string_agg(pg_get_userbyid(roleid), ',') FROM pg_auth_members WHERE member = r.oid AND admin_option), '-')
        || ' acl_parametros=' || (SELECT count(*) FROM pg_parameter_acl WHERE array_to_string(paracl, ',') LIKE '%migrador_nekkora%')
        FROM pg_roles r WHERE rolname = 'migrador_nekkora'" | tr -d '\r'
}
encerra_sessoes() { adm postgres -c "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE usename = 'migrador_nekkora'" > /dev/null; }
# psql informa a linha em que a instrucao TERMINA (em funcoes e DO, o "$$;"). Na 016 toda instrucao de nivel superior
# comeca na coluna 0 com a palavra-chave: a instrucao recusada e a ultima linha assim ate a linha informada (heuristica
# declarada; o texto completo do erro, com CONTEXT e LOCATION, fica na saida bruta).
instrucao_em() {   # $1 linha final informada pelo psql -> "inicio L: texto"
    awk -v n="$1" 'NR <= n && /^(CREATE|ALTER|GRANT|REVOKE|DO|COMMENT|INSERT|BEGIN|COMMIT)[ ;$]/ {l = NR; t = $0}
                   NR == n {print "inicio " l ": " substr(t, 1, 150); exit}' "$M016"
}
servidor() {   # versao, imagem e configuracao relevante do cluster (somente leitura)
    echo "imagem=$IMG id=$(docker image inspect -f '{{.Id}}' $IMG)"
    adm postgres <<'SQL'
SELECT 'versao|' || version();
SELECT 'config|' || name || '=' || setting FROM pg_settings WHERE name IN ('server_version', 'ssl', 'password_encryption',
       'createrole_self_grant', 'shared_preload_libraries', 'session_preload_libraries', 'default_transaction_isolation') ORDER BY name;
SQL
    echo "pg_hba_efetivo:"; docker exec "$C" sh -c 'grep -vE "^\s*(#|$)" "$PGDATA/pg_hba.conf"'
}

base_001_015() {   # $1 arquivo de registro
    local f n v modo aut real
    for f in "$REPO"/db/migrations/0*.sql; do
        n="$(basename "$f")"; v="${n:0:3}"
        [ "$v" = 016 ] && break
        modo=(-1); grep -qE '^\s*BEGIN\s*;' "$f" && modo=()
        aut="$(grep -vE '^\s*(#|$)' "$AQUI/implantacao/elevacao.txt" | awk -v v="$v" '$1 == v {print $2}')"
        real="$(sha256sum "$f" | cut -c1-64)"
        if [ -n "$aut" ]; then
            [ "$aut" = "$real" ] || { echo "ERRO: $n sha256 fora do autorizado" >&2; return 1; }
            adm postgres -c "ALTER ROLE migrador_nekkora SUPERUSER"
        fi
        if ! mig -v ON_ERROR_STOP=1 "${modo[@]}" -f "/p2/mig/$n" > /dev/null 2>"$EV/.erro_base"; then
            echo "ERRO: base $n falhou: $(head -3 "$EV/.erro_base")" >&2; return 1
        fi
        if [ -n "$aut" ]; then
            adm postgres -c "$REVOGA"; encerra_sessoes
            [ "$(estado_migrador)" = "0|0|0" ] || { echo "ERRO: revogacao depois de $n" >&2; return 1; }
            echo "$n elevada (SUPERUSER, sha256 autorizado) e revogada: estado 0|0|0" >> "$1"
        else
            echo "$n sem elevacao" >> "$1"
        fi
    done
    rm -f "$EV/.erro_base"
}

# ------------------------------------------------------------------------------------------------ estado
estado() {   # $1 rotulo -> fotografia do que interessa a 016 (somente leitura)
    echo "== estado: $1 ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
    echo "identidade_da_conexao_do_migrador|$(mig -c "SELECT session_user || '/' || current_user" 2>&1 | tr -d '\r')"
    adm nekkora <<'SQL'
SELECT 'atributos|' || rolname || '|login=' || rolcanlogin || '|super=' || rolsuper || '|createrole=' || rolcreaterole || '|createdb=' || rolcreatedb
       || '|replication=' || rolreplication || '|bypassrls=' || rolbypassrls
  FROM pg_roles WHERE rolname IN ('migrador_nekkora', 'nekkora_ops_mfa_owner', 'nekkora_ops_mfa', 'nekkora_ops_ca', 'nekkora_ops_intake') ORDER BY rolname;
SELECT 'papeis_016_ausentes|' || coalesce(string_agg(r, ',' ORDER BY r), '-') FROM unnest(ARRAY['nekkora_ops_mfa_owner', 'nekkora_ops_mfa',
       'nekkora_ops_ca', 'nekkora_ops_intake']) r WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r);
SELECT 'membership|' || pg_get_userbyid(roleid) || '->' || pg_get_userbyid(member) || '|admin=' || admin_option || '|inherit=' || inherit_option
       || '|set=' || set_option || '|grantor=' || pg_get_userbyid(grantor)
  FROM pg_auth_members WHERE pg_get_userbyid(member) IN ('migrador_nekkora', 'nekkora_ops_mfa_owner', 'nekkora_ops_mfa', 'nekkora_ops_ca', 'nekkora_ops_intake')
     OR pg_get_userbyid(roleid) IN ('migrador_nekkora', 'nekkora_ops_mfa_owner', 'nekkora_ops_mfa', 'nekkora_ops_ca', 'nekkora_ops_intake') ORDER BY 1;
SELECT 'dono_banco|' || pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'nekkora';
SELECT 'dono_schema|' || nspname || '|' || pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname IN ('public', 'ops') ORDER BY 1;
SELECT 'dono_tabela|' || c.relname || '|' || pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relname IN ('mfa_events', 'organizations', 'users', 'user_mfa_factors', 'user_sessions') ORDER BY 1;
SELECT 'donos_objetos_public|' || pg_get_userbyid(c.relowner) || '|' || count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') GROUP BY c.relowner ORDER BY 1;
SELECT 'extensao_disponivel|' || name || '|versao=' || version || '|trusted=' || trusted || '|superuser=' || superuser || '|instalada=' || installed
  FROM pg_available_extension_versions WHERE name = 'sslinfo';
SELECT 'extensao_instalada|' || e.extname || '|schema=' || n.nspname || '|dono=' || pg_get_userbyid(e.extowner) FROM pg_extension e
  JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'sslinfo';
SELECT 'schema_ops|existe=' || EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'ops')
       || '|dono=' || coalesce((SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'ops'), '-');
SELECT 'acl_parametro|' || parname || '|' || array_to_string(paracl, ',') FROM pg_parameter_acl ORDER BY parname;
SELECT 'acl_parametro_total|' || count(*) FROM pg_parameter_acl;
SELECT 'catalogo_016|funcoes_publicas_ops=' || (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'ops\_%')
       || '|tabelas_ops=' || (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops' AND c.relkind = 'r')
       || '|politicas_ops_function_context=' || (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND policyname = 'ops_function_context')
       || '|mfa_events.ops_request_id=' || EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'mfa_events' AND column_name = 'ops_request_id');
SQL
    echo "estado_migrador(atributos|memberships|sessoes)|$(estado_migrador)"
}

tentativa_integral() {   # $1 rotulo -> codigo do psql
    local out="$EV/$CASO/016_integral_$1.txt" rc
    { echo "comando: psql -X -U migrador_nekkora -d nekkora -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -f /p2/mig/016_recuperacao_emergencial_mfa.sql"
      echo "sha256_016_no_container=$(docker exec "$C" sha256sum /p2/mig/016_recuperacao_emergencial_mfa.sql | cut -c1-64)"
      echo "inicio_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$out"
    mig -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -f /p2/mig/016_recuperacao_emergencial_mfa.sql >> "$out" 2>&1; rc=$?
    echo "codigo_saida=$rc" >> "$out"
    local e; e="$(grep -m1 -E 'ERROR:' "$out")"
    local linha; linha="$(echo "$e" | sed -nE 's/^psql:[^:]*:([0-9]+): .*/\1/p')"
    echo "resultado|$1|codigo=$rc|erro=${e#*ERROR:  }" | tee -a "$EV/$CASO/resumo.txt"
    if [ -n "$linha" ]; then
        echo "instrucao_recusada|linha final $linha|$(instrucao_em "$linha")" | tee -a "$EV/$CASO/resumo.txt"
    fi
    return $rc
}

diagnostico() {   # SONDA: todas as recusas, com a linha e o texto da instrucao
    local out="$EV/$CASO/016_diagnostico.txt"
    { echo "SONDA DE DIAGNOSTICO (nao e tentativa integral): psql -v ON_ERROR_STOP=0 -v ON_ERROR_ROLLBACK=on -v VERBOSITY=verbose (mesmos bytes)"
      echo "sha256_016_no_container=$(docker exec "$C" sha256sum /p2/mig/016_recuperacao_emergencial_mfa.sql | cut -c1-64)"; } > "$out"
    mig -v ON_ERROR_STOP=0 -v ON_ERROR_ROLLBACK=on -v VERBOSITY=verbose -f /p2/mig/016_recuperacao_emergencial_mfa.sql >> "$out" 2>&1
    echo "codigo_saida=$?" >> "$out"
    grep -E '^psql:.*ERROR:' "$out" | while read -r l; do
        n="$(echo "$l" | sed -nE 's/^psql:[^:]*:([0-9]+): .*/\1/p')"
        echo "recusa|linha final $n|${l#*ERROR:  }|$(instrucao_em "$n")"
    done | tee "$EV/$CASO/recusas.txt"
    echo "total_recusas=$(wc -l < "$EV/$CASO/recusas.txt")" | tee -a "$EV/$CASO/resumo.txt"
}

precria_papeis() {   # papeis da 016 criados ANTES pelo administrador, com os atributos que a propria 016 aplica
    adm postgres <<'SQL'
CREATE ROLE nekkora_ops_mfa_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE nekkora_ops_mfa       NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE nekkora_ops_ca        LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE nekkora_ops_intake    LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SQL
    echo "precondicao|papeis da 016 pre-criados pelo admin_cluster (superusuario), atributos iguais aos da 016, sem senha, sem membership do migrador" >> "$EV/$CASO/resumo.txt"
}

invariantes() {   # bloco de invariantes da 016 (sec. 19) da COPIA no container, em BEGIN/ROLLBACK, como administrador (como o b5)
    local out="$EV/$CASO/invariantes_016.txt"
    docker exec "$C" sh -c "awk '/^-- === 19\\. /{f=1} f && /^COMMIT;/{exit} f' /p2/mig/016_recuperacao_emergencial_mfa.sql > /p2/inv.sql"
    { echo "bloco: da linha '-- === 19.' ate o COMMIT final da 016 (copia no container, sha256 conferido); DO blocks: $(docker exec "$C" grep -c '^DO \$\$' /p2/inv.sql)"
      echo "linhas: $(docker exec "$C" sh -c 'wc -l < /p2/inv.sql')"; } > "$out"
    docker exec "$C" sh -c '{ echo "BEGIN;"; cat /p2/inv.sql; echo "ROLLBACK;"; } > /p2/inv_tx.sql'
    if adm nekkora -v VERBOSITY=verbose -f /p2/inv_tx.sql >> "$out" 2>&1; then echo "invariantes_016=OK" | tee -a "$out" "$EV/$CASO/resumo.txt"
    else echo "invariantes_016=FALHA" | tee -a "$out" "$EV/$CASO/resumo.txt"; fi
}

sonda_sql() {   # $1 descricao, $2 identidade (adm|mig), $3 sql -> SONDA isolada, rotulada
    local r
    if [ "$2" = mig ]; then r="$(mig -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -c "$3" 2>&1 | tr -d '\r' | tr '\n' ' ')"
    else r="$(adm nekkora -v VERBOSITY=verbose -c "$3" 2>&1 | tr -d '\r' | tr '\n' ' ')"; fi
    echo "SONDA|$1|como=$2|sql=$3|resultado=${r:-ok}" | tee -a "$EV/$CASO/sondas.txt"
}

# ------------------------------------------------------------------------------------------------ casos
roda() {
    CASO="$1"
    mkdir -p "$EV/$CASO"
    : > "$EV/$CASO/resumo.txt"
    echo "caso=$CASO inicio_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) imagem=$IMG" >> "$EV/$CASO/resumo.txt"
    novo_cluster "$CASO" || { echo "caso $CASO: cluster" | tee -a "$EV/$CASO/resumo.txt"; fim_cluster; return 1; }
    echo "cluster=$C id=$(docker inspect -f '{{.Id}}' "$C" | cut -c1-12) servidor=$(adm postgres -c 'SHOW server_version' | tr -d '\r')" >> "$EV/$CASO/resumo.txt"
    base_001_015 "$EV/$CASO/base_001_015.txt" || { echo "caso $CASO: base 001-015 FALHOU" | tee -a "$EV/$CASO/resumo.txt"; fim_cluster; return 1; }
    echo "base_001_015=OK ($(grep -c elevada "$EV/$CASO/base_001_015.txt") janelas de elevacao, todas revogadas 0|0|0)" >> "$EV/$CASO/resumo.txt"
    servidor > "$EV/$CASO/servidor.txt" 2>&1
    echo "migrador_antes_do_caso|$(atributos_migrador)" >> "$EV/$CASO/resumo.txt"
    case "$CASO" in
        M1)  estado "antes (base normal)" > "$EV/$CASO/estado_antes.txt"
             tentativa_integral base_normal
             estado "depois" > "$EV/$CASO/estado_depois.txt" ;;
        M2)  precria_papeis
             estado "antes (papeis pre-criados)" > "$EV/$CASO/estado_antes.txt"
             tentativa_integral papeis_precriados
             estado "depois" > "$EV/$CASO/estado_depois.txt" ;;
        D1)  estado "antes (base normal)" > "$EV/$CASO/estado_antes.txt"; diagnostico ;;
        D2)  precria_papeis; estado "antes (papeis pre-criados)" > "$EV/$CASO/estado_antes.txt"; diagnostico ;;
        M3a) adm postgres -c "ALTER ROLE migrador_nekkora CREATEROLE"
             echo "precondicao|migrador_nekkora com CREATEROLE (so isso; sem pre-criacao)" >> "$EV/$CASO/resumo.txt"
             estado "antes" > "$EV/$CASO/estado_antes.txt"
             tentativa_integral createrole
             estado "depois" > "$EV/$CASO/estado_depois.txt"
             adm postgres -c "$REVOGA"; encerra_sessoes; echo "retirada|$(estado_migrador)" >> "$EV/$CASO/resumo.txt" ;;
        M3b) precria_papeis
             adm postgres -c "GRANT nekkora_ops_mfa_owner, nekkora_ops_mfa, nekkora_ops_ca, nekkora_ops_intake TO migrador_nekkora WITH ADMIN TRUE, INHERIT FALSE, SET TRUE"
             echo "precondicao|papeis pre-criados + migrador com ADMIN e SET em cada um (nenhum atributo)" >> "$EV/$CASO/resumo.txt"
             estado "antes" > "$EV/$CASO/estado_antes.txt"
             tentativa_integral precriados_admin_set
             estado "depois" > "$EV/$CASO/estado_depois.txt" ;;
        M3c) adm postgres -c "ALTER ROLE migrador_nekkora CREATEROLE REPLICATION BYPASSRLS"
             echo "precondicao|migrador com CREATEROLE REPLICATION BYPASSRLS (todos os atributos que a 016 altera, MENOS SUPERUSER)" >> "$EV/$CASO/resumo.txt"
             estado "antes" > "$EV/$CASO/estado_antes.txt"
             tentativa_integral createrole_replication_bypassrls
             estado "depois" > "$EV/$CASO/estado_depois.txt"
             adm postgres -c "$REVOGA"; encerra_sessoes; echo "retirada|$(estado_migrador)" >> "$EV/$CASO/resumo.txt" ;;
        D3)  # SONDA: o maximo sem SUPERUSER - todos os atributos que a 016 altera, papeis pre-criados com ADMIN/SET do
             # migrador e SET no parametro de contexto. O que sobrar e o que so o superusuario resolve.
             precria_papeis
             adm postgres -c "ALTER ROLE migrador_nekkora CREATEROLE REPLICATION BYPASSRLS"
             adm postgres -c "GRANT nekkora_ops_mfa_owner, nekkora_ops_mfa, nekkora_ops_ca, nekkora_ops_intake TO migrador_nekkora WITH ADMIN TRUE, INHERIT FALSE, SET TRUE"
             adm postgres -c "GRANT SET ON PARAMETER nekkora.fn_organization_id TO migrador_nekkora"
             echo "precondicao|papeis pre-criados + migrador CREATEROLE REPLICATION BYPASSRLS + ADMIN/SET nos 4 papeis + GRANT SET ON PARAMETER nekkora.fn_organization_id (tudo exceto SUPERUSER)" >> "$EV/$CASO/resumo.txt"
             estado "antes" > "$EV/$CASO/estado_antes.txt"; diagnostico
             adm postgres -c "$REVOGA"; encerra_sessoes; echo "retirada_atributos|$(estado_migrador)" >> "$EV/$CASO/resumo.txt" ;;
        M3d) estado "antes" > "$EV/$CASO/estado_antes.txt"
             # SONDAS isoladas (nao substituem a tentativa integral), uma pre-condicao por vez, cada uma numa transacao.
             # (a) sslinfo
             sonda_sql "a1 CREATE EXTENSION sslinfo por migrador normal (dono do banco)" mig "CREATE SCHEMA p2_a1; CREATE EXTENSION sslinfo SCHEMA p2_a1"
             adm postgres -c "ALTER ROLE migrador_nekkora CREATEROLE REPLICATION BYPASSRLS" > /dev/null
             sonda_sql "a2 CREATE EXTENSION sslinfo com CREATEROLE REPLICATION BYPASSRLS" mig "CREATE SCHEMA p2_a2; CREATE EXTENSION sslinfo SCHEMA p2_a2"
             # (b) CREATE ROLE e ALTER ROLE clausula a clausula, com CREATEROLE REPLICATION BYPASSRLS (papel criado pelo proprio migrador)
             sonda_sql "b1 CREATE ROLE com CREATEROLE" mig "CREATE ROLE p2_b1 NOLOGIN"
             sonda_sql "b2 membership automatica do criador (createrole_self_grant)" adm "SELECT string_agg(pg_get_userbyid(roleid) || '->' || pg_get_userbyid(member) || ' admin=' || admin_option || ' set=' || set_option || ' inherit=' || inherit_option, '; ') FROM pg_auth_members WHERE roleid = 'p2_b1'::regrole"
             sonda_sql "b3 ALTER ROLE ... NOSUPERUSER (CREATEROLE + ADMIN)" mig "ALTER ROLE p2_b1 NOSUPERUSER"
             sonda_sql "b4 ALTER ROLE ... NOLOGIN NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS (tudo menos NOSUPERUSER; CREATEROLE REPLICATION BYPASSRLS + ADMIN)" mig "ALTER ROLE p2_b1 NOLOGIN NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"
             adm postgres -c "ALTER ROLE migrador_nekkora NOREPLICATION NOBYPASSRLS" > /dev/null; encerra_sessoes
             sonda_sql "b5 ALTER ROLE ... NOREPLICATION (so CREATEROLE + ADMIN)" mig "ALTER ROLE p2_b1 NOREPLICATION"
             sonda_sql "b6 ALTER ROLE ... NOBYPASSRLS (so CREATEROLE + ADMIN)" mig "ALTER ROLE p2_b1 NOBYPASSRLS"
             sonda_sql "b7 ALTER ROLE ... NOLOGIN NOCREATEDB NOCREATEROLE (so CREATEROLE + ADMIN)" mig "ALTER ROLE p2_b1 NOLOGIN NOCREATEDB NOCREATEROLE"
             adm postgres -c "$REVOGA" > /dev/null; encerra_sessoes
             # (c) papeis pre-criados pelo administrador: OWNER TO e ALTER ROLE sem CREATEROLE
             precria_papeis
             sonda_sql "c1 ALTER ROLE papel pre-criado, migrador sem nada" mig "ALTER ROLE nekkora_ops_mfa NOLOGIN NOCREATEDB NOCREATEROLE"
             sonda_sql "c2 OWNER TO papel pre-criado sem membership" mig "CREATE FUNCTION public.p2_f() RETURNS int LANGUAGE sql AS 'SELECT 1'; ALTER FUNCTION public.p2_f() OWNER TO nekkora_ops_mfa_owner"
             sonda_sql "c3 (preparo) GRANT ... WITH INHERIT FALSE, SET TRUE" adm "GRANT nekkora_ops_mfa_owner TO migrador_nekkora WITH INHERIT FALSE, SET TRUE"
             sonda_sql "c4 OWNER TO papel pre-criado com SET" mig "CREATE FUNCTION public.p2_g() RETURNS int LANGUAGE sql AS 'SELECT 1'; ALTER FUNCTION public.p2_g() OWNER TO nekkora_ops_mfa_owner"
             sonda_sql "c5 (preparo) GRANT ... WITH ADMIN TRUE" adm "GRANT nekkora_ops_mfa TO migrador_nekkora WITH ADMIN TRUE"
             sonda_sql "c6 ALTER ROLE ... NOLOGIN NOSUPERUSER (ADMIN, sem CREATEROLE)" mig "ALTER ROLE nekkora_ops_mfa NOLOGIN NOSUPERUSER"
             sonda_sql "c7 ALTER ROLE ... NOLOGIN NOCREATEDB NOCREATEROLE (ADMIN, sem CREATEROLE)" mig "ALTER ROLE nekkora_ops_mfa NOLOGIN NOCREATEDB NOCREATEROLE"
             # (d) SET de parametro de contexto na definicao de funcao (clausula SET da 016)
             sonda_sql "d1 CREATE FUNCTION ... SET nekkora.fn_organization_id = '' (migrador normal)" mig "CREATE FUNCTION public.p2_h() RETURNS int LANGUAGE sql SET nekkora.fn_organization_id = '' AS 'SELECT 1'"
             sonda_sql "d2 (preparo) GRANT SET ON PARAMETER nekkora.fn_organization_id" adm "GRANT SET ON PARAMETER nekkora.fn_organization_id TO migrador_nekkora"
             sonda_sql "d3 CREATE FUNCTION ... SET nekkora.fn_organization_id = '' (com SET no parametro)" mig "CREATE FUNCTION public.p2_h() RETURNS int LANGUAGE sql SET nekkora.fn_organization_id = '' AS 'SELECT 1'"
             sonda_sql "d4 (retirada) REVOKE SET ON PARAMETER" adm "REVOKE SET ON PARAMETER nekkora.fn_organization_id FROM migrador_nekkora"
             estado "depois" > "$EV/$CASO/estado_depois.txt" ;;
        M3e) adm nekkora -c "CREATE SCHEMA ops AUTHORIZATION migrador_nekkora; CREATE EXTENSION sslinfo SCHEMA ops"
             adm postgres -c "ALTER ROLE migrador_nekkora SUPERUSER"
             echo "precondicao|sslinfo PRE-INSTALADA pelo administrador no schema ops (alternativa de preparo) + migrador SUPERUSER (para isolar o efeito da pre-instalacao)" >> "$EV/$CASO/resumo.txt"
             estado "antes" > "$EV/$CASO/estado_antes.txt"
             tentativa_integral sslinfo_preinstalada_ops
             estado "depois" > "$EV/$CASO/estado_depois.txt"
             adm postgres -c "$REVOGA"; encerra_sessoes; echo "retirada|$(estado_migrador)" >> "$EV/$CASO/resumo.txt" ;;
        M3f) adm nekkora -c "CREATE EXTENSION sslinfo SCHEMA public"
             adm postgres -c "ALTER ROLE migrador_nekkora SUPERUSER"
             echo "precondicao|sslinfo PRE-INSTALADA pelo administrador no schema public (sem criar ops) + migrador SUPERUSER (isola a pre-instalacao)" >> "$EV/$CASO/resumo.txt"
             estado "antes" > "$EV/$CASO/estado_antes.txt"
             tentativa_integral sslinfo_preinstalada_public
             estado "depois" > "$EV/$CASO/estado_depois.txt"
             adm postgres -c "$REVOGA"; encerra_sessoes; echo "retirada|$(estado_migrador)" >> "$EV/$CASO/resumo.txt" ;;
        M3g) precria_papeis
             adm postgres -c "ALTER ROLE migrador_nekkora SUPERUSER"
             echo "precondicao|papeis pre-criados (sem membership) + migrador SUPERUSER: a pre-criacao e compativel com a janela?" >> "$EV/$CASO/resumo.txt"
             estado "antes" > "$EV/$CASO/estado_antes.txt"
             tentativa_integral precriados_superuser
             estado "depois" > "$EV/$CASO/estado_depois.txt"
             adm postgres -c "$REVOGA"; encerra_sessoes; echo "retirada|$(estado_migrador)" >> "$EV/$CASO/resumo.txt" ;;
        M4)  estado "antes (base normal)" > "$EV/$CASO/estado_antes.txt"
             echo "retirada_antes|$(estado_migrador)" >> "$EV/$CASO/resumo.txt"
             adm postgres -c "ALTER ROLE migrador_nekkora SUPERUSER"
             echo "precondicao|migrador_nekkora SUPERUSER temporario (janela so desta tentativa; so no laboratorio)" >> "$EV/$CASO/resumo.txt"
             estado "elevado" > "$EV/$CASO/estado_elevado.txt"
             tentativa_integral superuser_temporario
             cat_016="$(grep '^catalogo_016|' <(estado "catalogo") | tr -d '\r')"
             if [ "$cat_016" = "catalogo_016|funcoes_publicas_ops=37|tabelas_ops=21|politicas_ops_function_context=9|mfa_events.ops_request_id=true" ]
             then echo "catalogo_016=OK|$cat_016" | tee -a "$EV/$CASO/resumo.txt"; else echo "catalogo_016=DIVERGENTE|$cat_016" | tee -a "$EV/$CASO/resumo.txt"; fi
             invariantes
             # M5 - retirada: o comando do runner, encerramento das sessoes do migrador e conferencia
             echo "M5_antes|$(atributos_migrador)" | tee -a "$EV/$CASO/resumo.txt"
             adm postgres -c "$REVOGA"; encerra_sessoes
             echo "M5_depois|$(atributos_migrador)" | tee -a "$EV/$CASO/resumo.txt"
             estado "depois da retirada (M5)" > "$EV/$CASO/estado_depois_retirada.txt"
             echo "M5_retirada|estado_migrador=$(estado_migrador)" | tee -a "$EV/$CASO/resumo.txt"
             adm postgres -c "SELECT 'M5|migrador membro de: ' || coalesce(string_agg(pg_get_userbyid(roleid), ','), '-') || ' | papeis que o migrador administra: ' ||
                 coalesce((SELECT string_agg(pg_get_userbyid(member), ',') FROM pg_auth_members WHERE roleid = 'migrador_nekkora'::regrole), '-')
                 FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole" | tee -a "$EV/$CASO/resumo.txt"
             adm postgres -c "SELECT 'M5|papeis com atributo privilegiado: ' || string_agg(rolname || '(' || concat_ws(',', CASE WHEN rolsuper THEN 'super' END,
                 CASE WHEN rolcreaterole THEN 'createrole' END, CASE WHEN rolcreatedb THEN 'createdb' END, CASE WHEN rolreplication THEN 'replication' END,
                 CASE WHEN rolbypassrls THEN 'bypassrls' END) || ')', ', ' ORDER BY rolname) FROM pg_roles
                 WHERE (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls) AND rolname !~ '^pg_'" | tee -a "$EV/$CASO/resumo.txt"
             sonda_sql "depois da retirada: migrador nao recupera a elevacao (SET ROLE admin_cluster)" mig "SET ROLE admin_cluster"
             sonda_sql "depois da retirada: migrador nao cria papel" mig "CREATE ROLE p2_teste_recuperacao"
             sonda_sql "depois da retirada: migrador nao se concede atributo" mig "ALTER ROLE migrador_nekkora SUPERUSER" ;;
        *)   echo "caso desconhecido: $CASO" >&2 ;;
    esac
    echo "fim_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$EV/$CASO/resumo.txt"
    fim_cluster
}

for caso in "$@"; do roda "$caso"; done
echo "evidencias: $EV"
