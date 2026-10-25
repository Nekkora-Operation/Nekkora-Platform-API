#!/usr/bin/env bash
# Prova FUNCIONAL (opcional; exige Docker e saida de rede, por isso fora do validar-config) do proxy-m365 PELA composicao do runner (base + 016 + 021 + m365), num projeto temporario: so o proxy
# sobe (--no-deps); um curl na rede interna do projeto pede CONNECT a cada destino (sem credencial; so o tunel). Esperado:
# os tres nomes 200, outra regiao e outro destino 403, e nenhuma saida direta pela rede interna. Tudo removido no fim.
# Uso: bash testes/prova_proxy_m365.sh
set -uo pipefail
cd "$(dirname "$0")/.."
T="$(mktemp -d)"; S="$T/segredos"; mkdir -p "$S/m365" "$S/tls" "$S/redis" "$T/mig" "$T/config"
for f in postgres_admin flyway.conf platform.env portal.env operacao.env m365/aws.env m365/fila.env tls/ca.crt tls/ops-ca.crt tls/ops-ca.key tls/gateway.crt tls/gateway.key; do : > "$S/$f"; done
export HOMOLOG_PROJETO=nekkora-teste-proxy-compose HOMOLOG_ID=ensaio-teste-proxy HOMOLOG_SEGREDOS="$(cygpath -m "$S")" HOMOLOG_COMMIT=0000000 \
       HOMOLOG_PORTA=55998 HOMOLOG_MIGRACOES="$(cygpath -m "$T/mig")" HOMOLOG_SQL="$(cygpath -m "$PWD/sql")" HOMOLOG_CONFIG="$(cygpath -m "$T/config")" \
       HOMOLOG_REDE_INTERNA=10.252.120 HOMOLOG_REDE_BORDA=10.252.121 HOMOLOG_M365_DESTINATARIOS='controle+*@exemplo.test'
C="docker compose -p nekkora-teste-proxy-compose -f compose.homolog.yml -f compose.016.yml -f compose.021.yml -f compose.m365.yml"
$C up -d --no-deps proxy-m365 > "$T/up.txt" 2>&1 || { echo "proxy nao subiu"; cat "$T/up.txt"; }
sleep 5
echo "estado: $(docker inspect -f '{{.State.Status}}' nekkora-teste-proxy-compose-proxy-m365-1 2>&1)"
docker inspect -f '{{json .HostConfig.Tmpfs}}' nekkora-teste-proxy-compose-proxy-m365-1
REDE="nekkora-teste-proxy-compose_interna"
for d in login.microsoftonline.com:443 graph.microsoft.com:443 secretsmanager.sa-east-1.amazonaws.com:443 secretsmanager.us-east-1.amazonaws.com:443 example.com:443; do
    r=$(MSYS_NO_PATHCONV=1 docker run --rm --network "$REDE" curlimages/curl:8.10.1 -s -o /dev/null -w '%{http_connect}' --max-time 15 -p -x http://10.252.120.34:3128 "https://$d/" 2>/dev/null)
    echo "$d CONNECT=$r"
done
# a rede interna nao tem saida direta: sem o proxy, nada sai
r=$(MSYS_NO_PATHCONV=1 docker run --rm --network "$REDE" curlimages/curl:8.10.1 -s -o /dev/null -w '%{http_code}' --max-time 8 "https://graph.microsoft.com/" 2>/dev/null)
echo "direto_pela_interna=$r (000 = sem saida)"
$C down --remove-orphans > /dev/null 2>&1
docker network ls --filter name=nekkora-teste-proxy-compose -q | wc -l | sed 's/^/redes_restantes=/'
rm -rf "$T"
