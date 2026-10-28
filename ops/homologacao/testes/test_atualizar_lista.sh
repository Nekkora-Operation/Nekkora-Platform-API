#!/usr/bin/env bash
# Prova ESTATICA (sem Docker, sem banco) dos controles da lista de elevacao alternativa (HOMOLOG_ELEVACAO_ARQ) no
# "atualizar" 021 -> 022 (missao PLATFORM-API-V1-003, revisao final) e, desde a missao de acessos de 2026-10-27, nos passos
# 022 -> 023 e 023 -> 024: executa o homologacao.sh REAL contra ambientes ficticios em saidas/ (removidos no fim) e
# confere, em cada recusa, a mensagem, o codigo e que NADA mudou no ambiente (mesmos arquivos e bytes, ambiente.env
# intacto, nenhum atualizacao-*). Salto de versao (021 -> 023) e recusado: um passo por vez. Desde a missao Platform-Agent
# de 2026-10-28, o passo 024 -> 025, SEM janela: uma linha da 025 (ou da 021) em qualquer lista e recusada pela gramatica; e o
# passo 025 -> 026, COM janela (vinculo obrigatorio, como 022-024).
#   - fora de ensaio-*: a opcao (e o atualizar) e recusada antes de qualquer alteracao;
#   - a lista alternativa passa pelas mesmas conferencias da revisada: existencia, gramatica (tipo e vinculo obrigatorio),
#     versao alvo, linha da 022 com o sha256 do arquivo e vinculada ao id exato;
#   - controle positivo: com a lista correta, as conferencias de arquivo passam e o atualizar so para na conferencia de
#     liberacao, que le o banco (aqui inexistente): "ambiente BLOQUEADO: nada alterado".
# O pre-voo, a janela e a retirada da elevacao com a lista alternativa sao do "implantar" (runner): provados no laboratorio
# evidencias/ensaio-api-v1-003-j022 (janela-022: P, F e S). O registro da lista usada: atualizacao/registro.txt de la.
# Uso: bash testes/test_atualizar_lista.sh   (codigo 0 = todas as provas OK)
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
H="$AQUI/homologacao.sh"
LAB=ensaio-validacao-atz; FORA=validacao-atz; LAB22=ensaio-validacao-atz22; LAB23=ensaio-validacao-atz23; LAB24=ensaio-validacao-atz24; LAB25=ensaio-validacao-atz25
for id in "$LAB" "$FORA" "$LAB22" "$LAB23" "$LAB24" "$LAB25"; do
    [ ! -e "$AQUI/saidas/$id" ] || { echo "ERRO: $AQUI/saidas/$id ja existe" >&2; exit 3; }
done
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP" "$AQUI/saidas/$LAB" "$AQUI/saidas/$FORA" "$AQUI/saidas/$LAB22" "$AQUI/saidas/$LAB23" "$AQUI/saidas/$LAB24" "$AQUI/saidas/$LAB25"' EXIT
falhas=0
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }

mkdir -p "$TMP/seg"; echo nao-e-segredo > "$TMP/seg/postgres_admin"
for id in "$LAB" "$FORA"; do
    mkdir -p "$AQUI/saidas/$id/migracoes"
    cp "$REPO"/db/migrations/0[01][0-9]_*.sql "$REPO"/db/migrations/02[01]_*.sql "$AQUI/saidas/$id/migracoes/"
    (cd "$AQUI/saidas/$id/migracoes" && sha256sum *.sql) > "$AQUI/saidas/$id/migracoes_sha256.txt"
    { echo "HOMOLOG_ID=$id"; echo "HOMOLOG_PROJETO=nekkora-homologacao-$id"; echo "HOMOLOG_SEGREDOS_HOST=$TMP/seg"
      echo "HOMOLOG_ATE=021"; echo "HOMOLOG_COMMIT=0000000"; echo "HOMOLOG_TRANSPORTE=homologacao"; } > "$AQUI/saidas/$id/ambiente.env"
done
# ambientes ficticios ja atualizados: em 022 (passo 022 -> 023), em 023 (passo 023 -> 024) e em 024 (passo 024 -> 025)
for par in "$LAB22 022" "$LAB23 023" "$LAB24 024" "$LAB25 025"; do
    set -- $par
    mkdir -p "$AQUI/saidas/$1/migracoes"
    for n in $(seq -w 1 "$((10#$2))"); do cp "$REPO"/db/migrations/0${n}_*.sql "$AQUI/saidas/$1/migracoes/"; done
    (cd "$AQUI/saidas/$1/migracoes" && sha256sum *.sql) > "$AQUI/saidas/$1/migracoes_sha256.txt"
    { echo "HOMOLOG_ID=$1"; echo "HOMOLOG_PROJETO=nekkora-homologacao-$1"; echo "HOMOLOG_SEGREDOS_HOST=$TMP/seg"
      echo "HOMOLOG_ATE=$2"; echo "HOMOLOG_COMMIT=0000000"; echo "HOMOLOG_TRANSPORTE=homologacao"; } > "$AQUI/saidas/$1/ambiente.env"
done
foto() { (cd "$AQUI/saidas/$1" && find . -type f | sort | while read -r f; do echo "$f $(sha256sum < "$f" | cut -c1-64)"; done); }

# listas: 011-013 e 016-020 da lista revisada (016-020 revinculadas ao id do caso) mais a variante da 022
S022="$(sha256sum < "$REPO"/db/migrations/022_*.sql | cut -c1-64)"
SX=1111111111111111111111111111111111111111111111111111111111111111
lista() {   # $1 arquivo, $2 id das linhas 016-020, $3 linha da 022 (vazia = sem linha)
    grep -E '^(01[1-3]|01[6-9]|020) ' "$AQUI/implantacao/elevacao.txt" | sed "s/ambiente=ensaio-api-v1-003\$/ambiente=$2/" > "$1"
    [ -z "$3" ] || echo "$3" >> "$1"
}
lista "$TMP/certa.txt" "$LAB" "022 $S022 tipo=superuser ambiente=$LAB"
lista "$TMP/certa_fora.txt" "$FORA" "022 $S022 tipo=superuser ambiente=$FORA"
lista "$TMP/sha_errado.txt" "$LAB" "022 $SX tipo=superuser ambiente=$LAB"
lista "$TMP/outro_id.txt" "$LAB" "022 $S022 tipo=superuser ambiente=ensaio-outro"
lista "$TMP/sem_022.txt" "$LAB" ""
lista "$TMP/sem_vinculo.txt" "$LAB" "022 $S022 tipo=superuser"
lista "$TMP/bypass.txt" "$LAB" "022 $S022 tipo=bypassrls ambiente=$LAB"
lista "$TMP/formato.txt" "$LAB" "022 abc ambiente=$LAB"
lista "$TMP/versao_021.txt" "$LAB" "021 $S022 tipo=superuser ambiente=$LAB"

S023="$(sha256sum < "$REPO"/db/migrations/023_*.sql | cut -c1-64)"
S024="$(sha256sum < "$REPO"/db/migrations/024_*.sql | cut -c1-64)"
lista "$TMP/certa22.txt" "$LAB22" "022 $S022 tipo=superuser ambiente=$LAB22"
echo "023 $S023 tipo=superuser ambiente=$LAB22" >> "$TMP/certa22.txt"
lista "$TMP/sem_023.txt" "$LAB22" "022 $S022 tipo=superuser ambiente=$LAB22"
lista "$TMP/sha23_errado.txt" "$LAB22" "022 $S022 tipo=superuser ambiente=$LAB22"
echo "023 $SX tipo=superuser ambiente=$LAB22" >> "$TMP/sha23_errado.txt"
lista "$TMP/certa23.txt" "$LAB23" "022 $S022 tipo=superuser ambiente=$LAB23"
printf '023 %s tipo=superuser ambiente=%s\n024 %s tipo=superuser ambiente=%s\n' "$S023" "$LAB23" "$S024" "$LAB23" >> "$TMP/certa23.txt"
lista "$TMP/outro_id24.txt" "$LAB23" "022 $S022 tipo=superuser ambiente=$LAB23"
printf '023 %s tipo=superuser ambiente=%s\n024 %s tipo=superuser ambiente=ensaio-outro\n' "$S023" "$LAB23" "$S024" >> "$TMP/outro_id24.txt"
lista "$TMP/sem_vinculo24.txt" "$LAB23" "022 $S022 tipo=superuser ambiente=$LAB23"
printf '023 %s tipo=superuser ambiente=%s\n024 %s tipo=superuser\n' "$S023" "$LAB23" "$S024" >> "$TMP/sem_vinculo24.txt"
S025="$(sha256sum < "$REPO"/db/migrations/025_*.sql | cut -c1-64)"
lista "$TMP/certa24.txt" "$LAB24" "022 $S022 tipo=superuser ambiente=$LAB24"
printf '023 %s tipo=superuser ambiente=%s\n024 %s tipo=superuser ambiente=%s\n' "$S023" "$LAB24" "$S024" "$LAB24" >> "$TMP/certa24.txt"
cp "$TMP/certa24.txt" "$TMP/com_025.txt"; echo "025 $S025 tipo=superuser ambiente=$LAB24" >> "$TMP/com_025.txt"
cp "$TMP/certa24.txt" "$TMP/com_025_legado.txt"; echo "025 $S025" >> "$TMP/com_025_legado.txt"
S026="$(sha256sum < "$REPO"/db/migrations/026_*.sql | cut -c1-64)"
lista "$TMP/certa25.txt" "$LAB25" "022 $S022 tipo=superuser ambiente=$LAB25"
printf '023 %s tipo=superuser ambiente=%s\n024 %s tipo=superuser ambiente=%s\n026 %s tipo=superuser ambiente=%s\n' "$S023" "$LAB25" "$S024" "$LAB25" "$S026" "$LAB25" >> "$TMP/certa25.txt"
lista "$TMP/sem_026.txt" "$LAB25" "022 $S022 tipo=superuser ambiente=$LAB25"
printf '023 %s tipo=superuser ambiente=%s\n024 %s tipo=superuser ambiente=%s\n' "$S023" "$LAB25" "$S024" "$LAB25" >> "$TMP/sem_026.txt"
lista "$TMP/sem_vinculo26.txt" "$LAB25" "022 $S022 tipo=superuser ambiente=$LAB25"
printf '023 %s tipo=superuser ambiente=%s\n024 %s tipo=superuser ambiente=%s\n026 %s tipo=superuser\n' "$S023" "$LAB25" "$S024" "$LAB25" "$S026" >> "$TMP/sem_vinculo26.txt"
lista "$TMP/sha26_errado.txt" "$LAB25" "022 $S022 tipo=superuser ambiente=$LAB25"
printf '023 %s tipo=superuser ambiente=%s\n024 %s tipo=superuser ambiente=%s\n026 %s tipo=superuser ambiente=%s\n' "$S023" "$LAB25" "$S024" "$LAB25" "$SX" "$LAB25" >> "$TMP/sha26_errado.txt"

caso() {   # $1 descricao, $2 id, $3 trecho esperado na mensagem, demais: variaveis de ambiente (alvo padrao: 022)
    local desc="$1" id="$2" esp="$3" antes depois rc=0; shift 3
    antes="$(foto "$id")"
    env -u HOMOLOG_ELEVACAO_ARQ -u HOMOLOG_ATUALIZAR_ATE HOMOLOG_ATUALIZAR_ATE=022 "$@" bash "$H" atualizar "$id" > "$TMP/saida.txt" 2>&1 || rc=$?
    depois="$(foto "$id")"
    if [ "$rc" = 0 ]; then nok "$desc: aceito indevidamente"
    elif ! grep -q "$esp" "$TMP/saida.txt"; then nok "$desc: rc=$rc, mensagem inesperada: $(tail -1 "$TMP/saida.txt" | cut -c1-110)"
    elif [ "$antes" != "$depois" ] || ls -d "$AQUI/saidas/$id"/atualizacao-* > /dev/null 2>&1; then nok "$desc: recusado, mas o ambiente mudou"
    else ok "$desc: $(grep -m1 '^ERRO' "$TMP/saida.txt" | cut -c1-120)"; fi
}

echo "== fora de ensaio-*: recusado antes de qualquer alteracao"
caso "fora de ensaio-* com a lista alternativa correta para o id" "$FORA" "atualizar so em ambiente descartavel" HOMOLOG_ELEVACAO_ARQ="$TMP/certa_fora.txt"
caso "fora de ensaio-* sem lista alternativa" "$FORA" "atualizar so em ambiente descartavel"

echo "== lista alternativa sujeita as mesmas conferencias"
caso "lista alternativa inexistente" "$LAB" "lista de elevacao inexistente" HOMOLOG_ELEVACAO_ARQ="$TMP/nao_existe.txt"
caso "linha da 022 fora do formato" "$LAB" "recusada pela gramatica" HOMOLOG_ELEVACAO_ARQ="$TMP/formato.txt"
caso "022 sem vinculo ao id (versao de vinculo obrigatorio)" "$LAB" "recusada pela gramatica" HOMOLOG_ELEVACAO_ARQ="$TMP/sem_vinculo.txt"
caso "022 com tipo=bypassrls (vinculo obrigatorio so superuser)" "$LAB" "recusada pela gramatica" HOMOLOG_ELEVACAO_ARQ="$TMP/bypass.txt"
caso "022 com sha256 diferente do arquivo" "$LAB" "a 022 exige a linha de elevacao vinculada a este id ($LAB) com o sha256" HOMOLOG_ELEVACAO_ARQ="$TMP/sha_errado.txt"
caso "022 vinculada a OUTRO id" "$LAB" "a 022 exige a linha de elevacao vinculada a este id ($LAB) com o sha256" HOMOLOG_ELEVACAO_ARQ="$TMP/outro_id.txt"
caso "sem linha da 022" "$LAB" "a 022 exige a linha de elevacao vinculada a este id ($LAB) com o sha256" HOMOLOG_ELEVACAO_ARQ="$TMP/sem_022.txt"
caso "linha sob a versao 021 (aplica sem elevacao: NUNCA_ELEVAR)" "$LAB" "recusada pela gramatica" HOMOLOG_ELEVACAO_ARQ="$TMP/versao_021.txt"
caso "salto 021 -> 023 (um passo por vez), mesmo com a lista correta" "$LAB" "atualizacao suportada: 021 -> 022, 022 -> 023, 023 -> 024, 024 -> 025, 025 -> 026 ou 026 -> 027, um passo por vez" HOMOLOG_ELEVACAO_ARQ="$TMP/certa.txt" HOMOLOG_ATUALIZAR_ATE=023
caso "lista revisada (022 vinculada ao ensaio-api-v1-003, nao a este id)" "$LAB" "a 022 exige a linha de elevacao vinculada a este id ($LAB) com o sha256"

echo "== passos 022 -> 023 e 023 -> 024"
caso "022 -> 023 sem linha da 023" "$LAB22" "a 023 exige a linha de elevacao vinculada a este id ($LAB22) com o sha256" HOMOLOG_ELEVACAO_ARQ="$TMP/sem_023.txt" HOMOLOG_ATUALIZAR_ATE=023
caso "022 -> 023 com sha256 da 023 diferente do arquivo" "$LAB22" "a 023 exige a linha de elevacao vinculada a este id ($LAB22) com o sha256" HOMOLOG_ELEVACAO_ARQ="$TMP/sha23_errado.txt" HOMOLOG_ATUALIZAR_ATE=023
caso "022 -> 023 pela lista revisada (sem linha da 023)" "$LAB22" "a 023 exige a linha de elevacao vinculada a este id ($LAB22) com o sha256" HOMOLOG_ATUALIZAR_ATE=023
caso "022 -> 024 (salto)" "$LAB22" "um passo por vez" HOMOLOG_ELEVACAO_ARQ="$TMP/certa23.txt" HOMOLOG_ATUALIZAR_ATE=024
caso "023 -> 024 com a 024 vinculada a OUTRO id" "$LAB23" "a 024 exige a linha de elevacao vinculada a este id ($LAB23) com o sha256" HOMOLOG_ELEVACAO_ARQ="$TMP/outro_id24.txt" HOMOLOG_ATUALIZAR_ATE=024
caso "023 -> 024 com a 024 sem vinculo (versao de vinculo obrigatorio)" "$LAB23" "recusada pela gramatica" HOMOLOG_ELEVACAO_ARQ="$TMP/sem_vinculo24.txt" HOMOLOG_ATUALIZAR_ATE=024
caso "023 -> 024 pela lista revisada (sem linha da 024)" "$LAB23" "a 024 exige a linha de elevacao vinculada a este id ($LAB23) com o sha256" HOMOLOG_ATUALIZAR_ATE=024
caso "023 -> 025 (fora do suportado)" "$LAB23" "um passo por vez" HOMOLOG_ELEVACAO_ARQ="$TMP/certa23.txt" HOMOLOG_ATUALIZAR_ATE=025

echo "== passo 024 -> 025 (SEM janela)"
caso "024 -> 025 com linha da 025 (vinculada ao id)" "$LAB24" "recusada pela gramatica" HOMOLOG_ELEVACAO_ARQ="$TMP/com_025.txt" HOMOLOG_ATUALIZAR_ATE=025
caso "024 -> 025 com linha legada da 025 (sem vinculo)" "$LAB24" "recusada pela gramatica" HOMOLOG_ELEVACAO_ARQ="$TMP/com_025_legado.txt" HOMOLOG_ATUALIZAR_ATE=025
caso "024 -> 026 (salto)" "$LAB24" "um passo por vez" HOMOLOG_ELEVACAO_ARQ="$TMP/certa24.txt" HOMOLOG_ATUALIZAR_ATE=026

echo "== passo 025 -> 026 (COM janela, vinculo obrigatorio)"
caso "025 -> 026 sem linha da 026" "$LAB25" "a 026 exige a linha de elevacao vinculada a este id ($LAB25) com o sha256" HOMOLOG_ELEVACAO_ARQ="$TMP/sem_026.txt" HOMOLOG_ATUALIZAR_ATE=026
caso "025 -> 026 com a 026 sem vinculo (versao de vinculo obrigatorio)" "$LAB25" "recusada pela gramatica" HOMOLOG_ELEVACAO_ARQ="$TMP/sem_vinculo26.txt" HOMOLOG_ATUALIZAR_ATE=026
caso "025 -> 026 com sha256 da 026 diferente do arquivo" "$LAB25" "a 026 exige a linha de elevacao vinculada a este id ($LAB25) com o sha256" HOMOLOG_ELEVACAO_ARQ="$TMP/sha26_errado.txt" HOMOLOG_ATUALIZAR_ATE=026
caso "025 -> 026 pela lista revisada (sem linha da 026)" "$LAB25" "a 026 exige a linha de elevacao vinculada a este id ($LAB25) com o sha256" HOMOLOG_ATUALIZAR_ATE=026
caso "025 -> 027 (salto)" "$LAB25" "um passo por vez" HOMOLOG_ELEVACAO_ARQ="$TMP/certa25.txt" HOMOLOG_ATUALIZAR_ATE=027

echo "== controle positivo: lista correta passa as conferencias de arquivo e para na liberacao (banco)"
caso "lista alternativa correta (sem banco: BLOQUEADO)" "$LAB" "ambiente BLOQUEADO: nada alterado" HOMOLOG_ELEVACAO_ARQ="$TMP/certa.txt"
caso "022 -> 023 com a lista correta (sem banco: BLOQUEADO)" "$LAB22" "ambiente BLOQUEADO: nada alterado" HOMOLOG_ELEVACAO_ARQ="$TMP/certa22.txt" HOMOLOG_ATUALIZAR_ATE=023
caso "023 -> 024 com a lista correta (sem banco: BLOQUEADO)" "$LAB23" "ambiente BLOQUEADO: nada alterado" HOMOLOG_ELEVACAO_ARQ="$TMP/certa23.txt" HOMOLOG_ATUALIZAR_ATE=024
caso "024 -> 025 com a lista do laboratorio, sem linha da 025 (sem banco: BLOQUEADO)" "$LAB24" "ambiente BLOQUEADO: nada alterado" HOMOLOG_ELEVACAO_ARQ="$TMP/certa24.txt" HOMOLOG_ATUALIZAR_ATE=025
caso "024 -> 025 pela lista revisada (a 025 nao precisa de linha; sem banco: BLOQUEADO)" "$LAB24" "ambiente BLOQUEADO: nada alterado" HOMOLOG_ATUALIZAR_ATE=025
caso "025 -> 026 com a lista correta (sem banco: BLOQUEADO)" "$LAB25" "ambiente BLOQUEADO: nada alterado" HOMOLOG_ELEVACAO_ARQ="$TMP/certa25.txt" HOMOLOG_ATUALIZAR_ATE=026

echo "RESULTADO: $falhas falha(s)"
[ "$falhas" = 0 ] && echo "RESULTADO: atualizar com lista alternativa OK"
[ "$falhas" = 0 ]
