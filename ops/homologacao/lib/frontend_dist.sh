# Build da aplicacao web do ensaio-frontend (F1.5, P-A), identificado pelo PAR de commits validado (web + lib).
# Funcoes para "source" (homologacao.sh criar). Nada aqui toca Docker, banco ou segredos.
#
#   frontend_conferir <web> <commit_web> <commit_lib>
#       Recusa (codigo 1, com motivo) se: <web> nao e repositorio Git; HEAD do web != <commit_web> (sha completo); copia
#       de trabalho do web com alteracao ou arquivo nao ignorado; node_modules/nekkora-lib ausente; a lib ligada nao e
#       repositorio Git, tem HEAD != <commit_lib> ou alteracoes. Nao escreve nada.
#   frontend_preparar <web> <commit_web> <commit_lib> <destino> <registro>
#       Confere de novo, compila a lib (npm run build na lib ligada) e o web (npm run build, que tambem confere as
#       exportacoes da lib), confere que os builds nao mudaram nada rastreado, copia web/dist para <destino> (novo) e
#       grava em <registro> os commits, as versoes de node/npm e o sha256 de cada arquivo do build copiado.
frontend_lib() { (cd "$1/node_modules/nekkora-lib" 2>/dev/null && pwd -P); }

frontend_conferir() {
    local web="$1" cw="$2" cl="$3" lib h
    [[ "$cw" =~ ^[0-9a-f]{40}$ ]] && [[ "$cl" =~ ^[0-9a-f]{40}$ ]] || { echo "commits do par web/lib devem ser sha completos (40 hexadecimais)"; return 1; }
    git -C "$web" rev-parse --git-dir > /dev/null 2>&1 || { echo "aplicacao web: $web nao e repositorio Git"; return 1; }
    h="$(git -C "$web" rev-parse HEAD)"
    [ "$h" = "$cw" ] || { echo "aplicacao web: HEAD $h diferente do commit declarado $cw"; return 1; }
    [ -z "$(git -C "$web" -c core.autocrlf=false status --porcelain 2>/dev/null)" ] || { echo "aplicacao web: copia de trabalho com alteracoes ou arquivos nao ignorados"; return 1; }
    lib="$(frontend_lib "$web")" && [ -n "$lib" ] || { echo "aplicacao web: node_modules/nekkora-lib ausente (npm install)"; return 1; }
    git -C "$lib" rev-parse --git-dir > /dev/null 2>&1 || { echo "lib ligada ($lib) nao e repositorio Git"; return 1; }
    h="$(git -C "$lib" rev-parse HEAD)"
    [ "$h" = "$cl" ] || { echo "lib ligada: HEAD $h diferente do commit declarado $cl"; return 1; }
    [ -z "$(git -C "$lib" -c core.autocrlf=false status --porcelain 2>/dev/null)" ] || { echo "lib ligada: copia de trabalho com alteracoes ou arquivos nao ignorados"; return 1; }
    return 0
}

frontend_preparar() {
    local web="$1" cw="$2" cl="$3" dest="$4" reg="$5" lib m
    m="$(frontend_conferir "$web" "$cw" "$cl")" || { echo "$m"; return 1; }
    [ ! -e "$dest" ] || { echo "destino do build ja existe: $dest"; return 1; }
    lib="$(frontend_lib "$web")"
    { echo "== build da lib ($lib)"; (cd "$lib" && npm run build); } > "$reg.build_lib.txt" 2>&1 || { echo "build da lib falhou (ver $reg.build_lib.txt)"; return 1; }
    { echo "== build do web ($web)"; (cd "$web" && npm run build); } > "$reg.build_web.txt" 2>&1 || { echo "build do web falhou (ver $reg.build_web.txt)"; return 1; }
    # o build nao pode ter mudado nada rastreado (nem criado arquivo nao ignorado) nos dois repositorios
    m="$(frontend_conferir "$web" "$cw" "$cl")" || { echo "depois do build: $m"; return 1; }
    [ -f "$web/dist/index.html" ] || { echo "build do web sem dist/index.html"; return 1; }
    cp -r "$web/dist" "$dest" || { echo "copia do build falhou"; return 1; }
    {
        echo "# build da aplicacao web do ensaio (F1.5): par web + lib, copia imutavel em $(basename "$dest")/"
        echo "web=$cw"
        echo "lib=$cl"
        echo "node=$(node -v 2>/dev/null) npm=$(npm -v 2>/dev/null)"
        echo "lib_dist_sha256=$(cd "$lib/dist" && find . -type f | sort | xargs sha256sum | sha256sum | cut -c1-64)"
        echo "# sha256 de cada arquivo do build copiado:"
        (cd "$dest" && find . -type f | sort | xargs sha256sum)
    } > "$reg" || { echo "registro do build nao gravado"; return 1; }
    return 0
}
