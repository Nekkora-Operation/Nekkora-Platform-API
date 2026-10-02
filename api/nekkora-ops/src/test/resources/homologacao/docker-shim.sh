#!/usr/bin/env bash
# SHIM DE TESTE do P1c (FixtureRunnerIT) - colocado ANTES do docker real no PATH do runner (homologacao.sh), so no teste.
# Redireciona APENAS
#     docker compose [-f <arq>]... [--profile <p>] run --rm -T [-e VAR]... ferramentas <comando...>
# para
#     docker exec -i [-e VAR]... <container cliente psql do teste> <comando...>
# O container cliente tem os mesmos caminhos do job "ferramentas" (/sql, /tls/ca.crt) e alcanca o servidor pelo nome
# "postgres" na rede propria do teste. Qualquer outro "docker compose" FALHA (o teste nao cria projeto compose); os demais
# comandos (ps, logs...) vao ao docker real.
# Variaveis: P1C_DOCKER_REAL (cliente docker real), P1C_CLIENTE (id do container cliente), P1C_APPNAME (application_name
# do psql, para o teste achar o backend), P1C_CONNECT_TIMEOUT (PGCONNECT_TIMEOUT), P1C_SONDA_SOL (arquivo de solicitacoes
# do runner: o conteudo dele NO INSTANTE em que o psql e chamado vai para <arquivo>.sonda - prova do registro preventivo).
set -u
real="${P1C_DOCKER_REAL:?}"
if [ "${1:-}" != compose ]; then exec "$real" "$@"; fi
shift
while [ $# -gt 0 ]; do
    case "$1" in
        -f|--profile|-p) shift 2 ;;
        run) shift; break ;;
        *) echo "shim: compose nao suportado no teste: $1" >&2; exit 97 ;;
    esac
done
envs=()
while [ $# -gt 0 ]; do
    case "$1" in
        --rm|-T) shift ;;
        -e) envs+=(-e "$2"); shift 2 ;;
        ferramentas) shift; break ;;
        *) echo "shim: servico ou opcao nao suportado no teste: $1" >&2; exit 97 ;;
    esac
done
[ -n "${P1C_APPNAME:-}" ] && envs+=(-e "PGAPPNAME=$P1C_APPNAME")
[ -n "${P1C_CONNECT_TIMEOUT:-}" ] && envs+=(-e "PGCONNECT_TIMEOUT=$P1C_CONNECT_TIMEOUT")
if [ -n "${P1C_SONDA_SOL:-}" ]; then
    { echo "== psql chamado em $(date -u +%Y-%m-%dT%H:%M:%SZ); conteudo de solicitacoes_com_fixture.txt neste instante:"
      cat "$P1C_SONDA_SOL" 2>/dev/null || echo "(arquivo ausente)"; } >> "$P1C_SONDA_SOL.sonda"
fi
MSYS_NO_PATHCONV=1 exec "$real" exec -i "${envs[@]}" "${P1C_CLIENTE:?}" "$@"
