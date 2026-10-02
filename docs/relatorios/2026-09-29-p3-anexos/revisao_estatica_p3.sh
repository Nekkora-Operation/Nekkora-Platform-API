#!/usr/bin/env bash
# P3 - revisao ESTATICA das alteracoes propostas de elevacao da 016 (sem Docker, banco ou implantacao).
#   docs/relatorios/2026-09-29-p3-anexos/revisao_estatica_p3.sh <dir_saida>
# Aplica os patches propostos SO em copias temporarias (fora do repositorio) e confere: aplicabilidade, linhas 011-013
# byte a byte, hash da 016, as MESMAS expressoes do runner (implantar.sh:133 e homologacao.sh:90) sobre a lista em LF e
# CRLF, casos negativos, sintaxe dos scripts alterados em B e que os arquivos operacionais nao mudaram.
# As expressoes do runner sao reproduzidas literalmente aqui (simulacao das expressoes, nao execucao do runner).
set -uo pipefail
OUT="${1:?dir_saida}"; mkdir -p "$OUT"
REPO="$(git rev-parse --show-toplevel)"; cd "$REPO"
ANX=docs/relatorios/2026-09-29-p3-anexos
SHA016=25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
n_ok=0; n_falha=0
r() { if [ "$1" = OK ]; then n_ok=$((n_ok + 1)); else n_falha=$((n_falha + 1)); fi; echo "$1|$2" | tee -a "$OUT/resultados.txt"; }
: > "$OUT/resultados.txt"
exec > >(tee "$OUT/saida.txt") 2>&1

echo "== 0. operacionais antes"
antes="$(sha256sum ops/homologacao/implantacao/elevacao.txt ops/homologacao/implantacao/implantar.sh ops/homologacao/lib/estado.sh ops/homologacao/homologacao.sh db/migrations/016_recuperacao_emergencial_mfa.sql)"
echo "$antes"
[ "$(sha256sum db/migrations/016_recuperacao_emergencial_mfa.sql | cut -c1-64)" = "$SHA016" ] && r OK "T0 016 no repositorio = $SHA016" || r FALHA "T0 016 no repositorio"
lf_blob="$(git rev-parse HEAD:ops/homologacao/implantacao/elevacao.txt)"
echo "blob HEAD elevacao.txt: $lf_blob; eol: $(git ls-files --eol ops/homologacao/implantacao/elevacao.txt | tr '\t' ' ')"
tr -d '\r' < ops/homologacao/implantacao/elevacao.txt | cmp -s - <(git show HEAD:ops/homologacao/implantacao/elevacao.txt) \
    && r OK "T0 copia de trabalho de elevacao.txt = HEAD a menos do fim de linha (CRLF por core.autocrlf)" || r FALHA "T0 elevacao.txt difere de HEAD"

echo "== 1. aplicabilidade dos patches (copias temporarias)"
for alt in A B; do
    d="$T/$alt"; mkdir -p "$d/ops/homologacao/implantacao"
    git show HEAD:ops/homologacao/implantacao/elevacao.txt > "$d/ops/homologacao/implantacao/elevacao.txt"   # LF (conteudo versionado)
    cp ops/homologacao/implantacao/implantar.sh "$d/ops/homologacao/implantacao/"
    cp ops/homologacao/homologacao.sh "$d/ops/homologacao/"
    p="$(ls "$REPO/$ANX"/p3-"$alt"-*.patch)"
    ( cd "$d" && git init -q . && git -c core.autocrlf=false apply --check "$p" && git -c core.autocrlf=false apply "$p" ) && r OK "T1 patch $alt aplica nas copias ($(basename "$p"))" || r FALHA "T1 patch $alt"
done
cmp -s ops/homologacao/implantacao/implantar.sh <(git show HEAD:ops/homologacao/implantacao/implantar.sh) && echo "(implantar.sh da copia de trabalho = HEAD)"
echo "(homologacao.sh: base do patch B = copia de trabalho aprovada em P1c/P2, sha256 $(sha256sum ops/homologacao/homologacao.sh | cut -c1-64))"

echo "== 2. linhas 011-013 byte a byte e linha da 016 (comparacao BINARIA em Python: grep/sed do Git Bash leem em modo texto e removem CR)"
git show HEAD:ops/homologacao/implantacao/elevacao.txt > "$T/orig.bin"
for alt in A B; do
    f="$T/$alt/ops/homologacao/implantacao/elevacao.txt"
    python - "$T/orig.bin" "$f" "$SHA016" "$alt" "$OUT/resultados.txt" <<'PY2'
import hashlib, sys
orig, novo, sha, alt, res = sys.argv[1:]
o = open(orig, 'rb').read(); n = open(novo, 'rb').read()
def r(ok, msg):
    s = ('OK' if ok else 'FALHA') + '|' + msg
    print(s); open(res, 'a', newline='\n').write(s + '\n')
lo = [l for l in o.split(b'\n') if l[:4] in (b'011 ', b'012 ', b'013 ')]
ln = [l for l in n.split(b'\n') if l[:4] in (b'011 ', b'012 ', b'013 ')]
r(lo == ln and len(lo) == 3, f'T2 {alt}: linhas 011-013 identicas byte a byte (sha256 das 3 linhas {hashlib.sha256(b"".join(l + b"\n" for l in ln)).hexdigest()[:16]}...)')
r(n.startswith(o), f'T2 {alt}: arquivo versionado preservado como prefixo exato (so acrescimos no fim)')
r(b'\r' not in n, f'T2 {alt}: resultado so com LF (como o conteudo versionado)')
l16 = [l for l in n.split(b'\n') if l[:4] == b'016 ']
r(len(l16) == 1, f'T2 {alt}: uma unica linha 016')
r(len(l16) == 1 and l16[0].split(b' ')[1].decode() == sha, f'T2 {alt}: hash da linha 016 = hash aprovado')
m = hashlib.sha256(open('db/migrations/016_recuperacao_emergencial_mfa.sql', 'rb').read()).hexdigest()
r(m == sha, f'T2 {alt}: hash aprovado = sha256 dos bytes de db/migrations/016 ({m[:16]}...)')
v = [l for l in n.split(b'\n') if l and not l.lstrip().startswith(b'#')]
r([x.split(b' ')[0] for x in v] == [b'011', b'012', b'013', b'016'], f'T2 {alt}: versoes na lista = 011 012 013 016')
PY2
done
n_ok=$(grep -c '^OK|' "$OUT/resultados.txt"); n_falha=$(grep -c '^FALHA|' "$OUT/resultados.txt")

# expressoes LITERAIS do runner
autorizado_de() { awk -v v="$2" '$1 == v {print $2}' <(grep -vE '^\s*(#|$)' "$1"); }            # implantar.sh:133
alvo_de()       { awk -v v="$2" '$1 == v {print $3}' <(grep -vE '^\s*(#|$)' "$1"); }            # proposta B
linha90_atual() { grep -vE '^\s*(#|$)' "$1" | grep -qE '^016 [0-9a-f]{64}$'; }                   # homologacao.sh:90 hoje
linha90_B()     { HOMOLOG_ID="$2"; grep -vE '^\s*(#|$)' "$1" | grep -qE "^016 [0-9a-f]{64}( $HOMOLOG_ID)?\$"; }   # proposta B
decide_B() {   # $1 lista $2 versao $3 id -> ELEVA|RECUSA_ALVO|RECUSA_HASH|SEM_ELEVACAO (ordem das verificacoes de B)
    local a al real; a="$(autorizado_de "$1" "$2")"; al="$(alvo_de "$1" "$2")"
    [ -n "$a" ] || { echo SEM_ELEVACAO; return; }
    [ -z "$al" ] || [ "$al" = "$3" ] || { echo RECUSA_ALVO; return; }
    real="$(sha256sum db/migrations/"$2"_*.sql | cut -d' ' -f1)"
    [ "$real" = "$a" ] && echo ELEVA || echo RECUSA_HASH
}

echo "== 3. expressoes do runner, LF e CRLF (ferramentas deste host: $(awk --version | head -1); $(grep --version | head -1))"
for alt in A B; do
    for eol in lf crlf; do
        f="$T/$alt.$eol.txt"; cp "$T/$alt/ops/homologacao/implantacao/elevacao.txt" "$f"; [ $eol = crlf ] && sed -i 's/$/\r/' "$f"
        for v in 011 012 013 016; do
            real="$(sha256sum db/migrations/"$v"_*.sql | cut -d' ' -f1)"; a="$(autorizado_de "$f" "$v")"
            [ "$real" = "$a" ] && r OK "T3 $alt/$eol: versao $v autorizada com sha256 igual ao do arquivo" || r FALHA "T3 $alt/$eol $v (len ${#a})"
        done
        if [ $alt = A ]; then linha90_atual "$f" && r OK "T3 A/$eol: homologacao.sh:90 (atual) aceita" || r FALHA "T3 A/$eol linha 90"
        else
            linha90_atual "$f" && r FALHA "T3 B/$eol: linha 90 ATUAL aceitaria a linha com 3a coluna" || r OK "T3 B/$eol: linha 90 ATUAL recusa a linha com 3a coluna (B exige o patch de homologacao.sh junto)"
            linha90_B "$f" ensaio-016a && r OK "T3 B/$eol: linha 90 de B aceita ensaio-016a" || r FALHA "T3 B/$eol linha90 016a"
            linha90_B "$f" ensaio-016b && r FALHA "T3 B/$eol: linha 90 de B aceitou ensaio-016b" || r OK "T3 B/$eol: linha 90 de B recusa ensaio-016b"
        fi
    done
done

echo "== 4. casos negativos e vinculo (B)"
f="$T/B.lf.txt"
[ "$(decide_B "$f" 016 ensaio-016a)" = ELEVA ] && r OK "T4 B: 016 em ensaio-016a -> eleva" || r FALHA "T4 B 016a"
for id in ensaio-016b ensaio-controles hml03 hml-20261001-000000; do
    [ "$(decide_B "$f" 016 "$id")" = RECUSA_ALVO ] && r OK "T4 B: 016 em $id -> recusa antes de conceder" || r FALHA "T4 B $id"
done
for v in 011 012 013; do [ "$(decide_B "$f" $v qualquer-id)" = ELEVA ] && r OK "T4 B: $v sem 3a coluna -> comportamento atual (sem vinculo)" || r FALHA "T4 B $v"; done
[ "$(decide_B "$f" 014 ensaio-016a)" = SEM_ELEVACAO ] && r OK "T4 B: 014 fora da lista -> sem elevacao" || r FALHA "T4 B 014"
sed 's/^016 25bef818/016 25bef819/' "$T/A.lf.txt" > "$T/A.hash_errado.txt"
[ "$(decide_B "$T/A.hash_errado.txt" 016 ensaio-016a)" = RECUSA_HASH ] && r OK "T4 A: hash errado na linha 016 -> recusa (implantar.sh:137)" || r FALHA "T4 hash errado"
git show HEAD:ops/homologacao/implantacao/elevacao.txt > "$T/sem016.txt"
linha90_atual "$T/sem016.txt" && r FALHA "T4 sem a linha: criar aceitaria" || r OK "T4 lista atual (sem 016): criar HOMOLOG_ATE=016 recusa antes de criar recursos"
[ -z "$(autorizado_de "$T/sem016.txt" 016)" ] && r OK "T4 lista atual: 016 sem autorizacao (seria aplicada SEM elevacao e falharia, P2/M1)" || r FALHA "T4 sem016"
# A nao vincula: qualquer id com a 016 no conjunto de migrations eleva
[ "$(decide_B "$T/A.lf.txt" 016 ensaio-016b)" = ELEVA ] && r OK "T4 A: 016 em ensaio-016b tambem eleva (A NAO vincula ao ambiente; so a regra ensaio-* de homologacao.sh:88)" || r FALHA "T4 A vinculo"

echo "== 4b. patch C (sobre B): parada, pre-voo, pausa por versao, concluir-criacao"
d="$T/C"; cp -r "$T/B" "$d"
( cd "$d" && git -c core.autocrlf=false apply --check "$REPO/$ANX/p3-C-parada-preflight.patch" && git -c core.autocrlf=false apply "$REPO/$ANX/p3-C-parada-preflight.patch" )     && r OK "T7 patch C aplica sobre B" || r FALHA "T7 patch C"
CI="$d/ops/homologacao/implantacao/implantar.sh"; CH="$d/ops/homologacao/homologacao.sh"
bash -n "$CI" && bash -n "$CH" && r OK "T7 bash -n implantar.sh e homologacao.sh (B+C)" || r FALHA "T7 sintaxe C"
[ -f "$d/ops/homologacao/implantacao/preflight/016.sql" ] && r OK "T7 preflight/016.sql criado (somente leitura: $(grep -ciE '^\s*(insert|update|delete|alter|create|drop|grant|revoke|truncate)\b' "$d/ops/homologacao/implantacao/preflight/016.sql") instrucoes de escrita)" || r FALHA "T7 preflight ausente"
grep -qiE '^\s*(insert|update|delete|alter|create|drop|grant|revoke|truncate)\b' "$d/ops/homologacao/implantacao/preflight/016.sql" && r FALHA "T7 preflight com escrita" || r OK "T7 preflight sem instrucao de escrita"
# ---- pausa: funcao ensaio_pausa REAL de C, executada sob set -u (como no runner), com arquivo de liberacao presente
eval "$(sed -n '/^ensaio_pausa() {/,/^}/p' "$CI")"
declare -f ensaio_pausa > "$T/pausa_c.sh"
pausa_em() {   # $1 valor da variavel ("__AUSENTE__" = nao definida), $2 ponto, $3 versao -> pausou|seguiu|ERRO(<saida>)
    rm -f "$T/p.chegou"; touch "$T/p"
    local o
    if [ "$1" = __AUSENTE__ ]; then o="$(env -u HOMOLOG_ENSAIO_PAUSA bash -u -c ". '$T/pausa_c.sh'; ensaio_pausa '$2' '$3'; echo fim" 2>&1)"
    else o="$(HOMOLOG_ENSAIO_PAUSA="$1" bash -u -c ". '$T/pausa_c.sh'; ensaio_pausa '$2' '$3'; echo fim" 2>&1)"; fi
    [ "$(echo "$o" | tail -1)" = fim ] || { echo "ERRO($o)"; return; }
    [ -e "$T/p.chegou" ] && echo pausou || echo seguiu
}
[ "$(pausa_em __AUSENTE__ antes-do-flyway 016)" = seguiu ] && r OK "T7 pausa, set -u, variavel AUSENTE -> segue sem erro" || r FALHA "T7 pausa ausente: $(pausa_em __AUSENTE__ antes-do-flyway 016)"
[ "$(pausa_em '' antes-do-flyway 016)" = seguiu ] && r OK "T7 pausa, set -u, variavel VAZIA -> segue" || r FALHA "T7 pausa vazia"
[ "$(pausa_em "antes-de-elevar:$T/p" antes-de-elevar 011)" = pausou ] && r OK "T7 pausa forma antiga (antes-de-elevar, usada em I9b/I9c) para na primeira versao" || r FALHA "T7 pausa antiga"
[ "$(pausa_em "antes-do-flyway:$T/p" antes-do-flyway 013)" = pausou ] && r OK "T7 pausa forma antiga (antes-do-flyway) para em qualquer versao" || r FALHA "T7 pausa antiga 2"
[ "$(pausa_em "antes-do-flyway@016:$T/p" antes-do-flyway 016)" = pausou ] && r OK "T7 pausa antes-do-flyway@016 para na 016" || r FALHA "T7 pausa 016"
[ "$(pausa_em "antes-do-flyway@016:$T/p" antes-do-flyway 013)" = seguiu ] && r OK "T7 pausa antes-do-flyway@016 NAO para na 013" || r FALHA "T7 pausa 013"
[ "$(pausa_em "antes-do-flyway@016:$T/p" antes-de-elevar 016)" = seguiu ] && r OK "T7 pausa de outro ponto nao dispara" || r FALHA "T7 pausa ponto"
# contraprova: a funcao da REVISAO 2 de C (texto literal abaixo) falha com set -u e variavel ausente (achado da revisao)
cat > "$T/pausa_rev2.sh" <<'REV2'
ensaio_pausa() {
    local ponto="${HOMOLOG_ENSAIO_PAUSA%%:*}"
    [ -n "${HOMOLOG_ENSAIO_PAUSA:-}" ] && { [ "$ponto" = "$1" ] || [ "$ponto" = "$1@$2" ]; } || return 0
}
REV2
o="$(env -u HOMOLOG_ENSAIO_PAUSA bash -u -c ". '$T/pausa_rev2.sh'; ensaio_pausa antes-do-flyway 016; echo fim" 2>&1)"
echo "$o" | grep -q 'HOMOLOG_ENSAIO_PAUSA: unbound variable' && r OK "T7 contraprova: a pausa da revisao 2 reproduz 'unbound variable' (corrigida na revisao 3)" || r FALHA "T7 contraprova rev2: $o"
# parada: mesma aritmetica do laco de C
para() { local ATE_MAX="$1" v="$2"; [ -n "$ATE_MAX" ] && [ "$((10#$v))" -gt "$((10#$ATE_MAX))" ] && echo para || echo aplica; }
[ "$(para 015 015)$(para 015 016)$(para '' 016)$(para 015 009)" = aplicaparaaplicaaplica ] && r OK "T7 HOMOLOG_IMPLANTAR_ATE=015: aplica 009 e 015, para na 016; sem a variavel, aplica a 016" || r FALHA "T7 parada"
grep -q "HOMOLOG_IMPLANTAR_ATE=015 HOMOLOG_AUTORIZA_ELEVACAO=1 bash" "$CH" && r OK "T7 criar com HOMOLOG_ATE=016 chama a implantacao com parada em 015" || r FALHA "T7 criar parada"
grep -q "ignoreMigrationPatterns='\*:pending' validate" "$CI" && r OK "T7 validate final ignora SO pendentes quando ha parada (comportamento do Flyway NAO testado aqui)" || r FALHA "T7 validate"
# ---- pre-voo: o BLOCO REAL de C (de 'if [ -n "$alvo" ]' ao 'fi' correspondente), executado sob set -u com admin_sql
# simulado (saida e codigo controlados) e encerra_com_falha/registra simulados
awk '/^        if \[ -n "\$alvo" \]; then$/{f=1} f{print} f && /^        fi$/{exit}' "$CI" > "$T/bloco_pf.sh"
[ "$(head -1 "$T/bloco_pf.sh")" = '        if [ -n "$alvo" ]; then' ] && [ "$(tail -1 "$T/bloco_pf.sh")" = '        fi' ] \
    && r OK "T7 bloco do pre-voo extraido de implantar.sh (C): $(wc -l < "$T/bloco_pf.sh") linhas" || r FALHA "T7 extracao do bloco"
mkdir -p "$T/impl/preflight" "$T/evid"; : > "$T/impl/preflight/016.sql"
gate() {   # $1 texto que admin_sql imprime (printf %b), $2 codigo de saida de admin_sql, $3 (opcional) "sem_arquivo"
    [ "${3:-}" = sem_arquivo ] && mv "$T/impl/preflight/016.sql" "$T/impl/016.sql.fora"
    HOMOLOG_EVID="$T/evid" DIR_IMPL="$T/impl" SAIDA_PF="$1" RC_PF="$2" bash -u -c '
        admin_sql() { cat > /dev/null; printf "%b" "$SAIDA_PF"; return "$RC_PF"; }
        encerra_com_falha() { echo "RECUSA($1): $2"; exit 2; }
        registra() { echo "$*"; }
        alvo=ensaio-016a v=016
        . "'"$T/bloco_pf.sh"'"
        echo ELEVA' 2>&1 | tail -1
    [ "${3:-}" = sem_arquivo ] && mv "$T/impl/016.sql.fora" "$T/impl/preflight/016.sql"
}
[ "$(gate 'a|OK|-\nPREFLIGHT_OK\n' 0)" = ELEVA ] && r OK "T7 pre-voo: codigo 0 + PREFLIGHT_OK -> eleva" || r FALHA "T7 pf ok: $(gate 'a|OK|-\nPREFLIGHT_OK\n' 0)"
case "$(gate 'a|OK|-\nPREFLIGHT_OK\n' 1)" in RECUSA*) r OK "T7 CONTRAPROVA pre-voo: imprime PREFLIGHT_OK mas codigo 1 -> RECUSA";; *) r FALHA "T7 contraprova codigo 1";; esac
case "$(gate 'PREFLIGHT_OK\n' 3)" in RECUSA*) r OK "T7 CONTRAPROVA pre-voo: PREFLIGHT_OK com codigo 3 -> RECUSA";; *) r FALHA "T7 contraprova codigo 3";; esac
case "$(gate 'a|FALHA|-\nPREFLIGHT_FALHOU\n' 0)" in RECUSA*) r OK "T7 pre-voo: codigo 0 + PREFLIGHT_FALHOU -> RECUSA";; *) r FALHA "T7 pf falhou";; esac
case "$(gate 'ERROR:  relation does not exist\n' 3)" in RECUSA*) r OK "T7 pre-voo: erro de consulta (codigo 3) -> RECUSA";; *) r FALHA "T7 pf erro";; esac
case "$(gate '' 0)" in RECUSA*) r OK "T7 pre-voo: saida vazia com codigo 0 -> RECUSA";; *) r FALHA "T7 pf vazio";; esac
[ "$(gate 'PREFLIGHT_OK\r\n' 0)" = ELEVA ] && r OK "T7 pre-voo: codigo 0 + PREFLIGHT_OK com CRLF -> eleva" || r FALHA "T7 pf crlf"
case "$(gate 'PREFLIGHT_OK\n' 0 sem_arquivo)" in RECUSA*) r OK "T7 pre-voo: preflight/016.sql ausente -> RECUSA";; *) r FALHA "T7 pf sem arquivo";; esac
grep -q 'concluir-criacao)' "$CH" && grep -q '^pos_implantacao() {' "$CH" && [ "$(grep -c 'pos_implantacao$' "$CH")" = 2 ]     && r OK "T7 concluir-criacao e pos_implantacao (chamada no criar e no concluir-criacao)" || r FALHA "T7 concluir-criacao"

echo "== 5. sintaxe dos scripts alterados em B"
bash -n "$T/B/ops/homologacao/implantacao/implantar.sh" && r OK "T5 bash -n implantar.sh (B)" || r FALHA "T5 implantar.sh"
bash -n "$T/B/ops/homologacao/homologacao.sh" && r OK "T5 bash -n homologacao.sh (B)" || r FALHA "T5 homologacao.sh"

echo "== 6. operacionais depois (nada aplicado ao repositorio)"
depois="$(sha256sum ops/homologacao/implantacao/elevacao.txt ops/homologacao/implantacao/implantar.sh ops/homologacao/lib/estado.sh ops/homologacao/homologacao.sh db/migrations/016_recuperacao_emergencial_mfa.sql)"
[ "$antes" = "$depois" ] && r OK "T6 elevacao.txt, implantar.sh, estado.sh, homologacao.sh e 016 inalterados" || r FALHA "T6 operacionais mudaram"
git diff --quiet HEAD -- ops/homologacao/implantacao/elevacao.txt && r OK "T6 elevacao.txt sem diff contra HEAD (validar_config.sh:83 continua aprovando; aplicado o patch, essa conferencia passa a falhar e precisa ser ajustada em P4)" || r FALHA "T6 git diff elevacao"

echo "== total: $n_ok OK, $n_falha FALHA"
echo "total|$n_ok OK|$n_falha FALHA" >> "$OUT/resultados.txt"
[ $n_falha = 0 ]
