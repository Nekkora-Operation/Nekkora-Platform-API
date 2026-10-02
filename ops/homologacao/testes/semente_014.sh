#!/usr/bin/env bash
# Conferencia da semeadura pre-014 (R2b-0, T3). SOMENTE LEITURA: so roda sql/semente_014_estado.sql (READ ONLY + ROLLBACK).
#   semente_014.sh antes  <manifesto>                 depois da semeadura e ANTES da 014: confere o estado inicial
#                                                     declarado e grava o manifesto (so contagens e digests)
#   semente_014.sh depois <manifesto> <inicio> <fim>  depois da RETIRADA da janela da 014: confere que so as alteracoes
#                                                     previstas aconteceram (inicio/fim = janela registrada da 014, ISO 8601)
# Conexao: "admin_sql nekkora" (exportada por homologacao.sh conferir-semente014) ou, fora da homologacao (base
# descartavel da R2b-0), o comando em SEMENTE_PSQL (ex.: "docker exec -i <c> psql -X -q -At -v ON_ERROR_STOP=1 -U <su> -d <db>").
# A identidade precisa ver todas as linhas (superusuario ou BYPASSRLS): sem isso o resultado e INCONCLUSIVO.
# Codigo: 0 OK; 1 FALHA (alguma conferencia divergiu); 3 INCONCLUSIVO (leitura incompleta, manifesto ausente ou chave faltando).
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
SQL="$AQUI/sql/semente_014_estado.sql"
modo="${1:-}"; MAN="${2:-}"
[ -n "$MAN" ] || { echo "uso: semente_014.sh antes|depois <manifesto> [<inicio> <fim>]" >&2; exit 3; }
roda() {   # $1 inicio, $2 fim
    if declare -F admin_sql > /dev/null; then admin_sql nekkora -v inicio="$1" -v fim="$2" < "$SQL"
    elif [ -n "${SEMENTE_PSQL:-}" ]; then MSYS_NO_PATHCONV=1 $SEMENTE_PSQL -v inicio="$1" -v fim="$2" < "$SQL"
    else echo "sem conexao (admin_sql ou SEMENTE_PSQL)" >&2; return 3; fi
}
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
case "$modo" in
    antes) roda - - > "$TMP" 2>&1 || { cat "$TMP"; echo "SEMENTE INCONCLUSIVO: leitura falhou"; exit 3; } ;;
    depois)
        [ -f "$MAN" ] || { echo "SEMENTE INCONCLUSIVO: manifesto ausente ($MAN)"; exit 3; }
        ini="${3:?inicio}"; fim="${4:?fim}"
        roda "$ini" "$fim" > "$TMP" 2>&1 || { cat "$TMP"; echo "SEMENTE INCONCLUSIVO: leitura falhou"; exit 3; } ;;
    *) echo "modo invalido: $modo" >&2; exit 3 ;;
esac
tr -d '\r' < "$TMP" > "$TMP.n" && mv "$TMP.n" "$TMP"
v() { sed -n "s/^$1=//p" "$2" | head -1; }
falhas=0; faltas=0
confere() {   # $1 descricao, $2 obtido, $3 esperado
    if [ -z "$2" ] || [ -z "$3" ]; then echo "INCONCLUSIVO|$1|obtido='${2}' esperado='${3}'"; faltas=$((faltas + 1))
    elif [ "$2" = "$3" ]; then echo "OK|$1|$2"
    else echo "FALHA|$1|obtido=$2 esperado=$3"; falhas=$((falhas + 1)); fi
}
idt="$(v identidade_ve_todas_as_linhas "$TMP")"
[ "$idt" = 1 ] || { echo "INCONCLUSIVO|identidade ve todas as linhas|obtido='$idt'"; echo "SEMENTE INCONCLUSIVO ($modo): identidade sem visao de todas as linhas (RLS)"; exit 3; }
echo "OK|identidade ve todas as linhas|1"
if [ "$modo" = antes ]; then
    confere "014 ainda nao aplicada (sem mfa_verified_at)" "$(v colunas_014 "$TMP")" 0
    for g in V E R; do confere "grupo $g: 6 sessoes" "$(v grupo_${g}_qtde "$TMP")" 6; done
    confere "grupo V: 6 nao revogadas" "$(v grupo_V_nao_revogadas "$TMP")" 6
    confere "grupo V: 0 expiradas" "$(v grupo_V_expiradas "$TMP")" 0
    confere "grupo E: 6 nao revogadas" "$(v grupo_E_nao_revogadas "$TMP")" 6
    confere "grupo E: 6 expiradas" "$(v grupo_E_expiradas "$TMP")" 6
    confere "grupo R: 0 nao revogadas (controle ja revogado)" "$(v grupo_R_nao_revogadas "$TMP")" 0
    confere "nenhuma sessao fora da semente" "$(v sessoes_fora_da_semente "$TMP")" 0
    if [ "$falhas" = 0 ] && [ "$faltas" = 0 ]; then
        grep -E '^(grupo_|organizacao_|sessoes_)' "$TMP" > "$MAN"; echo "manifesto gravado: $(wc -l < "$MAN") linhas"
    fi
else
    confere "014 aplicada (mfa_verified_at presente)" "$(v colunas_014 "$TMP")" 1
    for g in V E R; do confere "grupo $g: 6 sessoes" "$(v grupo_${g}_qtde "$TMP")" 6; done
    confere "grupos V e E: 0 nao revogadas" "$(v grupo_V_nao_revogadas "$TMP")/$(v grupo_E_nao_revogadas "$TMP")" 0/0
    confere "grupos V e E: um unico instante de revogacao" "$(v ve_instantes_distintos "$TMP")" 1
    confere "grupos V e E: 12 revogadas dentro da janela registrada da 014" "$(v ve_revogadas_na_janela "$TMP")" 12
    confere "grupo R: revoked_at inalterado" "$(v grupo_R_digest_revoked_at "$TMP")" "$(v grupo_R_digest_revoked_at "$MAN")"
    confere "grupo R: continua sem nao revogadas" "$(v grupo_R_nao_revogadas "$TMP")" 0
    for g in V E R; do
        confere "grupo $g: colunas preservadas identicas (digest)" "$(v grupo_${g}_digest_preservadas "$TMP")" "$(v grupo_${g}_digest_preservadas "$MAN")"
        confere "grupo $g: colunas novas nulas" "$(v grupo_${g}_colunas_novas_nao_nulas "$TMP")" 0
    done
    confere "organizacao e usuarios semeados inalterados (digest)" "$(v organizacao_e_usuarios_digest "$TMP")" "$(v organizacao_e_usuarios_digest "$MAN")"
    confere "nenhuma sessao fora da semente" "$(v sessoes_fora_da_semente "$TMP")" "$(v sessoes_fora_da_semente "$MAN")"
    confere "total de sessoes inalterado" "$(v sessoes_total "$TMP")" "$(v sessoes_total "$MAN")"
    confere "0 sessoes nao revogadas sem marcador na base" "$(v nao_revogadas_sem_marcador_na_base "$TMP")" 0
fi
if [ "$faltas" -gt 0 ]; then echo "SEMENTE INCONCLUSIVO ($modo): $faltas leitura(s) incompleta(s), $falhas falha(s)"; exit 3; fi
if [ "$falhas" -gt 0 ]; then echo "SEMENTE FALHA ($modo): $falhas conferencia(s) divergente(s)"; exit 1; fi
echo "SEMENTE OK ($modo)"
