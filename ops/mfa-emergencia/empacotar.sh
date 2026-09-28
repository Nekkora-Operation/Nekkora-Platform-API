#!/usr/bin/env bash
# Pacote de revisao da recuperacao emergencial de MFA (016, candidata) em ../relatorios/ (fora do repositorio):
# diff contra a base (padrao effa516, candidata revisada da rodada 2; BASE=ca4033b para o diff desde o contrato) com os arquivos novos, os ARQUIVOS FINAIS entregues em
# repo/<caminho>, git status, lista e manifesto SHA-256 dos arquivos entregues (conferidos contra repo/), relatorio, matriz e a evidencia sanitizada mais recente de ops/mfa-emergencia/validar.sh.
# Nao faz commit, stage nem push. Uso: ops/mfa-emergencia/empacotar.sh [diretorio-de-evidencia]
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
BASE="${BASE:-effa516}"
DESTINO="$(cd "$REPO/.." && pwd)/relatorios"
EVID="${1:-$(ls -td "$AQUI"/saidas/*/ 2>/dev/null | head -1)}"
[ -n "$EVID" ] && [ -f "$EVID/resumo.txt" ] || { echo "ERRO: evidencia de validar.sh ausente" >&2; exit 1; }
grep -q '^resultado=APROVADO' "$EVID/resumo.txt" || { echo "ERRO: a execucao indicada nao foi aprovada pelo runner" >&2; exit 1; }
# execucao anterior, INVALIDA/INCOMPLETA (parou na varredura; sem resumo.txt): vai separada, marcada, com a classificacao
ANTERIOR="$AQUI/saidas/2026-09-28T003057Z"
PY=python3; "$PY" -c 1 >/dev/null 2>&1 || PY=python
NOME="mfa-emergencia-implementacao-$(date -u +%Y%m%dT%H%M%SZ)"
PKG="$DESTINO/$NOME"
mkdir -p "$PKG/evidencia"

cd "$REPO"
# preexistentes nao rastreados (preservados, fora da entrega)
PREEXISTENTE='^(api/e2e/email/|db/tests/portal-8-2/|.*/saidas/)'
{ git ls-files --others --exclude-standard | grep -vE "$PREEXISTENTE" || true; } | sort > "$PKG/arquivos-novos.txt"   # vazio quando tudo ja esta commitado
git diff --name-only "$BASE" | sort > "$PKG/arquivos-alterados.txt"
cat "$PKG/arquivos-alterados.txt" "$PKG/arquivos-novos.txt" | sort -u > "$PKG/arquivos-entregues.txt"
{
    git diff "$BASE" --
    while read -r f; do git diff --no-index -- /dev/null "$f" || true; done < "$PKG/arquivos-novos.txt"
} > "$PKG/diff-$BASE.patch"
{
    echo "branch=$(git branch --show-current) HEAD=$(git rev-parse HEAD) base=$BASE"
    echo "db0164c incorporado? $(git merge-base --is-ancestor db0164c HEAD && echo SIM || echo nao)"
    git status --porcelain --untracked-files=all
} > "$PKG/git-status.txt"
cp docs/relatorios/2026-09-27-mfa-emergencia-implementacao.md docs/relatorios/2026-09-27-mfa-emergencia-matriz-016.md docs/relatorios/2026-09-28-mfa-emergencia-*.md "$PKG/"
# arquivos FINAIS entregues (versao completa, nao so o patch), no mesmo caminho do repositorio: repo/<caminho>
while read -r f; do
    [ -f "$f" ] || continue
    mkdir -p "$PKG/repo/$(dirname "$f")"
    cp "$f" "$PKG/repo/$f"
done < "$PKG/arquivos-entregues.txt"
cp -r "$EVID"/. "$PKG/evidencia/"
rm -f "$PKG/evidencia/maven.txt"   # log bruto fica local; os resumos e surefire vao no pacote
if [ -d "$ANTERIOR" ]; then
    mkdir -p "$PKG/execucao-anterior-invalida/evidencia"
    cp -r "$ANTERIOR"/. "$PKG/execucao-anterior-invalida/evidencia/"
    rm -f "$PKG/execucao-anterior-invalida/evidencia/maven.txt"
    cat > "$PKG/execucao-anterior-invalida/INVALIDA.txt" <<'TXT'
EXECUCAO INVALIDA / INCOMPLETA - NAO E EVIDENCIA DE REFERENCIA

Execucao: ops/mfa-emergencia/saidas/2026-09-28T003057Z (sessao anterior).
Estado: a suite de banco e a regressao Maven terminaram (BUILD SUCCESS, 125 testes), mas o runner PAROU na varredura
da evidencia e nao gerou resumo.txt. A varredura daquela versao contou 41 "ocorrencias de formato de token" e marcou
o proprio varredura.txt como contendo senha (o arquivo cita o padrao procurado).

O codigo daquela varredura foi substituido 36 s depois da execucao e nao foi preservado; o numero 41 nao pode ser
reproduzido um a um. Por isso a classificacao (classificacao.txt) cobre o SUPERCONJUNTO: toda sequencia de 43 ou mais
caracteres do alfabeto base64url na evidencia (fora o log bruto do Maven e o proprio varredura.txt). Nenhuma e de
formato de token; todas sao separadores, identificadores do catalogo e do codigo, nomes de arquivo ou hashes hex.
O log bruto do Maven daquela execucao tambem nao tem nenhuma ocorrencia (verificado localmente; o log nao vai no pacote).

A execucao oficial e a de evidencia/ (resumo.txt com os codigos de saida de cada etapa).
TXT
    "$PY" "$AQUI/varredura.py" classificar "$ANTERIOR" "$REPO" > "$PKG/execucao-anterior-invalida/classificacao.txt"         || { echo "ERRO: classificacao da execucao anterior encontrou formato de token" >&2; exit 1; }
fi
# demais execucoes: so resumo, etapas e o fim da suite de banco, para rastreabilidade; LEIA.txt diz o papel de cada uma
papel() {
    case "$1" in
        2026-09-28T125940Z) echo "OFICIAL DA RODADA 3 ANTES DAS CORRECOES DA REVISAO (pacote 20260928T153401Z; nao cobre os dois defeitos corrigidos na secao 5g)";;
        2026-09-28T041923Z) echo "OFICIAL DA RODADA 2 (commit effa516; nao cobre a revisao 4 nem I-1 a I-5)";;
        2026-09-28T030420Z) echo "OFICIAL DA RODADA 1 (revisada; valida para os cenarios executados, nao cobre os achados da rodada 2)";;
        2026-09-28T023718Z) echo "nao oficial: antes do teste da confirmacao tardia, da varredura corrigida e dos codigos por etapa";;
        2026-09-28T024805Z) echo "nao oficial: aprovada, mas sem a lista por metodo";;
        2026-09-28T025340Z) echo "nao oficial: REPROVADA na etapa 1 (recuo de relogio da VM do Docker numa fixture preexistente)";;
        2026-09-28T025915Z) echo "nao oficial: REPROVADA na etapa 1 (containers da execucao anterior mantidos para inspecao)";;
        *) echo "nao oficial: ver resumo.txt e o relatorio";;
    esac
}
mkdir -p "$PKG/outras-execucoes"
echo "Execucao oficial deste pacote: evidencia/ ($(basename "$EVID"))." > "$PKG/outras-execucoes/LEIA.txt"
for d in "$AQUI"/saidas/*/; do
    n="$(basename "$d")"
    [ "$d" -ef "$EVID" ] && continue
    [ "$d" -ef "$ANTERIOR/" ] && continue
    mkdir -p "$PKG/outras-execucoes/$n"
    echo "$n: $(papel "$n")" >> "$PKG/outras-execucoes/LEIA.txt"
    for f in resumo.txt etapas.txt; do [ -f "$d/$f" ] && cp "$d/$f" "$PKG/outras-execucoes/$n/"; done
    [ -f "$d/suite_banco.txt" ] && tail -15 "$d/suite_banco.txt" > "$PKG/outras-execucoes/$n/suite_banco_fim.txt"
done
"$PY" - "$REPO" "$PKG" <<'PY'
import hashlib, os, sys, zipfile
repo, pkg = sys.argv[1], sys.argv[2]
def h(p):
    with open(p, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()
# Caminhos do manifesto relativos a RAIZ DO PACOTE (a pasta de primeiro nivel do ZIP), sem prefixo: depois de extrair,
# `cd <pacote> && sha256sum -c manifesto.sha256` confere tudo diretamente. Arquivos entregues removidos (sem conteudo)
# vao para arquivos-removidos.txt, fora do manifesto. O proprio manifesto nao se lista.
linhas = []
removidos = []
for rel in open(os.path.join(pkg, 'arquivos-entregues.txt'), encoding='utf-8').read().split():
    p = os.path.join(repo, rel)
    if os.path.isfile(p):
        linhas.append(f"{h(p)}  repo/{rel}")
    else:
        removidos.append(f"repo/{rel}")
if removidos:
    open(os.path.join(pkg, 'arquivos-removidos.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(removidos) + '\n')
for raiz, _, arquivos in os.walk(pkg):
    for a in sorted(arquivos):
        p = os.path.join(raiz, a)
        rel = os.path.relpath(p, pkg).replace(os.sep, '/')
        if rel.startswith('repo/') or rel == 'manifesto.sha256':
            continue  # repo/ ja listado a partir do repositorio; o mesmo hash e conferido abaixo
        linhas.append(f"{h(p)}  {rel}")
# cada arquivo entregue do manifesto (repo/...) precisa estar no pacote, em repo/<caminho>, com o mesmo hash
faltas = []
for l in linhas:
    h_, rel = l.split('  ', 1)
    if rel.startswith('repo/'):
        p = os.path.join(pkg, rel)
        if not os.path.isfile(p) or h(p) != h_:
            faltas.append(rel)
if faltas:
    sys.exit('ERRO: arquivos do manifesto ausentes ou divergentes no pacote: ' + ', '.join(faltas))
open(os.path.join(pkg, 'manifesto.sha256'), 'w', encoding='utf-8', newline='\n').write('\n'.join(linhas) + '\n')
# conferencia direta, como o revisor fara: cada linha, relativa a raiz do pacote
for l in linhas:
    h_, rel = l.split('  ', 1)
    if h(os.path.join(pkg, rel)) != h_:
        sys.exit('ERRO: conferencia direta do manifesto falhou em ' + rel)
z = pkg + '.zip'
with zipfile.ZipFile(z, 'w', zipfile.ZIP_DEFLATED) as zf:
    for raiz, _, arquivos in os.walk(pkg):
        for a in sorted(arquivos):
            p = os.path.join(raiz, a)
            zf.write(p, os.path.join(os.path.basename(pkg), os.path.relpath(p, pkg)))
print(f"pacote={z} sha256={h(z)}")
PY
# copia sincronizada do relatorio e da matriz
cp docs/relatorios/2026-09-27-mfa-emergencia-implementacao.md docs/relatorios/2026-09-27-mfa-emergencia-matriz-016.md "$DESTINO/"
echo "arquivos entregues: $(wc -l < "$PKG/arquivos-entregues.txt") (alterados $(wc -l < "$PKG/arquivos-alterados.txt"), novos $(wc -l < "$PKG/arquivos-novos.txt"))"
echo "diretorio: $PKG"
