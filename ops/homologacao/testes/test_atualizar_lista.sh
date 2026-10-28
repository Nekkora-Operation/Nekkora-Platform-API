#!/usr/bin/env bash
# Prova ESTATICA (sem Docker, sem banco) dos controles da lista de elevacao alternativa (HOMOLOG_ELEVACAO_ARQ) no
# "atualizar" 021 -> 022 (missao PLATFORM-API-V1-003, revisao final): executa o homologacao.sh REAL contra dois ambientes
# ficticios em saidas/ (removidos no fim) e confere, em cada recusa, a mensagem, o codigo e que NADA mudou no ambiente
# (mesmos arquivos e bytes, ambiente.env intacto, nenhum atualizacao-*).
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
LAB=ensaio-validacao-atz; FORA=validacao-atz
for id in "$LAB" "$FORA"; do
    [ ! -e "$AQUI/saidas/$id" ] || { echo "ERRO: $AQUI/saidas/$id ja existe" >&2; exit 3; }
done
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP" "$AQUI/saidas/$LAB" "$AQUI/saidas/$FORA"' EXIT
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

caso() {   # $1 descricao, $2 id, $3 trecho esperado na mensagem, demais: variaveis de ambiente
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
caso "linha com o sha256 da 022 sob a versao 021 (versao errada)" "$LAB" "a 022 exige a linha de elevacao vinculada a este id ($LAB) com o sha256" HOMOLOG_ELEVACAO_ARQ="$TMP/versao_021.txt"
caso "versao alvo fora do suportado (023), mesmo com a lista correta" "$LAB" "atualizacao suportada: 021 -> 022" HOMOLOG_ELEVACAO_ARQ="$TMP/certa.txt" HOMOLOG_ATUALIZAR_ATE=023
caso "lista revisada (022 vinculada ao ensaio-api-v1-003, nao a este id)" "$LAB" "a 022 exige a linha de elevacao vinculada a este id ($LAB) com o sha256"

echo "== controle positivo: lista correta passa as conferencias de arquivo e para na liberacao (banco)"
caso "lista alternativa correta (sem banco: BLOQUEADO)" "$LAB" "ambiente BLOQUEADO: nada alterado" HOMOLOG_ELEVACAO_ARQ="$TMP/certa.txt"

echo "RESULTADO: $falhas falha(s)"
[ "$falhas" = 0 ] && echo "RESULTADO: atualizar com lista alternativa OK"
[ "$falhas" = 0 ]
