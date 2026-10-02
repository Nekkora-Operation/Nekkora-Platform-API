#!/usr/bin/env bash
# WIN-TRANSPORT-0: compila o harness com COPIAS VERBATIM das fontes do transporte (sha256 registrado) num diretorio
# temporario, roda os casos com limites FIXOS e resume por caso/resultado/excecao. Sem Docker, sem Maven, sem tocar
# api/*/target nem ambiente algum. Remove so os diretorios temporarios criados pelo proprio harness.
#   rodar.sh <dir de saida> [rodadas] [tentativas] [casos]  (padrao: 5 rodadas x 400 tentativas; casos P1 P2 H1 H2 H3 H4 H5)
set -uo pipefail
OUT="${1:?dir de saida}"; ROD="${2:-5}"; TEN="${3:-400}"; CASOS="${4:-P1 P2 H1 H2 H3 H4 H5}"
REPO="$(cd "$(dirname "$0")/../../../.." && pwd)"; AQUI="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
SLF="$(ls "$HOME"/.m2/repository/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar 2>/dev/null)"
[ -f "$SLF" ] || { echo "slf4j-api 2.0.18 ausente do cache local" >&2; exit 3; }
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
M="$REPO/api/nekkora-platform/src/main/java/com/nekkora/platform/mail"
mkdir -p "$W/src/com/nekkora/platform/mail" "$W/classes"
cp "$M/MailSender.java" "$M/CaixaHomologacaoMailSender.java" "$AQUI/Harness.java" "$W/src/com/nekkora/platform/mail/"
{ echo "fontes compiladas (copias verbatim; sha256 dos bytes):"; (cd "$W/src/com/nekkora/platform/mail" && sha256sum *.java)
  echo "repositorio: $(git -C "$REPO" rev-parse HEAD 2>/dev/null) (arvore de trabalho)"; echo "java: $(java -version 2>&1 | head -1)"
  echo "slf4j: $(basename "$SLF")"; echo "SO: $(uname -a)"; echo "rodadas=$ROD tentativas=$TEN inicio=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$OUT/contexto.txt"
javac -d "$W/classes" -cp "$SLF" "$W"/src/com/nekkora/platform/mail/*.java > "$OUT/javac.txt" 2>&1 || { cat "$OUT/javac.txt"; exit 3; }
CP="$(cygpath -w "$W/classes" 2>/dev/null || echo "$W/classes");$(cygpath -w "$SLF" 2>/dev/null || echo "$SLF")"
PY="$(command -v python)"
for c in $CASOS; do
    echo "== $c $(date -u +%H:%M:%SZ)" >> "$OUT/execucao.txt"
    java -cp "$CP" com.nekkora.platform.mail.Harness "$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")" "$c" "$ROD" "$TEN" "$(cygpath -w "$PY" 2>/dev/null || echo "$PY")" >> "$OUT/execucao.txt" 2>&1
    echo "codigo=$?" >> "$OUT/execucao.txt"
    b="$(tr -d '\r' < "$OUT/${c}_base.txt" 2>/dev/null)"; [ -n "$b" ] && rm -rf "$(cygpath -u "$b" 2>/dev/null || echo "$b")"
done
# resumo: contagem por caso x ator x resultado x excecao (Java) e pelos CSVs do processo Python
{ echo "caso,ator,resultado,excecao,contagem"
  for c in P1 P2 H1 H2 H3 H4 H5 H6 C1 C2 C3; do [ -f "$OUT/$c.csv" ] && tail -n +2 "$OUT/$c.csv" | tr -d '\r' | awk -F, '{print $1","$3","$7","$8}' | sort | uniq -c | awk '{n=$1; $1=""; sub(/^ /,""); print $0","n}'; done
  for f in "$OUT"/P2_python_r*.csv "$OUT"/H5_python_r*.csv; do [ -f "$f" ] && awk -F, -v c="$(basename "$f" | cut -d_ -f1)" '{print c",python,"$2","$3}' "$f" | tr -d '\r'; done | sort | uniq -c | awk '{n=$1; $1=""; sub(/^ /,""); print $0","n}'
} > "$OUT/resumo.csv"
echo "fim=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$OUT/contexto.txt"
cat "$OUT/resumo.csv"
