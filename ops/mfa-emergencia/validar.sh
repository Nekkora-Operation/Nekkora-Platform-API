#!/usr/bin/env bash
# Runner reproduzivel da validacao da recuperacao emergencial de MFA (016, candidata).
#
#   1. suite de banco db/tests/mfa-emergencia-016 (instalacao limpa, catalogo x matriz, invariantes anteriores,
#      atualizacao populada 015 -> 016, restauracao logica);
#   2. regressao Maven completa (core, ops, platform, portal) - inclui os grupos do contrato §10 em nekkora-ops, as
#      contraprovas de mutacao e o fluxo completo pela API (EmergencyRecoveryFlowIT);
#   3. contraprovas de mutacao (12, detectadas);
#   4. evidencia SANITIZADA em ops/mfa-emergencia/saidas/<instante>/ e varredura dela (varredura.py: formato de
#      token da v1, PEM, senhas), que precisa sair vazia, com controle positivo (tokens do gerador de producao).
#   resumo.txt sempre gerado, com o codigo de saida de cada etapa (etapas.txt).
#
# Tudo descartavel: containers de teste (Testcontainers e os da suite) com nomes aleatorios ou proprios, removidos ao
# final; nenhuma porta fixa, nenhum volume nomeado; nunca a stack principal nem o hml03. Uso: ops/mfa-emergencia/validar.sh
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)"
mkdir -p "$SAIDA"
falha() { echo "ERRO: $*" >&2; exit 1; }

{
    echo "data_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit=$(cd "$REPO" && git rev-parse HEAD) branch=$(cd "$REPO" && git branch --show-current)"
    echo "contrato=docs/relatorios/2026-09-27-contrato-recuperacao-emergencial-mfa.md sha256=$(cd "$REPO" && git show HEAD:docs/relatorios/2026-09-27-contrato-recuperacao-emergencial-mfa.md | sha256sum | cut -c1-64)"
    echo "migration_016_sha256=$(sha256sum "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql" | cut -c1-64)"
    echo "git_status_porcelain:"; (cd "$REPO" && git status --porcelain) | sed 's/^/  /'
    docker version --format 'docker={{.Server.Version}}'
    java -version 2>&1 | head -1
} > "$SAIDA/ambiente.txt"

# Cada etapa roda e tem o codigo de saida registrado em etapas.txt; o resumo.txt e sempre gerado, e o runner sai
# com erro se qualquer etapa falhar. Nenhuma etapa e "aprovada" sem codigo 0.
: > "$SAIDA/etapas.txt"
etapa() { echo "etapa=$1 codigo_saida=$2${3:+ detalhe=$3}" >> "$SAIDA/etapas.txt"; }

# Fotografia dos containers que NAO sao desta validacao (stack principal nekkora-postgres, hml03, demais): id, criacao,
# estado e inicio. Tem de ser identica antes e depois (etapa 5).
fotografar() {
    docker ps -a --format '{{.Names}}|{{.Label "org.testcontainers"}}' | grep -v '|true$' | cut -d'|' -f1 | grep -vE '^nekkora-mfa016-' | sort | while read -r c; do
        docker inspect "$c" --format '{{.Name}}|{{.Id}}|{{.Created}}|{{.State.Status}}|{{.State.StartedAt}}'
    done
}
fotografar > "$SAIDA/containers_antes.txt"

echo ">> 1/4 suite de banco da 016"
rc=0; bash "$REPO/db/tests/mfa-emergencia-016/run.sh" > "$SAIDA/suite_banco.txt" 2>&1 || rc=$?
if [ "$rc" = 0 ]; then
    ULTIMA="$(ls -td "$REPO"/db/tests/mfa-emergencia-016/saidas/*/ | head -1)"
    cp "$ULTIMA/resumo.txt" "$SAIDA/suite_banco_resumo.txt"
    cp "$ULTIMA/A01_diff_resumo.txt" "$ULTIMA/A03_listagem_ops.txt" "$ULTIMA/B08_diff_catalogo_limpa.txt" "$ULTIMA/B06_diff_dados.txt" "$SAIDA/"
    cp "$ULTIMA/A01_diff_catalogo.txt" "$SAIDA/suite_banco_diff_catalogo_015_016.txt"
else
    tail -20 "$SAIDA/suite_banco.txt" > "$SAIDA/suite_banco_resumo.txt"
fi
etapa 1_suite_banco "$rc"

echo ">> 2/4 regressao Maven (pode levar alguns minutos)"
rc=0; (cd "$REPO/api" && ./mvnw -B clean install) > "$SAIDA/maven.txt" 2>&1 || rc=$?
grep -E "Tests run: .* -- in com\.nekkora" "$SAIDA/maven.txt" | sed -E 's/^\[(INFO|WARNING|ERROR)\] //' > "$SAIDA/testes_por_classe.txt" || true
grep -E "^\[(INFO|WARNING|ERROR)\] Tests run: [0-9]+, Failures" "$SAIDA/maven.txt" | grep -v " -- in " > "$SAIDA/testes_por_modulo.txt" || true
grep -E "Reactor Summary|SUCCESS \[|FAILURE \[|BUILD" "$SAIDA/maven.txt" > "$SAIDA/maven_reator.txt" || true
for rel in "$REPO"/api/nekkora-ops/target/surefire-reports/*.txt "$REPO"/api/nekkora-platform/target/surefire-reports/*.txt; do
    case "$rel" in *-output.txt) continue;; esac
    [ -f "$rel" ] && cp "$rel" "$SAIDA/surefire_$(basename "$rel")"
done
# lista por metodo (classe.metodo resultado), tirada dos XML do surefire de todos os modulos
PYM=python3; "$PYM" -c 1 >/dev/null 2>&1 || PYM=python
"$PYM" - "$REPO/api" > "$SAIDA/testes_por_metodo.txt" <<'PYX' || true
import glob, os, sys, xml.etree.ElementTree as ET
for f in sorted(glob.glob(os.path.join(sys.argv[1], '*', 'target', 'surefire-reports', 'TEST-*.xml'))):
    for tc in ET.parse(f).getroot().iter('testcase'):
        st = 'OK'
        for k in ('failure', 'error', 'skipped'):
            if tc.find(k) is not None:
                st = k.upper()
        print(f"{st}	{tc.get('classname')}.{tc.get('name')}")
PYX
det=""
[ "$rc" = 0 ] && ! grep -q "BUILD SUCCESS" "$SAIDA/maven.txt" && { rc=90; det="sem_BUILD_SUCCESS"; }
if [ "$rc" = 0 ] && grep -qE "Failures: [1-9]|Errors: [1-9]|Skipped: [1-9]" "$SAIDA/testes_por_classe.txt"; then rc=91; det="teste_com_falha_ou_pulado"; fi
if [ "$rc" = 0 ] && grep -qvE "^OK	" "$SAIDA/testes_por_metodo.txt"; then rc=92; det="metodo_nao_OK"; fi
[ "$rc" = 0 ] && det="metodos_OK=$(grep -c . "$SAIDA/testes_por_metodo.txt")"
etapa 2_regressao_maven "$rc" "$det"

echo ">> 3/4 contraprovas de mutacao"
rc=0
grep -h "\[mutacao\]" "$REPO"/api/nekkora-ops/target/surefire-reports/com.nekkora.ops.MutacoesIT-output.txt > "$SAIDA/mutacoes.txt" 2>/dev/null || rc=1
[ "$rc" = 0 ] && [ "$(wc -l < "$SAIDA/mutacoes.txt")" != 12 ] && rc=2
etapa 3_mutacoes "$rc" "detectadas=$(wc -l < "$SAIDA/mutacoes.txt" 2>/dev/null || echo 0)_de_12"

echo ">> 4/4 varredura da evidencia e controle positivo"
PY=python3; "$PY" -c 1 >/dev/null 2>&1 || PY=python
rc=0; "$PY" "$AQUI/varredura.py" varrer "$SAIDA" "$REPO" > "$SAIDA/varredura.txt" 2>&1 || rc=$?
etapa 4a_varredura "$rc"
# controle positivo: tokens gerados pela classe de producao, so num temporario fora da evidencia
TMPC="$(mktemp -d)"; trap 'rm -rf "$TMPC"' EXIT
JAR="$(ls "$REPO"/api/nekkora-core/target/nekkora-core-*.jar 2>/dev/null | grep -v -- '-tests' | head -1)"
rc=0
if [ -n "$JAR" ] && java -cp "$JAR" "$AQUI/GerarTokensControle.java" 200 > "$TMPC/tokens.txt" 2>/dev/null; then
    "$PY" "$AQUI/varredura.py" controle "$SAIDA" "$REPO" "$TMPC/tokens.txt" > "$SAIDA/varredura_controle_positivo.txt" 2>&1 || rc=$?
else
    rc=3; echo "gerador de tokens indisponivel" > "$SAIDA/varredura_controle_positivo.txt"
fi
etapa 4b_controle_positivo "$rc"

echo ">> 5 preservacao: base principal, hml03 e migrations 001-015"
fotografar > "$SAIDA/containers_depois.txt"
rc=0
diff "$SAIDA/containers_antes.txt" "$SAIDA/containers_depois.txt" > "$SAIDA/containers_diff.txt" || rc=1
{
    echo "containers fora da validacao (antes = depois): $([ "$rc" = 0 ] && echo identicos || echo DIVERGENTES)"
    grep -E '^/(nekkora-postgres|nekkora-homologacao-hml03-)' "$SAIDA/containers_depois.txt" | cut -d'|' -f1,3,4,5
    for ref in ca4033b origin/main; do
        n="$(cd "$REPO" && git diff --name-only "$ref" -- $(cd "$REPO" && ls db/migrations/0*.sql | grep -vE '/01[6-9]_') | wc -l)"
        echo "migrations 001-015 diferentes de $ref: $n"
        [ "$n" = 0 ] || rc=2
    done
} > "$SAIDA/preservacao.txt"
etapa 5_preservacao "$rc"

FALHAS="$(grep -vc ' codigo_saida=0' "$SAIDA/etapas.txt" || true)"
{
    echo "validacao 016 - $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "resultado=$([ "$FALHAS" = 0 ] && echo APROVADO || echo REPROVADO) (etapas com codigo diferente de 0: $FALHAS)"
    echo "--- codigos de saida por etapa"
    cat "$SAIDA/etapas.txt"
    echo "--- suite de banco"
    cat "$SAIDA/suite_banco_resumo.txt"
    echo "--- maven: $(grep -c . "$SAIDA/testes_por_classe.txt") classes"
    cat "$SAIDA/testes_por_modulo.txt"
    echo "--- mutacoes"
    cat "$SAIDA/mutacoes.txt"
    echo "--- varredura"
    cat "$SAIDA/varredura.txt"
    echo "--- controle positivo da varredura"
    cat "$SAIDA/varredura_controle_positivo.txt"
    echo "--- preservacao"
    cat "$SAIDA/preservacao.txt"
} > "$SAIDA/resumo.txt"
cat "$SAIDA/resumo.txt"
echo "evidencia: $SAIDA"
[ "$FALHAS" = 0 ] || falha "$FALHAS etapa(s) com codigo diferente de 0 (ver etapas.txt)"
