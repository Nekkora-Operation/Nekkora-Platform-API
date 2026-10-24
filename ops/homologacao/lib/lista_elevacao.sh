# Lista de elevacao (implantacao/elevacao.txt ou a alternativa de ensaio): gramatica e normalizacao (P4-R2a-C, A6).
# Carregado (.) por implantacao/implantar.sh e por homologacao.sh. So le arquivos; nao conecta em nada.
#
#   linha     := VVV SP SHA256                          legado: SUPERUSER, sem vinculo
#              | VVV SP SHA256 SP ID                     legado: SUPERUSER, vinculado ao ambiente ID
#              | VVV SP SHA256 (SP CHAVE=VALOR)+         forma nova
#   CHAVE     := tipo | ambiente      tipo := superuser | bypassrls (OBRIGATORIO na forma nova)
#   ID/ambiente := [a-z0-9-]{3,40}
# O terceiro campo e legado (id) SE E SO SE nao contem "=". Linhas antigas continuam validas, com o mesmo significado.
# Politica por versao (defesa no proprio formato):
#   - 014: so "bypassrls" (a 014 precisa atravessar RLS, nao de SUPERUSER: P4-R2a-C, A1/A2);
#   - versoes de vinculo obrigatorio (VINCULO_OBRIGATORIO abaixo: 016 e, desde a missao PLATFORM-API-V1-003, 017-020): so
#     "superuser" e SEMPRE com ambiente; o pre-voo oficial (implantacao/preflight/<versao>.sql) continua no runner.
# Erro de leitura ou de formato recusa; NUNCA equivale a "versao fora da lista".
#
#   lista_elevacao_normalizada <arquivo> "<versoes de vinculo obrigatorio>"
#     codigo 0: uma linha por versao em stdout, "VVV SHA256 TIPO ALVO" (ALVO "-" sem vinculo)
#     codigo 1: mensagem "FALHA: ..." em stderr, nada em stdout
# Lista UNICA das versoes de vinculo obrigatorio, usada pelo runner, pelo criar e pelas validacoes (antes cada um repetia
# " 016 "). 017-020: elevacao medida (mapa_privilegios.sh) e pre-voo oficial proprio; 021 nao tem elevacao.
VINCULO_OBRIGATORIO=" 016 017 018 019 020 "

lista_elevacao_normalizada() {
    local arq="$1" vinculo="${2:-}" conteudo
    [ -f "$arq" ] && [ -r "$arq" ] || { echo "FALHA: lista de elevacao ausente ou ilegivel ($arq)" >&2; return 1; }
    conteudo="$(tr -d '\r' < "$arq")" || { echo "FALHA: erro de leitura da lista de elevacao ($arq)" >&2; return 1; }
    printf '%s\n' "$conteudo" | awk -v vinculo=" $vinculo " '
        function erro(m) { printf "FALHA: lista de elevacao, linha %d: %s\n", NR, m > "/dev/stderr"; ruim = 1; exit 1 }
        /^[[:space:]]*(#|$)/ { next }
        {
            if ($0 ~ /^[[:space:]]|[[:space:]]$|\t|  /) erro("linha fora do formato: espacos (um espaco entre campos)")
            if ($1 !~ /^[0-9][0-9][0-9]$/) erro("linha fora do formato: versao invalida \"" $1 "\"")
            if (NF < 2 || $2 !~ /^[0-9a-f]+$/ || length($2) != 64) erro("linha fora do formato: sha256 invalido na versao " $1)
            if ($1 in visto) erro("versao repetida " $1)
            visto[$1] = 1
            tipo = ""; alvo = "-"
            if (NF == 2) { tipo = "superuser" }
            else if (NF == 3 && index($3, "=") == 0) {
                if ($3 !~ /^[a-z0-9-]+$/ || length($3) < 3 || length($3) > 40) erro("linha fora do formato: id de ambiente invalido \"" $3 "\"")
                tipo = "superuser"; alvo = $3
            } else {
                delete k
                for (i = 3; i <= NF; i++) {
                    p = index($i, "=")
                    if (p == 0) erro("linha fora do formato: mistura de id posicional com chave=valor na versao " $1)
                    c = substr($i, 1, p - 1); val = substr($i, p + 1)
                    if (c != "tipo" && c != "ambiente") erro("linha fora do formato: chave desconhecida \"" c "\" na versao " $1)
                    if (c in k) erro("linha fora do formato: chave repetida \"" c "\" na versao " $1)
                    k[c] = val
                }
                if (!("tipo" in k)) erro("linha fora do formato: forma chave=valor sem tipo na versao " $1)
                if (k["tipo"] != "superuser" && k["tipo"] != "bypassrls") erro("linha fora do formato: tipo desconhecido \"" k["tipo"] "\" na versao " $1)
                tipo = k["tipo"]
                if ("ambiente" in k) {
                    if (k["ambiente"] !~ /^[a-z0-9-]+$/ || length(k["ambiente"]) < 3 || length(k["ambiente"]) > 40) erro("linha fora do formato: ambiente invalido \"" k["ambiente"] "\"")
                    alvo = k["ambiente"]
                }
            }
            if ($1 == "014" && tipo != "bypassrls") erro("a 014 so pode ter elevacao do tipo bypassrls (tem " tipo ")")
            if (index(vinculo, " " $1 " ") > 0) {
                if (tipo != "superuser") erro("versao " $1 " so pode ter elevacao do tipo superuser (tem " tipo ")")
                if (alvo == "-") erro("versao " $1 " sem vinculo ao id do ambiente (obrigatorio)")
            }
            saida[++n] = $1 " " $2 " " tipo " " alvo
        }
        END { if (ruim) exit 1; for (i = 1; i <= n; i++) print saida[i] }'
}
