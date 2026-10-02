# Entradas das imagens da homologacao e guarda de fontes (achados G1/G1b; proposta D-ELEV/P4, sec. 3.1) - usado por
# homologacao.sh (sourced) e por testes/guarda_fontes_imagens.sh. So le o Git: nao cria recurso nem altera o repositorio.
#
# FONTES_IMAGENS e a UNICA lista das entradas que determinam o conteudo das tres imagens (platform, portal, operacao):
#   - platform/portal: "./mvnw package" do reator inteiro + api/e2e/Dockerfile (ARG JAR);
#   - operacao: jar e dependencias da nekkora-ops + classes de api/nekkora-ops/src/test (FerramentaHomologacao, IdpSimulado,
#     Pki; o test-compile compila o diretorio inteiro) + api/e2e/Dockerfile.operacao;
#   - wrapper Maven (api/mvnw, api/.mvn), que escolhe a versao do Maven.
# A mesma lista vale para a guarda (alteracao, stage, arquivo novo) e para a identificacao (HOMOLOG_COMMIT). Caminhos
# EXPLICITOS, sem glob: nao dependem do diretorio corrente nem da semantica de "*" do pathspec do Git. Os modulos
# daqui tem de ser exatamente os <module> de api/pom.xml (conferido pela guarda). Fora da lista, de proposito:
# api/nekkora-platform/src/test (compilado para as ferramentas do ensaio, nao entra em imagem) e o repositorio Maven
# local (~/.m2), que nao e do Git.
FONTES_IMAGENS_MODULOS="nekkora-core nekkora-ops nekkora-platform nekkora-portal"
FONTES_IMAGENS="api/pom.xml api/mvnw api/.mvn api/e2e/Dockerfile api/e2e/Dockerfile.operacao api/nekkora-ops/src/test"
for _m in $FONTES_IMAGENS_MODULOS; do FONTES_IMAGENS="$FONTES_IMAGENS api/$_m/pom.xml api/$_m/src/main"; done
unset _m

# guarda_fontes_imagens <repo>: codigo 0 so se TODAS as entradas estao exatamente no HEAD. Imprime cada motivo de recusa.
# Recusa: alteracao na arvore (inclui arquivo removido), alteracao em stage (mesmo com a arvore igual ao HEAD),
# arquivo nao rastreado (inclusive os ignorados pelo .gitignore, que o Maven empacotaria), arquivo marcado
# assume-unchanged/skip-worktree (esconderia a alteracao do git diff) e lista de modulos divergente de api/pom.xml.
guarda_fontes_imagens() {
    local repo="$1" n=0 x
    # shellcheck disable=SC2086
    {
        x="$(grep -oE '<module>[^<]+</module>' "$repo/api/pom.xml" 2>/dev/null | sed -E 's#</?module>##g' | sort | tr '\n' ' ')"
        [ "$x" = "$(printf '%s\n' $FONTES_IMAGENS_MODULOS | sort | tr '\n' ' ')" ] \
            || { echo "RECUSA: modulos de api/pom.xml ($x) diferentes de FONTES_IMAGENS_MODULOS ($FONTES_IMAGENS_MODULOS)"; n=$((n + 1)); }
        x="$(git -C "$repo" -c core.safecrlf=false diff --name-status HEAD -- $FONTES_IMAGENS)" || { echo "RECUSA: git diff falhou"; return 2; }
        [ -z "$x" ] || { echo "RECUSA: alteracao nao commitada na arvore:"; echo "$x" | sed 's/^/  /'; n=$((n + 1)); }
        x="$(git -C "$repo" -c core.safecrlf=false diff --cached --name-status HEAD -- $FONTES_IMAGENS)" || { echo "RECUSA: git diff --cached falhou"; return 2; }
        [ -z "$x" ] || { echo "RECUSA: alteracao em stage (nao commitada):"; echo "$x" | sed 's/^/  /'; n=$((n + 1)); }
        x="$(git -C "$repo" ls-files --others -- $FONTES_IMAGENS)" || { echo "RECUSA: git ls-files falhou"; return 2; }
        [ -z "$x" ] || { echo "RECUSA: arquivo nao rastreado (inclusive ignorado):"; echo "$x" | sed 's/^/  /'; n=$((n + 1)); }
        x="$(git -C "$repo" ls-files -v -- $FONTES_IMAGENS | grep -E '^([a-z]|S) ' || true)"
        [ -z "$x" ] || { echo "RECUSA: arquivo assume-unchanged/skip-worktree:"; echo "$x" | sed 's/^/  /'; n=$((n + 1)); }
    }
    [ "$n" = 0 ]
}

# commit_fontes_imagens <repo>: hash curto do ultimo commit que tocou qualquer entrada de FONTES_IMAGENS (a tag das
# tres imagens). So tem valor depois de guarda_fontes_imagens passar.
commit_fontes_imagens() {
    # shellcheck disable=SC2086
    git -C "$1" log -1 --format=%h HEAD -- $FONTES_IMAGENS
}

# registro_fontes_imagens <repo> <commit>: o que identifica o conteudo das imagens, para a evidencia (fontes_imagens.txt).
registro_fontes_imagens() {
    echo "HOMOLOG_COMMIT=$2 ($(git -C "$1" rev-parse "$2"))"
    echo "HEAD=$(git -C "$1" rev-parse HEAD)"
    echo "arvore_api=$(git -C "$1" rev-parse "$2:api")"
    echo "FONTES_IMAGENS=$FONTES_IMAGENS"
}
