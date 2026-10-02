# Proveniencia HISTORICA da lista de elevacao (missao H02 x lista historica). Arquivo "sourced" por homologacao.sh
# (criar) e por testes/executar.sh (H02); so define funcoes, sem efeito ao carregar.
#
# Decisao: a expectativa de janelas do H02 vem da politica sob a qual o ambiente foi CRIADO, nunca da lista vigente no
# momento do teste. Ativar uma versao na lista operacional depois nao muda o que um ambiente antigo deve ter tido.
#
# Fotografia (gravada pelo criar ANTES de qualquer recurso Docker; se nao puder ser preservada, o criar falha):
#   saidas/<id>/elevacao_criacao/lista.txt         bytes EXATOS da lista usada pelo criar (somente leitura)
#   saidas/<id>/elevacao_criacao/proveniencia.txt  origem (caminho), tipo (operacional|alternativa-ensaio), sha256, bytes,
#                                                  id do ambiente, instante (somente leitura)
#   ambiente.env: HOMOLOG_ELEVACAO_CRIACAO_SHA256=<sha256 da lista>   (ancora 1, gravada na mesma criacao)
#   saidas/<id>/implantacao.txt, 1a linha: "... sha256 <sha256>"      (ancora 2, escrita pelo runner na criacao)
# Trocar a fotografia sem deteccao exigiria alterar, de forma coerente, a lista, a proveniencia, o ambiente.env E o log
# de criacao do runner; qualquer divergencia entre eles e FALHA.
#
# Ambientes LEGADOS (criados antes deste mecanismo: sem fotografia e sem HOMOLOG_ELEVACAO_CRIACAO_SHA256): so com fonte
# historica EXPLICITA (HOMOLOG_H02_LISTA_LEGADO=<arquivo>). Se o log de criacao registra o sha256 da lista, o arquivo tem de
# ter exatamente esse sha256; se nao registra (runner anterior ao P3), o modo e "LEGADO SEM ANCORA" e fica registrado.
# Sem fotografia e sem fonte explicita: FALHA fechada (nunca a lista vigente). Ambiente novo sem fotografia: FALHA,
# mesmo com fonte legada (o caminho legado nao substitui a proveniencia de um ambiente que deveria te-la).

eh_sha() { sha256sum "$1" 2>/dev/null | cut -c1-64; }

# eh_registrar <lista usada> <tipo> <dir saidas/<id>> <id> -> grava a fotografia; imprime o sha256; codigo 1 se falhar
eh_registrar() {
    local lista="$1" tipo="$2" base="$3" id="$4" d sha copia
    d="$base/elevacao_criacao"
    [ -f "$lista" ] && [ -r "$lista" ] || { echo "fotografia da lista: lista ilegivel ($lista)" >&2; return 1; }
    [ ! -e "$d" ] || { echo "fotografia da lista: ja existe ($d)" >&2; return 1; }
    mkdir -p "$d" || return 1
    sha="$(eh_sha "$lista")"; [ -n "$sha" ] || return 1
    cp "$lista" "$d/lista.txt" || return 1
    copia="$(eh_sha "$d/lista.txt")"
    [ "$copia" = "$sha" ] || { echo "fotografia da lista: copia difere da origem ($copia <> $sha)" >&2; return 1; }
    printf 'origem=%s\ntipo=%s\nsha256=%s\nbytes=%s\nambiente=%s\nregistrado_em=%s\n' \
        "$lista" "$tipo" "$sha" "$(wc -c < "$d/lista.txt" | tr -d ' ')" "$id" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$d/proveniencia.txt" || return 1
    chmod a-w "$d/lista.txt" "$d/proveniencia.txt" 2>/dev/null
    echo "$sha"
}

# eh_sha_log_criacao <dir saidas/<id>> -> sha256 registrado pelo runner na 1a linha do log de criacao (vazio se nao houver)
eh_sha_log_criacao() {
    head -1 "$1/implantacao.txt" 2>/dev/null | tr -d '\r' | sed -nE 's/^== implantacao .*\(lista de elevacao: .* sha256 ([0-9a-f]{64})\)$/\1/p'
}

# eh_lista_h02 <dir saidas/<id>> <id> <HOMOLOG_ELEVACAO_CRIACAO_SHA256 do ambiente.env (pode ser vazio)>
#   -> imprime o caminho da lista HISTORICA a usar; o modo e as conferencias vao para stderr; codigo 1 = FALHA fechada
eh_lista_h02() {
    local base="$1" id="$2" sha_env="$3" d="$1/elevacao_criacao" sha_lista sha_prov id_prov sha_log leg
    sha_log="$(eh_sha_log_criacao "$base")"
    if [ -d "$d" ]; then
        [ -f "$d/lista.txt" ] && [ -f "$d/proveniencia.txt" ] || { echo "FALHA: fotografia incompleta ($d)" >&2; return 1; }
        sha_lista="$(eh_sha "$d/lista.txt")"
        sha_prov="$(sed -n 's/^sha256=//p' "$d/proveniencia.txt" | tr -d '\r')"
        id_prov="$(sed -n 's/^ambiente=//p' "$d/proveniencia.txt" | tr -d '\r')"
        [[ "$sha_prov" =~ ^[0-9a-f]{64}$ ]] && [ "$(grep -c '^sha256=' "$d/proveniencia.txt")" = 1 ] \
            || { echo "FALHA: proveniencia malformada ($d/proveniencia.txt)" >&2; return 1; }
        [ "$id_prov" = "$id" ] || { echo "FALHA: fotografia de outro ambiente ('$id_prov' <> '$id')" >&2; return 1; }
        [ "$sha_lista" = "$sha_prov" ] || { echo "FALHA: lista da fotografia adulterada (sha256 $sha_lista <> proveniencia $sha_prov)" >&2; return 1; }
        [ -n "$sha_env" ] || { echo "FALHA: ambiente.env sem HOMOLOG_ELEVACAO_CRIACAO_SHA256 (ancora 1 ausente)" >&2; return 1; }
        [ "$sha_env" = "$sha_prov" ] || { echo "FALHA: sha256 da fotografia diverge do ambiente.env ($sha_prov <> $sha_env)" >&2; return 1; }
        [ -n "$sha_log" ] || { echo "FALHA: log de criacao sem o sha256 da lista (ancora 2 ausente)" >&2; return 1; }
        [ "$sha_log" = "$sha_prov" ] || { echo "FALHA: sha256 da fotografia diverge do log de criacao do runner ($sha_prov <> $sha_log)" >&2; return 1; }
        echo "MODO HISTORICO: fotografia da criacao $d/lista.txt sha256 $sha_prov (proveniencia, ambiente.env e log de criacao conferem)" >&2
        echo "$d/lista.txt"; return 0
    fi
    [ -z "$sha_env" ] || { echo "FALHA: ambiente com proveniencia declarada (ambiente.env) e SEM a fotografia ($d); sem recurso a lista vigente nem a fonte legada" >&2; return 1; }
    leg="${HOMOLOG_H02_LISTA_LEGADO:-}"
    [ -n "$leg" ] || { echo "FALHA: ambiente LEGADO sem fotografia e sem fonte historica explicita (HOMOLOG_H02_LISTA_LEGADO); a lista vigente NAO e usada" >&2; return 1; }
    [ -f "$leg" ] || { echo "FALHA: fonte historica legada ilegivel ($leg)" >&2; return 1; }
    if [ -n "$sha_log" ]; then
        [ "$(eh_sha "$leg")" = "$sha_log" ] || { echo "FALHA: fonte legada $leg (sha256 $(eh_sha "$leg")) nao e a lista registrada na criacao ($sha_log)" >&2; return 1; }
        echo "MODO LEGADO: fonte historica explicita $leg sha256 $(eh_sha "$leg") = sha256 do log de criacao" >&2
    else
        echo "MODO LEGADO SEM ANCORA: fonte historica explicita $leg sha256 $(eh_sha "$leg"); o log de criacao nao registra sha256 (runner anterior ao P3)" >&2
    fi
    echo "$leg"
}
