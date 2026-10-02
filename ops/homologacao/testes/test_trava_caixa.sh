#!/usr/bin/env bash
# WIN-TRANSPORT-1: controle LEVE da aquisicao da trava da caixa (sem estresse; o estresse e so prova do portao, em
# testes/wintransport/rodar.sh). Compila COPIAS VERBATIM de MailSender.java e CaixaHomologacaoMailSender.java com
# testes/wintransport/VerificaTrava.java num diretorio temporario e:
#   1. a fonte real tem de passar em todos os cenarios;
#   2. cada copia ADULTERADA tem de ser DETECTADA (o verificador falha): sem a condicao Windows, sem o caminho exato,
#      prazo reiniciado a cada acesso negado, causa trocada por timeout generico. Ancora ausente = FALHA (a correcao mudou
#      de forma; revisar este controle junto). Uso: bash testes/test_trava_caixa.sh  (codigo 0 = OK)
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"; REPO="$(cd "$AQUI/../.." && pwd)"
M="$REPO/api/nekkora-platform/src/main/java/com/nekkora/platform/mail"
SLF="$(ls "$HOME"/.m2/repository/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar 2>/dev/null)"
command -v javac > /dev/null || { echo "FALHA javac ausente (controle da trava nao executado)"; exit 1; }
[ -f "$SLF" ] || { echo "FALHA slf4j-api 2.0.18 ausente do cache local (controle da trava nao executado)"; exit 1; }
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
falhas=0
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }
cpj() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac

# $1 nome da variante, $2 fonte do transporte -> codigo do verificador (3 = nao compilou)
verifica() {
    local d="$W/$1"; mkdir -p "$d/src" "$d/classes"
    cp "$M/MailSender.java" "$AQUI/testes/wintransport/VerificaTrava.java" "$d/src/"
    cp "$2" "$d/src/CaixaHomologacaoMailSender.java"
    javac -d "$d/classes" -cp "$(cpj "$SLF")" "$d"/src/*.java > "$d/javac.txt" 2>&1 || return 3
    java -cp "$(cpj "$d/classes")$SEP$(cpj "$SLF")" com.nekkora.platform.mail.VerificaTrava > "$d/saida.txt" 2>&1
}

# $1 nome, $2 trecho original (unico, exato), $3 substituto -> copia adulterada em $W/<nome>.java
adultera() {
    python - "$M/CaixaHomologacaoMailSender.java" "$W/$1.java" "$2" "$3" <<'PY'
import sys
src, dst, de, para = sys.argv[1:5]
s = open(src, encoding='utf-8').read()
if s.count(de) != 1:
    sys.exit(f"ancora ausente ou repetida ({s.count(de)}x): {de!r}")
open(dst, 'w', encoding='utf-8', newline='').write(s.replace(de, para))
PY
}

if verifica real "$M/CaixaHomologacaoMailSender.java"; then
    ok "fonte real: contrato da aquisicao da trava ($(grep -c '^OK' "$W/real/saida.txt") cenarios)"
else
    nok "fonte real: verificador falhou (codigo $?)"; sed 's/^/      /' "$W/real/saida.txt" "$W/real/javac.txt" 2>/dev/null
fi

ADQ='if (!windows || e.getFile() == null || !Path.of(e.getFile()).equals(trava)) {'
while IFS='#' read -r nome de para; do
    if ! adultera "$nome" "$de" "$para" 2> "$W/$nome.err"; then
        nok "adulteracao $nome: $(cat "$W/$nome.err")"; continue
    fi
    verifica "$nome" "$W/$nome.java"; c=$?
    if [ "$c" = 1 ]; then ok "adulteracao $nome DETECTADA ($(grep -c '^FALHA' "$W/$nome/saida.txt") cenario(s) falharam)"
    elif [ "$c" = 3 ]; then nok "adulteracao $nome: copia nao compilou (controle invalido)"
    else nok "adulteracao $nome NAO detectada (codigo $c)"; fi
done <<EOF
sem_condicao_windows#$ADQ#if (e.getFile() == null || !Path.of(e.getFile()).equals(trava)) {
sem_caminho_exato#$ADQ#if (!windows || e.getFile() == null) {
prazo_reiniciado#acessoNegado = e;#acessoNegado = e; limite = Math.max(limite, System.currentTimeMillis() + 300);
causa_trocada_por_timeout#if (acessoNegado != null) {#if (acessoNegado != null && false) {
EOF

[ "$falhas" = 0 ] && echo "RESULTADO: OK" || echo "RESULTADO: $falhas falha(s)"
[ "$falhas" = 0 ]
