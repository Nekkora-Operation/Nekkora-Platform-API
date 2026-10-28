#!/usr/bin/env bash
# Validacao ESTATICA das adaptacoes da homologacao para a 016 (P1b; desenho P1a, sec. 9.1).
# NAO cria container, rede, volume nem imagem: sintaxe dos scripts, deteccao de sobreposicao de CIDR com casos
# sinteticos, configuracao gerada num diretorio temporario e "docker compose config" (so renderiza) com valores de
# exemplo, sem nenhum segredo. Uso: ops/homologacao/homologacao.sh validar-config
set -uo pipefail
AQUI="${AQUI:-$(cd "$(dirname "$0")/.." && pwd)}"
REPO="$(cd "$AQUI/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
falhas=0
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }
confere() { if eval "$2"; then ok "$1"; else nok "$1"; fi; }

echo "== 1. sintaxe"
for s in "$AQUI"/homologacao.sh "$AQUI"/mapa_privilegios.sh "$AQUI"/implantacao/*.sh "$AQUI"/testes/*.sh; do
    confere "bash -n $(basename "$s")" "bash -n '$s' 2>/dev/null"
done
for p in "$AQUI"/lib/*.py "$AQUI"/testes/*.py; do
    # ast.parse: confere a sintaxe sem gravar __pycache__
    confere "sintaxe python $(basename "$p")" "python -c 'import ast,sys; ast.parse(open(sys.argv[1], encoding=\"utf-8\").read())' '$p' 2>/dev/null"
done

echo "== 2. sobreposicao de CIDR (casos sinteticos, sem Docker)"
R="$AQUI/lib/redes.py"
sobre() { printf '%s\n' "${@:3}" | python "$R" sobreposicao "$1" "$2" > /dev/null 2>&1; }
confere "sem sobreposicao: 10.252.20/24 e 10.252.21/24 x redes da hml03" "sobre 10.252.20.0/24 10.252.21.0/24 10.252.10.0/24 10.252.11.0/24"
confere "igualdade recusada" "! sobre 10.252.10.0/24 10.252.21.0/24 10.252.10.0/24"
confere "rede MAIOR existente contendo a candidata recusada" "! sobre 10.252.20.0/24 10.252.21.0/24 10.252.0.0/16"
confere "rede MENOR existente dentro da candidata recusada" "! sobre 10.252.20.0/24 10.252.21.0/24 10.252.20.128/25"
confere "candidatas sobrepostas entre si recusadas" "! sobre 10.252.20.0/24 10.252.20.0/24"
confere "IPv6 existente nao interfere" "sobre 10.252.20.0/24 10.252.21.0/24 fd00::/64"
confere "linha ilegivel recusada por seguranca" "! sobre 10.252.20.0/24 10.252.21.0/24 lixo"
confere "prefixo valido 10.252.20" "python '$R' prefixo 10.252.20"
for inv in 10.252 10.252.020 10.256.1 a.b.c 10.252.20.0; do
    confere "prefixo invalido $inv recusado" "! python '$R' prefixo $inv"
done

echo "== 3. configuracao gerada (diretorio temporario)"
python "$R" gerar "$AQUI/postgres/pg_hba.modelo" "$TMP/pg_hba.conf" INTERNA=10.252.20 && \
python "$R" gerar "$AQUI/postgres/pg_ident.modelo" "$TMP/pg_ident.conf" && \
python "$R" gerar "$AQUI/gateway/nginx.modelo" "$TMP/nginx.conf" INTERNA=10.252.20 || nok "geracao"
confere "nenhum marcador restante" "! grep -q '@@' '$TMP'/*.conf"
confere "nenhum IP da rede padrao no pg_hba/nginx gerados" "! grep -q '10\.252\.10\.' '$TMP/pg_hba.conf' '$TMP/nginx.conf'"
# regras do desenho anterior preservadas: removidos comentarios, cabecalho e linhas da 016, o gerado = o original
# com o prefixo trocado
diff <(grep -vE '^\s*(#|$)' "$AQUI/postgres/pg_hba.conf" | sed 's/10\.252\.10\./P./') \
     <(grep -vE '^\s*(#|$)' "$TMP/pg_hba.conf" | grep -vE 'op_|nekkora_ops_(ca|intake)' | sed 's/10\.252\.20\./P./') > "$TMP/hba.diff" \
    && ok "pg_hba gerado = regras anteriores (com o prefixo novo) + linhas da 016" || { nok "pg_hba gerado difere das regras anteriores"; cat "$TMP/hba.diff"; }
confere "op_* so por certificado, so do .33" "[ \"\$(grep -cE '^hostssl +nekkora +/\^op_ +10\.252\.20\.33/32 +cert map=ops clientname=CN\$' '$TMP/pg_hba.conf')\" = 1 ] && ! grep -E 'op_' '$TMP/pg_hba.conf' | grep -v '^#' | grep -q scram"
confere "linhas da 016 antes dos reject finais" "[ \"\$(grep -nE 'op_|nekkora_ops_ca|nekkora_ops_intake' '$TMP/pg_hba.conf' | grep -v ':#' | tail -1 | cut -d: -f1)\" -lt \"\$(grep -nE '^host +all +all' '$TMP/pg_hba.conf' | head -1 | cut -d: -f1)\" ]"
confere "pg_ident: CN = papel op_*" "grep -qE '^ops +\"/\^\(op_\[a-z0-9_\]\{2,40\}\)\\\$\" +\\\\1\$' '$TMP/pg_ident.conf'"
diff <(sed 's/10\.252\.10\./P./' "$AQUI/gateway/nginx.conf") <(grep -v '^# MODELO\|^# continua o dos' "$TMP/nginx.conf" | sed 's/10\.252\.20\./P./') > "$TMP/nginx.diff" \
    && ok "nginx gerado = original com o prefixo novo" || { nok "nginx gerado difere"; cat "$TMP/nginx.diff"; }

echo "== 4. docker compose config (so renderiza; valores de exemplo, sem segredo)"
S="$TMP/segredos"; mkdir -p "$S/tls" "$S/redis" "$TMP/mig" "$TMP/config"
for f in postgres_admin flyway.conf platform.env portal.env operacao.env tls/ca.crt tls/ops-ca.crt tls/ops-ca.key tls/gateway.crt tls/gateway.key; do : > "$S/$f"; done
cp "$TMP"/*.conf "$TMP/config/"
export HOMOLOG_PROJETO=nekkora-homologacao-ensaio-validacao HOMOLOG_ID=ensaio-validacao HOMOLOG_SEGREDOS="$S" HOMOLOG_COMMIT=0000000 \
       HOMOLOG_PORTA=55999 HOMOLOG_MIGRACOES="$TMP/mig" HOMOLOG_SQL="$AQUI/sql" HOMOLOG_CONFIG="$TMP/config" \
       HOMOLOG_REDE_INTERNA=10.252.20 HOMOLOG_REDE_BORDA=10.252.21
B="$AQUI/compose.homolog.yml"; O16="$AQUI/compose.016.yml"; CX="$AQUI/compose.caixa.yml"; O21="$AQUI/compose.021.yml"; M365="$AQUI/compose.m365.yml"
render() { docker compose "$@" config 2>"$TMP/compose.err"; }
if render -f "$B" > "$TMP/base.yml"; then ok "base renderiza"; else nok "base nao renderiza: $(head -3 "$TMP/compose.err")"; fi
if render -f "$B" -f "$O16" --profile operacao > "$TMP/016.yml"; then ok "base + 016 renderiza"; else nok "016 nao renderiza: $(head -3 "$TMP/compose.err")"; fi
if render -f "$B" -f "$O16" -f "$CX" --profile operacao --profile caixa > "$TMP/caixa.yml"; then ok "base + 016 + caixa renderiza"; else nok "caixa nao renderiza: $(head -3 "$TMP/compose.err")"; fi
if render -f "$B" -f "$O16" -f "$CX" -f "$O21" --profile operacao --profile caixa > "$TMP/021.yml"; then ok "base + 016 + caixa + 021 renderiza"; else nok "021 nao renderiza: $(head -3 "$TMP/compose.err")"; fi
# transporte REAL (missao do e-mail real em homologacao): renderiza com valores de exemplo; a Platform continua so na
# interna, o unico caminho de saida e o proxy-m365 (Entra, Graph e o Secrets Manager da regiao), cliente e certificado SO
# do segredo da AWS (nenhum arquivo de chave), credenciais temporarias e chave da fila por arquivo de segredos, lista de
# destinatarios obrigatoria; nunca junto da caixa
mkdir -p "$S/m365"; echo NEKKORA_SENTINELA_AWS=1 > "$S/m365/aws.env"; echo NEKKORA_SENTINELA_FILA=1 > "$S/m365/fila.env"
echo NEKKORA_SENTINELA_PLATFORM=1 > "$S/platform.env"
if env HOMOLOG_M365_DESTINATARIOS='controle+*@exemplo.test' docker compose -f "$B" -f "$O16" -f "$O21" -f "$M365" config > "$TMP/m365.yml" 2>"$TMP/compose.err"; then
    ok "base + 016 + 021 + m365 renderiza"
else nok "m365 nao renderiza: $(head -3 "$TMP/compose.err")"; fi
env HOMOLOG_M365_DESTINATARIOS='controle+*@exemplo.test' docker compose -f "$B" -f "$O16" -f "$O21" -f "$M365" config --format json > "$TMP/m365.json" 2>/dev/null
confere "m365: Platform so na rede interna; proxy na interna e na saida propria; transporte m365 com o proxy, o tenant do Entra, o segredo da AWS (sa-east-1) e a lista de destinatarios" "python - '$TMP/m365.json' <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding='utf-8'))
plat, prox = c['services']['platform'], c['services']['proxy-m365']
env = plat.get('environment', {})
ok = (set(plat['networks']) == {'interna'} and set(prox['networks']) == {'interna', 'saida_m365'} and prox.get('read_only') is True
      and env.get('NEKKORA_MAIL_TRANSPORTE') == 'm365' and env.get('NEKKORA_M365_PROXY') == 'proxy-m365:3128'
      and env.get('NEKKORA_M365_TENANT') == 'ae4ed48d-e7d8-4f24-83f2-443e997c5a65' and env.get('NEKKORA_MAIL_REMETENTE') == 'no-reply@nekkora.com'
      and env.get('NEKKORA_M365_SEGREDO_AWS') == 'nekkora/homologacao/platform/m365' and env.get('NEKKORA_M365_AWS_REGIAO') == 'sa-east-1'
      and env.get('NEKKORA_MAIL_DESTINATARIOS_PERMITIDOS') == 'controle+*@exemplo.test'
      and not any(k in env for k in ('NEKKORA_M365_CLIENT', 'NEKKORA_M365_CERT', 'NEKKORA_M365_CERT_SENHA'))
      and not plat.get('secrets')
      and c['networks']['interna'].get('internal') is True and not c['networks']['saida_m365'].get('internal'))
sys.exit(0 if ok else 1)
PY"
confere "m365: variaveis de platform.env, m365/aws.env e m365/fila.env juntas na Platform (a sobreposicao acrescenta, nao troca)" "python - '$TMP/m365.json' <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding='utf-8'))
env = c['services']['platform'].get('environment', {})
sys.exit(0 if all(env.get(k) == '1' for k in ('NEKKORA_SENTINELA_PLATFORM', 'NEKKORA_SENTINELA_AWS', 'NEKKORA_SENTINELA_FILA')) else 1)
PY"
: > "$S/platform.env"
confere "m365: sem lista de destinatarios a sobreposicao nao renderiza" "! env -u HOMOLOG_M365_DESTINATARIOS docker compose -f '$B' -f '$O16' -f '$O21' -f '$M365' config > /dev/null 2>&1"
confere "m365: politica do proxy so CONNECT 443 para Entra, Graph e Secrets Manager sa-east-1 (tudo o mais negado)"     "grep -qx 'acl m365 dstdomain login.microsoftonline.com graph.microsoft.com secretsmanager.sa-east-1.amazonaws.com' '$AQUI/proxy/squid.conf' && grep -qx 'http_access allow CONNECT m365' '$AQUI/proxy/squid.conf'      && grep -qx 'http_access deny all' '$AQUI/proxy/squid.conf' && grep -qx 'http_access deny CONNECT !porta_tls' '$AQUI/proxy/squid.conf' && [ \"\$(grep -c '^http_access allow' '$AQUI/proxy/squid.conf')\" = 1 ] && [ \"\$(grep -c '^acl ' '$AQUI/proxy/squid.conf')\" = 3 ]"
confere "m365: tmpfs do proxy do usuario proxy (uid 13), sem o que o squid nao sobe com a raiz somente leitura" "python - '$TMP/m365.json' <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding='utf-8'))
t = c['services']['proxy-m365'].get('tmpfs') or []
sys.exit(0 if len(t) == 4 and all('uid=13' in x and 'gid=13' in x for x in t) else 1)
PY"
confere "m365: teto global de tentativas na Platform (20, diretorio montado de m365/tentativas; fila e prova no mesmo container)" "python - '$TMP/m365.json' <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
plat = c['services']['platform']
env = plat.get('environment') or {}
vols = [v for v in plat.get('volumes') or [] if v.get('target') == '/tentativas']
ok = (env.get('NEKKORA_MAIL_TENTATIVAS_DIR') == '/tentativas' and env.get('NEKKORA_MAIL_TENTATIVAS_MAXIMO') == '20'
      and len(vols) == 1 and vols[0]['type'] == 'bind' and vols[0]['source'].replace('\\\\', '/').endswith('/m365/tentativas')
      and not vols[0].get('read_only') and any(v.get('target') == '/tls/ca.crt' for v in plat.get('volumes') or []))
sys.exit(0 if ok else 1)
PY"
confere "m365: runner confere o teto antes de ligar e de cada prova, exige o evento de teto na partida e tem tentativas/autenticacao" "grep -q 'teto global de tentativas atingido' '$AQUI/homologacao.sh' && grep -q 'prova_envio teto_atingido' '$AQUI/homologacao.sh' && grep -q 'evento=mail.teto_tentativas_configurado maximo=\$TETO_MAX ' '$AQUI/homologacao.sh' && grep -q '^    tentativas)$' '$AQUI/homologacao.sh' && grep -q '^    autenticacao)$' '$AQUI/homologacao.sh' && grep -q 'ProvaAutenticacaoM365' '$AQUI/homologacao.sh'"
confere "m365: m365-aws.sh so apaga a chave no confirmar (publicar sem trap de remocao)" "! grep -q \"trap 'apagar'\" '$AQUI/aws/m365-aws.sh' && grep -q '^confirmar()' '$AQUI/aws/m365-aws.sh' && grep -q 'chave_privada_no_disco=MANTIDA' '$AQUI/aws/m365-aws.sh'"
confere "m365: com o transporte real, a lista de destinatarios vale para todo comando compose (prova, autenticacao, desligar)" "grep -q 'compose.m365.yml interpola a lista em TODO comando compose' '$AQUI/homologacao.sh' && grep -q 'HOMOLOG_M365_DESTINATARIOS=\"\$(python \"\$AQUI/lib/email_real.py\" destinatarios' '$AQUI/homologacao.sh'"
confere "m365: desligar confere pelo Docker que o proxy parou e para pelo rotulo se o compose falhar" "grep -q 'proxy-m365 CONTINUA no ar' '$AQUI/homologacao.sh' && grep -q '^    proxy_no_ar()' '$AQUI/homologacao.sh'"
confere "m365: prova e autenticacao sem conversao de caminho do Git Bash no docker exec" "[ \"\$(grep -c 'out=\"\$(MSYS_NO_PATHCONV=1 compose exec -T platform java' '$AQUI/homologacao.sh')\" = 2 ]"
confere "m365: regiao do segredo igual no compose e no proxy" "grep -q 'NEKKORA_M365_AWS_REGIAO: sa-east-1' '$M365' && grep -q 'secretsmanager.sa-east-1.amazonaws.com' '$AQUI/proxy/squid.conf'"
confere "m365: sobreposicao usada SO pela acao email-real do runner (troca com a caixa)" "python - '$AQUI/homologacao.sh' <<'PY'
import re, sys
h = open(sys.argv[1], encoding='utf-8').read()
i = h.rindex('\n# ====', 0, h.index('\nemail-real)\n')); j = h.index('\n# ====', h.index('\nemail-real)\n'))
fora = h[:i] + h[j:]
sys.exit(0 if 'compose.m365.yml' not in fora and h[i:j].count('compose.m365.yml') >= 2 else 1)
PY"
confere "m365: email-real so em ensaio-*, recusa sem credenciais/lista/fila vazia ANTES de mudar o ambiente, desligar corta o proxy primeiro" "python - '$AQUI/homologacao.sh' <<'PY'
import sys
h = open(sys.argv[1], encoding='utf-8').read()
i = h.index('\nemail-real)\n'); j = h.index('\n# ====', i); b = h[i:j]
lig = b[b.index('    ligar)'):b.index('    desligar)')]
des = b[b.index('    desligar)'):b.index('    prova)')]
antes = lig[:lig.index('grava_ambiente m365')]
ok = ('^ensaio-' in b[:b.index('case \"\$sub\"')] and 'credenciais \"\$M/aws.env\"' in antes and 'destinatarios \"\$M/destinatarios.txt\"' in antes
      and '[ \"\$(fila_ativas)\" = 0 ]' in antes and 'config -q' in antes and 'AwsSecretsManager.java' in antes and 'confere_liberacao' in antes
      and des.index('compose stop proxy-m365') < des.index('grava_ambiente homologacao') and 'rm -f \"\$M/aws.env\"' in des)
sys.exit(0 if ok else 1)
PY"
confere "m365: testes/test_email_real.py (credenciais temporarias, lista, chave da fila, cabecalhos recebidos)" "python '$AQUI/testes/test_email_real.py' > '$TMP/er.txt' 2>&1 && grep -q '^RESULTADO: email_real OK' '$TMP/er.txt'"
confere "m365: aws/m365-aws.sh (W1-W3, certificado e confirmar) contra AWS CLI falsa: nada secreto na linha de comando, chave so sai depois da leitura utilizavel" "bash '$AQUI/testes/test_m365_aws.sh' > '$TMP/maws.txt' 2>&1 && grep -q '^RESULTADO: m365_aws OK' '$TMP/maws.txt'"
confere "m365: politicas da AWS so GetSecretValue no segredo da homologacao, funcao so com MFA" "python - '$AQUI/aws' <<'PY'
import json, os, sys
d = sys.argv[1]
p = json.load(open(os.path.join(d, 'politica-leitura-segredo.json')))['Statement']
t = json.load(open(os.path.join(d, 'confianca-funcao-leitura.json')))['Statement']
ok = (len(p) == 1 and p[0]['Effect'] == 'Allow' and p[0]['Action'] == 'secretsmanager:GetSecretValue'
      and p[0]['Resource'].endswith(':secret:nekkora/homologacao/platform/m365-??????') and ':sa-east-1:' in p[0]['Resource']
      and len(t) == 1 and t[0]['Action'] == 'sts:AssumeRole' and t[0]['Condition']['Bool']['aws:MultiFactorAuthPresent'] == 'true')
sys.exit(0 if ok else 1)
PY"
confere "sub-redes pelo prefixo" "grep -q 'subnet: 10.252.20.0/24' '$TMP/base.yml' && grep -q 'subnet: 10.252.21.0/24' '$TMP/base.yml' && ! grep -q '10.252.10' '$TMP/base.yml'"
confere "job operacao em .33, so no perfil" "grep -q 'ipv4_address: 10.252.20.33' '$TMP/016.yml' && ! grep -q 'operacao:' '$TMP/base.yml'"
confere "chave da CA operacional montada so no job operacao" "[ \"\$(grep -c 'target: /tls/ops-ca.key' '$TMP/caixa.yml')\" = 1 ]"
confere "postgres com ssl_ca_file e ident_file so com a 016" "grep -q 'ssl_ca_file=/tls/ops-ca.crt' '$TMP/016.yml' && grep -q 'ident_file=' '$TMP/016.yml' && ! grep -q 'ssl_ca_file' '$TMP/base.yml'"
confere "021: chaves da identidade ligadas SO com a sobreposicao da 021, transporte continua o de homologacao" \
    "grep -q 'NEKKORA_CADASTRO: \"true\"' '$TMP/021.yml' && grep -q 'NEKKORA_ENTRADA_GERAL: \"true\"' '$TMP/021.yml' && grep -q 'NEKKORA_VINCULACAO: \"true\"' '$TMP/021.yml' \
     && grep -q 'NEKKORA_RECUPERACAO_SENHA: \"true\"' '$TMP/021.yml' && grep -q 'NEKKORA_CRIACAOORGANIZACAO_ENABLED: \"true\"' '$TMP/021.yml' && grep -q 'NEKKORA_MAIL_TRANSPORTE: homologacao' '$TMP/021.yml' \
     && ! grep -qE 'NEKKORA_(CADASTRO|ENTRADA_GERAL|VINCULACAO|RECUPERACAO_SENHA|CRIACAOORGANIZACAO)' '$TMP/caixa.yml' && ! grep -qE 'NEKKORA_M365|m365' '$TMP/021.yml'"
confere "transporte de homologacao so com a sobreposicao da caixa" "grep -q 'NEKKORA_MAIL_TRANSPORTE: homologacao' '$TMP/caixa.yml' && ! grep -q 'NEKKORA_MAIL_TRANSPORTE' '$TMP/016.yml'"
confere "caixa e volume Docker rotulado (nao pasta do host)" "grep -A3 '^  caixa:' '$TMP/caixa.yml' | grep -q 'nekkora.homologacao: ensaio-validacao' && [ \"\$(grep -B3 'target: /caixa' '$TMP/caixa.yml' | grep -c 'type: volume')\" = 3 ] && ! grep -B3 'target: /caixa' '$TMP/caixa.yml' | grep -q 'type: bind'"
confere "ferramenta da caixa sem rede e somente leitura" "python - '$TMP/caixa.yml' <<'PY'
import sys, re
t = open(sys.argv[1], encoding='utf-8').read()
bloco = re.search(r'\n  caixa-ferramenta:\n(.*?)(\n  [a-z]|\Z)', t, re.S).group(1)
sys.exit(0 if 'network_mode: none' in bloco and 'read_only: true' in bloco else 1)
PY"

# P3-R015: 015 em sub-rede de ensaio (base + compose.015-rede.yml), pg_hba/nginx GERADOS dos estaticos do caminho 015
mkdir -p "$TMP/config015"
{ python "$R" trocar-prefixo "$AQUI/postgres/pg_hba.conf" "$TMP/config015/pg_hba.conf" 10.252.10 10.252.20 \
  && python "$R" trocar-prefixo "$AQUI/gateway/nginx.conf" "$TMP/config015/nginx.conf" 10.252.10 10.252.20; } > "$TMP/t015.txt" 2>&1 || nok "geracao 015: $(tail -1 "$TMP/t015.txt")"
confere "015 ensaio: pg_hba e nginx gerados (6 e 2 enderecos trocados), sem 10.252.10 restante" \
    "grep -q '^6 endereco' '$TMP/t015.txt' && grep -q '^2 endereco' '$TMP/t015.txt' && ! grep -q '10\.252\.10\.' '$TMP/config015/pg_hba.conf' '$TMP/config015/nginx.conf'"
confere "015 ensaio: regras identicas aos estaticos, exceto o prefixo" \
    "diff <(tr -d '\r' < '$AQUI/postgres/pg_hba.conf' | sed 's/10\.252\.10\./P./g') <(sed 's/10\.252\.20\./P./g' '$TMP/config015/pg_hba.conf') > /dev/null && diff <(tr -d '\r' < '$AQUI/gateway/nginx.conf' | sed 's/10\.252\.10\./P./g') <(sed 's/10\.252\.20\./P./g' '$TMP/config015/nginx.conf') > /dev/null"
confere "015 ensaio: nada da 016 no pg_hba gerado (sem nekkora_ops, cert, map, ident)" \
    "! grep -vE '^[[:space:]]*#' '$TMP/config015/pg_hba.conf' | grep -qE 'nekkora_ops|clientcert|[[:space:]]cert([[:space:]]|\$)|map=|[[:space:]]ident([[:space:]]|\$)'"
O15="$AQUI/compose.015-rede.yml"
if HOMOLOG_CONFIG="$TMP/config015" render -f "$B" -f "$O15" > "$TMP/015.yml"; then ok "base + 015-rede renderiza"; else nok "015-rede nao renderiza: $(head -3 "$TMP/compose.err")"; fi
confere "015 ensaio: postgres e gateway montam SO os gerados (os estaticos nao sao montados)" \
    "[ \"\$(grep -c 'config015' '$TMP/015.yml')\" = 2 ] && ! grep -qE 'postgres[/\\\\]pg_hba\.conf|gateway[/\\\\]nginx\.conf' '$TMP/015.yml'"
confere "015 ensaio: sem recursos da 016 (ssl_ca_file, ident_file, operacao, transporte)" \
    "! grep -qE 'ssl_ca_file|ident_file|^  operacao:|NEKKORA_MAIL_TRANSPORTE' '$TMP/015.yml'"
confere "015 ensaio: sub-redes e IPs pelo prefixo" "grep -q 'subnet: 10.252.20.0/24' '$TMP/015.yml' && grep -q 'ipv4_address: 10.252.20.10' '$TMP/015.yml' && ! grep -q '10.252.10' '$TMP/015.yml'"

echo "== 5. elevacao e migrations"
# F4 (P3-IT): o CONTEUDO da lista (fim de linha normalizado para LF) tem de ser uma versao APROVADA, nao so "sem diff".
#   7fd92fae... = operacional atual: 011-013, SEM a 016 (a linha da 016 depende da decisao D-ELEV);
#   47bb9596... = proposta de P3 com "016 <sha256> ensaio-016a" (docs/relatorios/2026-09-29-p3it-anexos/elevacao-016-proposta.patch).
# A proposta so e ACEITA como conteudo aprovado; esta validacao nao a ativa.
#   d122d22a... = missao PLATFORM-API-V1-003 (PROPOSTA, para revisao): 011-013 legadas + 016-020 tipo=superuser vinculadas
#   ao ensaio-api-v1-003 (na primeira versao do PR, 374c6620..., ao ensaio-api-v1-002); com a ativacao (022), bf040a47... acrescenta
#   a 022 vinculada ao mesmo id. Enquanto o PR nao for aprovado, este e o conteudo do PR, nao uma autorizacao vigente.
# Base fixada das proposta/adulteracoes abaixo: a lista 011-013 (7fd92fae...) no commit bf977d7 (main antes da missao 003),
# e nao HEAD - com a lista operacional nova, acrescentar uma 016 a HEAD daria "versao repetida" e mudaria o caso testado.
LISTA_011_013_SHA=7fd92faed3a8d66eb297d727806518a2983572d90eae0633bf4ddc5a7778370d
LISTA_OPERACIONAL_SHA=bf040a4712989bc5b744c00d16c2907c632c5547ae27609bdae9f23ac1df315c
LISTA_PROPOSTA_016_SHA=47bb959662063868351a3f15b4248fb876d8f8cb2e14f9404bbb46f606eef1e3
lista_aprovada() {   # $1 arquivo -> imprime "operacional" | "lista-011-013" | "proposta-016"; codigo 1 se nao aprovado
    local s; s="$(tr -d '\r' < "$1" | sha256sum | cut -c1-64)"
    case "$s" in
        "$LISTA_OPERACIONAL_SHA") echo operacional;;
        "$LISTA_011_013_SHA") echo lista-011-013;;
        "$LISTA_PROPOSTA_016_SHA") echo proposta-016;;
        *) return 1;;
    esac
}
BASE_LISTA="$TMP/l_base_011_013.txt"
git -C "$REPO" show bf977d7:ops/homologacao/implantacao/elevacao.txt > "$BASE_LISTA"
confere "base fixada (bf977d7) com o conteudo 011-013 aprovado" "[ \"\$(lista_aprovada '$BASE_LISTA')\" = lista-011-013 ]"
confere "elevacao.txt operacional com conteudo aprovado (011-013 + 016-020 e 022 vinculadas ao ensaio-api-v1-003)" "[ \"\$(lista_aprovada '$AQUI/implantacao/elevacao.txt')\" = operacional ]"
PROP="$REPO/docs/relatorios/2026-09-29-p3it-anexos/elevacao-016-proposta.patch"
mkdir -p "$TMP/lista/ops/homologacao/implantacao"
cp "$BASE_LISTA" "$TMP/lista/ops/homologacao/implantacao/elevacao.txt"
confere "copia proposta com a 016 vinculada (patch aplicado so na copia temporaria) reconhecida como aprovada" \
    "(cd '$TMP/lista' && git init -q . && git -c core.autocrlf=false apply '$PROP') && [ \"\$(lista_aprovada '$TMP/lista/ops/homologacao/implantacao/elevacao.txt')\" = proposta-016 ]"
cp "$TMP/lista/ops/homologacao/implantacao/elevacao.txt" "$TMP/proposta.txt"
sed 's/\r\?$/\r/' "$TMP/proposta.txt" > "$TMP/proposta_crlf.txt"
confere "copia proposta em CRLF tambem reconhecida (conteudo, nao fim de linha)" "[ \"\$(lista_aprovada '$TMP/proposta_crlf.txt')\" = proposta-016 ]"
sed 's/ ensaio-016a$/ ensaio-016b/' "$TMP/proposta.txt" > "$TMP/adult_id.txt"
sed 's/^016 25bef818/016 25bef819/' "$TMP/proposta.txt" > "$TMP/adult_hash.txt"
sed 's/^011 38a50984/011 38a50985/' "$TMP/proposta.txt" > "$TMP/adult_011.txt"
{ cat "$TMP/proposta.txt"; echo "014 0000000000000000000000000000000000000000000000000000000000000000"; } > "$TMP/adult_extra.txt"
sed '/^016 /s/ ensaio-016a$//' "$TMP/proposta.txt" > "$TMP/adult_sem_vinculo.txt"
for a in adult_id adult_hash adult_011 adult_extra adult_sem_vinculo; do
    confere "lista adulterada recusada ($a)" "! lista_aprovada '$TMP/$a.txt' > /dev/null"
done
# revisao 5 da candidata (P4-R2a; a revisao 4, 25bef818..., continua registrada no P4 e no ensaio-016a)
confere "migration 016 com o sha256 revisado (revisao 5)" "[ \"\$(sha256sum '$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql' | cut -c1-64)\" = f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732 ]"

echo "== 6. recusas do criar ANTES de qualquer recurso (combinacoes invalidas; nada e criado)"
unset HOMOLOG_PROJETO HOMOLOG_ID HOMOLOG_SEGREDOS HOMOLOG_COMMIT HOMOLOG_PORTA HOMOLOG_MIGRACOES HOMOLOG_SQL HOMOLOG_CONFIG HOMOLOG_REDE_INTERNA HOMOLOG_REDE_BORDA   # os da secao 4
recusa() {   # $1 descricao, $2 id, demais: variaveis de ambiente
    local desc="$1" id="$2"; shift 2
    if env HOMOLOG_SEGREDOS="$TMP/nao-criar-$id" "$@" bash "$AQUI/homologacao.sh" criar "$id" > "$TMP/recusa.txt" 2>&1; then
        nok "$id aceito indevidamente"
    elif [ -e "$AQUI/saidas/$id" ] || [ -e "$TMP/nao-criar-$id" ]; then
        nok "$id recusado, mas deixou evidencia ou segredos"
    else
        ok "$desc: $(tail -1 "$TMP/recusa.txt" | cut -c1-110)"
    fi
}
recusa "016 fora de ensaio-*" hml-validacao-a HOMOLOG_ATE=016
recusa "016 sem a linha de elevacao (P3)" ensaio-validacao-b HOMOLOG_ATE=016
recusa "015 em ensaio-* com SO a interna fora do padrao" ensaio-validacao-c HOMOLOG_ATE=015 HOMOLOG_REDE_INTERNA=10.252.20
recusa "015 em sub-redes alternativas FORA de ensaio-*" validacao-m HOMOLOG_ATE=015 HOMOLOG_REDE_INTERNA=10.252.20 HOMOLOG_REDE_BORDA=10.252.21
recusa "015 em ensaio-* com as duas sub-redes iguais (sobreposicao)" ensaio-validacao-n HOMOLOG_ATE=015 HOMOLOG_REDE_INTERNA=10.252.23 HOMOLOG_REDE_BORDA=10.252.23
recusa "015 em ensaio-* com prefixo invalido" ensaio-validacao-o HOMOLOG_ATE=015 HOMOLOG_REDE_INTERNA=10.252.023 HOMOLOG_REDE_BORDA=10.252.24
recusa "transporte sem a 016" ensaio-validacao-d HOMOLOG_ATE=015 HOMOLOG_TRANSPORTE=homologacao
recusa "transporte desconhecido" ensaio-validacao-e HOMOLOG_ATE=015 HOMOLOG_TRANSPORTE=smtp
recusa "prefixo invalido" ensaio-validacao-f HOMOLOG_REDE_INTERNA=10.252.2000
recusa "ultima migration invalida" ensaio-validacao-g HOMOLOG_ATE=017
# missao PLATFORM-API-V1-003: 021 so em ensaio-*, com e-mail simulado e com 016-020 vinculadas ao id exato
recusa "021 fora de ensaio-*" hml-validacao-q HOMOLOG_ATE=021 HOMOLOG_TRANSPORTE=homologacao
recusa "021 sem o transporte de homologacao (e-mail simulado)" ensaio-validacao-r HOMOLOG_ATE=021
recusa "021 com a lista operacional, vinculada a OUTRO id (ensaio-api-v1-003)" ensaio-validacao-s HOMOLOG_ATE=021 HOMOLOG_TRANSPORTE=homologacao
# P3-IT (correcao): a 016 so com a linha VINCULADA ao id exato; lista alternativa so em ensaio-* e existente
S016=25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61
{ cat "$BASE_LISTA"; echo "016 $S016"; } > "$TMP/l016_sem_vinculo.txt"
{ cat "$BASE_LISTA"; echo "016 $S016 ensaio-outro"; } > "$TMP/l016_outro_id.txt"
recusa "016 com linha SEM vinculo ao id" ensaio-validacao-h HOMOLOG_ATE=016 HOMOLOG_ELEVACAO_ARQ="$TMP/l016_sem_vinculo.txt"
recusa "016 com linha vinculada a OUTRO id" ensaio-validacao-i HOMOLOG_ATE=016 HOMOLOG_ELEVACAO_ARQ="$TMP/l016_outro_id.txt"
recusa "lista alternativa inexistente" ensaio-validacao-j HOMOLOG_ATE=016 HOMOLOG_ELEVACAO_ARQ="$TMP/nao_existe.txt"
recusa "lista alternativa fora de ensaio-*" validacao-k HOMOLOG_ATE=015 HOMOLOG_ELEVACAO_ARQ="$TMP/l016_outro_id.txt"
# implantar.sh (execucao REAL, sem banco): lista validada antes de qualquer acao; erro de leitura/formato = codigo 3
lista_recusada() {   # $1 descricao, $2 lista, $3 trecho da mensagem
    local o rc; mkdir -p "$TMP/impl_ev" "$TMP/impl_mig"
    o="$(env -u HOMOLOG_ENSAIO_PAUSA HOMOLOG_ID=ensaio-validacao-l HOMOLOG_EVID="$TMP/impl_ev" HOMOLOG_MIGRACOES_HOST="$TMP/impl_mig" \
        HOMOLOG_TRAVA="$TMP/impl_trava" HOMOLOG_ELEVACAO_ARQ="$2" bash "$AQUI/implantacao/implantar.sh" 2>&1)"; rc=$?
    if [ $rc = 3 ] && echo "$o" | grep -q "$3" && [ ! -e "$TMP/impl_trava" ]; then ok "implantar: $1 (codigo 3, antes de qualquer acao)"
    else nok "implantar: $1 (rc=$rc: $(echo "$o" | head -1 | cut -c1-90))"; fi
}
lista_recusada "lista inexistente" "$TMP/nao_existe.txt" "ausente ou ilegivel"
lista_recusada "016 sem vinculo" "$TMP/l016_sem_vinculo.txt" "sem vinculo ao id"
{ cat "$BASE_LISTA"; echo "016 abc ensaio-x"; } > "$TMP/l_formato.txt"
lista_recusada "linha fora do formato" "$TMP/l_formato.txt" "fora do formato"
{ cat "$BASE_LISTA"; echo "011 $S016"; } > "$TMP/l_repetida.txt"
lista_recusada "versao repetida" "$TMP/l_repetida.txt" "versao repetida"
# P4-R2a-C (A6): gramatica inequivoca da lista (lib/lista_elevacao.sh). Legado intacto; forma nova
# "VVV <sha> tipo=superuser|bypassrls [ambiente=<id>]"; 014 so bypassrls; 016 so superuser e sempre vinculada.
. "$AQUI/lib/lista_elevacao.sh"
cp "$BASE_LISTA" "$TMP/l_op.txt"
SX=1111111111111111111111111111111111111111111111111111111111111111
gram() {   # $1 descricao, $2 OK ou trecho da recusa, $3 linha acrescentada a lista operacional, $4 (OK) linha normalizada esperada
    local o rc; { cat "$TMP/l_op.txt"; printf '%s\n' "$3"; } > "$TMP/g.txt"
    o="$(lista_elevacao_normalizada "$TMP/g.txt" " 016 " 2>&1)"; rc=$?
    if [ "$2" = OK ]; then
        if [ $rc = 0 ] && echo "$o" | grep -qx "$4"; then ok "lista: $1"; else nok "lista: $1 (rc=$rc)"; fi
    else
        if [ $rc = 1 ] && echo "$o" | grep -q "$2" && ! echo "$o" | grep -qE '^[0-9]{3} '; then ok "lista: $1 recusada"; else nok "lista: $1 (rc=$rc: $(echo "$o" | head -1 | cut -c1-80))"; fi
    fi
}
confere "lista: base 011-013 normalizada sem mudanca de significado (011-013 superuser, sem vinculo)" \
    "[ \"\$(lista_elevacao_normalizada '$TMP/l_op.txt' ' 016 ' | awk '{print \$1, \$3, \$4}' | tr '\n' ' ')\" = '011 superuser - 012 superuser - 013 superuser - ' ]"
gram "legado 016 vinculado" OK "016 $S016 ensaio-x" "016 $S016 superuser ensaio-x"
gram "forma nova 014 bypassrls" OK "014 $SX tipo=bypassrls" "014 $SX bypassrls -"
gram "forma nova 014 bypassrls vinculada" OK "014 $SX tipo=bypassrls ambiente=ensaio-x" "014 $SX bypassrls ensaio-x"
gram "forma nova 016 superuser vinculada (ordem livre)" OK "016 $S016 ambiente=ensaio-x tipo=superuser" "016 $S016 superuser ensaio-x"
gram "tipo desconhecido" "tipo desconhecido" "014 $SX tipo=root"
gram "chave desconhecida" "chave desconhecida" "014 $SX tipo=bypassrls modo=x"
gram "chave repetida" "chave repetida" "014 $SX tipo=bypassrls tipo=bypassrls"
gram "forma nova sem tipo" "sem tipo" "014 $SX ambiente=ensaio-x"
gram "mistura de id posicional com chave=valor" "mistura" "014 $SX ensaio-x tipo=bypassrls"
gram "014 legado (superuser implicito)" "so pode ter elevacao do tipo bypassrls" "014 $SX"
gram "014 tipo=superuser" "so pode ter elevacao do tipo bypassrls" "014 $SX tipo=superuser"
gram "016 forma nova sem ambiente" "sem vinculo ao id" "016 $S016 tipo=superuser"
gram "016 tipo=bypassrls" "so pode ter elevacao do tipo superuser" "016 $S016 tipo=bypassrls ambiente=ensaio-x"
gram "ambiente invalido" "ambiente invalido" "014 $SX tipo=bypassrls ambiente=X_y"
{ cat "$TMP/l_op.txt"; echo "014 $SX tipo=superuser"; } > "$TMP/l_014_su.txt"
lista_recusada "014 com tipo=superuser" "$TMP/l_014_su.txt" "so pode ter elevacao do tipo bypassrls"
{ cat "$TMP/l_op.txt"; echo "014 $SX tipo=root"; } > "$TMP/l_tipo.txt"
lista_recusada "tipo de elevacao desconhecido" "$TMP/l_tipo.txt" "tipo desconhecido"
{ cat "$TMP/l_op.txt"; echo "016 $S016 tipo=superuser ambiente=ensaio-outro"; } > "$TMP/l016_nova_outro_id.txt"
recusa "016 em forma nova vinculada a OUTRO id" ensaio-validacao-p HOMOLOG_ATE=016 HOMOLOG_ELEVACAO_ARQ="$TMP/l016_nova_outro_id.txt"
# 021 com lista alternativa (so ensaio-*): sem a 019, com a 018 sem vinculo, com a 017 de tipo errado
l021() {   # $1 arquivo, $2 id, demais: versoes a omitir/alterar no formato "VVV=omitir|semvinculo|bypass"
    local arq="$1" id="$2" v m sha; shift 2
    cp "$BASE_LISTA" "$arq"
    for v in 016 017 018 019 020; do
        sha="$(sha256sum "$REPO"/db/migrations/${v}_*.sql | cut -c1-64)"; m=""
        for x in "$@"; do [ "${x%%=*}" = "$v" ] && m="${x#*=}"; done
        case "$m" in
            omitir) ;;
            semvinculo) echo "$v $sha tipo=superuser" >> "$arq" ;;
            bypass) echo "$v $sha tipo=bypassrls ambiente=$id" >> "$arq" ;;
            *) echo "$v $sha tipo=superuser ambiente=$id" >> "$arq" ;;
        esac
    done
}
l021 "$TMP/l021_sem_019.txt" ensaio-validacao-t 019=omitir
recusa "021 com lista alternativa SEM a linha da 019" ensaio-validacao-t HOMOLOG_ATE=021 HOMOLOG_TRANSPORTE=homologacao HOMOLOG_ELEVACAO_ARQ="$TMP/l021_sem_019.txt"
l021 "$TMP/l021_018_sem_vinculo.txt" ensaio-validacao-u 018=semvinculo
recusa "021 com lista alternativa e a 018 SEM vinculo" ensaio-validacao-u HOMOLOG_ATE=021 HOMOLOG_TRANSPORTE=homologacao HOMOLOG_ELEVACAO_ARQ="$TMP/l021_018_sem_vinculo.txt"
lista_recusada "018 sem vinculo (versao de vinculo obrigatorio)" "$TMP/l021_018_sem_vinculo.txt" "sem vinculo ao id"
l021 "$TMP/l021_017_bypass.txt" ensaio-validacao-v 017=bypass
lista_recusada "017 com tipo=bypassrls (vinculo obrigatorio so superuser)" "$TMP/l021_017_bypass.txt" "so pode ter elevacao do tipo superuser"
confere "lista operacional normalizada: 011-013 legadas e 016-020 e 022 superuser vinculadas SO ao ensaio-api-v1-003" \
    "[ \"\$(lista_elevacao_normalizada '$AQUI/implantacao/elevacao.txt' \"\$VINCULO_OBRIGATORIO\" | awk '{print \$1, \$3, \$4}' | tr '\n' ' ')\" = '011 superuser - 012 superuser - 013 superuser - 016 superuser ensaio-api-v1-003 017 superuser ensaio-api-v1-003 018 superuser ensaio-api-v1-003 019 superuser ensaio-api-v1-003 020 superuser ensaio-api-v1-003 022 superuser ensaio-api-v1-003 ' ]"
confere "lista operacional: sha256 de 016-020 = bytes LF de db/migrations; 021 sem linha" \
    "( for v in 016 017 018 019 020; do lista_elevacao_normalizada '$AQUI/implantacao/elevacao.txt' \"\$VINCULO_OBRIGATORIO\" | grep -q \"^\$v \$(sha256sum '$REPO'/db/migrations/\${v}_*.sql | cut -c1-64) \" || exit 1; done ) && ! grep -q '^021 ' '$AQUI/implantacao/elevacao.txt'"
confere "runner: versao de vinculo obrigatorio SEM linha recusada antes do Flyway; pre-voo oficial existente para 016-020 e 022-024" \
    "grep -q 'exige elevacao vinculada e nao esta na lista de elevacao; nada aplicado' '$AQUI/implantacao/implantar.sh' \
     && ( for v in 016 017 018 019 020 022 023 024; do [ -s '$AQUI/implantacao/preflight/'\$v.sql ] && grep -q PREFLIGHT_OK '$AQUI/implantacao/preflight/'\$v.sql || exit 1; done ) \
     && [ \"\$VINCULO_OBRIGATORIO\" = ' 016 017 018 019 020 022 023 024 ' ]"
confere "elevacao.txt operacional SEM linha da 023 nem da 024 (so propostas, sem aprovacao; o laboratorio usa lista alternativa fora do repositorio)" \
    "! grep -qE '^02[34] ' '$AQUI/implantacao/elevacao.txt'"

echo "== 7. classificacao e reconciliacao das fixtures (casos sinteticos, sem banco)"
F="$AQUI/lib/fixture_conferencia.py"
classe() { printf '%s\n' "$@" | python "$F" classificar | tr -d '\r'; }
confere "APLICADA: codigo 0, marca e verificacoes=OK" "[ \"\$(classe 'ANTES|x' 'FIXTURE_COMMIT_CONCLUIDO' 'fixture_contatos: verificacoes=OK' 'codigo=0')\" = APLICADA ]"
confere "falha DEPOIS do COMMIT nao e 'nada alterado'" "[ \"\$(classe 'FIXTURE_COMMIT_CONCLUIDO' 'psql:x: ERROR:  fixture: alteracao nao persistida' 'codigo=3')\" = APLICADA_COM_FALHA_POS_COMMIT ]"
confere "erro do servidor antes do COMMIT -> NAO_APLICADA" "[ \"\$(classe 'ANTES|x' 'psql:x: ERROR:  fixture: campo nao autorizado alterado' 'codigo=3')\" = NAO_APLICADA ]"
confere "conexao perdida (codigo 2, sem marca) -> INDETERMINADA" "[ \"\$(classe 'ANTES|x' 'psql: error: server closed the connection unexpectedly' 'codigo=2')\" = INDETERMINADA ]"
confere "job falhou sem saida do psql -> INDETERMINADA" "[ \"\$(classe 'codigo=1')\" = INDETERMINADA ]"
confere "codigo 3 sem erro do servidor -> INDETERMINADA" "[ \"\$(classe 'ANTES|x' 'codigo=3')\" = INDETERMINADA ]"
FXT="$TMP/fixtures.txt"
cat > "$FXT" <<'TXT'
== fixture solicitacao execucao=11111111-1111-1111-1111-111111111111 2026-09-28T00:00:00Z alvo=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa horas=25
ANTES|req|r1|opened_at|90000000000
ANTES|req|r1|closed_at|NULL
codigo=2
classificacao=INDETERMINADA execucao=11111111-1111-1111-1111-111111111111
== fixture contatos execucao=22222222-2222-2222-2222-222222222222 2026-09-28T00:00:00Z alvo=c1 horas=721
codigo=2
TXT
rec() { printf '%s\n' "${@:2}" | python "$F" "$FXT" "$1" | tr -d '\r'; }
E1=11111111-1111-1111-1111-111111111111
confere "reconciliacao: estado igual ao de partida -> NAO_APLICADA" "rec $E1 'ESTADO|req|r1|opened_at|90000000000' 'ESTADO|req|r1|closed_at|NULL' | grep -q 'NAO_APLICADA'"
confere "reconciliacao: partida - 25 h -> APLICADA" "rec $E1 'ESTADO|req|r1|opened_at|0' 'ESTADO|req|r1|closed_at|NULL' | grep -q ': APLICADA'"
confere "reconciliacao: outro valor -> DIVERGENTE (codigo 1)" "! rec $E1 'ESTADO|req|r1|opened_at|5' 'ESTADO|req|r1|closed_at|NULL' > /dev/null"
confere "reconciliacao: linha alvo faltando -> DIVERGENTE" "! rec $E1 'ESTADO|req|r1|opened_at|90000000000' > /dev/null"
confere "reconciliacao: sem linhas ANTES -> SEM_ESTADO_DE_PARTIDA" "! rec 22222222-2222-2222-2222-222222222222 'ESTADO|contato|c1|created_at|1' > /dev/null"
for s in fixture_016_contatos fixture_016_solicitacao; do
    q="$AQUI/sql/$s.sql"
    confere "$s: estado de partida (ANTES) antes do BEGIN" "[ \"\$(grep -n '^\\\\ir fixture_016_estado.sql' '$q' | cut -d: -f1)\" -lt \"\$(grep -n '^BEGIN;' '$q' | cut -d: -f1)\" ]"
    confere "$s: marca IMEDIATAMENTE depois do COMMIT" "[ \"\$(grep -A1 '^COMMIT;' '$q' | tail -1)\" = '\\echo FIXTURE_COMMIT_CONCLUIDO' ]"
done
confere "runner: psql das fixtures SEM --single-transaction (so em comentario)" "! grep -vE '^[[:space:]]*#' '$AQUI/homologacao.sh' | grep -q -- '--single-transaction'"
confere "runner: solicitacao registrada como impropria para B9 ANTES de executar" "python - '$AQUI/homologacao.sh' <<'PY'
import sys
t = open(sys.argv[1], encoding='utf-8').read()
a = t.index('|tentativa|')
b = t.index('-f /sql/fixture_016_solicitacao.sql')
c = t.index('psql_ferr \"\${args[@]}\"')
sys.exit(0 if a < c and b < c else 1)
PY"

echo "== 8. B6: expectativas coerentes com o pg_hba e com a causa da recusa TLS"
H16="$AQUI/testes/h016.sh"
FJ="$REPO/api/nekkora-ops/src/test/java/com/nekkora/ops/homologacao/FerramentaHomologacao.java"
confere "pg_hba gerado termina nas regras EXPLICITAS de recusa (IPv4 e IPv6)" "[ \"\$(grep -vE '^[[:space:]]*(#|\$)' '$TMP/pg_hba.conf' | tail -2 | awk '{print \$1\"|\"\$4\"|\"\$5}' | tr '\n' ' ')\" = 'host|0.0.0.0/0|reject host|::/0|reject ' ]"
confere "B6 (.31) espera a mensagem da regra explicita de recusa" "grep -q \"grep -qi 'pg_hba.conf rejects connection'\" '$H16' && ! grep -q 'no pg_hba.conf entry' '$H16'"
confere "B6 (CA estranha) exige unknown_ca no cliente E 'certificate verify failed' no servidor" "grep -q 'resultado=RECUSADA_TLS:unknown_ca ' '$H16' && grep -q \"grep -qi 'certificate verify failed'\" '$H16'"
confere "ferramenta: RECUSADA_TLS so para unknown_ca (alerta generico = FALHA_TECNICA)" "python - '$FJ' <<'PY'
import re, sys
t = open(sys.argv[1], encoding='utf-8').read()
literais = re.findall(r'\"(RECUSADA_TLS[^\"]*)\"', t)
sys.exit(0 if literais and set(literais) == {'RECUSADA_TLS:unknown_ca'} and '\"FALHA_TECNICA:TLS:\"' in t else 1)
PY"

echo "== 9. achados do P1c (execucao em recursos descartaveis): travas contra regressao"
DO="$REPO/api/e2e/Dockerfile.operacao"
confere "Dockerfile.operacao: nenhum '\$' em caminho de COPY (o Dockerfile expandiria como variavel, ex.: Pki\$Ca)" "! grep -E '^COPY' '$DO' | grep -q '\\\$'"
confere "Dockerfile.operacao: da pasta homologacao so FerramentaHomologacao*.class (testes do P1c fora da imagem)" "grep -qE '^COPY .*/homologacao/FerramentaHomologacao\*\.class ' '$DO' && ! grep -qE '^COPY .*/homologacao/ ' '$DO'"
confere "fixture_016.sql: primeiro ORG_ADMIN so por provision_first_org_admin (sem INSERT sem responsavel)" "grep -q 'provision_first_org_admin(' '$AQUI/sql/fixture_016.sql' && [ \"\$(grep -c 'INSERT INTO organization_role_assignments' '$AQUI/sql/fixture_016.sql')\" = 1 ] && grep -A1 'INSERT INTO organization_role_assignments' '$AQUI/sql/fixture_016.sql' | grep -q assigned_by_membership_id"
confere "sonda ca-estranha: apresenta o certificado (fabrica propria) e le o alerta antes de escrever" "grep -q 'CertificadoForcado.class.getName()' '$FJ' && grep -q 'ssl.startHandshake();' '$FJ' && grep -q 'ssl.setSoTimeout(2000);' '$FJ'"
confere "B6: avaliacao separada (avalia_b6) usada pelo b6 e exposta em 'h016.sh avaliar-b6'" "grep -q 'avalia_b6 \"\$f\" || ok=1' '$H16' && grep -q 'avaliar-b6' '$H16'"
confere "B10: varredura manual com espera real (SEGURAR, SIGKILL, controle antes dos 10 min, espera >= 600 s, sem fixture)" "grep -qF 'controle proximo SEGURAR' '$H16' && grep -qF 'compose kill platform' '$H16' && grep -qF -e '-ge 600' '$H16' && grep -qF 'estado_apos_varredura_1=2' '$H16' && grep -qF 'fixtures_desta_tentativa=0' '$H16' && grep -qF 'Dloader.main=com.nekkora.platform.emergency.VarreduraAvisos' '$H16'"
confere "caixa: SEGURAR so como regra 'proximo' (nunca por destinatario)" "grep -qF 'EXCECAO|SEGURAR)|destinatario' '$AQUI/homologacao.sh' && [ \"\$(grep -c 'SEGURAR' '$AQUI/homologacao.sh')\" = 2 ]"
confere "B11: restauracao em cluster DESCARTAVEL fora do projeto (sem rede, papeis sem senha, removido antes do B12)" "grep -qF -e '--network none' '$H16' && grep -qF -e '--roles-only --no-role-passwords' '$H16' && grep -qF 'cluster_descartavel=REMOVIDO' '$H16' && grep -qF 'contagens=IGUAIS' '$H16' && grep -qF 'b11_restauracao || ok=1' '$H16'"
confere "B11: privilegios do catalogo comparados (exigidos) e pgcrypto criada pelo migrador antes da janela" "grep -qF 'b11_privilegios.sql' '$H16' && grep -qF \"grep -q '^privilegios=IGUAIS\$'\" '$H16' && grep -qF 'pgcrypto=CRIADA_PELO_MIGRADOR_SEM_JANELA' '$H16' && [ -f '$AQUI/testes/b11_privilegios.sql' ]"

echo "== 10. guarda e identificacao das imagens (FONTES_IMAGENS; G1/G1b) em repositorio Git temporario, sem Docker"
if bash "$AQUI/testes/guarda_fontes_imagens.sh" > "$TMP/guarda.txt" 2>&1; then ok "guarda_fontes_imagens.sh: $(tail -1 "$TMP/guarda.txt")"; else nok "guarda_fontes_imagens.sh: $(tail -1 "$TMP/guarda.txt")"; grep '^FALHA' "$TMP/guarda.txt"; fi

echo "== 11. script do modelo do migrador e configuracao de desenvolvimento (P4-R2a-C2)"
SM="$REPO/db/bootstrap/aplicar_como_migrador.sh"
confere "script do modelo em db/bootstrap/ (e nao mais em db/tests/lib/)" "[ -f '$SM' ] && [ ! -e '$REPO/db/tests/lib/aplicar_como_migrador.sh' ]"
confere "consumidores apontam para db/bootstrap/aplicar_como_migrador.sh (RealDatabase, OpsBanco, suite da 016, compose do modelo)" \
    "grep -q 'resolveSibling(\"bootstrap/aplicar_como_migrador.sh\")' '$REPO/api/nekkora-core/src/test/java/com/nekkora/core/testsupport/RealDatabase.java' \
     && grep -q 'resolveSibling(\"bootstrap/aplicar_como_migrador.sh\")' '$REPO/api/nekkora-ops/src/test/java/com/nekkora/ops/testsupport/OpsBanco.java' \
     && grep -q 'MODELO=\"\$REPO/db/bootstrap/aplicar_como_migrador.sh\"' '$REPO/db/tests/mfa-emergencia-016/run.sh' \
     && grep -q '\./db/bootstrap/aplicar_como_migrador.sh:/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh:ro' '$REPO/docker-compose.modelo.yml'"
confere "nenhuma referencia ao caminho antigo db/tests/lib/ em codigo (api, db/tests, ops/homologacao, compose)" \
    "! grep -rlE 'tests/lib/aplicar_como_migrador' '$REPO/api' '$REPO/db/tests' '$AQUI' '$REPO/docker-compose.yml' '$REPO/docker-compose.modelo.yml' \
        --include='*.java' --include='*.sh' --include='*.py' --include='*.yml' --exclude=validar_config.sh --exclude-dir=saidas --exclude-dir=target --exclude-dir=node_modules 2>/dev/null | grep -q ."
confere "compose do modelo isolado da stack principal (projeto, container, volume e porta proprios)" "python - '$REPO/docker-compose.yml' '$REPO/docker-compose.modelo.yml' <<'PY'
import re, sys
def campos(p):
    t = open(p, encoding='utf-8').read()
    g = lambda r: (re.search(r, t, re.M) or [None, None])[1]
    return g(r'^name: *(\S+)'), g(r'^ *container_name: *(\S+)'), g(r'^ *- *(\w+):/var/lib/postgresql/data'), g(r'^ *- *\"\\$\{(\w+):-')
a, b = campos(sys.argv[1]), campos(sys.argv[2])
sys.exit(0 if all(a) and all(b) and all(x != y for x, y in zip(a, b)) else 1)
PY"

echo "== 12. pre-voo da 014, semeadura pre-014 e sua conferencia (R2b-0: T1, T2, T3)"
P14="$AQUI/implantacao/preflight/014.sql"; FX14="$AQUI/sql/fixture_014_pre.sql"; ES14="$AQUI/sql/semente_014_estado.sql"
# somente leitura: transacao READ ONLY desfeita no fim e nenhum comando de escrita fora de comentario
so_leitura() { python - "$1" <<'PY'
import re, sys
t = '\n'.join(l.split('--', 1)[0] for l in open(sys.argv[1], encoding='utf-8').read().splitlines())
ok = t.lstrip().startswith('BEGIN TRANSACTION READ ONLY;') and t.rstrip().endswith('ROLLBACK;') \
     and not re.search(r'\b(INSERT|UPDATE|DELETE|MERGE|ALTER|CREATE|DROP|GRANT|REVOKE|TRUNCATE|COPY|COMMIT)\b', t, re.I)
sys.exit(0 if ok else 1)
PY
}
confere "T1: implantacao/preflight/014.sql existe, e somente leitura e termina em PREFLIGHT_OK/PREFLIGHT_FALHOU (7 itens)" \
    "[ -f '$P14' ] && so_leitura '$P14' && grep -q \"count(\\*) = 7 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU'\" '$P14'"
confere "T3: sql/semente_014_estado.sql e somente leitura" "[ -f '$ES14' ] && so_leitura '$ES14'"
confere "T2: a semeadura reconfere ambiente ensaio-*, historico 001-013, ausencia da 014, migrador sem atributo e semente anterior" \
    "grep -q \"v_amb !~ '^ensaio-\" '$FX14' && grep -q 'historico fora do ponto da semeadura' '$FX14' && grep -q 'ja tem colunas da 014' '$FX14' \
     && grep -q 'migrador_nekkora com atributo elevado' '$FX14' && grep -q 'semente anterior encontrada' '$FX14'"
confere "T2: semear-pre014 recusado fora de ensaio-* (antes de ler qualquer ambiente)" \
    "! bash '$AQUI/homologacao.sh' semear-pre014 hml-validar > '$TMP/sem.txt' 2>&1 && grep -q 'so em ambiente descartavel (id ensaio-\\*)' '$TMP/sem.txt'"
confere "T3: conferir-semente014 recusado fora de ensaio-*" \
    "! bash '$AQUI/homologacao.sh' conferir-semente014 hml-validar antes > '$TMP/conf.txt' 2>&1 && grep -q 'so em ambiente descartavel (id ensaio-\\*)' '$TMP/conf.txt'"
confere "T2: semear-pre014 exige a pausa antes-de-elevar@014 do runner, a chegada nela, pausa nao liberada, trava e 0|0|0" \
    "grep -q 'antes-de-elevar@014:?\\*' '$AQUI/homologacao.sh' && grep -q 'o runner nao chegou a pausa antes de elevar a 014' '$AQUI/homologacao.sh' \
     && grep -q 'a pausa ja foi liberada' '$AQUI/homologacao.sh' && grep -q 'sem trava local da implantacao' '$AQUI/homologacao.sh'"
confere "ganchos de pausa e largada do runner continuam recusados fora de ensaio-*" \
    "grep -q 'ganchos de ensaio so em ambiente descartavel (id ensaio-\\*)' '$AQUI/implantacao/implantar.sh'"
confere "elevacao.txt operacional sem linha da 014; 016-020 e 022 SO vinculadas ao ensaio-api-v1-003 (nenhuma sem vinculo ou de outro id)" \
    "lista_elevacao_normalizada '$AQUI/implantacao/elevacao.txt' \"\$VINCULO_OBRIGATORIO\" > '$TMP/lista_op.txt' 2>&1 && ! grep -qE '^014 ' '$TMP/lista_op.txt' \
     && [ \"\$(grep -cE '^(016|017|018|019|020) [0-9a-f]{64} superuser ensaio-api-v1-003\$' '$TMP/lista_op.txt')\" = 5 ] \
     && [ \"\$(grep -cE '^022 [0-9a-f]{64} superuser ensaio-api-v1-003\$' '$TMP/lista_op.txt')\" = 1 ] \
     && [ \"\$(grep -cE '^(016|017|018|019|020) ' '$TMP/lista_op.txt')\" = 5 ]"

echo "== 13. H02: janela BYPASSRLS da 014 esperada pela lista de elevacao em vigor (R2b-0b; h02_esperadas do executar.sh)"
EXE="$AQUI/testes/executar.sh"
awk '/^h02_esperadas\(\) \{/ {p = 1} p {print} p && /^}/ {exit}' "$EXE" > "$TMP/h02_esperadas.sh"
mkdir -p "$TMP/h02m"; for n in 001 011 012 013 014 015 016; do : > "$TMP/h02m/${n}_x.sql"; done
S14V=5e5726b9a8f58d319b6b17dcbf7801c21e258999b003d25a023680f0d271d8fc
grep -E '^01[123] ' "$AQUI/implantacao/elevacao.txt" > "$TMP/h02_l_op.txt"
{ cat "$TMP/h02_l_op.txt"; echo "014 $S14V tipo=bypassrls ambiente=ensaio-v"; } > "$TMP/h02_l_vinc.txt"
{ cat "$TMP/h02_l_op.txt"; echo "014 $S14V tipo=bypassrls ambiente=ensaio-outro"; } > "$TMP/h02_l_outro.txt"
{ cat "$TMP/h02_l_op.txt"; echo "014 nao-e-sha"; } > "$TMP/h02_l_mal.txt"
h02e() { (AQUI="$AQUI"; . "$TMP/h02_esperadas.sh"; h02_esperadas "$TMP/h02m" "$1" ensaio-v 2>/dev/null); }
confere "H02 usa h02_esperadas (sem a lista fixa 011 012 013 016 no h02)" \
    "[ -s '$TMP/h02_esperadas.sh' ] && grep -q 'esperadas=\"\$(h02_esperadas ' '$EXE' && ! grep -q 'for v in 011 012 013 016; do ls \"\$base/migracoes' '$EXE'"
confere "H02: lista sem 014 -> 011 012 013 016 (comportamento anterior)" "[ \"\$(h02e '$TMP/h02_l_op.txt')\" = '011 012 013 016' ]"
confere "H02: 014 bypassrls vinculada a este id -> 014 esperada" "[ \"\$(h02e '$TMP/h02_l_vinc.txt')\" = '011 012 013 014 016' ]"
confere "H02: 014 vinculada a outro id -> 014 nao esperada" "[ \"\$(h02e '$TMP/h02_l_outro.txt')\" = '011 012 013 016' ]"
confere "H02: lista fora do formato -> recusa (nunca 'sem 014')" "! h02e '$TMP/h02_l_mal.txt' > /dev/null"

echo "== 14. elegibilidade da porta publicada (R2b-1a, F-R2b1-1: listener, bind real e faixas reservadas do Windows)"
PORTA_PY="$AQUI/lib/porta.py"; H="$AQUI/homologacao.sh"
confere "testes de lib/porta.py (parser do netsh, falha fechada, sockets reais em portas efemeras, decisao)" \
    "python -B '$AQUI/testes/test_porta.py' > '$TMP/test_porta.txt' 2>&1"
confere "porta.py: bind exclusivo de verdade, tabela excludedportrange e falha fechada no Windows" \
    "grep -q 'SO_EXCLUSIVEADDRUSE' '$PORTA_PY' && grep -q \"'excludedportrange', 'protocol=tcp'\" '$PORTA_PY' && grep -q 'falha fechada' '$PORTA_PY'"
# ordem: no criar, a elegibilidade vem ANTES de qualquer recurso (antes da guarda das imagens e do diretorio de saida);
# no pos_implantacao (concluir-criacao), ANTES de subir os servicos
l_criar="$(grep -n 'python "$AQUI/lib/porta.py" elegivel "$HOMOLOG_BIND" "$HOMOLOG_PORTA" || falha' "$H" | head -1 | cut -d: -f1)"
l_guarda="$(grep -n 'guarda_fontes_imagens "$REPO" >&2' "$H" | head -1 | cut -d: -f1)"
l_evid="$(grep -n '^    mkdir -p "$EVID" "$SEG/tls"' "$H" | head -1 | cut -d: -f1)"
l_pos="$(grep -n 'python "$AQUI/lib/porta.py" elegivel "$HOMOLOG_BIND" "$HOMOLOG_PORTA" > "$EVID/porta_antes_dos_servicos.txt"' "$H" | head -1 | cut -d: -f1)"
l_up="$(grep -n 'compose up -d platform portal gateway' "$H" | head -1 | cut -d: -f1)"
confere "criar confere a porta antes de qualquer recurso (linha $l_criar < guarda $l_guarda e < saida/segredos $l_evid)" \
    "[ -n '$l_criar' ] && [ -n '$l_guarda' ] && [ -n '$l_evid' ] && [ '$l_criar' -lt '$l_guarda' ] && [ '$l_criar' -lt '$l_evid' ]"
confere "concluir-criacao confere a porta antes de subir os servicos (linha $l_pos < $l_up)" \
    "[ -n '$l_pos' ] && [ -n '$l_up' ] && [ '$l_pos' -lt '$l_up' ]"
# execucao REAL do criar com porta reservada pelo sistema (so se o host tiver faixa reservada; nada e criado)
# o caso do STOP: porta reservada SEM listener (reservada com listener ja e recusada antes, por "em uso")
RES=""
for p in $(netsh interface ipv4 show excludedportrange protocol=tcp 2>/dev/null | tr -d '\r' | awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ && $1 > 1024 {print $1; print $2}'); do
    if ! (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then RES="$p"; break; fi
done
if [ -n "$RES" ]; then
    if env HOMOLOG_SEGREDOS="$TMP/nao-criar-porta" HOMOLOG_ATE=015 HOMOLOG_REDE_INTERNA=10.252.240 HOMOLOG_REDE_BORDA=10.252.241 HOMOLOG_PORTA="$RES" \
        bash "$H" criar ensaio-validacao-porta > "$TMP/criar_porta.txt" 2>&1; then nok "criar aceitou porta reservada $RES"
    elif ! grep -q 'indisponivel para publicacao' "$TMP/criar_porta.txt"; then nok "criar recusou por outro motivo: $(tail -1 "$TMP/criar_porta.txt" | cut -c1-100)"
    elif [ -e "$AQUI/saidas/ensaio-validacao-porta" ] || [ -e "$TMP/nao-criar-porta" ]; then nok "criar recusou a porta $RES, mas deixou evidencia ou segredos"
    else ok "criar recusa porta reservada pelo sistema e sem listener ($RES, limite de faixa) antes de qualquer recurso"; fi
else
    ok "criar com porta reservada: host sem faixa reservada acima de 1024 (coberto pelos testes de porta.py)"
fi

echo "== 15. H03 por perfil do modelo de migrations (missao H03 pos-016, F-R2b1c-1)"
H03P="$AQUI/testes/h03_perfis.sh"; H03F="$TMP/h03_funcao.sh"
awk '/^h03\(\) \{/ {p = 1} p {print} p && /^}/ {exit}' "$AQUI/testes/executar.sh" > "$H03F"
confere "testes de testes/h03_perfis.sh (modelo com falha fechada, perfis 015/016, invariantes e troca exata da regra da 014)" \
    "bash '$AQUI/testes/test_h03_perfis.sh' > '$TMP/test_h03.txt' 2>&1"
confere "h03 sem a lista fixa de papeis do desenho 015 (papeis com senha vem do perfil)" \
    "[ -s '$H03F' ] && ! grep -q 'login_com_senha|admin_cluster,backup_nekkora' '$H03F' && grep -q 'login_com_senha|\$H03_LOGIN_COM_SENHA' '$H03F'"
confere "h03 sem a regra fixa de 1 politica: invariantes so por h03_invariantes_sql do perfil (sem extrair blocos das migrations no h03)" \
    "grep -q 'h03_invariantes_sql \"\$mig\"' '$H03F' && ! grep -qE 'awk .*(012_security_rls|013_portal_externo|014_mfa_sessoes|015_restauracao_logica)' '$H03F' \
     && grep -q 'IS DISTINCT FROM .ops_function_context,tenant_request_context.' '$H03P'"
confere "perfil 016 inclui a sec. 19 da 016 e a troca da regra da 014; perfil 015 mantem a regra original" \
    "grep -q '016_recuperacao_emergencial_mfa.sql|^-- === 19' '$H03P' && grep -q '014_mfa_sessoes.sql|^-- === 8\\\\. |pol014_016' '$H03P' && grep -q '014_mfa_sessoes.sql|^-- === 8\\\\. |-' '$H03P'"
confere "referencia pelo modelo suportado (aplicar_como_migrador.sh + marcador da versao), sem migrations no initdb.d" \
    "grep -q 'h03_referencia \"\$HOMOLOG_ID\" \"\$mig\" \"\$modelo\" \"\$REPO/db/bootstrap/aplicar_como_migrador.sh\"' '$H03F' \
     && grep -q 'nekkora-inicializacao-completa versao=\$modelo' '$H03P' && grep -q ':/nekkora/migracoes:ro' '$H03P' \
     && ! grep -qE 'migracoes[^ ]*\):/docker-entrypoint-initdb.d(:ro)?\"' '$H03F' '$H03P'"
confere "modelo nao determinado = FALHA fechada (h03 para antes de avaliar)" \
    "grep -q 'modelo de migrations nao determinado; H03 nao avalia nada (falha fechada)' '$H03F' && grep -A3 'if ! modelo=' '$H03F' | grep -q 'return'"

echo "== 16. evidencia de fontes da regressao Maven (manifesto antes e depois; missao manifesto de fontes Maven)"
RM="$AQUI/regressao_maven.sh"; FMV="$AQUI/lib/fontes_maven.py"
confere "bash -n regressao_maven.sh" "bash -n '$RM'"
confere "testes de lib/fontes_maven.py e regressao_maven.sh (repositorio sintetico, mvnw falso; estavel, mutacao/adicao/remocao durante, depois, adulteracao)" \
    "python -B '$AQUI/testes/test_fontes_maven.py' > '$TMP/test_fontes_maven.txt' 2>&1"
l_m1="$(grep -n 'python -B "$FM" manifesto "$RAIZ" "$OUT/fontes.sha256"' "$RM" | head -1 | cut -d: -f1)"
l_mvn="$(grep -n '( cd "$RAIZ/api" && ./mvnw "$@" )' "$RM" | head -1 | cut -d: -f1)"
l_m2="$(grep -n 'python -B "$FM" manifesto "$RAIZ" "$OUT/fontes_fim.sha256"' "$RM" | head -1 | cut -d: -f1)"
confere "manifesto das fontes ANTES do Maven e de novo DEPOIS (linhas $l_m1 < $l_mvn < $l_m2), com comparacao" \
    "[ -n '$l_m1' ] && [ -n '$l_mvn' ] && [ -n '$l_m2' ] && [ '$l_m1' -lt '$l_mvn' ] && [ '$l_mvn' -lt '$l_m2' ] && grep -q 'python -B \"\$FM\" comparar \"\$OUT/fontes.sha256\" \"\$OUT/fontes_fim.sha256\"' '$RM'"
confere "reaproveitamento PERMITIDO so sem motivo; fontes instaveis, testes com falha ou nenhum teste = PROIBIDO; falha do manifesto inicial impede o Maven" \
    "grep -q 'if \[ -z \"\$motivo\" \]; then echo \"REAPROVEITAMENTO=PERMITIDO\"; else echo \"REAPROVEITAMENTO=PROIBIDO\"' '$RM' \
     && grep -q 'fontes alteradas durante a execucao' '$RM' && grep -q 'nenhum teste executado' '$RM' && grep -q 'o Maven NAO foi executado' '$RM'"
confere "fontes_maven.py sem mtime, ordem por bytes, conjunto declarado (modulos do pom, db, ops/homologacao) e recusa de caminho nao representavel" \
    "! grep -qE 'st_mtime|st_ctime|getmtime|getctime|os\.stat\(|os\.lstat\(|scandir' '$FMV' && grep -q \"sorted(caminhos, key=lambda c: c.encode('utf-8'))\" '$FMV' \
     && grep -q \"'db/migrations', 'db/bootstrap', 'ops/homologacao'\" '$FMV' && grep -q 'caminho nao representavel no manifesto' '$FMV'"
confere "diretorio de saida existente recusado (evidencia nunca sobrescrita)" "grep -q 'a evidencia de uma execucao nunca e reaproveitada nem sobrescrita' '$RM'"

echo "== 17. H02 pela lista HISTORICA da criacao (fotografia imutavel; missao H02 x lista historica)"
EH="$AQUI/lib/elevacao_historica.sh"; H02F="$TMP/h02_funcao.sh"; EHL="$TMP/eh_lista_h02.sh"
awk '/^h02\(\) \{/ {p = 1} p {print} p && /^}/ {exit}' "$AQUI/testes/executar.sh" > "$H02F"
awk '/^eh_lista_h02\(\) \{/ {p = 1} p {print} p && /^}/ {exit}' "$EH" > "$EHL"
# Obrigatorios e portaveis (sem caminho de maquina): sinteticos, legado hml03 pelos registros versionados e legado ancorado
# sintetico. O laboratorio legado (registros reais de ensaio-016a/016c, em clones de outra maquina) e OPCIONAL: so os casos
# 9a0, 9a e 9b podem ser PULADOS, e sao listados abaixo; nenhum obrigatorio pode faltar (contagem minima de OK).
confere "testes de lib/elevacao_historica.sh (fotografia, ancoras, adulteracao, legado explicito, falha fechada; legado hml03 versionado)" \
    "bash '$AQUI/testes/test_elevacao_historica.sh' > '$TMP/test_eh.txt' 2>&1 && grep -qE '^RESULTADO: [0-9]+ OK, 0 FALHA, [0-3] PULADO' '$TMP/test_eh.txt' \
     && [ \"\$(grep -c '^OK ' '$TMP/test_eh.txt')\" -ge 26 ] && ! grep '^PULADO' '$TMP/test_eh.txt' | grep -qvE '^PULADO (9a0|9a|9b) \\(laboratorio legado opcional ausente'"
grep '^PULADO' "$TMP/test_eh.txt" 2>/dev/null | sed 's/^/   (laboratorio opcional, nao conta como OK) /'
confere "H02 so pela lista historica (eh_lista_h02); nunca a lista vigente (HOMOLOG_ELEVACAO_ARQ ou implantacao/elevacao.txt)" \
    "[ -s '$H02F' ] && grep -q 'lista_h02=\"\$(eh_lista_h02 \"\$base\" \"\$HOMOLOG_ID\" \"\${HOMOLOG_ELEVACAO_CRIACAO_SHA256:-}\"' '$H02F' \
     && ! grep -qE 'HOMOLOG_ELEVACAO_ARQ:-|implantacao/elevacao\\.txt' '$H02F'"
confere "sem fallback silencioso: eh_lista_h02 nao le a lista vigente; ambiente com proveniencia e sem fotografia falha; legado so com fonte explicita" \
    "[ -s '$EHL' ] && ! grep -qE 'HOMOLOG_ELEVACAO_ARQ|elevacao\\.txt' '$EHL' && grep -q 'e SEM a fotografia' '$EHL' \
     && grep -q 'leg=\"\${HOMOLOG_H02_LISTA_LEGADO:-}\"' '$EHL' && grep -q 'sem fonte historica explicita' '$EHL'"
confere "integridade da fotografia: lista x proveniencia x ambiente.env x log de criacao do runner" \
    "grep -q 'lista da fotografia adulterada' '$EHL' && grep -q 'diverge do ambiente.env' '$EHL' && grep -q 'diverge do log de criacao do runner' '$EHL' \
     && grep -q 'fotografia de outro ambiente' '$EHL' && grep -q 'proveniencia malformada' '$EHL'"
l_val="$(grep -n 'lista_elevacao_normalizada \"\$LISTA_CRIACAO\" \"\$VINCULO_OBRIGATORIO\"' "$H" | head -1 | cut -d: -f1)"
l_ev="$(grep -n '^    mkdir -p \"\$EVID\" \"\$SEG/tls\"' "$H" | head -1 | cut -d: -f1)"
l_reg="$(grep -n 'SHA_LISTA_CRIACAO=\"\$(eh_registrar \"\$LISTA_CRIACAO\"' "$H" | head -1 | cut -d: -f1)"
l_seg="$(grep -n '^    registro_fontes_imagens \"\$REPO\"' "$H" | head -1 | cut -d: -f1)"
l_dock="$(awk '/^criar\)/ {c = 1} c && /compose (up|build|run)|docker (run|network create|volume create)/ {print NR; exit}' "$H")"
confere "criar: lista validada antes de tudo e fotografada logo apos os diretorios vazios, ANTES de segredos, configuracao e de qualquer recurso Docker (linhas $l_val < $l_ev < $l_reg < $l_seg < $l_dock), sha no ambiente.env" \
    "[ -n '$l_val' ] && [ -n '$l_ev' ] && [ -n '$l_reg' ] && [ -n '$l_seg' ] && [ -n '$l_dock' ] && [ '$l_val' -lt '$l_ev' ] && [ '$l_ev' -lt '$l_reg' ] && [ '$l_reg' -lt '$l_seg' ] && [ '$l_seg' -lt '$l_dock' ] \
     && grep -q '^HOMOLOG_ELEVACAO_CRIACAO_SHA256=\$SHA_LISTA_CRIACAO' '$H' && grep -q 'fotografia historica da lista de elevacao nao preservada; nenhum recurso foi criado' '$H'"
confere "fotografia nunca sobrescrita (eh_registrar recusa diretorio existente) e gravada somente leitura" \
    "grep -q 'fotografia da lista: ja existe' '$EH' && grep -q 'chmod a-w' '$EH'"

echo "== 18. aquisicao da trava da caixa (WIN-TRANSPORT-1; controle leve, o estresse fica so como prova do portao)"
MAIL="$REPO/api/nekkora-platform/src/main/java/com/nekkora/platform/mail"; MAILT="$REPO/api/nekkora-platform/src/test/java/com/nekkora/platform/mail"
CX="$MAIL/CaixaHomologacaoMailSender.java"; ADQ="$TMP/adquirir_trava.java"
awk '/^    static void adquirirTrava\(/ {p = 1} p {print} p && /^    }$/ {exit}' "$CX" > "$ADQ"
confere "testes/test_trava_caixa.sh: adquirirTrava real cumpre o contrato e 4 copias adulteradas sao detectadas (sem Windows, sem caminho exato, prazo reiniciado, causa trocada)" \
    "bash '$AQUI/testes/test_trava_caixa.sh' > '$TMP/test_trava.txt' 2>&1"
confere "desfecho adquire a trava SO pelo adquirirTrava, com o prazo existente e Files::createDirectory; nenhum createDirectory direto da trava" \
    "[ \"\$(grep -c 'adquirirTrava(trava, System.currentTimeMillis() + ESPERA_TRAVA_MS, Files::createDirectory, WINDOWS);' '$CX')\" = 1 ] && ! grep -q 'Files.createDirectory(trava)' '$CX'"
confere "AccessDeniedException tratado UMA vez, so na aquisicao, com Windows + caminho exato; demais IOException falham" \
    "[ -s '$ADQ' ] && [ \"\$(grep -c 'catch (AccessDeniedException' '$CX')\" = 1 ] && grep -q 'catch (AccessDeniedException' '$ADQ' \
     && grep -qF 'if (!windows || e.getFile() == null || !Path.of(e.getFile()).equals(trava)) {' '$ADQ' && grep -qF 'throw new UncheckedIOException(e);' '$ADQ'"
confere "prazo unico (limite nunca reatribuido), causa AccessDeniedException preservada e sem heuristica de mensagem/existencia" \
    "! grep -qE '(^|[^.a-zA-Z])limite[[:space:]]*(=|\\+=)[^=]' '$ADQ' && grep -qF 'nao adquirida no prazo (acesso negado)\", acessoNegado);' '$ADQ' \
     && ! grep -qE 'getMessage|getLocalizedMessage|getReason|Files\\.(exists|notExists|isDirectory)' '$ADQ'"
confere "escritor externo do IT com a mesma regra (Windows + caminho exato; senao falha) e testes unitarios da aquisicao presentes" \
    "grep -qF 'if (!CaixaHomologacaoMailSender.WINDOWS || pendente.getFile() == null || !Path.of(pendente.getFile()).equals(trava)) {' '$MAILT/CaixaHomologacaoMailSenderIT.java' \
     && [ \"\$(grep -cE 'void (acessoNegadoForaDoWindowsEFalhaImediata|acessoNegadoEmOutroCaminhoEFalhaImediataMesmoNoWindows|acessoNegadoPersistenteNaoViraSucessoEPreservaACausa|prazoUnicoComOcupadaEAcessoNegadoAlternadosNaoReinicia)\\(' '$MAILT/AquisicaoTravaTest.java')\" = 4 ]"
confere "ferramenta do runner (Linux) com o protocolo inalterado: mkdir da trava, anexa, rmdir" \
    "grep -qF 'mkdir /caixa/controle.trava && { printf \"%s\\n\" \"\$1\" >> /caixa/controle.conf; rmdir /caixa/controle.trava; }' '$AQUI/homologacao.sh'"

echo "== 19. aplicacao web no gateway (ensaio-frontend, F1.5 P-A; a prova de roteamento e testes/test_gateway_frontend.sh)"
GF="$AQUI/lib/gateway_frontend.py"
confere "modelo do nginx com a ancora 'location / { return 404; }' exatamente uma vez" \
    "[ \"\$(python -c 'import sys; sys.path.insert(0, sys.argv[1]); import gateway_frontend as g; print(open(sys.argv[2], encoding=\"utf-8\").read().count(g.ANCORA))' '$AQUI/lib' '$AQUI/gateway/nginx.modelo')\" = 1 ]"
python "$GF" aplicar "$TMP/nginx.conf" "$TMP/nginx.frontend.conf" > /dev/null 2>&1 || nok "bloco da aplicacao nao aplicado ao nginx gerado"
confere "nginx da aplicacao = gerado + bloco, e mais nada (rotas /api/v1/ e /portal/api/v1/ do modelo intactas)" "python '$GF' conferir '$TMP/nginx.conf' '$TMP/nginx.frontend.conf' > /dev/null"
confere "aplicar de novo sobre o ja aplicado e recusado" "! python '$GF' aplicar '$TMP/nginx.frontend.conf' '$TMP/nginx.frontend2.conf' > /dev/null 2>&1"
confere "bloco sem error_page, sem proxy_intercept_errors e sem proxy_pass novo" \
    "! python -c 'import sys; sys.path.insert(0, sys.argv[1]); import gateway_frontend as g; b = g.BLOCO_APLICACAO; sys.exit(0 if (\"error_page\" in b or \"intercept\" in b or \"proxy_pass\" in b) else 1)' '$AQUI/lib'"
mkdir -p "$TMP/spa"
if ( export HOMOLOG_PROJETO=nekkora-homologacao-ensaio-validacao HOMOLOG_ID=ensaio-validacao HOMOLOG_SEGREDOS="$S" HOMOLOG_COMMIT=0000000 \
        HOMOLOG_PORTA=55999 HOMOLOG_MIGRACOES="$TMP/mig" HOMOLOG_SQL="$AQUI/sql" HOMOLOG_CONFIG="$TMP/config" HOMOLOG_REDE_INTERNA=10.252.20 \
        HOMOLOG_REDE_BORDA=10.252.21 HOMOLOG_FRONTEND_DIST="$TMP/spa"
     docker compose -f "$B" -f "$O16" -f "$AQUI/compose.frontend.yml" config > "$TMP/frontend.yml" 2>"$TMP/compose.err" ); then
    ok "base + 016 + frontend renderiza"
else
    nok "frontend nao renderiza: $(head -3 "$TMP/compose.err")"
fi
confere "build montado SO no gateway, em /spa, somente leitura" "python - '$TMP/frontend.yml' <<'PY'
import re, sys
t = open(sys.argv[1], encoding='utf-8').read()
servicos = re.split(r'(?m)^  (?=[a-z][a-z0-9-]*:$)', t.split('\nservices:\n', 1)[1].split('\n' + 'volumes:', 1)[0].split('\nnetworks:', 1)[0])
com = [b.split(':', 1)[0] for b in servicos if 'target: /spa' in b]
gw = next(b for b in servicos if b.startswith('gateway:'))
bloco = gw[gw.index('target: /spa') - 200:gw.index('target: /spa') + 60]
sys.exit(0 if com == ['gateway'] and 'read_only: true' in bloco else 1)
PY"
confere "sem HOMOLOG_FRONTEND_DIST a sobreposicao nao renderiza (falha fechada)" \
    "! ( export HOMOLOG_PROJETO=x HOMOLOG_ID=ensaio-validacao HOMOLOG_SEGREDOS='$S' HOMOLOG_COMMIT=0 HOMOLOG_PORTA=55999 HOMOLOG_MIGRACOES='$TMP/mig' HOMOLOG_SQL='$AQUI/sql' HOMOLOG_CONFIG='$TMP/config'; unset HOMOLOG_FRONTEND_DIST; docker compose -f '$B' -f '$O16' -f '$AQUI/compose.frontend.yml' config > /dev/null 2>&1 )"
# recusas do criar (nada e criado): par web/lib em repositorios Git TEMPORARIOS deste teste
ZERO=0000000000000000000000000000000000000000
# recusa + MOTIVO: a mensagem tem de ser a esperada (uma recusa posterior, por outro motivo, nao vale)
recusa_fe() { local msg="$1"; shift; recusa "$@"; grep -qF -- "$msg" "$TMP/recusa.txt" || nok "recusa por outro motivo: $1 (esperado: $msg)"; }
gitc() { git -c user.name=validacao -c user.email=validacao@nekkora.test -c core.autocrlf=false -c commit.gpgsign=false "$@"; }
W="$TMP/web_par"; L="$W/node_modules/nekkora-lib"
mkdir -p "$L" && printf 'node_modules/\ndist/\n' > "$W/.gitignore" && echo web > "$W/arquivo.txt" && echo lib > "$L/arquivo.txt"
( cd "$W" && gitc init -q && gitc add -A && gitc commit -q -m web ) && ( cd "$L" && gitc init -q && gitc add -A && gitc commit -q -m lib ) || nok "repositorios temporarios do par"
CW="$(git -C "$W" rev-parse HEAD)"; CL="$(git -C "$L" rev-parse HEAD)"
recusa_fe "so em ambiente descartavel" "aplicacao web fora de ensaio-*" hml-validacao-fw HOMOLOG_ATE=016 HOMOLOG_FRONTEND_WEB="$W" HOMOLOG_FRONTEND_COMMIT="$CW" HOMOLOG_FRONTEND_LIB_COMMIT="$CL"
recusa_fe "exige HOMOLOG_ATE=016" "aplicacao web com HOMOLOG_ATE=015" ensaio-validacao-fa HOMOLOG_ATE=015 HOMOLOG_FRONTEND_WEB="$W" HOMOLOG_FRONTEND_COMMIT="$CW" HOMOLOG_FRONTEND_LIB_COMMIT="$CL"
recusa_fe "exige HOMOLOG_FRONTEND_COMMIT e HOMOLOG_FRONTEND_LIB_COMMIT" "aplicacao web sem o commit da lib" ensaio-validacao-fb HOMOLOG_ATE=016 HOMOLOG_FRONTEND_WEB="$W" HOMOLOG_FRONTEND_COMMIT="$CW"
recusa_fe "sem HOMOLOG_FRONTEND_WEB" "commit do web sem HOMOLOG_FRONTEND_WEB" ensaio-validacao-fc HOMOLOG_ATE=016 HOMOLOG_FRONTEND_COMMIT="$CW" HOMOLOG_FRONTEND_LIB_COMMIT="$CL"
recusa_fe "sha completos (40 hexadecimais)" "commit abreviado" ensaio-validacao-fd HOMOLOG_ATE=016 HOMOLOG_FRONTEND_WEB="$W" HOMOLOG_FRONTEND_COMMIT="${CW:0:12}" HOMOLOG_FRONTEND_LIB_COMMIT="$CL"
recusa_fe "aplicacao web: HEAD" "web com HEAD diferente do declarado" ensaio-validacao-fe HOMOLOG_ATE=016 HOMOLOG_FRONTEND_WEB="$W" HOMOLOG_FRONTEND_COMMIT="$ZERO" HOMOLOG_FRONTEND_LIB_COMMIT="$CL"
recusa_fe "lib ligada: HEAD" "lib com HEAD diferente do declarado" ensaio-validacao-ff HOMOLOG_ATE=016 HOMOLOG_FRONTEND_WEB="$W" HOMOLOG_FRONTEND_COMMIT="$CW" HOMOLOG_FRONTEND_LIB_COMMIT="$ZERO"
recusa_fe "nao e repositorio Git" "diretorio do web que nao e repositorio" ensaio-validacao-fg HOMOLOG_ATE=016 HOMOLOG_FRONTEND_WEB="$TMP/mig" HOMOLOG_FRONTEND_COMMIT="$CW" HOMOLOG_FRONTEND_LIB_COMMIT="$CL"
echo sujo > "$W/nao_rastreado.txt"
recusa_fe "aplicacao web: copia de trabalho com alteracoes" "web com arquivo nao ignorado" ensaio-validacao-fh HOMOLOG_ATE=016 HOMOLOG_FRONTEND_WEB="$W" HOMOLOG_FRONTEND_COMMIT="$CW" HOMOLOG_FRONTEND_LIB_COMMIT="$CL"
rm -f "$W/nao_rastreado.txt"; echo alterado >> "$L/arquivo.txt"
recusa_fe "lib ligada: copia de trabalho com alteracoes" "lib com alteracao" ensaio-validacao-fi HOMOLOG_ATE=016 HOMOLOG_FRONTEND_WEB="$W" HOMOLOG_FRONTEND_COMMIT="$CW" HOMOLOG_FRONTEND_LIB_COMMIT="$CL"
( cd "$L" && gitc checkout -q -- arquivo.txt )
# par correto: o frontend e aceito e a criacao segue ate a recusa SEGUINTE (lista da 016 sem a linha), sem criar nada
recusa "par web/lib conferido passa e a criacao para na lista da 016" ensaio-validacao-fj HOMOLOG_ATE=016 HOMOLOG_FRONTEND_WEB="$W" HOMOLOG_FRONTEND_COMMIT="$CW" HOMOLOG_FRONTEND_LIB_COMMIT="$CL"
confere "  (a recusa acima foi a da lista da 016, nao a do par)" "grep -q 'a 016 exige a linha de elevacao' '$TMP/recusa.txt'"
# primeira ocorrencia de registro_fontes_imagens = a do criar (o atualizar, mais abaixo, tambem registra as fontes)
confere "criar compila e copia o build DEPOIS da fotografia da lista e ANTES dos segredos" \
    "awk '/registro_fontes_imagens \"\\\$REPO\"/ && !a {a=NR} /frontend_preparar \"\\\$FRONTEND_WEB\"/ {b=NR} /segredos.py\" gerar/ && !c {c=NR} END {exit !(a && b && c && a < b && b < c)}' '$AQUI/homologacao.sh'"

echo "== 20. semeadura, jornadas e sessao do ensaio-frontend (F1.5 P-A; banco real: testes/test_semear_frontend_banco.sh)"
H="$AQUI/homologacao.sh"; SQLS="$AQUI/sql/semear_frontend.sql"; SQLX="$AQUI/sql/sessao_frontend.sql"; ORQ="$AQUI/lib/semear_frontend.py"
bloco_fe() { awk '/^semear-frontend\|jornadas-frontend\|frontend-sessao\)/ {p=1} p {print} p && /^    ;;$/ {exit}' "$H"; }
confere "acoes do frontend SO em ensaio-* criado com a aplicacao web e LIBERADO" \
    "bloco_fe | grep -qF '[[ \"\$HOMOLOG_ID\" =~ ^ensaio- ]] && [ -n \"\${HOMOLOG_FRONTEND_DIST:-}\" ]' && bloco_fe | grep -qF 'confere_liberacao \"\$TRAVA\" > /dev/null || falha'"
confere "jornadas pelo runner SO pelo gateway com TLS conferido (--alvo + --ca); o modo --teste-http nunca e usado pelo runner" \
    "bloco_fe | grep -qF -- '--alvo \"https://\$HOMOLOG_HOST:\$HOMOLOG_PORTA\" --ca' && ! grep -q -- '--teste-http' '$H'"
confere "frontend-sessao so com 'absoluta' (inatividade so com o relogio real)" "bloco_fe | grep -qF '[ \"\${3:-}\" = absoluta ] || falha'"
confere "sessao: SO a conta exclusiva expira@ da Nekkora Demo, SO 'absoluta', e o UPDATE so encurta expires_at" \
    "grep -qF \"x.email = 'expira@demo.nekkora.test' AND g.name = 'Nekkora Demo'\" '$SQLX' && grep -qF \"IF t <> 'absoluta' THEN\" '$SQLX' \
     && [ \"\$(grep -c 'UPDATE user_sessions' '$SQLX')\" = 1 ] && grep -qF \"UPDATE user_sessions SET expires_at = created_at + interval '1 millisecond'\" '$SQLX' \
     && ! grep -E 'SET .*(created_at|last_seen_at|mfa_verified_at|revoked_at) *=' '$SQLX' | grep -v 'expires_at = created_at' | grep -q ."
confere "semeadura: recusa de semeadura existente ANTES da primeira escrita; conferencia ANTES do COMMIT; um BEGIN e um COMMIT" \
    "awk '/^DO [$][$]/ {d=NR} d && /SEMEADURA_RECUSADA/ && !r {r=NR} d && /^    INSERT INTO/ && !i {i=NR} /CONFERENCIA antes do COMMIT/ {c=NR} /^COMMIT;/ {m=NR} END {exit !(d && r && i && r < i && c && m && c < m)}' '$SQLS' \
     && [ \"\$(grep -c '^BEGIN;' '$SQLS')\" = 1 ] && [ \"\$(grep -c '^COMMIT;' '$SQLS')\" = 1 ]"
confere "semeadura: primeiro ORG_ADMIN so pelo provision_first_org_admin (012); nenhum dado real (dominio sintetico)" \
    "grep -qF 'PERFORM provision_first_org_admin(' '$SQLS' && grep -qF \"DOMINIO = 'demo.nekkora.test'\" '$ORQ'"
confere "orquestrador: plano com as credenciais gravado (atomico e EXCLUSIVO) ANTES de enviar a transacao ao banco" \
    "awk '/grava_atomico\(pend, plano, exclusivo=True\)/ && !g {g=NR} /roda\(bash, a.psql, script_sql/ && !s {s=NR} END {exit !(g && s && g < s)}' '$ORQ'"
confere "orquestrador (P-A-R1): trava obtida ANTES do estado; promocao confere a identidade, grava o mapa e SO DEPOIS renomeia" \
    "awk '/^def semear\(a\):/ {d=1} d && /if not trava.adquire\(\):/ && !t {t=NR} d && /return _semear\(a, bash\)/ && !c {c=NR} END {exit !(t && c && t < c)}' '$ORQ' \
     && awk '/^def promove\(/ {d=1} d && /identidade\(atual\) != identidade\(plano\)/ && !i {i=NR} d && /^    grava_mapa\(evid, plano\)/ && !m {m=NR} d && /^    _renomeia\(pend, fin\)/ && !r {r=NR} END {exit !(i && m && r && i < m && m < r)}' '$ORQ'"
JF="$AQUI/testes/jornadas_frontend.py"
confere "seletor (P-B-C1): so sessoes-pos-revogacao e expiracao-absoluta sao selecionaveis; O5 limitada a 2" \
    "grep -qxF \"JORNADAS_SELECIONAVEIS = ('sessoes-pos-revogacao', 'expiracao-absoluta')\" '$JF' && grep -q '^O5_MAXIMO = 2 ' '$JF'"
confere "seletor: validado ANTES de qualquer requisicao ou arquivo (valida_selecao antes do OpenAPI, do alvo e do relatorio)" \
    "awk '/selecao = valida_selecao\(a\)/ && !v {v=NR} /alvo = Alvo\(a\)/ && !t {t=NR} /rel = Relatorio\(a.evid\)/ && !r {r=NR} END {exit !(v && t && r && v < t && v < r)}' '$JF'"
SG="$AQUI/lib/selecao_gateway.sh"
confere "runner (P-B-C1-R1): com --somente, jornadas SO por selecao_executa (guardas do gateway); sem --somente, exec direto; UMA definicao do comando" \
    "bloco_fe | awk '/JORN=\(python \"\\\$AQUI\/testes\/jornadas_frontend.py\"/ {c++; if (!j) j=NR} /\[ -n \"\\\$SOMENTE\" \] \|\| exec \"\\\$\{JORN\[@\]\}\"/ && !e {e=NR} /\. \"\\\$AQUI\/lib\/selecao_gateway.sh\"/ && !l {l=NR} /selecao_executa \"\\\$J\" \"\\\$\{JORN\[@\]\}\" \|\| rc=\\\$\?/ && !s {s=NR} END {exit !(c == 1 && j < e && e < l && l < s)}' \
     && [ \"\$(bloco_fe | grep -c 'jornadas_frontend.py')\" = 1 ] && ! bloco_fe | grep -q 'compose logs'"
confere "guardas: coleta do gateway verificada SEPARADA da contagem (ps em execucao, logs e filtro com codigo conferido; nada de || true nem 2>/dev/null na coleta)" \
    "awk '/^coleta_gateway\(\) \{/ {d=1} d {print} d && /^\}/ {exit}' '$SG' > '$TMP/coleta.txt' && grep -qF 'compose ps -q --status running gateway' '$TMP/coleta.txt' \
     && grep -qF 'compose logs --no-color --timestamps --since \"\$desde\" gateway' '$TMP/coleta.txt' && ! grep -qE '\|\| true|2>/dev/null' '$TMP/coleta.txt' \
     && [ \"\$(grep -c 'return 1' '$TMP/coleta.txt')\" = 4 ]"
confere "guardas: pre-condicao ANTES da unica chamada; conferencia final desde o MESMO inicio; inconclusiva = codigo 3; funcional e conferencia em arquivos separados" \
    "awk '/^selecao_executa\(\) \{/ {d=1} d && /coleta_gateway \"\\\$desde\" \"\\\$J\/pre_condicao_gateway.txt\"/ && !p {p=NR} d && /^    \"\\\$@\" \|\| rc=\\\$\?/ {c++; if (!x) x=NR} d && /coleta_gateway \"\\\$desde\" \"\\\$J\/gateway_janela.txt\"/ && !f {f=NR} END {exit !(p && x && f && p < x && x < f && c == 1)}' '$SG' \
     && grep -qF '[ \"\$inconclusiva\" = 0 ] || return 3' '$SG' && grep -qF 'resultado_funcional.txt' '$SG' && grep -qF 'conferencia_trafego.txt' '$SG' \
     && ! grep -q 'IGUAIS\|sem trafego concorrente' '$SG'"
confere "credenciais de navegacao (P-C-T): so contas uso=navegacao; entrega pela area de transferencia fora do historico; nenhum valor na saida; TOTP com passo posterior registrado antes" \
    "PYTHONDONTWRITEBYTECODE=1 python '$AQUI/testes/test_credencial_navegacao.py' > '$TMP/cred.txt' 2>&1 && grep -q '^OK' '$TMP/cred.txt' && ! grep -q 'skipped' '$TMP/cred.txt' \
     && grep -qF \"if c.get('uso') != NAVEGACAO:\" '$AQUI/lib/credencial_navegacao.py' && ! grep -qE 'print\([^)]*valor|escreve\([^)]*\{valor' '$AQUI/lib/credencial_navegacao.py'"
confere "procedimento do gateway (P-C-R2): referencia do preparo reconferida antes de escrever; coleta verificada (selecao_gateway.sh); falhas sem escrita nem reload: testes/test_gateway_tipos.sh" \
    "bash '$AQUI/testes/test_gateway_tipos.sh' > '$TMP/gw_tipos.txt' 2>&1 && grep -q '^RESULTADO: procedimento do gateway OK' '$TMP/gw_tipos.txt' \
     && grep -qF '. \"\$GT_OPS/lib/selecao_gateway.sh\"' '$AQUI/lib/gateway_tipos.sh' && ! grep -E 'logs .*\| *grep -c' '$AQUI/lib/gateway_tipos.sh' | grep -q ."
confere "guardas da selecao com compose controlado (coleta falha, trafego recente, uma chamada, final inconclusiva, sucesso): testes/test_selecao_gateway.sh" \
    "bash '$AQUI/testes/test_selecao_gateway.sh' > '$TMP/selecao_gw.txt' 2>&1 && grep -q '^RESULTADO: guardas da selecao OK' '$TMP/selecao_gw.txt'"
SNAKE="$(ls "$HOME"/.m2/repository/org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar 2>/dev/null)"
if [ -n "$SNAKE" ] && java -cp "$(cygpath -m "$SNAKE" 2>/dev/null || echo "$SNAKE")" "$(cygpath -m "$AQUI/lib/OpenApiJson.java" 2>/dev/null || echo "$AQUI/lib/OpenApiJson.java")" \
        "$(cygpath -m "$REPO/api/openapi/nekkora-api-v1.yaml" 2>/dev/null || echo "$REPO/api/openapi/nekkora-api-v1.yaml")" "$(cygpath -m "$TMP/openapi.json" 2>/dev/null || echo "$TMP/openapi.json")" > "$TMP/openapi_conv.txt" 2>&1; then
    ok "OpenAPI convertido para as jornadas ($(tail -1 "$TMP/openapi_conv.txt" | cut -c1-90))"
else
    nok "conversao do OpenAPI (SnakeYAML 2.6 do repositorio Maven local)"
fi
confere "testes unitarios das ferramentas (validador com controles, date-time, TOTP RFC 6238, catalogo, estados do orquestrador, gateway, modo de teste; contrato real sem restricao fora do escopo)" \
    "OPENAPI_JSON='$(cygpath -w "$TMP/openapi.json" 2>/dev/null || echo "$TMP/openapi.json")' PYTHONIOENCODING=utf-8 python '$AQUI/testes/test_frontend_ferramentas.py' > '$TMP/unit_fe.txt' 2>&1 && grep -q '^OK' '$TMP/unit_fe.txt' && ! grep -q 'skipped' '$TMP/unit_fe.txt'"

echo "== 21. validacao administrativa da F6a (F6a-V-T; banco real: testes/test_semear_f6a_banco.sh)"
S6="$AQUI/lib/semear_f6a.py"; SQL6="$AQUI/sql/semear_f6a.sql"; VF6="$AQUI/testes/validar_f6a.py"; FA="$AQUI/lib/frontend_atualizar.sh"
bloco_f6a() { awk '/^semear-f6a\|validar-f6a\)/ {p=1} p {print} p && /^    ;;$/ {exit}' "$H"; }
confere "bloco da F1.5 com o rotulo aprovado; acoes da F6a num bloco PROPRIO, com as mesmas exigencias (ensaio-*, aplicacao web, LIBERADO)" \
    "grep -qx 'semear-frontend|jornadas-frontend|frontend-sessao)' '$H' && bloco_f6a | grep -qF '[[ \"\$HOMOLOG_ID\" =~ ^ensaio- ]] && [ -n \"\${HOMOLOG_FRONTEND_DIST:-}\" ]' \
     && bloco_f6a | grep -qF 'confere_liberacao \"\$TRAVA\" > /dev/null || falha'"
confere "validar-f6a preparar/conferir SO por selecao_executa (pre-condicao de 31 s, UMA chamada, conferencia de trafego), pelo gateway com TLS conferido" \
    "bloco_f6a | awk '/\. \"\\\$AQUI\/lib\/selecao_gateway.sh\"/ && !l {l=NR} /selecao_executa \"\\\$J\" \"\\\$\{VF\[@\]\}\" \"\\\$sub\"/ && !s {s=NR} END {exit !(l && s && l < s)}' \
     && bloco_f6a | grep -qF -- '--alvo \"https://\$HOMOLOG_HOST:\$HOMOLOG_PORTA\" --ca' && [ \"\$(bloco_f6a | grep -c 'exec \"\${VF\[@\]}\" \(preparar\|conferir\)')\" = 0 ]"
confere "semeadura complementar: MESMA trava da F1.5; base da F1.5 CONCLUIDA exigida antes do estado; plano gravado (atomico e EXCLUSIVO) antes da transacao; promocao confere a identidade, grava o mapa e SO DEPOIS renomeia" \
    "grep -qF 'trava = sf.Trava(a.segredos)' '$S6' && awk '/^def _semear/ {d=1} d && /est_base != .CONCLUIDA./ && !b {b=NR} d && /est, plano = estado\(/ && !e {e=NR} d && /sf.grava_atomico\(pend, plano, exclusivo=True\)/ && !g {g=NR} d && /sf.roda\(bash, a.psql, script_sql/ && !s {s=NR} END {exit !(b && e && g && s && b < e && g < s)}' '$S6' \
     && awk '/^def promove\(/ {d=1} d && /identidade\(atual\) != identidade\(plano\)/ && !i {i=NR} d && /^    grava_mapa\(evid, plano\)/ && !m {m=NR} d && /sf._renomeia\(pend, fin\)/ && !r {r=NR} END {exit !(i && m && r && i < m && m < r)}' '$S6'"
confere "SQL complementar: recusa de f6av-* existente ANTES da primeira escrita; conferencia ANTES do COMMIT; um BEGIN e um COMMIT; so INSERT (nenhum UPDATE/DELETE)" \
    "awk '/^DO [$][$]/ {d=NR} d && /SEMEADURA_F6A_RECUSADA: ja existe conta f6av/ && !r {r=NR} d && /^        INSERT INTO/ && !i {i=NR} /CONFERENCIA antes do COMMIT/ {c=NR} /^COMMIT;/ {m=NR} END {exit !(d && r && i && r < i && c && m && c < m)}' '$SQL6' \
     && [ \"\$(grep -c '^BEGIN;' '$SQL6')\" = 1 ] && [ \"\$(grep -c '^COMMIT;' '$SQL6')\" = 1 ] && ! grep -qE '^[^-]*\b(UPDATE|DELETE FROM)\b' '$SQL6'"
confere "contas complementares: prefixo f6av-, dominio sintetico, uso f6a-alvo (fora da navegacao e das jornadas)" \
    "grep -qxF \"PREFIXO = 'f6av-'\" '$S6' && grep -qxF \"USO = 'f6a-alvo'\" '$S6' && grep -qxF 'DOMINIO = sf.DOMINIO' '$S6'"
confere "credencial totp-reuso: SO com a autorizacao de uso unico (removida ANTES da entrega), passo = ultimo entregue e <= last_used_step; nenhum passo novo" \
    "awk '/if tipo == .totp-reuso.:/ {d=1} d && /if passo != m.get\(.ultimo_passo.\)/ && !u {u=NR} d && /passo > aceito/ && !a {a=NR} d && /os.remove\(aut\)/ && !r {r=NR} d && /valor = totp\(m\[.totp.\], passo\)/ && !v {v=NR} d && /elif tipo == .senha.:/ {ok = (u && a && r && v && r < v && a < r && u < r); exit} END {exit !ok}' '$AQUI/lib/credencial_navegacao.py' \
     && ! awk '/if tipo == .totp-reuso.:/ {d=1} d && /elif tipo == .senha.:/ {exit} d {print}' '$AQUI/lib/credencial_navegacao.py' | grep -q \"m\[.ultimo_passo.\] =\""
confere "verificador: so SELECT no banco (nenhum INSERT/UPDATE/DELETE nas consultas); nada sensivel na foto (colunas sensiveis fora do JSON)" \
    "! grep -nE \"(INSERT INTO|UPDATE [a-z_]+ SET|DELETE FROM)\" '$VF6' | grep -q . && grep -qF \"(to_jsonb(t) - {sens})::text\" '$VF6' \
     && grep -qF \"SENSIVEL = re.compile(r'(hash|token|secret|nonce|ciphertext|password|csrf)', re.I)\" '$VF6'"
confere "atualizacao do frontend: coleta do gateway pelo selecao_gateway.sh; referencia ANTES da coleta; copia de seguranca ANTES de escrever; recria SO o gateway; falha depois da escrita pede reverter (sem nova tentativa)" \
    "grep -qF '. \"\$FA_OPS/lib/selecao_gateway.sh\"' '$FA' && awk '/^fa_aplicar\(\) \{/ {d=1} d && /^    fa_confere_referencia$/ && !r {r=NR} d && /^    fa_sem_trafego$/ && !t {t=NR} d && /cp -p \"\\\$FA_ENV\" \"\\\$FA_BKP\"/ && !c {c=NR} d && /cat \"\\\$FA_D\/ambiente.env.novo\" > \"\\\$FA_ENV\"/ && !w {w=NR} d && /^\}/ {exit !(r && t && c && w && r < t && t < c && c < w)}' '$FA' \
     && [ \"\$(grep -v '^ *#' '$FA' | grep -c 'compose up -d --no-deps --force-recreate gateway')\" = 1 ] && ! grep -E 'logs .*\| *grep -c' '$FA' | grep -q ."
confere "runner: pre-voo da pasta da lista alternativa SO para versao sintetica 9xx sem pre-voo oficial (022 e 016-020 sempre pelo oficial)" \
    "[ \"\$(grep -c 'pf=\"\$(dirname \"\$LISTA\")/preflight/\$v.sql\"' '$AQUI/implantacao/implantar.sh')\" = 1 ] \
     && grep -qF '[ ! -f \"\$pf\" ] && [ -n \"\${HOMOLOG_ELEVACAO_ARQ:-}\" ] && [[ \"\$v\" =~ ^9[0-9]{2}\$ ]]; then pf=' '$AQUI/implantacao/implantar.sh'"
confere "atualizar 021 -> 022, 022 -> 023 e 023 -> 024 (um passo) com lista alternativa: recusa fora de ensaio-* e nas conferencias de existencia, gramatica, versao, sha256 e vinculo, sem alterar o ambiente; lista correta so para na liberacao: testes/test_atualizar_lista.sh" \
    "bash '$AQUI/testes/test_atualizar_lista.sh' > '$TMP/atz.txt' 2>&1 && grep -q '^RESULTADO: atualizar com lista alternativa OK' '$TMP/atz.txt'"
confere "procedimento da atualizacao do frontend com compose controlado (preparo, recusas sem escrita nem up, falhas pedindo reverter, caminho valido e reversao): testes/test_frontend_atualizar.sh" \
    "bash '$AQUI/testes/test_frontend_atualizar.sh' > '$TMP/fa.txt' 2>&1 && grep -q '^RESULTADO: atualizacao do frontend OK' '$TMP/fa.txt'"
confere "verificador e reuso de TOTP (docker, regras da atora, anti-replay, totp-reuso, intencoes, ritmo, fases): testes/test_validar_f6a.py" \
    "PYTHONDONTWRITEBYTECODE=1 PYTHONIOENCODING=utf-8 python '$AQUI/testes/test_validar_f6a.py' > '$TMP/vf6.txt' 2>&1 && grep -q '^OK' '$TMP/vf6.txt' && ! grep -q 'skipped' '$TMP/vf6.txt'"

echo
[ "$falhas" = 0 ] && echo "RESULTADO: validacao estatica OK" || echo "RESULTADO: $falhas falha(s)"
[ "$falhas" = 0 ]
