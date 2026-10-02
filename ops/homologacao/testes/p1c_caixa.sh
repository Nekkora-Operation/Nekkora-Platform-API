#!/usr/bin/env bash
# P1c - caixa do transporte de homologacao no VOLUME Linux do Docker (desenho P1a/P1b v2.2, sec. 4.2-4.4), pelo caminho
# implementado: compose.homolog.yml + compose.016.yml + compose.caixa.yml REAIS (docker compose), o "caixa-init" como o
# "criar" o chama, a ferramenta restrita pelo RUNNER REAL (homologacao.sh caixa <id> ...) e a remocao pelo "destruir".
#
#   ops/homologacao/testes/p1c_caixa.sh <dir_evidencia>
#
# Ambiente de TESTE "ensaio-p1c-cx-<hex>": so ambiente.env em saidas/<id>/ e segredos/config FICTICIOS num diretorio
# temporario fora do repositorio (nenhum servico sobe: sem postgres, rede ou Platform). Cria apenas o volume rotulado
# "caixa" do projeto e containers efemeros (--rm). A Platform de TESTE (imagem nekkora-p1c-platform:p1c, jar da arvore
# atual) recebe temporariamente a tag nekkora-homologacao-platform:<commit ficticio>, removida no fim.
# O transporte grava pela JVM Linux da imagem (CaixaLinuxSonda, classes de teste montadas somente leitura). Conteudo de
# mensagens lido pela ferramenta vai para arquivo temporario fora da evidencia e e apagado. Pre-requisitos: imagem de
# teste construida e "./mvnw -pl nekkora-platform test-compile".
set -uo pipefail
EV="${1:?dir_evidencia}"
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
H="$AQUI/homologacao.sh"
mkdir -p "$EV"
LOG="$EV/caixa_linux.txt"
: > "$LOG"
ok_total=0; falhas=0
confere() { if [ "$2" = 0 ]; then echo "OK    $1" | tee -a "$LOG"; ok_total=$((ok_total+1)); else echo "FALHA $1" | tee -a "$LOG"; falhas=$((falhas+1)); fi; }
reg() { echo "$*" >> "$LOG"; }
mixed() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

ID="ensaio-p1c-cx-$(python -c 'import secrets; print(secrets.token_hex(3))' | tr -d '\r')"
PROJ="nekkora-homologacao-$ID"
COMMIT="p1c-$(python -c 'import secrets; print(secrets.token_hex(3))' | tr -d '\r')"
TMP="$(mktemp -d)"
SAI="$AQUI/saidas/$ID"
reg "id=$ID projeto=$PROJ tag_temporaria=nekkora-homologacao-platform:$COMMIT inicio=$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# sub-redes do projeto (so declaradas; nenhum servico com rede e executado) sem sobreposicao com as redes existentes
docker network ls -q | xargs -r docker network inspect -f '{{range .IPAM.Config}}{{println .Subnet}}{{end}}' \
    | python "$AQUI/lib/redes.py" sobreposicao 10.252.48.0/24 10.252.49.0/24 >> "$LOG" 2>&1
confere "sub-redes declaradas 10.252.48.0/24 e 10.252.49.0/24 sem sobreposicao com as redes existentes" $?

mkdir -p "$SAI" "$TMP/seg/tls" "$TMP/config" "$TMP/mig" "$TMP/sql"
printf 'ficticio' > "$TMP/seg/postgres_admin"; : > "$TMP/seg/operacao.env"
cat > "$SAI/ambiente.env" <<EOF
# ambiente de TESTE do P1c (p1c_caixa.sh): so a caixa; segredos e config FICTICIOS fora do repositorio
HOMOLOG_ID=$ID
HOMOLOG_PROJETO=$PROJ
HOMOLOG_HOST=homologacao.nekkora.test
HOMOLOG_PORTA=55999
HOMOLOG_BIND=127.0.0.1
HOMOLOG_COMMIT=$COMMIT
HOMOLOG_SEGREDOS=$(mixed "$TMP/seg")
HOMOLOG_SEGREDOS_HOST=$(mixed "$TMP/seg")
HOMOLOG_MIGRACOES=$(mixed "$TMP/mig")
HOMOLOG_SQL=$(mixed "$TMP/sql")
HOMOLOG_ATE=016
HOMOLOG_REDE_INTERNA=10.252.48
HOMOLOG_REDE_BORDA=10.252.49
HOMOLOG_TRANSPORTE=homologacao
HOMOLOG_CONFIG=$(mixed "$TMP/config")
HOMOLOG_COMPOSE_EXTRA="compose.016.yml compose.caixa.yml"
EOF
docker tag nekkora-p1c-platform:p1c "nekkora-homologacao-platform:$COMMIT"
set -a; . "$SAI/ambiente.env"; set +a
# MSYS_NO_PATHCONV so nas chamadas docker DESTE script (exportado, vazaria para o runner e para o python dele)
C=(env MSYS_NO_PATHCONV=1 docker compose -f "$(mixed "$AQUI/compose.homolog.yml")" -f "$(mixed "$AQUI/compose.016.yml")" -f "$(mixed "$AQUI/compose.caixa.yml")")

# ---- caixa-init exatamente como o "criar" (dono e permissao conferidos no Linux do Docker)
r="$("${C[@]}" --profile caixa run --rm -T caixa-init 2>/dev/null | tr -d '\r' | tail -1)"
reg "caixa-init: $r"
[ "$r" = "nekkora 700" ]; confere "caixa-init: volume exclusivo com dono nekkora e modo 700 (obtido: $r)" $?
VOL="$(docker volume ls -q --filter "label=nekkora.homologacao=$ID")"
reg "volume do projeto: $VOL rotulos=$(docker volume inspect "$VOL" -f '{{json .Labels}}')"
[ "$VOL" = "${PROJ}_caixa" ]; confere "volume rotulado nekkora.homologacao=$ID e o unico do ambiente" $?

TC="$(mixed "$REPO/api/nekkora-platform/target/test-classes")"
sonda() {   # transporte REAL na JVM Linux da imagem, usuario nekkora, sem rede, volume do ambiente
    MSYS_NO_PATHCONV=1 docker run --rm --network none --label nekkora.p1c=caixa -v "$VOL:/caixa" --mount "type=bind,src=$TC,dst=/testes,readonly" \
        --entrypoint java nekkora-p1c-platform:p1c -Dloader.path=/testes -Dloader.main=com.nekkora.platform.mail.CaixaLinuxSonda \
        -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$@" /caixa "$ID" 2>/dev/null | tr -d '\r' | grep -E '^(tentativa|desfecho|falha)='
}
ferr() { "${C[@]}" --profile caixa run --rm -T caixa-ferramenta "$@" 2>/dev/null | tr -d '\r'; }

r="$(sonda mensagens)"; reg "transporte: $r"
T="$(echo "$r" | sed -nE 's/^tentativa=([0-9a-f-]{36}).*/\1/p')"
echo "$r" | grep -q 'desfechos=ACEITO_PELO_TRANSPORTE,ACEITO_PELO_TRANSPORTE outras=16'; confere "transporte na JVM Linux: tentativa, repeticao e 16 mensagens concorrentes" $?

# ---- dono e permissoes no volume (visto pela ferramenta restrita)
ferr sh -c "find /caixa -exec stat -c '%U %a %F %n' {} +" > "$TMP/stat.txt"
sed -E 's/[0-9a-f]{8}-[0-9a-f-]{27}/<uuid>/g; s/[0-9]{8}T[0-9]{9}Z/<instante>/g' "$TMP/stat.txt" | sort | uniq -c >> "$LOG"
! grep -v '^nekkora ' "$TMP/stat.txt" | grep -q .; confere "tudo na caixa pertence a nekkora" $?
! grep ' directory ' "$TMP/stat.txt" | grep -vq '^nekkora 700 '; confere "diretorios da caixa com modo 700" $?
! grep ' regular ' "$TMP/stat.txt" | grep -vq '^nekkora 600 '; confere "arquivos da caixa com modo 600 (mensagens, indice, controle)" $?
grep -q "/caixa/avisos/$T.repeticao-1.json" "$TMP/stat.txt"; confere "repeticao da tentativa publicada como repeticao-1 (sem sobrescrita)" $?
[ "$(grep -c ' regular .* /caixa/outras/' "$TMP/stat.txt")" = 16 ]; confere "16 mensagens sem attempt_id com nomes distintos" $?
[ "$(grep -c ' /caixa/.tmp/' "$TMP/stat.txt")" = 0 ]; confere "nenhum temporario restante em .tmp" $?

# ---- isolamento da ferramenta: sem rede, raiz somente leitura, so o volume gravavel
r="$(ferr sh -c 'echo interfaces=$(tail -n +3 /proc/net/dev | cut -d: -f1 | tr -d " " | tr "\n" ","); touch /p1c-escrita 2>/dev/null && echo raiz=gravavel || echo raiz=somente-leitura; touch /caixa/.p1c && rm /caixa/.p1c && echo volume=gravavel; echo usuario=$(id -un)')"
reg "ferramenta restrita: $(echo "$r" | tr '\n' ' ')"
echo "$r" | grep -qx 'interfaces=lo,'; confere "caixa-ferramenta sem rede (so lo)" $?
echo "$r" | grep -qx 'raiz=somente-leitura'; confere "caixa-ferramenta com raiz somente leitura" $?
echo "$r" | grep -qx 'volume=gravavel' && echo "$r" | grep -qx 'usuario=nekkora'; confere "caixa-ferramenta: so o volume gravavel, usuario nekkora" $?

# ---- runner real: listar, ler (fora da evidencia), controle, trava, liberar-trava, exportar-indice
n="$(bash "$H" caixa "$ID" listar 2>/dev/null | grep -c '\.json$')"; reg "listar: $n arquivos"
[ "$n" = 18 ]; confere "caixa listar (18 mensagens)" $?
bash "$H" caixa "$ID" ler "avisos/$T.json" > "$TMP/msg.json" 2>/dev/null
python -c "import json,sys; d=json.load(open(sys.argv[1])); assert d['simulado'] is True and d['attempt_id']==sys.argv[2] and d['token_confirmar'] and d['token_contestar']" "$(mixed "$TMP/msg.json")" "$T"
confere "caixa ler: mensagem completa lida para arquivo do operador (fora da evidencia), simulado=true" $?
rm -f "$TMP/msg.json"
bash "$H" caixa "$ID" ler "../indice.jsonl" > /dev/null 2>&1; [ $? != 0 ]; confere "caixa ler recusa caminho fora do formato" $?
bash "$H" caixa "$ID" controle proximo ENTREGUE > /dev/null 2>&1; [ $? != 0 ]; confere "caixa controle recusa regra fora da lista" $?
bash "$H" caixa "$ID" controle proximo RECUSADO >> "$LOG" 2>&1; confere "caixa controle proximo RECUSADO" $?
r="$(sonda aviso)"; reg "aviso depois da regra: $r"
[ "$r" = "desfecho=RECUSADO_DEFINITIVO" ]; confere "regra proximo consumida pelo transporte na JVM Linux" $?
r="$(sonda aviso)"; [ "$r" = "desfecho=ACEITO_PELO_TRANSPORTE" ]; confere "regra consumida uma unica vez (proxima entrega: ACEITO)" $?
# trava orfa (queda no meio do consumo): o transporte falha, sem expiracao; so a liberacao explicita resolve
ferr sh -c 'mkdir /caixa/controle.trava' ; reg "trava orfa criada: $(ferr sh -c 'ls -ld /caixa/controle.trava | cut -c1-10')"
bash "$H" caixa "$ID" controle proximo INCERTO > "$TMP/c.txt" 2>&1; rc=$?; reg "controle com trava ocupada: $(tail -1 "$TMP/c.txt")"
[ $rc != 0 ] && grep -q 'trava do controle ocupada' "$TMP/c.txt"; confere "caixa controle recusa com a trava ocupada" $?
t0=$(date +%s); r="$(sonda aviso)"; t1=$(date +%s); reg "aviso com trava ocupada: $r (${t1}-${t0}s)"
echo "$r" | grep -q '^falha=IllegalStateException:caixa: trava do controle ocupada'; confere "transporte falha com a trava ocupada (apos a espera de 5 s: $((t1-t0)) s)" $?
r="$(sonda aviso)"; echo "$r" | grep -q '^falha='; confere "sem expiracao: continua falhando na tentativa seguinte" $?
bash "$H" caixa "$ID" liberar-trava > "$TMP/l.txt" 2>&1; rc=$?; reg "liberar-trava sem confirmacao: $(tail -1 "$TMP/l.txt")"
[ $rc != 0 ] && grep -q 'HOMOLOG_CONFIRMA_TRAVA_ORFA=1' "$TMP/l.txt"; confere "liberar-trava exige confirmacao explicita" $?
HOMOLOG_CONFIRMA_TRAVA_ORFA=1 bash "$H" caixa "$ID" liberar-trava >> "$LOG" 2>&1; confere "liberar-trava com confirmacao" $?
r="$(sonda aviso)"; [ "$r" = "desfecho=ACEITO_PELO_TRANSPORTE" ]; confere "depois da liberacao explicita o transporte volta a funcionar" $?
bash "$H" caixa "$ID" controle proximo ENFILEIRADO >> "$LOG" 2>&1
r="$(ferr sh -c "stat -c '%U %a %n' /caixa/controle.conf /caixa/indice.jsonl")"; reg "depois do controle: $(echo "$r" | tr '
' ' ')"
echo "$r" | grep -q '^nekkora 600 /caixa/controle.conf$' && echo "$r" | grep -q '^nekkora 600 /caixa/indice.jsonl$'
confere "controle.conf (gravado pela ferramenta) e indice com modo 600" $?
r="$(sonda aviso)"; [ "$r" = "desfecho=ENFILEIRADO" ]; confere "regra gravada pela ferramenta consumida pelo transporte" $?
r="$(ferr sh -c "stat -c '%U %a %n' /caixa/controle.conf")"; reg "controle regravado pelo transporte: $r"
[ "$r" = "nekkora 600 /caixa/controle.conf" ]; confere "controle regravado pelo transporte continua 600" $?

# ---- indice: exportacao sanitizada e recusa de linha fora do formato
bash "$H" caixa "$ID" exportar-indice >> "$LOG" 2>&1; confere "exportar-indice (sanitizado por lib/indice_caixa.py)" $?
cp "$SAI/caixa_indice.jsonl" "$EV/caixa_indice.jsonl"
[ "$(wc -l < "$EV/caixa_indice.jsonl")" = 22 ]; confere "indice exportado com 22 linhas (18 + 4 avisos entregues)" $?
! grep -qE 'tk-|@p1c\.test' "$EV/caixa_indice.jsonl"; confere "indice exportado sem token nem endereco" $?
ferr sh -c 'printf "%s\n" "{\"arquivo\":\"avisos/x.json\",\"token\":\"vazamento\"}" >> /caixa/indice.jsonl'
bash "$H" caixa "$ID" exportar-indice > "$TMP/x.txt" 2>&1; rc=$?; reg "exportar com linha fora do formato: $(grep -E 'indice recusado|ERRO' "$TMP/x.txt" | tr '
' ' ')"
[ $rc != 0 ] && grep -q 'indice da caixa recusado pela sanitizacao' "$TMP/x.txt" && grep -q 'indice recusado: linha 23: campos inesperados' "$TMP/x.txt" && [ ! -s "$SAI/caixa_indice.jsonl" ]
confere "indice com linha fora do formato recusado inteiro (nada exportado)" $?

# ---- destruir pelo rotulo; tag temporaria e segredos ficticios removidos
HOMOLOG_CONFIRMA_DESTRUIR="$ID" bash "$H" destruir "$ID" >> "$LOG" 2>&1; confere "homologacao.sh destruir $ID" $?
[ -z "$(docker volume ls -q --filter "label=nekkora.homologacao=$ID")" ]; confere "volume da caixa removido pelo rotulo" $?
docker rmi "nekkora-homologacao-platform:$COMMIT" >> "$LOG" 2>&1; confere "tag temporaria removida (a imagem de teste segue com a propria tag)" $?
rm -rf "$TMP"
reg "fim=$(date -u +%Y-%m-%dT%H:%M:%SZ) ok=$ok_total falhas=$falhas saidas=ops/homologacao/saidas/$ID"
echo "resultado: ok=$ok_total falhas=$falhas"
[ "$falhas" = 0 ]
