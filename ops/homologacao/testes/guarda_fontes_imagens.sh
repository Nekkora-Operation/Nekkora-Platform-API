#!/usr/bin/env bash
# Testes da guarda e da identificacao das imagens (lib/fontes_imagens.sh; achados G1/G1b). SO um repositorio Git
# TEMPORARIO com a estrutura da api/: sem Docker, sem ambiente de homologacao, sem banco e sem tocar o repositorio real
# (que so e LIDO nas conferencias de cobertura da secao 5). Uso: bash ops/homologacao/testes/guarda_fontes_imagens.sh
set -uo pipefail
AQUI="${AQUI:-$(cd "$(dirname "$0")/.." && pwd)}"
REPO_REAL="$(cd "$AQUI/../.." && pwd)"
. "$AQUI/lib/fontes_imagens.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
R="$T/repo"
falhas=0; casos=0
ok() { echo "OK    $*"; casos=$((casos + 1)); }
nok() { echo "FALHA $*"; casos=$((casos + 1)); falhas=$((falhas + 1)); }
g() { git -C "$R" -c core.safecrlf=false "$@"; }
# commit_indice: grava SO o stage (sem add -A), para montar estados com arquivo fora do indice
commit_indice() { g -c user.name=teste -c user.email=teste@exemplo.invalid commit -q -m "$1"; }
commit() { g add -A && commit_indice "$1"; }
curto() { g rev-parse --short HEAD; }
passa() { guarda_fontes_imagens "$R" > "$T/saida.txt" 2>&1; }
# recusa <caso> <motivo esperado na saida>
recusa() { if passa; then nok "$1: guarda PASSOU (esperada recusa)"; elif grep -q "$2" "$T/saida.txt"; then ok "$1"; else nok "$1: recusou sem o motivo '$2': $(tr '\n' ' ' < "$T/saida.txt")"; fi; }
aceita() { if passa; then ok "$1"; else nok "$1: guarda RECUSOU: $(tr '\n' ' ' < "$T/saida.txt")"; fi; }
# restaura o repositorio temporario exatamente no HEAD (arvore, stage, nao rastreados e ignorados, marcas do indice)
# (as duas marcas em chamadas separadas: numa so, o git aplica apenas a ultima opcao)
limpa() { g update-index -q --no-assume-unchanged $(g ls-files); g update-index -q --no-skip-worktree $(g ls-files); g reset -q --hard HEAD; g clean -q -fdx; }

echo "== 0. repositorio temporario com a estrutura da api/"
git init -q "$R"
g config core.autocrlf false
mods=""; for m in $FONTES_IMAGENS_MODULOS; do mods="$mods<module>$m</module>"; done
mkdir -p "$R/api/.mvn/wrapper" "$R/api/e2e" "$R/api/nekkora-ops/src/test/java/t" "$R/api/nekkora-ops/src/test/resources" \
         "$R/api/nekkora-platform/src/test/java/t" "$R/docs"
echo "<project><modules>$mods</modules></project>" > "$R/api/pom.xml"
echo "#!/bin/sh" > "$R/api/mvnw"
echo "distributionUrl=x" > "$R/api/.mvn/wrapper/maven-wrapper.properties"
echo "FROM x" > "$R/api/e2e/Dockerfile"
echo "FROM y" > "$R/api/e2e/Dockerfile.operacao"
for m in $FONTES_IMAGENS_MODULOS; do
    mkdir -p "$R/api/$m/src/main/java/t" "$R/api/$m/src/main/resources"
    echo "<project/>" > "$R/api/$m/pom.xml"
    echo "class A {}" > "$R/api/$m/src/main/java/t/A.java"
    echo "a: 1" > "$R/api/$m/src/main/resources/application.yml"
done
echo "class F {}" > "$R/api/nekkora-ops/src/test/java/t/FerramentaHomologacao.java"
echo "x" > "$R/api/nekkora-ops/src/test/resources/shim.sh"
echo "class T {}" > "$R/api/nekkora-platform/src/test/java/t/T.java"
echo "doc" > "$R/docs/leia.md"
printf 'target/\n*.local\n' > "$R/.gitignore"
commit base
# um arquivo representativo de CADA entrada de FONTES_IMAGENS (diretorio -> um arquivo dentro dele)
REPRES="api/pom.xml api/mvnw api/.mvn/wrapper/maven-wrapper.properties api/e2e/Dockerfile api/e2e/Dockerfile.operacao
        api/nekkora-ops/src/test/java/t/FerramentaHomologacao.java"
for m in $FONTES_IMAGENS_MODULOS; do REPRES="$REPRES api/$m/pom.xml api/$m/src/main/java/t/A.java"; done
# conferencia do proprio teste: todo item da lista tem um representante, e so eles
n_lista=$(echo $FONTES_IMAGENS | wc -w); n_rep=$(echo $REPRES | wc -w)
[ "$n_lista" = "$n_rep" ] && ok "um representante para cada uma das $n_lista entradas de FONTES_IMAGENS" || nok "representantes ($n_rep) != entradas ($n_lista)"
for f in $REPRES; do
    dentro=0; for e in $FONTES_IMAGENS; do case "$f" in "$e"|"$e"/*) dentro=1;; esac; done
    [ $dentro = 1 ] || nok "representante $f fora de FONTES_IMAGENS"
done

echo "== 1. fontes commitadas passam; identificacao = ultimo commit das entradas"
aceita "arvore limpa passa"
[ "$(commit_fontes_imagens "$R")" = "$(curto)" ] && ok "HOMOLOG_COMMIT = commit base" || nok "HOMOLOG_COMMIT != commit base"
(cd / && guarda_fontes_imagens "$R" > /dev/null 2>&1) && ok "guarda independe do diretorio corrente (sem glob)" || nok "guarda depende do diretorio corrente"

echo "== 2. recusas (cada caso em arvore restaurada ao HEAD)"
for f in $REPRES; do
    limpa; echo "// alterado" >> "$R/$f"
    recusa "alterado na arvore: $f" "alteracao nao commitada na arvore"
done
for f in $REPRES; do
    limpa; rm "$R/$f"
    recusa "excluido da arvore: $f" "alteracao nao commitada na arvore"
done
limpa; g rm -q api/nekkora-platform/src/main/java/t/A.java
recusa "exclusao em stage (git rm)" "alteracao em stage"
limpa; echo "// alterado" >> "$R/api/nekkora-core/src/main/java/t/A.java"; g add api/nekkora-core/src/main/java/t/A.java
recusa "alterado em stage (arvore = stage)" "alteracao em stage"
limpa; echo "// so no stage" >> "$R/api/e2e/Dockerfile.operacao"; g add api/e2e/Dockerfile.operacao; g show HEAD:api/e2e/Dockerfile.operacao > "$R/api/e2e/Dockerfile.operacao"
if passa; then nok "alteracao SO no stage (arvore = HEAD): guarda PASSOU"; elif grep -q "alteracao em stage" "$T/saida.txt" && ! grep -q "alteracao nao commitada na arvore" "$T/saida.txt"; then ok "alteracao SO no stage (arvore = HEAD) detectada pelo stage"; else nok "alteracao SO no stage: motivo inesperado: $(tr '\n' ' ' < "$T/saida.txt")"; fi
limpa; echo "class N {}" > "$R/api/nekkora-portal/src/main/java/t/N.java"; g add api/nekkora-portal/src/main/java/t/N.java
recusa "arquivo novo em stage" "alteracao em stage"
for d in api/.mvn api/nekkora-ops/src/test/java/t api/nekkora-ops/src/test/resources $(for m in $FONTES_IMAGENS_MODULOS; do echo "api/$m/src/main/java/t api/$m/src/main/resources"; done); do
    limpa; echo "novo" > "$R/$d/Novo.java"
    recusa "novo nao rastreado: $d/Novo.java" "arquivo nao rastreado"
done
limpa; echo "segredo=1" > "$R/api/nekkora-platform/src/main/resources/app.local"
recusa "novo IGNORADO pelo .gitignore em src/main (o Maven empacotaria)" "arquivo nao rastreado"
# Dockerfile.operacao nunca commitado (a situacao real antes do snapshot)
limpa; g rm -q --cached api/e2e/Dockerfile.operacao; commit_indice "sem Dockerfile.operacao"
recusa "Dockerfile.operacao nao rastreado" "arquivo nao rastreado"
g add api/e2e/Dockerfile.operacao; commit_indice "com Dockerfile.operacao"
g cat-file -e HEAD:api/e2e/Dockerfile.operacao && ok "Dockerfile.operacao de volta ao HEAD" || nok "Dockerfile.operacao fora do HEAD"
limpa; echo "// escondido" >> "$R/api/nekkora-platform/src/main/java/t/A.java"; g update-index --assume-unchanged api/nekkora-platform/src/main/java/t/A.java
recusa "alteracao escondida por assume-unchanged" "assume-unchanged/skip-worktree"
limpa; echo "// escondido" >> "$R/api/nekkora-ops/src/test/java/t/FerramentaHomologacao.java"; g update-index --skip-worktree api/nekkora-ops/src/test/java/t/FerramentaHomologacao.java
recusa "alteracao escondida por skip-worktree" "assume-unchanged/skip-worktree"
limpa; sed -i 's#</modules>#<module>nekkora-novo</module></modules>#' "$R/api/pom.xml"; commit "modulo novo"
recusa "modulo novo em api/pom.xml fora de FONTES_IMAGENS_MODULOS" "modulos de api/pom.xml"
limpa; sed -i 's#<module>nekkora-novo</module>##' "$R/api/pom.xml"; commit "remove modulo novo"
aceita "modulos de volta ao conjunto da lista"

echo "== 3. fora de FONTES_IMAGENS nao bloqueia nem muda a identificacao"
limpa; antes="$(commit_fontes_imagens "$R")"
echo "// teste" >> "$R/api/nekkora-platform/src/test/java/t/T.java"; echo "mais" >> "$R/docs/leia.md"; echo "novo" > "$R/docs/novo.md"
aceita "alteracao e arquivo novo so fora da lista (platform/src/test, docs) passam"
commit "fora da lista"
[ "$(commit_fontes_imagens "$R")" = "$antes" ] && ok "commit so fora da lista nao muda HOMOLOG_COMMIT" || nok "commit fora da lista mudou HOMOLOG_COMMIT"

echo "== 4. mudanca commitada em CADA entrada atualiza a identificacao"
for f in $REPRES; do
    limpa; antes="$(commit_fontes_imagens "$R")"
    echo "// mudanca" >> "$R/$f"; commit "muda $f"
    if passa && [ "$(commit_fontes_imagens "$R")" = "$(curto)" ] && [ "$(curto)" != "$antes" ]; then ok "commit em $f: passa e HOMOLOG_COMMIT = novo commit"; else nok "commit em $f: identificacao nao atualizada"; fi
done
limpa; antes="$(commit_fontes_imagens "$R")"
echo "novo" > "$R/api/nekkora-ops/src/test/resources/novo.txt"; commit "arquivo novo commitado"
[ "$(commit_fontes_imagens "$R")" = "$(curto)" ] && [ "$(curto)" != "$antes" ] && ok "arquivo novo commitado em nekkora-ops/src/test atualiza HOMOLOG_COMMIT" || nok "arquivo novo commitado nao atualiza"
g rm -q api/nekkora-portal/src/main/resources/application.yml; commit "exclusao commitada"
[ "$(commit_fontes_imagens "$R")" = "$(curto)" ] && ok "exclusao commitada em src/main atualiza HOMOLOG_COMMIT" || nok "exclusao commitada nao atualiza"
registro_fontes_imagens "$R" "$(commit_fontes_imagens "$R")" | grep -q "^arvore_api=[0-9a-f]\{40\}$" && ok "registro traz a arvore api/ do commit" || nok "registro sem arvore api/"

echo "== 5. cobertura contra os arquivos do build (leitura ESTRITA; LF/CRLF normalizado so em copia temporaria)"
# le <arquivo> <destino>: copia o conteudo sem \r para <destino> (o original nao e alterado). Falha EXPLICITA se o
# arquivo nao existe (ou nao e arquivo regular), se a leitura falha ou se o conteudo fica vazio. Nunca devolve
# lista vazia como se fosse sucesso.
le() {
    [ -f "$1" ] || { echo "LEITURA_FALHA ausente ou nao e arquivo: $1"; return 1; }
    tr -d '\r' < "$1" > "$2" 2>/dev/null || { echo "LEITURA_FALHA erro de leitura: $1"; return 1; }
    [ -s "$2" ] || { echo "LEITURA_FALHA conteudo vazio: $1"; return 1; }
}
# cobertura <raiz> <dir_homologacao> <tmp>: confere que TODA entrada dos builds esta coberta por FONTES_IMAGENS.
# Imprime COB_OK/COB_FALHA por verificacao; codigo 0 so sem nenhuma falha E com cada extracao nao vazia.
cobertura() {
    local raiz="$1" dh="$2" w="$3" n=0 f d o m x linha df="" ctx="" jars="" copias=0 fontes=0
    mkdir -p "$w"
    falhou() { echo "COB_FALHA $*"; n=$((n + 1)); }
    # modulos do reator
    if le "$raiz/api/pom.xml" "$w/pom.xml"; then
        x="$(grep -oE '<module>[^<]+</module>' "$w/pom.xml" | sed -E 's#</?module>##g' | sort | tr '\n' ' ')"
        if [ -z "$x" ]; then falhou "nenhum <module> extraido de api/pom.xml"
        elif [ "$x" = "$(printf '%s\n' $FONTES_IMAGENS_MODULOS | sort | tr '\n' ' ')" ]; then echo "COB_OK modulos de api/pom.xml = FONTES_IMAGENS_MODULOS ($x)"
        else falhou "modulos de api/pom.xml ($x) != FONTES_IMAGENS_MODULOS"; fi
    else n=$((n + 1)); fi
    # arquivos compose: o base e o da 016 sao obrigatorios (juntos constroem as tres imagens)
    for f in compose.homolog.yml compose.016.yml; do [ -f "$dh/$f" ] || falhou "compose obrigatorio ausente: $dh/$f"; done
    for f in "$dh"/compose*.yml; do
        [ -e "$f" ] || { falhou "nenhum compose*.yml em $dh"; continue; }
        le "$f" "$w/c.yml" || { n=$((n + 1)); continue; }
        df="$df $(grep -E '^[[:space:]]+dockerfile:' "$w/c.yml" | awk '{print "api/"$2}')"
        ctx="$ctx $(grep -E '^[[:space:]]+context:' "$w/c.yml" | awk '{print $2}')"
        jars="$jars $(grep -E '^[[:space:]]+JAR:' "$w/c.yml" | awk '{print $2}')"
    done
    df="$(echo $df | tr ' ' '\n' | sort -u | tr '\n' ' ')"; ctx="$(echo $ctx | tr ' ' '\n' | sort -u | tr '\n' ' ')"
    [ -n "${df// /}" ] || falhou "extracao vazia: nenhum 'dockerfile:' nos compose"
    [ -n "${jars// /}" ] || falhou "extracao vazia: nenhum 'JAR:' nos compose"
    case " $df " in *" api/e2e/Dockerfile "*) ;; *) falhou "Dockerfile esperado ausente da extracao: api/e2e/Dockerfile (obtido: '$df')";; esac
    case " $df " in *" api/e2e/Dockerfile.operacao "*) ;; *) falhou "Dockerfile esperado ausente da extracao: api/e2e/Dockerfile.operacao (obtido: '$df')";; esac
    [ "$ctx" = "../../api " ] && echo "COB_OK contexto de build unico: api/" || falhou "contexto de build inesperado: '$ctx'"
    for d in $df; do case " $FONTES_IMAGENS " in *" $d "*) ;; *) falhou "Dockerfile fora de FONTES_IMAGENS: $d";; esac; done
    for m in $jars; do case " $FONTES_IMAGENS_MODULOS " in *" ${m%%/*} "*) echo "COB_OK JAR $m de modulo coberto";; *) falhou "JAR de modulo nao coberto: $m";; esac; done
    # cada COPY de cada Dockerfile: pelo menos um COPY por Dockerfile e pelo menos uma origem por COPY
    for d in $df; do
        le "$raiz/$d" "$w/Dockerfile" || { n=$((n + 1)); continue; }
        grep -E '^COPY[[:space:]]' "$w/Dockerfile" | sed -E 's/--chown=[^ ]+//g' > "$w/copias.txt"
        [ -s "$w/copias.txt" ] || { falhou "$d sem nenhuma instrucao COPY"; continue; }
        while read -r linha; do
            copias=$((copias + 1))
            # shellcheck disable=SC2086
            set -f; set -- $linha; set +f; shift
            [ $# -ge 2 ] || { falhou "$d: COPY sem origem e destino: '$linha'"; continue; }
            while [ $# -gt 1 ]; do
                o="$1"; shift; fontes=$((fontes + 1))
                case "$o" in
                    '${JAR}') ;;
                    nekkora-ops/target/test-classes/*) case " $FONTES_IMAGENS " in *" api/nekkora-ops/src/test "*) ;; *) falhou "$d: origem $o sem api/nekkora-ops/src/test na lista";; esac;;
                    */target/*) m="${o%%/*}"; case " $FONTES_IMAGENS_MODULOS " in *" $m "*) ;; *) falhou "$d: origem $o de modulo nao coberto";; esac;;
                    *) falhou "$d: origem $o fora da cobertura (so artefato target/ de modulo coberto ou \${JAR})";;
                esac
            done
        done < "$w/copias.txt"
    done
    { [ "$copias" -gt 0 ] && [ "$fontes" -gt 0 ]; } || falhou "extracao vazia: $copias COPY, $fontes origem(ns)"
    [ "$n" = 0 ] && echo "COB_OK $copias COPY e $fontes origem(ns) conferidos em: $df"
    [ "$n" = 0 ]
}
# avalia_cob <caso> <passa|falha> <motivo esperado na falha (regex)> <raiz> <dir_homologacao>
avalia_cob() {
    local s="$T/cob_saida.txt" rc
    rm -rf "$T/cob_w"; cobertura "$4" "$5" "$T/cob_w" > "$s" 2>&1; rc=$?
    if [ "$2" = passa ]; then
        [ $rc = 0 ] && ok "$1" || nok "$1: cobertura FALHOU: $(grep -E 'FALHA' "$s" | tr '\n' ' ')"
    elif [ $rc != 0 ] && grep -qE -- "$3" "$s"; then ok "$1"
    else nok "$1: esperada falha com '$3' (rc=$rc): $(tr '\n' ' ' < "$s")"; fi
}

echo "-- 5a. contraprovas em fixtures sinteticas (nao usam o repositorio real)"
F="$T/fix"
# fixture <lf|crlf>: arvore minima que imita a real (pom, dois compose, dois Dockerfiles)
fixture() {
    local f mods=""
    rm -rf "$F"; mkdir -p "$F/api/e2e" "$F/ops/homologacao"
    for f in $FONTES_IMAGENS_MODULOS; do mods="$mods    <module>$f</module>\n"; done
    printf "<project>\n  <modules>\n$mods  </modules>\n</project>\n" > "$F/api/pom.xml"
    printf 'services:\n  platform:\n    build:\n      context: ../../api\n      dockerfile: e2e/Dockerfile\n      args:\n        JAR: nekkora-platform/target/p.jar\n  portal:\n    build:\n      context: ../../api\n      dockerfile: e2e/Dockerfile\n      args:\n        JAR: nekkora-portal/target/q.jar\n' > "$F/ops/homologacao/compose.homolog.yml"
    printf 'services:\n  operacao:\n    build:\n      context: ../../api\n      dockerfile: e2e/Dockerfile.operacao\n' > "$F/ops/homologacao/compose.016.yml"
    printf 'FROM x\nARG JAR\nCOPY --chown=root:root ${JAR} /app/app.jar\n' > "$F/api/e2e/Dockerfile"
    printf 'FROM y\nCOPY --chown=root:root nekkora-ops/target/o.jar /app/lib/\nCOPY nekkora-ops/target/test-classes/com/F*.class nekkora-ops/target/test-classes/com/P*.class /app/classes/\n' > "$F/api/e2e/Dockerfile.operacao"
    if [ "${1:-lf}" = crlf ]; then for f in "$F/api/pom.xml" "$F"/ops/homologacao/*.yml "$F"/api/e2e/Dockerfile*; do sed -i 's/$/\r/' "$f"; done; fi
}
bytes_cr() { od -An -c "$1" | grep -o '\\r' | wc -l; }
fixture lf;   avalia_cob "fixture LF passa (controle positivo da fixture)" passa "" "$F" "$F/ops/homologacao"
fixture crlf
antes="$(cat "$F/api/pom.xml" "$F"/ops/homologacao/*.yml "$F"/api/e2e/Dockerfile* | sha256sum)"
[ "$(bytes_cr "$F/ops/homologacao/compose.homolog.yml")" -gt 0 ] && [ "$(bytes_cr "$F/api/e2e/Dockerfile.operacao")" -gt 0 ] && ok "fixture CRLF tem \\r nos bytes (od)" || nok "fixture CRLF sem \\r"
avalia_cob "fixture CRLF passa (normalizacao LF/CRLF)" passa "" "$F" "$F/ops/homologacao"
[ "$(cat "$F/api/pom.xml" "$F"/ops/homologacao/*.yml "$F"/api/e2e/Dockerfile* | sha256sum)" = "$antes" ] && ok "arquivos CRLF conferidos nao foram alterados (sha256 antes = depois)" || nok "a conferencia alterou os arquivos"
le "$F/ops/homologacao/compose.homolog.yml" "$T/n.yml" > /dev/null && [ "$(bytes_cr "$T/n.yml")" = 0 ] && ok "copia normalizada sem nenhum \\r (od)" || nok "copia normalizada ainda tem \\r"
fixture lf; rm "$F/api/e2e/Dockerfile.operacao"
avalia_cob "contraprova: Dockerfile.operacao ausente" falha "LEITURA_FALHA ausente ou nao e arquivo: .*Dockerfile\.operacao" "$F" "$F/ops/homologacao"
fixture lf; rm "$F/api/e2e/Dockerfile"; mkdir "$F/api/e2e/Dockerfile"
avalia_cob "contraprova: Dockerfile ilegivel (diretorio no lugar do arquivo)" falha "LEITURA_FALHA ausente ou nao e arquivo: .*e2e/Dockerfile$" "$F" "$F/ops/homologacao"
fixture lf; : > "$F/api/e2e/Dockerfile"
avalia_cob "contraprova: Dockerfile vazio" falha "LEITURA_FALHA conteudo vazio" "$F" "$F/ops/homologacao"
fixture lf; printf 'FROM y\nRUN true\n' > "$F/api/e2e/Dockerfile.operacao"
avalia_cob "contraprova: Dockerfile sem os COPY esperados" falha "sem nenhuma instrucao COPY" "$F" "$F/ops/homologacao"
fixture lf; echo 'COPY outro-modulo/target/x.jar /app/lib/' >> "$F/api/e2e/Dockerfile.operacao"
avalia_cob "contraprova: COPY de modulo fora da lista" falha "de modulo nao coberto" "$F" "$F/ops/homologacao"
fixture lf; echo 'COPY nekkora-platform/src/test/java/X.java /app/' >> "$F/api/e2e/Dockerfile.operacao"
avalia_cob "contraprova: COPY de fonte (fora de target/) fora da cobertura" falha "fora da cobertura" "$F" "$F/ops/homologacao"
fixture lf; rm "$F/api/pom.xml"
avalia_cob "contraprova: api/pom.xml ausente" falha "LEITURA_FALHA ausente ou nao e arquivo: .*api/pom\.xml" "$F" "$F/ops/homologacao"
fixture lf; printf '<project/>\n' > "$F/api/pom.xml"
avalia_cob "contraprova: api/pom.xml sem <module>" falha "nenhum <module>" "$F" "$F/ops/homologacao"
fixture lf; rm "$F/ops/homologacao/compose.016.yml"
avalia_cob "contraprova: compose.016.yml ausente (imagem operacao sem Dockerfile)" falha "compose obrigatorio ausente" "$F" "$F/ops/homologacao"
fixture lf; sed -i '/dockerfile:/d; /JAR:/d' "$F"/ops/homologacao/*.yml
avalia_cob "contraprova: compose sem 'dockerfile:' (extracao vazia)" falha "extracao vazia: nenhum 'dockerfile:'" "$F" "$F/ops/homologacao"
fixture lf; sed -i 's#context: ../../api#context: ../..#' "$F/ops/homologacao/compose.016.yml"
avalia_cob "contraprova: contexto de build diferente de api/" falha "contexto de build inesperado" "$F" "$F/ops/homologacao"

echo "-- 5b. arquivos REAIS (somente leitura; fora do checkout exato, ver REPRODUCAO.txt do pacote)"
faltam=""
for f in api/pom.xml api/e2e/Dockerfile api/e2e/Dockerfile.operacao ops/homologacao/compose.homolog.yml ops/homologacao/compose.016.yml ops/homologacao/homologacao.sh; do
    [ -f "$REPO_REAL/$f" ] || faltam="$faltam $f"
done
[ -z "$faltam" ] && ok "arvore real completa para a conferencia" || nok "arvore real INCOMPLETA (faltam:$faltam): reconstruir o checkout exato (REPRODUCAO.txt)"
rm -rf "$T/cob_real"
cobertura "$REPO_REAL" "$AQUI" "$T/cob_real" > "$T/cob_real.txt" 2>&1 && ok "cobertura real: $(grep -c '^COB_OK' "$T/cob_real.txt") verificacoes, nenhuma falha" || nok "cobertura real FALHOU"
sed 's/^/      /' "$T/cob_real.txt"
# o runner usa a lib (mesma lista) antes de criar qualquer recurso, e nao restou a guarda antiga
if le "$AQUI/homologacao.sh" "$T/h.sh" > "$T/h.err"; then
    lg="$(grep -n 'guarda_fontes_imagens "$REPO"' "$T/h.sh" | head -1 | cut -d: -f1)"; lm="$(grep -n 'mkdir -p "$EVID" "$SEG/tls"' "$T/h.sh" | head -1 | cut -d: -f1)"
    [ -n "$lg" ] && [ -n "$lm" ] && [ "$lg" -lt "$lm" ] && ok "criar: guarda antes do primeiro recurso (linha $lg < $lm)" || nok "criar: guarda ausente ou depois da criacao"
    grep -q 'HOMOLOG_COMMIT="$(commit_fontes_imagens "$REPO")"' "$T/h.sh" && ok "criar: HOMOLOG_COMMIT pela mesma lista" || nok "criar: HOMOLOG_COMMIT nao usa commit_fontes_imagens"
    ! grep -qE 'BASE_API|diff --quiet HEAD -- api/' "$T/h.sh" && ok "criar: guarda e identificacao antigas removidas" || nok "criar: restou a guarda antiga"
else nok "homologacao.sh: $(cat "$T/h.err")"; fi

echo "== 6. controle negativo: a guarda e a identificacao ANTERIORES (homologacao.sh ate P3-R015) nao pegam G1/G1b"
# reproduz as linhas 167-168 anteriores no repositorio temporario; espera-se que ELAS falhem onde a nova recusa
antiga_guarda() { (cd "$R" && git -c core.safecrlf=false diff --quiet HEAD -- api/pom.xml api/*/pom.xml api/*/src/main api/e2e/Dockerfile); }
antiga_id() { g log -1 --format=%h -- api/pom.xml api/nekkora-core/src/main api/nekkora-platform/src/main api/nekkora-portal/src/main api/nekkora-core/pom.xml api/nekkora-platform/pom.xml api/nekkora-portal/pom.xml api/e2e/Dockerfile; }
limpa; echo "class N {}" > "$R/api/nekkora-platform/src/main/java/t/N.java"
antiga_guarda && ! passa && ok "G1: nao rastreado em src/main passava na antiga; a nova recusa" || nok "controle G1 inconclusivo"
limpa; echo "// x" >> "$R/api/nekkora-ops/src/test/java/t/FerramentaHomologacao.java"
antiga_guarda && ! passa && ok "G1b: alteracao em nekkora-ops/src/test passava na antiga; a nova recusa" || nok "controle G1b (guarda) inconclusivo"
limpa; echo "// x" >> "$R/api/e2e/Dockerfile.operacao"
antiga_guarda && ! passa && ok "G1b: alteracao em Dockerfile.operacao passava na antiga; a nova recusa" || nok "controle G1b (Dockerfile.operacao) inconclusivo"
limpa; antes="$(antiga_id)"; echo "// x" >> "$R/api/nekkora-ops/src/main/java/t/A.java"; commit "so nekkora-ops/src/main"
[ "$(antiga_id)" = "$antes" ] && [ "$(commit_fontes_imagens "$R")" = "$(curto)" ] && ok "G1b: commit so em nekkora-ops/src/main nao mudava a tag antiga; a nova muda" || nok "controle G1b (identificacao) inconclusivo"

echo "RESULTADO: $casos caso(s), $falhas falha(s)"
[ "$falhas" = 0 ]
