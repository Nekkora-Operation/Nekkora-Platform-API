#!/usr/bin/env bash
# I8 - instalacao incompleta: "migrador recuperado" NAO e "ambiente apto". SO em ambiente DESCARTAVEL (id "ensaio-*"),
# criado por este proprio teste e destruido ao final.
#
#   uso: ops/homologacao/testes/instalacao_incompleta.sh ensaio-<nome>
#
# 1. "homologacao.sh criar <id>" em segundo plano; assim que a janela de elevacao da 011 abre, kill -9 no criar e no
#    runner (PID da trava local) e docker kill no container do Flyway: a instalacao para ANTES de concluir as migrations
#    necessarias (012-015 nunca executam; a 011 pode ou nao ter sido confirmada, conforme o instante do kill);
# 2. estado: BLOQUEADO (trava local, elevacao residual);
# 3. recuperar: codigo 0 (migrador recuperado: revogado, sessoes encerradas, 0|0|0) MAS "ambiente CONTINUA BLOQUEADO"
#    por instalacao incompleta, com as versoes que faltam;
# 4. estado continua BLOQUEADO (so pela instalacao incompleta) e testar e recusado;
# 5. falha de consulta tambem bloqueia: com o postgres parado, estado = BLOQUEADO (consulta falhou / banco sem resposta);
# 6. implantar conclui as versoes pendentes -> LIBERADO; estado LIBERADO.
set -uo pipefail

AQUI="$(cd "$(dirname "$0")/.." && pwd)"
ID="${1:?uso: instalacao_incompleta.sh ensaio-<nome>}"
[[ "$ID" =~ ^ensaio- ]] || { echo "ERRO: so em ambiente descartavel (id ensaio-*)" >&2; exit 1; }
H="$AQUI/homologacao.sh"
S="$AQUI/saidas/$ID"
EV="$AQUI/saidas/i8-$ID-$(date -u +%Y%m%dT%H%M%SZ)"   # fora de saidas/<id>, que o criar exige inexistente
mkdir -p "$EV"
RES="$EV/resumo.txt"
echo "passo|resultado|detalhe" > "$RES"
ok_geral=0
registra() { echo "$1|$2|$3" >> "$RES"; echo "[$1] $2 - $3"; [ "$2" = OK ] || ok_geral=1; }
PROJ="nekkora-homologacao-$ID"
PG="$PROJ-postgres-1"

# P3-R015: sub-redes e porta do LABORATORIO repassadas EXPLICITAMENTE ao criar (HOMOLOG_REDE_INTERNA/BORDA e
# HOMOLOG_PORTA; ausentes = padrao do criar). Com a hml03 preservada, as sub-redes padrao sao recusadas pelo criar
# (sobreposicao); em ensaio-* o 015 aceita sub-redes proprias (pg_hba/nginx gerados). Valores efetivos registrados.
LAB_REDE=()
[ -n "${HOMOLOG_REDE_INTERNA:-}" ] && LAB_REDE+=("HOMOLOG_REDE_INTERNA=$HOMOLOG_REDE_INTERNA")
[ -n "${HOMOLOG_REDE_BORDA:-}" ] && LAB_REDE+=("HOMOLOG_REDE_BORDA=$HOMOLOG_REDE_BORDA")
[ -n "${HOMOLOG_PORTA:-}" ] && LAB_REDE+=("HOMOLOG_PORTA=$HOMOLOG_PORTA")
echo "parametros de laboratorio repassados ao criar: ${LAB_REDE[*]:-nenhum (padrao)}" > "$EV/0_parametros.txt"

# ---------------------------------------------------------------- 1. interromper a criacao na janela da 011
( exec env "${LAB_REDE[@]}" bash "$H" criar "$ID" > "$EV/1_criar_saida.txt" 2>&1 ) &
pc=$!
aberta=""
for i in $(seq 1 1200); do
    grep -q 'inicio versao=011' "$S/janelas_de_elevacao.txt" 2>/dev/null && { aberta=1; break; }
    kill -0 "$pc" 2>/dev/null || break
    sleep 0.25
done
pr="$(sed -nE 's/.*pid=([0-9]+).*/\1/p' "$S/implantacao.trava/dono" 2>/dev/null)"
# espera o container do Flyway da 011 estar em execucao (migration em andamento): o kill atinge o processo de
# implantacao inteiro, sem deixar um "compose run" orfao que ainda criaria o container depois
rodando=""
for i in $(seq 1 240); do
    [ -n "$(docker ps -q --filter "label=com.docker.compose.project=$PROJ" --filter "label=com.docker.compose.service=flyway")" ] && { rodando=1; break; }
    sleep 0.25
done
kill -9 "$pc" 2>/dev/null
[ -n "$pr" ] && kill -9 "$pr" 2>/dev/null
for c in $(docker ps -q --filter "label=com.docker.compose.project=$PROJ" --filter "label=com.docker.compose.service=flyway"); do docker kill "$c" > /dev/null; done
sleep 3
{ echo "valores efetivos do ambiente:"; grep -E '^HOMOLOG_(REDE_INTERNA|REDE_BORDA|PORTA|REDE_015_ENSAIO|CONFIG|COMPOSE_EXTRA|ATE)=' "$S/ambiente.env" 2>/dev/null; } >> "$EV/0_parametros.txt"
for p in "${LAB_REDE[@]}"; do grep -qx "$p" "$S/ambiente.env" 2>/dev/null || echo "DIVERGENTE: $p nao consta do ambiente.env" >> "$EV/0_parametros.txt"; done
{ echo "janela da 011 aberta antes do kill: ${aberta:-nao}; container do Flyway em execucao: ${rodando:-nao}"; echo "PID do criar: $pc; PID do runner (trava): ${pr:-?}"
  echo "janelas:"; cat "$S/janelas_de_elevacao.txt" 2>/dev/null; } > "$EV/1_interrupcao.txt"
if [ -n "$aberta" ] && [ -n "$rodando" ] && [ -n "$pr" ]; then
    registra 1 OK "criacao interrompida (kill -9 no criar e no runner, docker kill no Flyway) com a janela da 011 aberta"
else
    registra 1 FALHA "janela nao observada ou runner sem PID (ver 1_*)"
fi

# ---------------------------------------------------------------- 2. bloqueado apos a interrupcao
bash "$H" estado "$ID" > "$EV/2_estado.txt" 2>&1; r2=$?
if [ $r2 != 0 ] && grep -q '^BLOQUEADO' "$EV/2_estado.txt" && grep -q 'trava local' "$EV/2_estado.txt"; then
    registra 2 OK "estado BLOQUEADO depois da interrupcao ($(grep -c '^BLOQUEIO' "$EV/2_estado.txt") bloqueio(s) do migrador)"
else registra 2 FALHA "estado rc=$r2 (ver 2_estado.txt)"; fi

# ---------------------------------------------------------------- 3. recuperar: migrador recuperado, ambiente bloqueado
bash "$H" recuperar "$ID" > "$EV/3_recuperar.txt" 2>&1; r3=$?
if [ $r3 = 0 ] && grep -q 'RESULTADO: migrador recuperado; ambiente CONTINUA BLOQUEADO' "$EV/3_recuperar.txt" \
   && grep -q 'estado do migrador depois da revogacao: 0|0|0' "$EV/3_recuperar.txt" \
   && grep -q 'BLOQUEIO: instalacao incompleta ou divergente (faltam:' "$EV/3_recuperar.txt"; then
    registra 3 OK "recuperar: migrador recuperado (0|0|0, rc=0), ambiente CONTINUA BLOQUEADO: $(grep -o 'faltam:[^;]*' "$EV/3_recuperar.txt" | head -1)"
else registra 3 FALHA "recuperar rc=$r3 (ver 3_recuperar.txt)"; fi

# ---------------------------------------------------------------- 4. estado e testar continuam recusados
bash "$H" estado "$ID" > "$EV/4_estado.txt" 2>&1; r4=$?
bash "$H" testar "$ID" > "$EV/4_testar.txt" 2>&1; r4t=$?
if [ $r4 != 0 ] && grep -q '^migrador recuperado' "$EV/4_estado.txt" && grep -q 'instalacao incompleta' "$EV/4_estado.txt" \
   && grep -q '^BLOQUEADO' "$EV/4_estado.txt" && [ $r4t != 0 ] && grep -q 'ambiente BLOQUEADO: testes e liberacao recusados' "$EV/4_testar.txt"; then
    registra 4 OK "com o migrador recuperado, estado continua BLOQUEADO so pela instalacao incompleta; testar recusado"
else registra 4 FALHA "estado rc=$r4, testar rc=$r4t (ver 4_*)"; fi

# ---------------------------------------------------------------- 5. falha de consulta bloqueia
docker stop "$PG" > /dev/null
bash "$H" estado "$ID" > "$EV/5_estado_banco_parado.txt" 2>&1; r5=$?
docker start "$PG" > /dev/null
for i in $(seq 1 60); do docker exec "$PG" pg_isready -q > /dev/null 2>&1 && break; sleep 1; done
sleep 2
if [ $r5 != 0 ] && grep -q '^BLOQUEADO' "$EV/5_estado_banco_parado.txt" && grep -qE 'consulta da trava no banco falhou|banco sem resposta' "$EV/5_estado_banco_parado.txt"; then
    registra 5 OK "postgres parado: consultas falharam e o estado BLOQUEOU (nao liberou por ausencia de resposta)"
else registra 5 FALHA "estado rc=$r5 (ver 5_estado_banco_parado.txt)"; fi

# ---------------------------------------------------------------- 6. concluir a instalacao -> liberado
HOMOLOG_AUTORIZA_ELEVACAO=1 bash "$H" implantar "$ID" > "$EV/6_implantar.txt" 2>&1; r6=$?
bash "$H" estado "$ID" > "$EV/6_estado.txt" 2>&1; r6e=$?
if [ $r6 = 0 ] && grep -q '== implantacao OK' "$EV/6_implantar.txt" && [ $r6e = 0 ] && grep -q '^LIBERADO' "$EV/6_estado.txt" \
   && grep -q 'ambiente apto: versoes 001-015' "$EV/6_estado.txt"; then
    registra 6 OK "implantar aplicou as pendentes ($(grep -c '^-- [0-9]' "$EV/6_implantar.txt") versao(oes)) com revogacao conferida; estado LIBERADO (001-015, flyway validate)"
else registra 6 FALHA "implantar rc=$r6, estado rc=$r6e (ver 6_*)"; fi

cp "$S/janelas_de_elevacao.txt" "$EV/janelas_de_elevacao.txt" 2>/dev/null
echo "evidencias: $EV"
column -t -s'|' "$RES"
exit $ok_geral
