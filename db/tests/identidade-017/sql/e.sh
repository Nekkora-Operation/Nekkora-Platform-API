#!/bin/sh
# e.sh <rotulo> - roda DENTRO do container (chamado por \! dos scripts). Registra E-1|E-2|E-3|viva em /tmp/e.log e
# marca /tmp/E_FALHOU em qualquer desvio; o runner confere o marcador depois de cada script.
r="$(psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora -f /tmp/e_check.sql 2>&1)" || { echo "$1|ERRO $r" >> /tmp/e.log; touch /tmp/E_FALHOU; echo "E $1: ERRO"; exit 0; }
echo "$1|$r" >> /tmp/e.log
if [ "$r" = "0|0|0|0" ]; then echo "E $1: ok"; else touch /tmp/E_FALHOU; echo "E $1: VIOLADO $r"; fi
