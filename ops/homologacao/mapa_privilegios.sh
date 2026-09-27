#!/usr/bin/env bash
# Mapa de elevacao da baseline 001-015 para a identidade de implantacao (Etapa C da missao de homologacao).
#
# Cluster PostgreSQL 16 DESCARTAVEL e isolado (container rotulado, sem porta publicada, sem volume nomeado):
#   - administrador do cluster (bootstrap) = POSTGRES_USER, senha aleatoria, autenticacao SCRAM (sem trust);
#   - identidade de implantacao migrador_nekkora: LOGIN, NAO superusuario, dona do banco nekkora.
# Para cada migration, em ordem: tenta aplicar como migrador_nekkora SEM elevacao; se falhar, registra o SQLSTATE e a
# mensagem, eleva (SUPERUSER), aplica, rebaixa (NOSUPERUSER) e confere o rebaixamento. 012-015 tem BEGIN/COMMIT proprio;
# 001-011 NAO tem, e vao com psql --single-transaction: toda tentativa que falha e desfeita por inteiro.
#
# Saida: tabela migration | aplica sem elevacao? | primeiro erro. O container e removido ao final (so ele).
# Variante MAPA_PAPEIS_PRECRIADOS=1: o bootstrap cria antes, como administrador, todos os papeis que as migrations criam
# (mesmos atributos), para medir o que ainda exige elevacao quando CREATE ROLE sai do caminho.
# Uso: ops/homologacao/mapa_privilegios.sh   |   MAPA_PAPEIS_PRECRIADOS=1 ops/homologacao/mapa_privilegios.sh
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
ID="mapa-$(date -u +%Y%m%dT%H%M%SZ)${MAPA_PAPEIS_PRECRIADOS:+-precriados}"
C="nekkora-homolog-${ID}"
SAIDA="$AQUI/saidas/${ID}"
falha() { echo "ERRO: $*" >&2; exit 1; }

docker container inspect "$C" >/dev/null 2>&1 && falha "container $C ja existe"
mkdir -p "$SAIDA"
ADM="$(head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
MIG="$(head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"

MSYS_NO_PATHCONV=1 docker run -d --name "$C" --label nekkora.homologacao="$ID" --label nekkora.homologacao.papel=mapa-privilegios \
    -e POSTGRES_USER=admin_cluster -e POSTGRES_PASSWORD="$ADM" -e POSTGRES_DB=postgres \
    -e POSTGRES_INITDB_ARGS="--auth-local=scram-sha-256 --auth-host=scram-sha-256" postgres:16-alpine >/dev/null
trap 'docker rm -f -v "$C" >/dev/null 2>&1 || true' EXIT
for i in $(seq 1 60); do
    [ "$(docker logs "$C" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
    sleep 2
done
adm() { docker exec -i -e PGPASSWORD="$ADM" "$C" psql -X -q -At -v ON_ERROR_STOP=1 -U admin_cluster "$@"; }
mig() { docker exec -i -e PGPASSWORD="$MIG" "$C" psql -X -q -At -v ON_ERROR_STOP=1 -U migrador_nekkora -d nekkora "$@"; }

docker exec "$C" sh -c 'grep -vE "^\s*(#|$)" "$PGDATA/pg_hba.conf"' > "$SAIDA/pg_hba_efetivo.txt"
grep -q trust "$SAIDA/pg_hba_efetivo.txt" && falha "pg_hba com trust"
printf "CREATE ROLE migrador_nekkora LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS PASSWORD '%s';\nCREATE DATABASE nekkora OWNER migrador_nekkora;\n" "$MIG" \
    | adm -d postgres
if [ -n "${MAPA_PAPEIS_PRECRIADOS:-}" ]; then
    adm -d postgres <<'SQL'
CREATE ROLE nekkora_retention_tables_owner NOLOGIN; CREATE ROLE nekkora_retention_owner NOLOGIN; CREATE ROLE nekkora_retention_executor LOGIN;
CREATE ROLE nekkora_app LOGIN; CREATE ROLE nekkora_platform LOGIN; CREATE ROLE nekkora_ingestion LOGIN; CREATE ROLE nekkora_scheduler LOGIN;
CREATE ROLE nekkora_provisioning LOGIN; CREATE ROLE nekkora_scheduler_owner NOLOGIN; CREATE ROLE nekkora_provisioning_owner NOLOGIN;
CREATE ROLE nekkora_portal LOGIN; CREATE ROLE nekkora_portal_owner NOLOGIN;
SQL
fi

{
    echo "migration|aplica_sem_elevacao|primeiro_erro"
    for f in "$REPO"/db/migrations/0*.sql; do
        n="$(basename "$f")"
        um=(); grep -qE '^\s*BEGIN\s*;' "$f" || um=(-1)
        if mig "${um[@]}" < "$f" > "$SAIDA/$n.sem_elevacao.txt" 2>&1; then
            echo "$n|sim|-"
        else
            erro="$(grep -m1 -E '^(psql:.*)?ERROR' "$SAIDA/$n.sem_elevacao.txt" | sed -E 's/^psql:[^:]*:[0-9]+: //' | tr '|' '/' | cut -c1-200)"
            echo "$n|NAO|$erro"
            adm -d postgres -c "ALTER ROLE migrador_nekkora SUPERUSER" >/dev/null
            mig "${um[@]}" < "$f" > "$SAIDA/$n.elevado.txt" 2>&1 || { cat "$SAIDA/$n.elevado.txt"; falha "$n falhou mesmo elevada"; }
            adm -d postgres -c "ALTER ROLE migrador_nekkora NOSUPERUSER" >/dev/null
            [ "$(adm -d postgres -c "SELECT rolsuper FROM pg_roles WHERE rolname = 'migrador_nekkora'")" = f ] || falha "rebaixamento falhou"
        fi
    done
} | tee "$SAIDA/mapa_elevacao.txt"

# estado final: donos, atributos e memberships
adm -d nekkora > "$SAIDA/estado_final.txt" <<'SQL'
SELECT 'atributos|' || rolname || '|super=' || rolsuper || '|createrole=' || rolcreaterole || '|createdb=' || rolcreatedb || '|bypassrls=' || rolbypassrls
FROM pg_roles WHERE rolname IN ('migrador_nekkora') OR rolname LIKE 'nekkora%' ORDER BY rolname;
SELECT 'membership|' || pg_get_userbyid(roleid) || '->' || pg_get_userbyid(member) || '|admin=' || admin_option FROM pg_auth_members
WHERE pg_get_userbyid(member) NOT LIKE 'pg\_%' ORDER BY 1;
SELECT 'donos_tabelas|' || dono || '|' || n FROM (SELECT pg_get_userbyid(c.relowner) AS dono, count(*) AS n FROM pg_class c
  JOIN pg_namespace ns ON ns.oid = c.relnamespace WHERE ns.nspname = 'public' AND c.relkind IN ('r','p') GROUP BY 1) t ORDER BY 1;
SELECT 'donos_funcoes|' || dono || '|secdef=' || secdef || '|' || n FROM (SELECT pg_get_userbyid(p.proowner) AS dono, p.prosecdef AS secdef, count(*) AS n
  FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace WHERE ns.nspname = 'public' GROUP BY 1, 2) t ORDER BY 1;
SELECT 'dono_banco|' || pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'nekkora';
SELECT 'dono_schema_public|' || pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'public';
SQL
cat "$SAIDA/estado_final.txt"
echo "saidas: $SAIDA (container removido)"
