#!/usr/bin/env bash
# Prova do gateway do ensaio-frontend (F1.5, P-A): roteamento das APIs, erros das APIs sem a aplicacao, URLs diretas
# das telas e arquivos inexistentes com 404 - num nginx DESCARTAVEL (imagem do gateway da homologacao), com a
# configuracao GERADA de verdade (gateway/nginx.modelo -> lib/redes.py gerar -> lib/gateway_frontend.py aplicar).
#
# Unica adaptacao de teste (declarada): os dois "server" dos upstreams sao trocados por servicos SIMULADOS no proprio
# container (127.0.0.1:18081 = platform, :18082 = portal), que respondem JSON com o nome do servico e a URI; nada mais
# do arquivo gerado muda (conferido por diff). TLS: CA e certificado descartaveis gerados aqui; a conexao confere o
# certificado (--cacert) e o nome (SNI), sem ignorar erro de certificado.
#
# Recursos: UM container rotulado nekkora.teste=gateway-frontend-<execucao>, removido pelo ID no fim (trap). Nenhum
# ambiente de homologacao e tocado. Opcional: TESTE_FRONTEND_DIST=<dist do web> repete as provas da aplicacao com o
# build real (index e um arquivo de assets).
#
# Uso: bash testes/test_gateway_frontend.sh [dir_de_saida]   (codigo 0 = todas as provas OK)
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
IMAGEM="${HOMOLOG_IMG_NGINX:-nginx:1.29-alpine}"
EXEC="gwf-$(date -u +%Y%m%dT%H%M%SZ)-$$"
SAIDA="${1:-}"
TMP="$(mktemp -d)"
CID=""
falhas=0
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }
mixed() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
limpa() {
    if [ -n "$CID" ]; then docker rm -f "$CID" > /dev/null 2>&1 && echo "container de teste removido pelo ID: ${CID:0:12}"; fi
    rm -rf "${TMP:?}"
}
trap limpa EXIT

ORG=6f1c2a9e-3b4d-4e5f-8a7b-9c0d1e2f3a4b
EXE=0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d
HOST=frontend.nekkora.test

echo "== 1. configuracao gerada (modelo -> redes.py gerar -> gateway_frontend.py aplicar)"
python "$AQUI/lib/redes.py" gerar "$AQUI/gateway/nginx.modelo" "$TMP/nginx.base.conf" INTERNA=10.252.99 > /dev/null || { nok "geracao do nginx base"; exit 1; }
python "$AQUI/lib/gateway_frontend.py" aplicar "$TMP/nginx.base.conf" "$TMP/nginx.frontend.conf" > /dev/null || { nok "aplicacao do bloco"; exit 1; }
python "$AQUI/lib/gateway_frontend.py" conferir "$TMP/nginx.base.conf" "$TMP/nginx.frontend.conf" > /dev/null && ok "G00 gerado + bloco da aplicacao, e mais nada" || nok "G00 conferencia do gerado"
python "$AQUI/lib/gateway_frontend.py" aplicar "$TMP/nginx.frontend.conf" "$TMP/duas_vezes.conf" > /dev/null 2>&1 && nok "G00b aplicar duas vezes deveria ser recusado" || ok "G00b aplicar sobre o ja aplicado: recusado"

# servicos simulados: so os dois "server" dos upstreams mudam (cada um exatamente uma vez) + dois server {} no fim do http
python - "$TMP/nginx.frontend.conf" "$TMP/nginx.teste.conf" <<'PY' || { nok "adaptacao de teste"; exit 1; }
import sys
s = open(sys.argv[1], encoding='utf-8').read()
for a, b in (('server 10.252.99.20:8081;', 'server 127.0.0.1:18081;'), ('server 10.252.99.21:8082;', 'server 127.0.0.1:18082;')):
    assert s.count(a) == 1, a
    s = s.replace(a, b)
stubs = '''
    # ---- SERVICOS SIMULADOS (so neste teste)
    server {
        listen 127.0.0.1:18081;
        default_type application/json;
        location = /api/v1/csrf { return 200 '{"svc":"platform","ok":true}'; }
        location = /api/v1/teste/erro500 { return 500 '{"svc":"platform","erro":500}'; }
        location = /api/v1/teste/derrubar { return 444; }
        location / { return 404 '{"svc":"platform","uri":"$uri"}'; }
    }
    server {
        listen 127.0.0.1:18082;
        default_type application/json;
        location / { return 404 '{"svc":"portal","uri":"$uri"}'; }
    }
}
'''
fim = s.rstrip()
assert fim.endswith('}')
s = fim[:-1] + stubs
open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(s)
PY
# reversao exata da adaptacao (upstreams de volta + servicos simulados retirados) = arquivo gerado
python - "$TMP/nginx.frontend.conf" "$TMP/nginx.teste.conf" <<'PY' && ok "G00c adaptacao de teste: so os upstreams e os servicos simulados" || nok "G00c adaptacao de teste alterou mais que o declarado"
import sys
g = open(sys.argv[1], encoding='utf-8').read()
t = open(sys.argv[2], encoding='utf-8').read()
t = t[:t.index('\n    # ---- SERVICOS SIMULADOS')] + '}'
t = t.replace('server 127.0.0.1:18081;', 'server 10.252.99.20:8081;').replace('server 127.0.0.1:18082;', 'server 10.252.99.21:8082;')
sys.exit(0 if t == g.rstrip() else 1)
PY

echo "== 2. TLS descartavel e aplicacao sintetica"
mkdir -p "$TMP/tls" "$TMP/spa/assets"
(
    cd "$TMP/tls" && export MSYS2_ARG_CONV_EXCL='*'
    openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=Teste gateway frontend CA" -addext "basicConstraints=critical,CA:true" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" -keyout ca.key -out ca.crt 2>/dev/null
    printf 'subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature,keyEncipherment\n' "$HOST" > g.ext
    openssl req -newkey rsa:2048 -nodes -subj "/CN=$HOST" -keyout gateway.key -out g.csr 2>/dev/null
    openssl x509 -req -in g.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 1 -extfile g.ext -out gateway.crt 2>/dev/null
    chmod 644 gateway.key
) || { nok "certificados de teste"; exit 1; }
MARCA="index-sintetico-$EXEC"
printf '<!doctype html><html><head><title>teste</title></head><body><!-- %s --><div id="root"></div><script type="module" src="/assets/app-1a2b3c.js"></script></body></html>\n' "$MARCA" > "$TMP/spa/index.html"
printf 'console.log("asset sintetico %s");\n' "$EXEC" > "$TMP/spa/assets/app-1a2b3c.js"
printf '.sintetico-%s { color: #123456; }\n' "$EXEC" > "$TMP/spa/assets/app-1a2b3c.css"
printf 'User-agent: *\nDisallow: /\n' > "$TMP/spa/robots.txt"

echo "== 3. nginx descartavel ($IMAGEM)"
PORTA="$(python -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
monta=(-v "$(mixed "$TMP/nginx.teste.conf"):/etc/nginx/nginx.conf:ro" -v "$(mixed "$TMP/tls/gateway.crt"):/tls/gateway.crt:ro"
       -v "$(mixed "$TMP/tls/gateway.key"):/tls/gateway.key:ro" -v "$(mixed "$TMP/spa"):/spa:ro")
if MSYS_NO_PATHCONV=1 docker run --rm --label "nekkora.teste=gateway-frontend-$EXEC" "${monta[@]}" "$IMAGEM" nginx -t > "$TMP/nginx_t.txt" 2>&1; then
    ok "G01 nginx -t aceita a configuracao gerada"
else
    nok "G01 nginx -t"; cat "$TMP/nginx_t.txt"; exit 1
fi
CID="$(MSYS_NO_PATHCONV=1 docker run -d --label "nekkora.teste=gateway-frontend-$EXEC" -p "127.0.0.1:$PORTA:443" "${monta[@]}" "$IMAGEM")" || { nok "subida do nginx de teste"; exit 1; }
URL="https://$HOST:$PORTA"
pede() {  # metodo caminho -> grava status, content-type e corpo em $TMP/r.*
    curl -s --ssl-no-revoke --cacert "$TMP/tls/ca.crt" --resolve "$HOST:$PORTA:127.0.0.1" -X "$1" -o "$TMP/r.corpo" -D "$TMP/r.cab" \
        -w '%{http_code}' "$URL$2" > "$TMP/r.status" 2>/dev/null || echo "000" > "$TMP/r.status"
}
for i in $(seq 1 30); do pede GET /index.html; [ "$(cat "$TMP/r.status")" = 200 ] && break; sleep 1; done
st() { cat "$TMP/r.status"; }
corpo() { cat "$TMP/r.corpo"; }
eh_index() { cmp -s "$TMP/r.corpo" "$TMP/spa/index.html"; }
tem_marca() { grep -q "$MARCA" "$TMP/r.corpo"; }
cab() { grep -i "^$1:" "$TMP/r.cab" | tr -d '\r' | cut -d' ' -f2-; }

echo "== 4. provas"
prova() {  # id descricao metodo caminho condicao
    pede "$3" "$4"
    if eval "$5"; then ok "$1 $2 ($3 $4 -> $(st))"; else nok "$1 $2 ($3 $4 -> $(st); corpo: $(head -c 160 "$TMP/r.corpo" | tr '\n' ' '))"; fi
}
# aplicacao: raiz, URLs diretas das telas, index sem cache
prova G02 "raiz: aplicacao, sem cache" GET / '[ "$(st)" = 200 ] && eh_index && [ "$(cab cache-control)" = no-store ]'
prova G03 "URL direta da organizacao" GET "/o/$ORG" '[ "$(st)" = 200 ] && eh_index'
prova G04 "URL direta: detalhe da execucao" GET "/o/$ORG/execucoes/$EXE" '[ "$(st)" = 200 ] && eh_index && [ "$(cab cache-control)" = no-store ]'
prova G05 "URL direta: lista com filtros na query" GET "/o/$ORG/execucoes?periodo=7d&estado=failed" '[ "$(st)" = 200 ] && eh_index'
prova G06 "URL direta: workspace e automacao" GET "/o/$ORG/workspaces/$EXE" '[ "$(st)" = 200 ] && eh_index'
prova G07 "URL direta: login da organizacao" GET "/o/$ORG/login" '[ "$(st)" = 200 ] && eh_index'
prova G08 "index.html direto, sem cache" GET /index.html '[ "$(st)" = 200 ] && eh_index && [ "$(cab cache-control)" = no-store ]'
# arquivos estaticos
prova G09 "arquivo do build existente: bytes exatos" GET /assets/app-1a2b3c.js '[ "$(st)" = 200 ] && cmp -s "$TMP/r.corpo" "$TMP/spa/assets/app-1a2b3c.js"'
prova G10 "arquivo do build inexistente: 404, nunca a aplicacao" GET /assets/app-inexistente.js '[ "$(st)" = 404 ] && ! tem_marca'
prova G11 "diretorio de assets: 404, nunca a aplicacao" GET /assets/ '[ "$(st)" = 404 ] && ! tem_marca'
prova G12 "arquivo na raiz inexistente (favicon.ico): 404" GET /favicon.ico '[ "$(st)" = 404 ] && ! tem_marca'
prova G13 "arquivo na raiz existente (robots.txt)" GET /robots.txt '[ "$(st)" = 200 ] && cmp -s "$TMP/r.corpo" "$TMP/spa/robots.txt"'
prova G14 "rota com extensao inexistente: 404" GET "/o/$ORG/relatorio.json" '[ "$(st)" = 404 ] && ! tem_marca'
# APIs: chegam aos servicos certos; erros passam intactos, nunca a aplicacao
prova G15 "/api/v1/ chega a Platform (200)" GET /api/v1/csrf '[ "$(st)" = 200 ] && corpo | grep -q "\"svc\":\"platform\"" && ! tem_marca'
prova G16 "404 da Platform passa intacto (JSON do servico)" GET "/api/v1/o/$ORG/workspaces" '[ "$(st)" = 404 ] && corpo | grep -q "\"svc\":\"platform\"" && ! tem_marca'
prova G17 "rota de autenticacao (regex do modelo) chega a Platform" POST "/api/v1/o/$ORG/auth/login" '[ "$(st)" = 404 ] && corpo | grep -q "\"uri\":\"/api/v1/o/$ORG/auth/login\"" && ! tem_marca'
prova G18 "500 da Platform passa intacto" GET /api/v1/teste/erro500 '[ "$(st)" = 500 ] && corpo | grep -q "\"erro\":500" && ! tem_marca'
prova G19 "Platform derruba a conexao: 502 do gateway, nunca a aplicacao" GET /api/v1/teste/derrubar '[ "$(st)" = 502 ] && ! tem_marca'
prova G20 "/portal/api/v1/ chega ao Portal" GET "/portal/api/v1/o/$ORG/workspaces" '[ "$(st)" = 404 ] && corpo | grep -q "\"svc\":\"portal\"" && ! tem_marca'
prova G21 "caminho de API desconhecido (/api/v2): 404 JSON do gateway" GET /api/v2/qualquer '[ "$(st)" = 404 ] && [ "$(corpo)" = "{\"error\":\"not_found\"}" ] && ! tem_marca'
prova G22 "/api sem barra: 404 JSON, nunca a aplicacao" GET /api '[ "$(st)" = 404 ] && [ "$(corpo)" = "{\"error\":\"not_found\"}" ]'
prova G23 "/portal fora da API: 404 JSON" GET /portal/qualquer '[ "$(st)" = 404 ] && [ "$(corpo)" = "{\"error\":\"not_found\"}" ]'
prova G24 "POST numa rota da aplicacao nao chega a servico nenhum" POST "/o/$ORG/execucoes" '! corpo | grep -q "\"svc\":"'
# P-C-R1: tipos dos arquivos do build (sem eles o navegador recusa o script de modulo: tela em branco)
tipo() { cab content-type | cut -d';' -f1; }
prova G25 "index.html: text/html" GET /index.html '[ "$(st)" = 200 ] && [ "$(tipo)" = text/html ] && eh_index'
prova G26 "JS do build: text/javascript e bytes exatos" GET /assets/app-1a2b3c.js '[ "$(st)" = 200 ] && [ "$(tipo)" = text/javascript ] && cmp -s "$TMP/r.corpo" "$TMP/spa/assets/app-1a2b3c.js"'
prova G27 "CSS do build: text/css e bytes exatos" GET /assets/app-1a2b3c.css '[ "$(st)" = 200 ] && [ "$(tipo)" = text/css ] && cmp -s "$TMP/r.corpo" "$TMP/spa/assets/app-1a2b3c.css"'
prova G28 "rota da aplicacao (fallback): index com text/html" GET "/o/$ORG/login" '[ "$(st)" = 200 ] && [ "$(tipo)" = text/html ] && eh_index'
# (a pagina de erro do proprio nginx e sempre HTML; o que importa: 404 e NUNCA o index da aplicacao)
prova G29 "JS inexistente: 404 com a pagina de erro do nginx, nunca o index" GET /assets/nao-existe-9z.js '[ "$(st)" = 404 ] && ! eh_index && ! tem_marca && ! grep -q "id=\"root\"" "$TMP/r.corpo" && grep -q "<title>404 Not Found</title>" "$TMP/r.corpo"'
prova G29b "CSS inexistente: 404, nunca o index" GET /assets/nao-existe-9z.css '[ "$(st)" = 404 ] && ! eh_index && ! tem_marca'
prova G29c "erros das APIs continuam JSON: 404 do gateway em /api" GET /api/v2/qualquer '[ "$(st)" = 404 ] && [ "$(tipo)" = application/json ]'
prova G29d "erros das APIs continuam JSON: resposta da Platform" GET /api/v1/csrf '[ "$(st)" = 200 ] && [ "$(tipo)" = application/json ]'

if [ -n "${TESTE_FRONTEND_DIST:-}" ]; then
    echo "== 5. build real do web ($TESTE_FRONTEND_DIST)"
    docker rm -f "$CID" > /dev/null; CID=""
    real=(-v "$(mixed "$TMP/nginx.teste.conf"):/etc/nginx/nginx.conf:ro" -v "$(mixed "$TMP/tls/gateway.crt"):/tls/gateway.crt:ro"
          -v "$(mixed "$TMP/tls/gateway.key"):/tls/gateway.key:ro" -v "$(mixed "$TESTE_FRONTEND_DIST"):/spa:ro")
    CID="$(MSYS_NO_PATHCONV=1 docker run -d --label "nekkora.teste=gateway-frontend-$EXEC" -p "127.0.0.1:$PORTA:443" "${real[@]}" "$IMAGEM")"
    for i in $(seq 1 30); do pede GET /index.html; [ "$(st)" = 200 ] && break; sleep 1; done
    ASSET="$(cd "$TESTE_FRONTEND_DIST" && ls assets/*.js | head -1)"
    prova G30 "build real: URL direta recebe o index do build" GET "/o/$ORG/execucoes/$EXE" '[ "$(st)" = 200 ] && cmp -s "$TMP/r.corpo" "$TESTE_FRONTEND_DIST/index.html"'
    prova G31 "build real: arquivo de assets com bytes exatos" GET "/$ASSET" '[ "$(st)" = 200 ] && cmp -s "$TMP/r.corpo" "$TESTE_FRONTEND_DIST/$ASSET"'
    prova G32 "build real: API continua na Platform" GET "/api/v1/o/$ORG/me" '[ "$(st)" = 404 ] && corpo | grep -q "\"svc\":\"platform\""'
    CSS="$(cd "$TESTE_FRONTEND_DIST" && ls assets/*.css | head -1)"
    prova G33 "build real: index.html com text/html e bytes exatos" GET /index.html '[ "$(st)" = 200 ] && [ "$(tipo)" = text/html ] && cmp -s "$TMP/r.corpo" "$TESTE_FRONTEND_DIST/index.html"'
    prova G34 "build real: JS com text/javascript e bytes exatos" GET "/$ASSET" '[ "$(st)" = 200 ] && [ "$(tipo)" = text/javascript ] && cmp -s "$TMP/r.corpo" "$TESTE_FRONTEND_DIST/$ASSET"'
    prova G35 "build real: CSS com text/css e bytes exatos" GET "/$CSS" '[ "$(st)" = 200 ] && [ "$(tipo)" = text/css ] && cmp -s "$TMP/r.corpo" "$TESTE_FRONTEND_DIST/$CSS"'
    prova G36 "build real: favicon.ico com image/x-icon" GET /favicon.ico '[ "$(st)" = 200 ] && [ "$(tipo)" = image/x-icon ] && cmp -s "$TMP/r.corpo" "$TESTE_FRONTEND_DIST/favicon.ico"'
fi

docker logs "$CID" > "$TMP/nginx.log" 2>&1 || true
if [ -n "$SAIDA" ]; then
    mkdir -p "$SAIDA"
    cp "$TMP/nginx.frontend.conf" "$SAIDA/nginx.frontend.gerado.conf"
    cp "$TMP/nginx.teste.conf" "$SAIDA/nginx.teste.conf"
    cp "$TMP/nginx.log" "$SAIDA/nginx_log_do_teste.txt"
fi
restantes="$(docker ps -aq --filter "label=nekkora.teste=gateway-frontend-$EXEC" | wc -l)"
echo "containers do teste ainda presentes antes da limpeza: $restantes (o trap remove pelo ID)"
[ "$falhas" = 0 ] && echo "RESULTADO: gateway do ensaio-frontend OK" || echo "RESULTADO: $falhas falha(s)"
exit $([ "$falhas" = 0 ] && echo 0 || echo 1)
