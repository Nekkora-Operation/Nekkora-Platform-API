# Ensaio pontual (fora do repositorio): a checagem de elevacao compara bytes, sem normalizar fim de linha.
# Versao 900 de ensaio autorizada pelo sha256 dos bytes LF; o arquivo apresentado ao runner e a MESMA versao com CRLF.
# Esperado: recusa antes de conceder (codigo 2), nenhuma janela aberta, migrador 0|0|0, historico sem 900.
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
set -a; . "$AQUI/saidas/ensaio-lf/ambiente.env"; set +a
export PGPASSWORD="$(cat "$HOMOLOG_SEGREDOS_HOST/postgres_admin")" COMPOSE_ARQ="$(cygpath -m "$AQUI/compose.homolog.yml")" AQUI
compose() { docker compose -f "$COMPOSE_ARQ" "$@"; }
admin_sql() { local db="$1"; shift; compose exec -T -e PGPASSWORD postgres psql -X -q -At -v ON_ERROR_STOP=1 -U admin_cluster -d "$db" "$@"; }
export -f compose admin_sql
. "$AQUI/lib/estado.sh"; export -f estado_migrador

EV="$AQUI/saidas/ensaio-lf/variante-crlf-$(date -u +%Y%m%dT%H%M%SZ)"; M="$EV/migracoes"; mkdir -p "$M"
cp "$AQUI/saidas/ensaio-lf/migracoes/"*.sql "$M/"
printf -- '-- ENSAIO variante CRLF (fora do repositorio)\nSELECT 1;\n' > "$EV/900_lf.sql"
sed 's/$/\r/' "$EV/900_lf.sql" > "$M/900_variante_crlf.sql"
printf '900 %s\n' "$(sha256sum "$EV/900_lf.sql" | cut -d' ' -f1)" > "$EV/lista.txt"
{ echo "sha256 LF autorizado : $(cut -d' ' -f2 "$EV/lista.txt")"
  echo "sha256 CRLF entregue : $(sha256sum "$M/900_variante_crlf.sql" | cut -d' ' -f1)"
  echo "conteudo igual fora o fim de linha: $(python -c "import sys;a=open(sys.argv[1],'rb').read();b=open(sys.argv[2],'rb').read();print(b.replace(b'\r\n',b'\n')==a)" "$EV/900_lf.sql" "$M/900_variante_crlf.sql")"; } > "$EV/resumo.txt"
HIST0="$(admin_sql nekkora -c "SELECT string_agg(version || ':' || success || ':' || checksum, ' ' ORDER BY installed_rank) FROM flyway_schema_history")"
env HOMOLOG_ELEVACAO_ARQ="$EV/lista.txt" HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_MIGRACOES="$(cygpath -m "$M")" HOMOLOG_MIGRACOES_HOST="$M" \
    HOMOLOG_EVID="$EV/runner" bash "$AQUI/implantacao/implantar.sh" > "$EV/runner_saida.txt" 2>&1; rc=$?
HIST1="$(admin_sql nekkora -c "SELECT string_agg(version || ':' || success || ':' || checksum, ' ' ORDER BY installed_rank) FROM flyway_schema_history")"
{ echo "runner rc=$rc"
  echo "janela aberta: $( [ -s "$EV/runner/janelas_de_elevacao.txt" ] && echo SIM || echo nao)"
  echo "migrador: $(estado_migrador)"
  echo "historico inalterado: $( [ "$HIST0" = "$HIST1" ] && echo sim || echo NAO)"
  grep 'RECUSADA' "$EV/runner/implantacao.txt"; } >> "$EV/resumo.txt"
cat "$EV/resumo.txt"
[ $rc = 2 ] && grep -q 'elevacao RECUSADA antes de conceder' "$EV/runner/implantacao.txt" && [ ! -s "$EV/runner/janelas_de_elevacao.txt" ] \
    && [ "$(estado_migrador)" = "0|0|0" ] && [ "$HIST0" = "$HIST1" ] && echo "RESULTADO: OK" || { echo "RESULTADO: FALHA"; exit 1; }
