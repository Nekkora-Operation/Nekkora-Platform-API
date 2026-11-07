#!/usr/bin/env bash
# Suite de banco da V1: instalacao LIMPA de todas as migrations (001 ate a ultima de db/migrations) num PostgreSQL 16
# DESCARTAVEL e isolado (container proprio, --network none, sem porta publicada, sem volume nomeado). Nunca toca a stack
# principal nem a homologacao.
#
# Modelo de aplicacao: db/bootstrap/aplicar_como_migrador.sh (o mesmo das bases Java e do docker-compose.modelo.yml), com a
# janela de SUPERUSER so nas versoes que a exigem e retirada conferida em 0|0|0.
#
# Confere: todas as versoes aplicadas, cada janela de elevacao retirada, migrador terminando 0|0|0, marcador da
# inicializacao completa na ultima versao, papeis de servico presentes e o catalogo (listagem gravada em saidas/).
# O comportamento (rotas, sessoes, MFA, e-mail, portal, agent) e coberto pelos testes Java (cd api && ./mvnw test).
#
# Uso: db/tests/run.sh   (Git Bash no Windows: MSYS_NO_PATHCONV=1 e definido aqui)
#      TESTE_PROJETO=<nome> para rodar ao lado de outra execucao.
# Sucesso: o container e removido. Falha: fica no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail
export MSYS_NO_PATHCONV=1

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
LISTAGEM="$AQUI/sql/c_listagem_catalogo.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
# versoes que exigem a janela de SUPERUSER (cabecalho de cada migration)
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018 019 020 022 023 024 026 027 029}"
PROJETO="${TESTE_PROJETO:-nekkora-v1-banco}"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine

falha() {
    echo "ERRO: $*" >&2
    echo "container mantido para inspecao. Remocao: docker rm -f -v $PROJETO" >&2
    exit 1
}
case "$PROJETO" in nekkora-platform|nekkora|*hml03*|*frontlab*) falha "projeto '$PROJETO' reservado";; esac
docker container inspect "$PROJETO" >/dev/null 2>&1 && falha "container $PROJETO ja existe"

mkdir -p "$SAIDA/mig"
ULTIMA="$(ls "$REPO"/db/migrations/[0-9][0-9][0-9]_*.sql | sed -E 's#.*/([0-9]{3})_.*#\1#' | sort | tail -1)"
TOTAL=$((10#$ULTIMA))
for n in $(seq -w 1 "$TOTAL"); do
    v="$(printf '%03d' "$((10#$n))")"
    f=("$REPO"/db/migrations/${v}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration $v ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
sha256sum "$SAIDA"/mig/0*.sql | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt"
caminho() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

psqlc() { docker exec -i "$PROJETO" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora "$@"; }
consulta() { psqlc -c "$1" < /dev/null; }

echo ">> instalacao limpa 001-$ULTIMA ($PROJETO)"
docker create --name "$PROJETO" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
    -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE="$ULTIMA" \
    -e NEKKORA_ELEVAR="$ELEVAR" -e NEKKORA_MIGRACOES=/nekkora-migracoes \
    "$IMAGEM" postgres -c cluster_name="$PROJETO" > /dev/null
docker cp "$(caminho "$SAIDA/mig")" "$PROJETO":/nekkora-migracoes > /dev/null
docker cp "$(caminho "$SAIDA/modelo.sh")" "$PROJETO":/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh > /dev/null
docker start "$PROJETO" > /dev/null
for _ in $(seq 1 300); do
    [ "$(docker logs "$PROJETO" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
    docker ps -q -f "name=^$PROJETO\$" | grep -q . || break
    sleep 1
done
docker logs "$PROJETO" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|sourcing|aplicar_como_migrador|ERROR|FATAL|init process complete" > "$SAIDA/entrypoint.txt" || true
grep -qE "ERROR|FATAL|aplicar_como_migrador: FALHA" "$SAIDA/entrypoint.txt" && falha "erro na aplicacao das migrations (ver $SAIDA/entrypoint.txt)"
[ "$(grep -c 'aplicar_como_migrador: aplicada versao=' "$SAIDA/entrypoint.txt")" = "$TOTAL" ] || falha "esperadas $TOTAL migrations aplicadas"
grep -q "aplicar_como_migrador: fim db=nekkora aplicadas=$TOTAL estado=0|0|0" "$SAIDA/entrypoint.txt" || falha "migrador nao terminou 0|0|0"
[ "$(consulta 'SHOW cluster_name')" = "$PROJETO" ] || falha "cluster inesperado"

JANELAS="$(grep -c 'SUPERUSER revogado, estado 0|0|0' "$SAIDA/entrypoint.txt" || true)"
[ "$JANELAS" -ge 1 ] || falha "nenhuma janela de elevacao retirada registrada"
MARCADOR="$(consulta "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()")"
[ "$MARCADOR" = "nekkora-inicializacao-completa versao=$ULTIMA" ] || falha "marcador da instalacao: $MARCADOR"
MIGRADOR="$(consulta "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
    FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
    OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')")"
[ "$MIGRADOR" = "0|0|0" ] || falha "migrador fora de 0|0|0 depois da instalacao: $MIGRADOR"
for papel in nekkora_platform nekkora_portal nekkora_ingestion; do
    [ "$(consulta "SELECT count(*) FROM pg_roles WHERE rolname = '$papel' AND rolcanlogin AND NOT rolsuper")" = 1 ] \
        || falha "papel de servico $papel ausente, sem LOGIN ou com SUPERUSER"
done
psqlc < "$LISTAGEM" > "$SAIDA/catalogo.txt" 2>&1 || falha "listagem do catalogo"

{
    echo "instalacao limpa: $TOTAL migrations (001-$ULTIMA), server_version=$(consulta 'SHOW server_version')"
    echo "janelas de SUPERUSER retiradas em 0|0|0: $JANELAS; migrador 0|0|0; marcador versao=$ULTIMA"
    echo "papeis de servico: nekkora_platform, nekkora_portal, nekkora_ingestion (LOGIN, sem SUPERUSER)"
    echo "catalogo: $(wc -l < "$SAIDA/catalogo.txt" | tr -d ' ') linhas (catalogo.txt)"
    echo "RESULTADO: OK"
} | tee "$SAIDA/resumo.txt"
docker rm -f -v "$PROJETO" > /dev/null
echo "saidas: $SAIDA"
