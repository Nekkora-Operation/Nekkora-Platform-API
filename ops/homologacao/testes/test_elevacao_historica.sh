#!/usr/bin/env bash
# Testes SEM Docker da proveniencia historica da lista de elevacao (lib/elevacao_historica.sh) e da expectativa do H02
# (h02_esperadas e h02_janelas EXTRAIDAS de testes/executar.sh). Ambientes SINTETICOS em diretorio temporario, o LEGADO
# hml03 pelos registros VERSIONADOS (evidencias/hml03/criacao) e, so como laboratorio OPCIONAL (HOMOLOG_LAB_LEGADO_*), os
# registros reais de ensaio-016a e ensaio-016c - sem tocar ambiente algum. Nenhum caminho de maquina e exigido.
# Uso (raiz do repositorio): bash ops/homologacao/testes/test_elevacao_historica.sh
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"; AQUI="$REPO/ops/homologacao"; EX="$AQUI/testes/executar.sh"
. "$AQUI/lib/elevacao_historica.sh"
T="$(mktemp -d)"; trap 'chmod -R u+w "$T" 2>/dev/null; rm -rf "$T"' EXIT
for f in h02_esperadas h02_janelas; do awk -v f="$f" '$0 ~ "^" f "\\(\\) \\{" {p = 1} p {print} p && /^}/ {exit}' "$EX" > "$T/$f.sh"; . "$T/$f.sh"; done
ok=0; falhas=0; pulados=0
c() { if eval "$2" > /dev/null 2>&1; then echo "OK    $1"; ok=$((ok + 1)); else echo "FALHA $1"; falhas=$((falhas + 1)); fi; }
S14=5e5726b9a8f58d319b6b17dcbf7801c21e258999b003d25a023680f0d271d8fc; S16=f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732
grep -E '^01[123] ' "$AQUI/implantacao/elevacao.txt" > "$T/l_op.txt"                                   # sem 014
{ cat "$T/l_op.txt"; echo "014 $S14 tipo=bypassrls"; } > "$T/l_op_com_014.txt"                         # operacional "futura" com 014
{ cat "$T/l_op.txt"; echo "014 $S14 tipo=bypassrls ambiente=ensaio-x"; } > "$T/l_014_x.txt"
{ cat "$T/l_op.txt"; echo "014 $S14 tipo=bypassrls ambiente=ensaio-outro"; } > "$T/l_014_outro.txt"
# ambiente sintetico: migracoes 001-015, fotografia pelo eh_registrar e as duas ancoras (ambiente.env e log de criacao)
amb() {   # $1 nome, $2 lista usada na criacao -> diretorio; define SHA_<nome>
    local b="$T/$1"; mkdir -p "$b/migracoes"; for n in 001 011 012 013 014 015; do : > "$b/migracoes/${n}_x.sql"; done
    local s; s="$(eh_registrar "$2" alternativa-ensaio "$b" ensaio-x)" || return 1
    echo "== implantacao 2026-10-02T00:00:00.000Z (lista de elevacao: $(basename "$2") ALTERNATIVA DE ENSAIO sha256 $s)" > "$b/implantacao.txt"
    echo "$s" > "$b/sha_env"
}
esp() {   # $1 dir, [$2 sha do ambiente.env] -> expectativa pela lista historica
    local l; l="$(eh_lista_h02 "$1" ensaio-x "${2-$(cat "$1/sha_env" 2>/dev/null)}")" || return 1
    h02_esperadas "$1/migracoes" "$l" ensaio-x
}

amb A "$T/l_op.txt"; amb B "$T/l_014_x.txt"; amb O "$T/l_014_outro.txt"
c "fotografia: bytes identicos, proveniencia com origem/tipo/sha256/ambiente, somente leitura" \
  'cmp -s "$T/l_op.txt" "$T/A/elevacao_criacao/lista.txt" && grep -q "^sha256=$(eh_sha "$T/l_op.txt")$" "$T/A/elevacao_criacao/proveniencia.txt" && grep -q "^ambiente=ensaio-x$" "$T/A/elevacao_criacao/proveniencia.txt" && grep -q "^origem=" "$T/A/elevacao_criacao/proveniencia.txt" && [ ! -w "$T/A/elevacao_criacao/lista.txt" ]'
c "1. criado SEM 014; a lista operacional ganha 014 depois (HOMOLOG_ELEVACAO_ARQ do teste) -> continua SEM 014" \
  '[ "$(HOMOLOG_ELEVACAO_ARQ="$T/l_op_com_014.txt" esp "$T/A")" = "011 012 013" ]'
c "2. criado COM 014 BYPASSRLS vinculada a este ambiente -> espera 014" '[ "$(esp "$T/B")" = "011 012 013 014" ]'
cp "$T/l_op.txt" "$T/l_op_original.txt"; cat "$T/l_op_com_014.txt" > "$T/l_op.txt"
c "3. a lista de ORIGEM muda depois da criacao -> expectativa NAO muda (vem da fotografia)" '[ "$(esp "$T/A")" = "011 012 013" ]'
cp "$T/l_op_original.txt" "$T/l_op.txt"
c "3b. inverso: criado COM 014 e a lista corrente perde a 014 -> continua esperando 014" '[ "$(HOMOLOG_ELEVACAO_ARQ="$T/l_op.txt" esp "$T/B")" = "011 012 013 014" ]'
c "4. 014 vinculada a OUTRO ambiente na fotografia -> nao entra" '[ "$(esp "$T/O")" = "011 012 013" ]'

cp -r "$T/A" "$T/adult"; chmod u+w "$T/adult/elevacao_criacao/lista.txt"; echo "014 $S14 tipo=bypassrls" >> "$T/adult/elevacao_criacao/lista.txt"
c "5. fotografia ADULTERADA (lista.txt) -> FALHA" '! esp "$T/adult"'
cp -r "$T/A" "$T/prov"; chmod u+w "$T/prov/elevacao_criacao/proveniencia.txt"; sed -i "s/^sha256=.*/sha256=$(printf "%064d" 0)/" "$T/prov/elevacao_criacao/proveniencia.txt"
c "6a. sha256 da proveniencia divergente -> FALHA" '! esp "$T/prov"'
c "6b. sha256 do ambiente.env divergente -> FALHA" '! esp "$T/A" "$(printf "%064d" 1)"'
cp -r "$T/A" "$T/log"; sed -i "s/sha256 [0-9a-f]\{64\})/sha256 $(printf "%064d" 2))/" "$T/log/implantacao.txt"
c "6c. sha256 do log de criacao do runner divergente -> FALHA" '! esp "$T/log"'
# troca COERENTE da fotografia (lista + proveniencia), sem as ancoras: detectada
cp -r "$T/A" "$T/troca"; chmod -R u+w "$T/troca/elevacao_criacao"; cp "$T/l_op_com_014.txt" "$T/troca/elevacao_criacao/lista.txt"
sed -i "s/^sha256=.*/sha256=$(eh_sha "$T/l_op_com_014.txt")/" "$T/troca/elevacao_criacao/proveniencia.txt"
c "6d. substituicao coerente de lista+proveniencia sem as ancoras -> FALHA (ambiente.env e log de criacao)" '! esp "$T/troca"'
cp -r "$T/A" "$T/mal1"; chmod u+w "$T/mal1/elevacao_criacao/proveniencia.txt"; sed -i '/^sha256=/d' "$T/mal1/elevacao_criacao/proveniencia.txt"
c "7a. proveniencia malformada (sem sha256) -> FALHA" '! esp "$T/mal1"'
cp -r "$T/A" "$T/mal2"; chmod u+w "$T/mal2/elevacao_criacao/proveniencia.txt"; grep '^sha256=' "$T/mal2/elevacao_criacao/proveniencia.txt" > "$T/dup.txt"; cat "$T/dup.txt" >> "$T/mal2/elevacao_criacao/proveniencia.txt"
c "7b. proveniencia malformada (sha256 duplicado) -> FALHA" '! esp "$T/mal2"'
echo "014 isto-nao-e-sha" > "$T/l_mal.txt"; mkdir -p "$T/mal3"; amb mal3 "$T/l_mal.txt"
c "7c. lista fotografada fora do formato (ancoras coerentes) -> FALHA" '! esp "$T/mal3"'
cp -r "$T/A" "$T/outro_id"; chmod u+w "$T/outro_id/elevacao_criacao/proveniencia.txt"; sed -i 's/^ambiente=.*/ambiente=ensaio-y/' "$T/outro_id/elevacao_criacao/proveniencia.txt"
c "7d. fotografia de OUTRO ambiente -> FALHA" '! esp "$T/outro_id"'
cp -r "$T/A" "$T/semfoto"; chmod -R u+w "$T/semfoto/elevacao_criacao"; rm -rf "$T/semfoto/elevacao_criacao"
c "8. ambiente NOVO sem fotografia -> FALHA fechada" '! esp "$T/semfoto"'
c "8b. ambiente NOVO sem fotografia, mesmo com fonte legada -> FALHA (legado nao substitui proveniencia)" '! HOMOLOG_H02_LISTA_LEGADO="$T/l_op.txt" esp "$T/semfoto"'
c "8c. ambiente NOVO sem fotografia, com HOMOLOG_ELEVACAO_ARQ no teste -> FALHA (lista vigente nunca e usada)" '! HOMOLOG_ELEVACAO_ARQ="$T/l_op.txt" esp "$T/semfoto"'
cp -r "$T/A" "$T/incompleta"; chmod -R u+w "$T/incompleta/elevacao_criacao"; rm "$T/incompleta/elevacao_criacao/proveniencia.txt"
c "8d. fotografia incompleta (sem proveniencia) -> FALHA" '! esp "$T/incompleta"'

# ---- LEGADO (ambiente sem fotografia): obrigatorio e portavel, com os registros VERSIONADOS e um legado sintetico ancorado
# hml03: registros de criacao versionados em evidencias/hml03/criacao (log sem sha256: runner anterior ao P3). A arvore
# de migracoes e reconstruida pelos nomes de migracoes_sha256.txt (h02_esperadas so olha os nomes).
BH03="$T/hml03"; mkdir -p "$BH03/migracoes"
cp "$AQUI/evidencias/hml03/criacao/implantacao.txt" "$AQUI/evidencias/hml03/criacao/janelas_de_elevacao.txt" "$BH03/"
tr -d '\r' < "$AQUI/evidencias/hml03/criacao/migracoes_sha256.txt" | sed -E 's/^[0-9a-f]{64} \*?//' | while read -r n; do : > "$BH03/migracoes/$n"; done
# legado ANCORADO sintetico: log de criacao com o sha256 da lista (runner P3+), sem fotografia e sem sha no ambiente.env
BLA="$T/legado_ancorado"; mkdir -p "$BLA/migracoes"; for n in 001 011 012 013 014 015; do : > "$BLA/migracoes/${n}_x.sql"; done
echo "== implantacao 2026-09-29T00:00:00.000Z (lista de elevacao: elevacao.txt sha256 $(eh_sha "$T/l_op.txt"))" > "$BLA/implantacao.txt"
leg() {   # $1 base, $2 id, [$3 fonte legada] -> expectativa (ambiente legado: sem fotografia e sem sha no ambiente.env)
    local l; l="$(HOMOLOG_H02_LISTA_LEGADO="${3:-}" eh_lista_h02 "$1" "$2" "" 2> "$T/leg_modo.txt")" || return 1
    h02_esperadas "$1/migracoes" "$l" "$2"
}
concl() { mkdir -p "$T/j_$2"; h02_janelas "$1" "$T/j_$2" | cut -d'|' -f1; }
c "9c. LEGADO hml03 (registros versionados; log sem sha256) + fonte explicita -> 'LEGADO SEM ANCORA' registrado e '011 012 013'" \
  '[ "$(leg "$BH03" hml03 "$T/l_op.txt")" = "011 012 013" ] && grep -q "^MODO LEGADO SEM ANCORA: " "$T/leg_modo.txt" && [ "$(concl "$BH03" h)" = "011 012 013" ]'
c "9d. LEGADO ancorado + a fonte da criacao (sha do log) -> 'MODO LEGADO' e '011 012 013'" \
  '[ "$(leg "$BLA" ensaio-legado "$T/l_op.txt")" = "011 012 013" ] && grep -q "^MODO LEGADO: " "$T/leg_modo.txt"'
c "10a. LEGADO sem fonte historica -> FALHA (nunca a lista vigente)" '! leg "$BLA" ensaio-legado "" && ! HOMOLOG_ELEVACAO_ARQ="$T/l_op.txt" leg "$BLA" ensaio-legado "" && ! leg "$BH03" hml03 ""'
c "10b. LEGADO com fonte que NAO e a da criacao (lista operacional de hoje, sha diferente do log) -> FALHA" '! leg "$BLA" ensaio-legado "$AQUI/implantacao/elevacao.txt"'
c "10c. LEGADO com fonte inexistente -> FALHA" '! leg "$BLA" ensaio-legado "$T/nao_existe.txt" && ! leg "$BH03" hml03 "$T/nao_existe.txt"'

# ---- LABORATORIO LEGADO OPCIONAL: registros REAIS de ensaio-016a e ensaio-016c (somente leitura). Ficam em clones de
# laboratorio de outra maquina (saidas/ nao e versionado e os commits das listas so existem nesses clones). So rodam com
# HOMOLOG_LAB_LEGADO_P4 (clone lab-p4-20260929T192708Z/nekkora-platform) e HOMOLOG_LAB_LEGADO_R2B1C (clone
# lab-r2b1c-20261001T233433Z/nekkora-platform). Sem eles, os casos 9a0, 9a e 9b sao PULADOS e contados a parte: nunca
# contam como OK, e nenhuma conferencia obrigatoria depende deles (a mesma regra e coberta por 9c, 9d e 10a-c).
pula() { echo "PULADO $1 ($2)"; pulados=$((pulados + 1)); }
LP4="${HOMOLOG_LAB_LEGADO_P4:-}"; L1C="${HOMOLOG_LAB_LEGADO_R2B1C:-}"
B016A="$LP4/ops/homologacao/saidas/ensaio-016a"; B016C="$L1C/ops/homologacao/saidas/ensaio-016c"
if [ -n "$LP4" ] && [ -d "$B016A" ] && git -C "$LP4" show 296808b:ops/homologacao/implantacao/elevacao.txt > "$T/lista_016a_S2_lf.txt" 2>/dev/null; then
    # o runner do P4 leu a COPIA DE TRABALHO do clone (core.autocrlf=true: CRLF); a fonte historica exata sao esses bytes
    sed 's/$/\r/' "$T/lista_016a_S2_lf.txt" > "$T/lista_016a_S2.txt"
    c "9a0. bytes historicos do ensaio-016a reconstruidos (CRLF) = sha256 do log de criacao; a versao LF NAO e (bytes exatos)" \
      '[ "$(eh_sha "$T/lista_016a_S2.txt")" = "$(eh_sha_log_criacao "$B016A")" ] && [ "$(eh_sha "$T/lista_016a_S2_lf.txt")" != "$(eh_sha_log_criacao "$B016A")" ] && ! leg "$B016A" ensaio-016a "$T/lista_016a_S2_lf.txt"'
    c "9a. LEGADO ensaio-016a + lista historica explicita (S2, sha do log de criacao) -> '011 012 013 016' = janelas registradas" \
      '[ "$(leg "$B016A" ensaio-016a "$T/lista_016a_S2.txt")" = "011 012 013 016" ] && [ "$(concl "$B016A" a)" = "011 012 013 016" ] && grep -q "^MODO LEGADO: " "$T/leg_modo.txt"'
else
    pula 9a0 "laboratorio legado opcional ausente: HOMOLOG_LAB_LEGADO_P4"; pula 9a "laboratorio legado opcional ausente: HOMOLOG_LAB_LEGADO_P4"
fi
if [ -n "$L1C" ] && [ -d "$B016C" ] && git -C "$L1C" show e01884f:ops/homologacao/implantacao/elevacao_lab_ensaio-016c.txt > "$T/lista_016c_S2.txt" 2>/dev/null; then
    c "9b. LEGADO ensaio-016c + lista de lab do S2'' (sha do log) -> '011 012 013 014 016' = janelas registradas" \
      '[ "$(leg "$B016C" ensaio-016c "$T/lista_016c_S2.txt")" = "011 012 013 014 016" ] && [ "$(concl "$B016C" c)" = "011 012 013 014 016" ]'
else
    pula 9b "laboratorio legado opcional ausente: HOMOLOG_LAB_LEGADO_R2B1C"
fi

# ---- eh_registrar
c "11a. eh_registrar recusa lista inexistente" '! eh_registrar "$T/nao_existe.txt" operacional "$T/novo1" ensaio-x'
c "11b. eh_registrar recusa fotografia ja existente (nunca sobrescreve)" '! eh_registrar "$T/l_op.txt" operacional "$T/A" ensaio-x'
echo "RESULTADO: $ok OK, $falhas FALHA, $pulados PULADO(S) (laboratorio legado opcional)"
[ "$falhas" = 0 ]
