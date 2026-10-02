#!/usr/bin/env bash
# Regressao Maven COM EVIDENCIA DAS FONTES (missao "manifesto de fontes Maven"). Toda execucao relevante deve passar por
# aqui, para que o resultado fique amarrado a arvore que o produziu, sem depender de mtime:
#
#   ops/homologacao/regressao_maven.sh <diretorio de saida novo> [argumentos do mvnw; padrao: -B verify]
#
# Ordem (garantida e conferida pelo validar-config):
#   1. ANTES de qualquer execucao do Maven: fontes.sha256 e fontes_lf.sha256 (lib/fontes_maven.py: conjunto de entrada
#      declarado, rastreado ou nao, ordem deterministica) e contexto.txt (inicio, HEAD, arvore suja/limpa, entradas nao
#      rastreadas, numero de arquivos, sha256 do proprio fontes.sha256, comando);
#   2. api/mvnw <argumentos>, com a saida em maven.txt;
#   3. DEPOIS: fontes_fim.sha256 recalculado e comparado com o do inicio;
#   4. resultado.txt: RESULTADO_TESTES (OK/FALHA pelo codigo do Maven e totais do surefire), FONTES_ESTAVEIS (OK/FALHA,
#      com os caminhos alterados, adicionados ou removidos) e REAPROVEITAMENTO: PERMITIDO SO com testes OK, ao menos um
#      teste executado e fontes estaveis; senao PROIBIDO (com o motivo);
#   5. manifesto.sha256 do proprio diretorio (integridade da evidencia).
# Reaproveitar depois: python ops/homologacao/lib/fontes_maven.py verificar <diretorio> <raiz> (confere integridade e se a
# arvore atual ainda e a do inicio). Limite: uma mutacao desfeita ANTES do fim da execucao nao e detectada (o manifesto
# compara inicio e fim).
set -uo pipefail
OUT="${1:?uso: regressao_maven.sh <diretorio de saida novo> [argumentos do mvnw]}"; shift
[ $# -gt 0 ] || set -- -B verify
AQUI="$(cd "$(dirname "$0")" && pwd)"; RAIZ="$(cd "$AQUI/../.." && pwd)"
FM="$AQUI/lib/fontes_maven.py"
[ ! -e "$OUT" ] || { echo "ERRO: $OUT ja existe (a evidencia de uma execucao nunca e reaproveitada nem sobrescrita)" >&2; exit 3; }
mkdir -p "$OUT" || exit 3
OUT="$(cd "$OUT" && pwd)"
agora() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }

# ---- 1. manifesto das fontes ANTES do Maven
INICIO="$(agora)"
python -B "$FM" manifesto "$RAIZ" "$OUT/fontes.sha256" "$OUT/fontes_lf.sha256" > "$OUT/manifesto_inicio.txt" 2>&1 \
    || { cat "$OUT/manifesto_inicio.txt"; echo "ERRO: manifesto das fontes nao gerado; o Maven NAO foi executado" >&2; exit 3; }
N="$(grep -c . "$OUT/fontes.sha256")"
{
    echo "inicio=$INICIO"
    echo "raiz=$RAIZ"
    if git -C "$RAIZ" rev-parse --verify -q HEAD > /dev/null 2>&1; then
        echo "head=$(git -C "$RAIZ" rev-parse HEAD)"
        echo "ramo=$(git -C "$RAIZ" branch --show-current)"
        echo "arvore=$( [ -z "$(git -C "$RAIZ" status --porcelain --untracked-files=no)" ] && echo limpa || echo suja)"
        echo "entradas_com_alteracao_nao_commitada=$(git -C "$RAIZ" status --porcelain --untracked-files=no -- api db/migrations db/bootstrap ops/homologacao | grep -c .)"
        # entradas do manifesto que o Git nao rastreia (novas ou ignoradas): ENTRAM no manifesto, e a contagem fica registrada
        git -C "$RAIZ" ls-files -- api db/migrations db/bootstrap ops/homologacao | LC_ALL=C sort > "$OUT/.rastreados.tmp"
        cut -c67- "$OUT/fontes.sha256" | LC_ALL=C sort | LC_ALL=C comm -23 - "$OUT/.rastreados.tmp" > "$OUT/entradas_nao_rastreadas.txt"
        rm -f "$OUT/.rastreados.tmp"
        echo "entradas_nao_rastreadas_no_manifesto=$(grep -c . "$OUT/entradas_nao_rastreadas.txt") (lista em entradas_nao_rastreadas.txt)"
        echo "core.autocrlf=$(git -C "$RAIZ" config core.autocrlf || echo nao-definido)"
    else
        echo "head=sem-git"; echo "arvore=desconhecida (sem Git: so o manifesto identifica as fontes)"
    fi
    echo "arquivos_no_manifesto=$N"
    echo "sha256_fontes=$(sha256sum "$OUT/fontes.sha256" | cut -c1-64)"
    echo "sha256_fontes_lf=$(sha256sum "$OUT/fontes_lf.sha256" | cut -c1-64)"
    echo "comando=api/mvnw $*"
    echo "java=$(java -version 2>&1 | head -1)"
} > "$OUT/contexto.txt"

# ---- 2. Maven
( cd "$RAIZ/api" && ./mvnw "$@" ) > "$OUT/maven.txt" 2>&1; RC=$?
FIM="$(agora)"

# ---- 3. manifesto das fontes DEPOIS e comparacao
python -B "$FM" manifesto "$RAIZ" "$OUT/fontes_fim.sha256" "$OUT/fontes_fim_lf.sha256" > "$OUT/manifesto_fim.txt" 2>&1; RCM=$?
if [ $RCM = 0 ]; then
    python -B "$FM" comparar "$OUT/fontes.sha256" "$OUT/fontes_fim.sha256" > "$OUT/fontes_diferencas.txt" 2>&1; RCC=$?
else RCC=3; echo "manifesto do fim nao gerado" > "$OUT/fontes_diferencas.txt"; fi

# ---- 4. resultado
tot() { tr -d '\r' < "$OUT/maven.txt" | grep -E '^\[(INFO|WARNING|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' \
        | sed -E 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+)$/\1 \2 \3 \4/' \
        | awk '{t += $1; f += $2; e += $3; s += $4} END {printf "%d %d %d %d", t, f, e, s}'; }
read -r T F ER S <<< "$(tot)"
if [ $RC = 0 ] && [ "$F" = 0 ] && [ "$ER" = 0 ]; then RT=OK; else RT=FALHA; fi
if [ $RCC = 0 ]; then FE=OK; else FE=FALHA; fi
motivo=""
[ "$RT" = OK ] || motivo="testes FALHA (codigo $RC)"
[ "$T" -gt 0 ] || motivo="${motivo:+$motivo; }nenhum teste executado"
[ "$FE" = OK ] || motivo="${motivo:+$motivo; }fontes alteradas durante a execucao"
{
    echo "RESULTADO_TESTES=$RT"
    echo "CODIGO_MAVEN=$RC"
    echo "TESTES=$T FALHAS=$F ERROS=$ER IGNORADOS=$S"
    echo "FONTES_ESTAVEIS=$FE"
    [ "$FE" = OK ] || sed 's/^/  /' "$OUT/fontes_diferencas.txt" | head -50
    if [ -z "$motivo" ]; then echo "REAPROVEITAMENTO=PERMITIDO"; else echo "REAPROVEITAMENTO=PROIBIDO"; echo "MOTIVO=$motivo"; fi
    echo "inicio=$INICIO"; echo "fim=$FIM"
    echo "sha256_fontes=$(sha256sum "$OUT/fontes.sha256" | cut -c1-64)"
} > "$OUT/resultado.txt"

# ---- 5. integridade da evidencia
( cd "$OUT" && for f in contexto.txt entradas_nao_rastreadas.txt fontes.sha256 fontes_lf.sha256 maven.txt fontes_fim.sha256 fontes_fim_lf.sha256 fontes_diferencas.txt resultado.txt manifesto_inicio.txt manifesto_fim.txt; do
    [ -f "$f" ] && echo "$(sha256sum "$f" | cut -c1-64)  $f"; done > manifesto.sha256 )
cat "$OUT/resultado.txt"
[ "$RT" = OK ] && [ "$FE" = OK ]
