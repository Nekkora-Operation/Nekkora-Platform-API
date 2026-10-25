#!/usr/bin/env bash
# Prova ESTATICA (sem AWS, sem rede) do aws/m365-aws.sh: roda o script REAL contra uma AWS CLI falsa que registra cada
# chamada, num diretorio de segredos temporario (removido no fim). Confere:
#   - renderizar: modelos sem variavel pendente, JSON valido, valores da conta; recusa conta invalida e MFA que nao seja
#     de codigo (chave FIDO);
#   - gerar: PKCS#12 com exatamente uma chave, SecretString nekkora-m365/1 com os cinco campos, senha fora da saida;
#     recusa client invalido e segundo certificado nao publicado;
#   - funcao (W1): create-role com 3600 s e a confianca renderizada; recusa funcao existente e sessao de outra identidade;
#   - publicar (W2): recusa sem a funcao e com segredo existente; o SecretString vai por file:// (nenhum segredo na linha
#     de comando); politica de recurso com --block-public-policy; a chave FICA no disco no sucesso e na falha;
#   - credenciais (W3): aws.env sem CR e conferido; credencial de longo prazo (AKIA) recusada e apagada;
#   - confirmar: so apaga a chave com a sessao da funcao, o administrador sem leitura e a leitura utilizavel (conteudo
#     armazenado igual ao publicado, certificado = .cer); cada falha mantem a chave e apaga as credenciais;
#   - nenhuma chamada registrada contem a senha, o PKCS#12 ou a chave secreta.
# Uso: bash testes/test_m365_aws.sh   (codigo 0 = todas as provas OK)
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
S="$AQUI/aws/m365-aws.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
falhas=0
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }
confere() { if eval "$2"; then ok "$1"; else nok "$1"; fi; }

export HOMOLOG_SEGREDOS="$TMP/seg" FAKE="$TMP/fake"
M="$HOMOLOG_SEGREDOS/m365"; C="$M/certificado"; LOG="$FAKE/chamadas.log"
mkdir -p "$M" "$FAKE" "$TMP/bin"
cat > "$TMP/bin/aws" <<'AWS'
#!/usr/bin/env bash
echo "$*" >> "$FAKE/chamadas.log"
case "$1 $2" in
    "--version ") echo "aws-cli/2.32.5 Python/3.13.9 Windows/11 exe/AMD64";;
    "sts get-caller-identity")
        # com --profile: a sessao do administrador; sem perfil, as credenciais do ambiente (as da funcao, do aws.env)
        case " $* " in *" --profile "*) echo "${FAKE_ARN:-arn:aws:iam::111122223333:user/operadora-teste}";;
            *) [ -n "${AWS_SESSION_TOKEN:-}" ] || exit 253; echo "${FAKE_ROLE_ARN:-arn:aws:sts::111122223333:assumed-role/nekkora-homologacao-platform-m365-leitura/nekkora-hml-m365}";; esac;;
    "iam list-mfa-devices") printf '%s\t%s\n' "arn:aws:iam::111122223333:u2f/user/operadora-teste/chave" "arn:aws:iam::111122223333:mfa/celular";;
    "iam get-role") [ -f "$FAKE/funcao" ] || exit 254;;
    "iam create-role") touch "$FAKE/funcao"; for a in "$@"; do case "$a" in file://*) cp "${a#file://}" "$FAKE/confianca.json";; esac; done
        echo "arn:aws:iam::111122223333:role/nekkora-homologacao-platform-m365-leitura";;
    "iam put-role-policy") for a in "$@"; do case "$a" in file://*) cp "${a#file://}" "$FAKE/leitura.json";; esac; done;;
    "iam list-role-policies") echo "ler-segredo-m365";;
    "iam list-attached-role-policies") echo 0;;
    "secretsmanager list-secrets") [ -f "$FAKE/segredo" ] && echo 1 || echo 0;;
    "secretsmanager create-secret") for a in "$@"; do case "$a" in file://*) [ -f "${a#file://}" ] && echo arquivo_presente >> "$FAKE/criacao" && cp "${a#file://}" "$FAKE/armazenado";; esac; done
        [ -n "${FAKE_FALHA_CRIAR:-}" ] && exit 255
        touch "$FAKE/segredo"; echo "arn:aws:secretsmanager:sa-east-1:111122223333:secret:nekkora/homologacao/platform/m365-AbCdEf";;
    "secretsmanager validate-resource-policy") echo True;;
    "secretsmanager get-secret-value") [ -n "${FAKE_ADMIN_LE:-}" ] && { echo v-1; exit 0; }
        echo "An error occurred (AccessDeniedException) when calling the GetSecretValue operation" >&2; exit 254;;
    "secretsmanager put-resource-policy") touch "$FAKE/politica";;
    "configure export-credentials") printf 'AWS_ACCESS_KEY_ID=%s\r\nAWS_SECRET_ACCESS_KEY=chave-secreta-falsa-123\r\nAWS_SESSION_TOKEN=token-falso\r\nAWS_CREDENTIAL_EXPIRATION=%s\r\n' "${FAKE_ID:-ASIAFALSO}" "$(date -u -d '+55 min' +%Y-%m-%dT%H:%M:%SZ)";;
    *) echo "chamada inesperada: $*" >&2; exit 99;;
esac
AWS
chmod +x "$TMP/bin/aws"
# a chamada Java da Platform (ProvaAutenticacaoM365 --segredo), falsa: le o "armazenado" da AWS falsa, so com as
# credenciais da funcao e o ambiente da Platform; compara o SHA-256 pedido e devolve a impressao do certificado
cat > "$TMP/bin/prova-java" <<'JAVA'
#!/usr/bin/env bash
echo "$*" >> "$FAKE/java.log"
[ -n "${AWS_SESSION_TOKEN:-}" ] && [ "${NEKKORA_M365_SEGREDO_AWS:-}" = nekkora/homologacao/platform/m365 ] && [ -f "$FAKE/armazenado" ] \
    || { echo "autenticacao_segredo leitura=FALHOU causa=segredo da AWS recusado (status 400, AccessDeniedException)"; exit 5; }
esp=""; while [ $# -gt 0 ]; do [ "$1" = --esperado-sha256 ] && esp="$2"; shift; done
lido="$(sha256sum < "$FAKE/armazenado" | cut -c1-64)"
echo "autenticacao_segredo leitura=OK versao=v-1 formato=OK chave_privada=1 certificado_sha256=${FAKE_CER:?} certificado_dias=364 conteudo=$([ "$esp" = "$lido" ] && echo IGUAL || echo DIFERENTE)"
JAVA
chmod +x "$TMP/bin/prova-java"
export PATH="$TMP/bin:$PATH" M365_AWS_PROVA_JAVA="$TMP/bin/prova-java"
roda() { bash "$S" "$@" > "$TMP/out.txt" 2>&1; }

# ---------------------------------------------------------------------------------------------------- renderizar
printf 'CONTA_AWS=1111222233\nUSUARIO_IAM=operadora-teste\nMFA_SERIAL=arn:aws:iam::111122223333:mfa/celular\n' > "$M/aws-local.env"
roda renderizar; confere "renderizar recusa conta sem 12 digitos" "[ \$? -ne 0 ] && grep -q '12 digitos' '$TMP/out.txt' && [ ! -d '$M/aws' ]"
printf 'CONTA_AWS=111122223333\nUSUARIO_IAM=operadora-teste\nMFA_SERIAL=arn:aws:iam::111122223333:u2f/user/operadora-teste/chave\n' > "$M/aws-local.env"
roda renderizar; confere "renderizar recusa MFA FIDO (a CLI so envia codigo)" "[ \$? -ne 0 ] && grep -q 'MFA de codigo' '$TMP/out.txt'"
printf 'CONTA_AWS=111122223333\r\nUSUARIO_IAM=operadora-teste\r\nMFA_SERIAL=arn:aws:iam::111122223333:mfa/celular\r\n' > "$M/aws-local.env"
roda renderizar; confere "renderizar (aws-local.env com CRLF) gera os 4 arquivos" "[ \$? -eq 0 ] && [ \$(ls '$M/aws' | wc -l) = 4 ]"
confere "renderizados sem variavel pendente" "! grep -q '\\\${' '$M/aws/'*"
confere "confianca: so o usuario da conta, com MFA" "python -c \"import json,sys; s=json.load(open(sys.argv[1]))['Statement']; assert len(s)==1 and s[0]['Principal']['AWS']=='arn:aws:iam::111122223333:user/operadora-teste' and s[0]['Condition']=={'Bool':{'aws:MultiFactorAuthPresent':'true'}}\" '$(cygpath -m "$M/aws/confianca-funcao-leitura.json" 2>/dev/null || echo "$M/aws/confianca-funcao-leitura.json")'"
confere "leitura: so GetSecretValue no ARN do segredo da conta, sem condicao de estagio" "python -c \"import json,sys; s=json.load(open(sys.argv[1]))['Statement']; assert len(s)==1 and s[0]['Action']=='secretsmanager:GetSecretValue' and s[0]['Resource']=='arn:aws:secretsmanager:sa-east-1:111122223333:secret:nekkora/homologacao/platform/m365-??????' and 'Condition' not in s[0]\" '$(cygpath -m "$M/aws/politica-leitura-segredo.json" 2>/dev/null || echo "$M/aws/politica-leitura-segredo.json")'"
confere "perfil da CLI com a funcao, o MFA e 3600 s" "grep -qx 'role_arn = arn:aws:iam::111122223333:role/nekkora-homologacao-platform-m365-leitura' '$M/aws/perfil-cli.ini' && grep -qx 'mfa_serial = arn:aws:iam::111122223333:mfa/celular' '$M/aws/perfil-cli.ini' && grep -qx 'duration_seconds = 3600' '$M/aws/perfil-cli.ini'"

# ---------------------------------------------------------------------------------------------------- conferir
roda conferir; confere "conferir: sessao, MFA de codigo, funcao e segredo inexistentes" "[ \$? -eq 0 ] && grep -q 'sessao=OK' '$TMP/out.txt' && grep -q 'mfa_dispositivos=2 de_codigo=1' '$TMP/out.txt' && grep -q 'funcao=INEXISTENTE' '$TMP/out.txt' && grep -q 'segredo=INEXISTENTE' '$TMP/out.txt'"

# ---------------------------------------------------------------------------------------------------- gerar
roda gerar --client 0000-invalido; confere "gerar recusa client que nao e GUID" "[ \$? -ne 0 ] && [ ! -e '$C/m365.p12' ]"
roda gerar --client 00000000-1111-2222-3333-444444444444
confere "gerar: PKCS#12, .cer, impressao SHA-256 e SecretString" "[ \$? -eq 0 ] && [ -f '$C/m365.p12' ] && [ -f '$C/nekkora-platform-mail.cer' ] && grep -q '^SHA256: ' '$C/impressao.txt' && [ -f '$C/segredo.json' ]"
SENHA="$(cat "$C/senha.txt")"
confere "senha aleatoria (>= 40 caracteres) fora da saida" "[ \${#SENHA} -ge 40 ] && ! grep -qF -- '$SENHA' '$TMP/out.txt'"
confere "SecretString nekkora-m365/1 com os cinco campos e o tenant" "python -c \"import json,sys; d=json.load(open(sys.argv[1])); assert sorted(d)==['certificado_pkcs12','certificado_senha','client_id','formato','tenant_id'] and d['formato']=='nekkora-m365/1' and d['tenant_id']=='ae4ed48d-e7d8-4f24-83f2-443e997c5a65'\" '$(cygpath -m "$C/segredo.json" 2>/dev/null || echo "$C/segredo.json")'"
confere "exatamente uma chave privada no PKCS#12" "[ \"\$(keytool -J-Duser.language=en -list -v -storetype PKCS12 -keystore '$(cygpath -m "$C/m365.p12" 2>/dev/null || echo "$C/m365.p12")' -storepass:file '$(cygpath -m "$C/senha.txt" 2>/dev/null || echo "$C/senha.txt")' | grep -c 'Entry type: PrivateKeyEntry')\" = 1 ]"
P12B64="$(python -c "import json,sys; print(json.load(open(sys.argv[1]))['certificado_pkcs12'][:60])" "$(cygpath -m "$C/segredo.json" 2>/dev/null || echo "$C/segredo.json")")"
roda gerar --client 00000000-1111-2222-3333-444444444444; confere "gerar recusa segundo certificado nao publicado (o primeiro fica)" "[ \$? -ne 0 ] && [ \"\$(cat '$C/senha.txt')\" = '$SENHA' ]"

# ---------------------------------------------------------------------------------------------------- publicar antes da funcao
roda publicar; confere "publicar recusa sem a funcao (W1 antes do W2) e nao apaga a chave" "[ \$? -ne 0 ] && grep -q 'W1 antes do W2' '$TMP/out.txt' && [ -f '$C/m365.p12' ] && ! grep -q 'create-secret' '$LOG'"

# ---------------------------------------------------------------------------------------------------- funcao
FAKE_ARN="arn:aws:iam::111122223333:user/outra" bash "$S" funcao > "$TMP/out.txt" 2>&1
confere "funcao recusa sessao de outra identidade" "[ \$? -ne 0 ] && ! grep -q 'create-role' '$LOG'"
roda funcao; confere "funcao criada: 3600 s, politica inline, nenhuma anexada" "[ \$? -eq 0 ] && grep -q 'funcao=CRIADA' '$TMP/out.txt' && grep -q 'create-role .*--max-session-duration 3600' '$LOG' && cmp -s '$FAKE/confianca.json' '$M/aws/confianca-funcao-leitura.json' && cmp -s '$FAKE/leitura.json' '$M/aws/politica-leitura-segredo.json'"
roda funcao; confere "funcao recusa a segunda criacao" "[ \$? -ne 0 ] && grep -q 'ja existe' '$TMP/out.txt' && [ \$(grep -c 'create-role' '$LOG') = 1 ]"

# ---------------------------------------------------------------------------------------------------- publicar com falha na AWS
FAKE_FALHA_CRIAR=1 bash "$S" publicar > "$TMP/out.txt" 2>&1
confere "falha no create-secret: a chave FICA no disco, sem politica" "[ \$? -ne 0 ] && [ -f '$C/m365.p12' ] && [ -f '$C/senha.txt' ] && [ -f '$C/segredo.json' ] && [ ! -e '$FAKE/politica' ]"

# ---------------------------------------------------------------------------------------------------- publicar com sucesso
roda publicar
confere "publicar: segredo criado, politica com --block-public-policy, arquivo lido por file://" "[ \$? -eq 0 ] && grep -q 'segredo=CRIADO' '$TMP/out.txt' && grep -q 'put-resource-policy .*--block-public-policy' '$LOG' && [ \$(grep -c arquivo_presente '$FAKE/criacao') = 2 ]"
confere "publicar: a chave FICA no disco ate o confirmar" "grep -q 'chave_privada_no_disco=MANTIDA' '$TMP/out.txt' && [ -f '$C/m365.p12' ] && [ -f '$C/senha.txt' ] && [ -f '$C/segredo.json' ]"
roda publicar; confere "publicar recusa segredo existente e nao chama create-secret de novo" "[ \$? -ne 0 ] && grep -q 'ja existe' '$TMP/out.txt' && [ \$(grep -c 'create-secret' '$LOG') = 2 ]"
export FAKE_CER="$(sed -n 's/^SHA256: //p' "$C/impressao.txt")"

# ---------------------------------------------------------------------------------------------------- confirmar
roda confirmar; confere "confirmar recusa sem as credenciais da funcao (chave fica)" "[ \$? -ne 0 ] && grep -q 'credenciais da funcao ausentes' '$TMP/out.txt' && [ -f '$C/m365.p12' ]"
roda credenciais
FAKE_ROLE_ARN="arn:aws:sts::111122223333:assumed-role/outra-funcao/x" bash "$S" confirmar > "$TMP/out.txt" 2>&1
confere "confirmar: sessao que nao e a da funcao -> chave fica, credenciais apagadas" "[ \$? -ne 0 ] && grep -q 'sessao_da_funcao=FALHOU' '$TMP/out.txt' && grep -q 'confirmado=NAO chave_privada_no_disco=MANTIDA' '$TMP/out.txt' && [ -f '$C/m365.p12' ] && [ ! -e '$M/aws.env' ]"
roda credenciais
FAKE_ADMIN_LE=1 bash "$S" confirmar > "$TMP/out.txt" 2>&1
confere "confirmar: administrador le o valor (politica nao nega) -> chave fica" "[ \$? -ne 0 ] && grep -q 'administrador_le=SIM' '$TMP/out.txt' && [ -f '$C/m365.p12' ]"
roda credenciais
cp "$FAKE/armazenado" "$FAKE/armazenado.bom"; printf 'x' >> "$FAKE/armazenado"
roda confirmar; confere "confirmar: conteudo armazenado diferente do publicado -> chave fica" "[ \$? -ne 0 ] && grep -q 'conteudo=DIFERENTE' '$TMP/out.txt' && grep -q 'leitura_utilizavel=FALHOU' '$TMP/out.txt' && [ -f '$C/m365.p12' ]"
cp "$FAKE/armazenado.bom" "$FAKE/armazenado"
roda credenciais
FAKE_CER="00:11" bash "$S" confirmar > "$TMP/out.txt" 2>&1
confere "confirmar: certificado lido diferente do .cer -> chave fica" "[ \$? -ne 0 ] && grep -q 'leitura_utilizavel=FALHOU' '$TMP/out.txt' && [ -f '$C/m365.p12' ]"
roda credenciais
roda confirmar
confere "confirmar: sessao da funcao, administrador sem leitura, leitura utilizavel" "[ \$? -eq 0 ] && grep -q 'sessao_da_funcao=OK' '$TMP/out.txt' && grep -q 'administrador_le=NAO (AccessDenied)' '$TMP/out.txt' && grep -q 'leitura_utilizavel=OK' '$TMP/out.txt' && grep -q 'confirmado=OK' '$TMP/out.txt'"
confere "confirmar: so entao a chave, a senha, o SecretString e as credenciais saem do disco (.cer fica)" "[ ! -e '$C/m365.p12' ] && [ ! -e '$C/senha.txt' ] && [ ! -e '$C/segredo.json' ] && [ ! -e '$M/aws.env' ] && [ -f '$C/nekkora-platform-mail.cer' ]"
confere "confirmar: a leitura do administrador so pede o VersionId; a Platform recebe so o SHA-256 esperado" "grep -q 'get-secret-value .*--query VersionId' '$LOG' && ! grep -q 'SecretString' '$LOG' && grep -q -- '--segredo --esperado-sha256 [0-9a-f]\\{64\\}' '$FAKE/java.log'"

# ---------------------------------------------------------------------------------------------------- credenciais
roda credenciais; confere "credenciais: aws.env sem CR e conferido (ASIA, expiracao)" "[ \$? -eq 0 ] && grep -q 'credenciais_temporarias=OK' '$TMP/out.txt' && ! grep -q \$'\\r' '$M/aws.env'"
confere "credenciais: nada da credencial na saida" "! grep -q 'chave-secreta-falsa' '$TMP/out.txt' && ! grep -q 'token-falso' '$TMP/out.txt'"
FAKE_ID=AKIALONGOPRAZO bash "$S" credenciais > "$TMP/out.txt" 2>&1
confere "credenciais de longo prazo (AKIA) recusadas e apagadas" "[ \$? -ne 0 ] && [ ! -e '$M/aws.env' ]"

# ---------------------------------------------------------------------------------------------------- linha de comando
confere "nenhuma chamada da AWS CLI nem da Platform leva senha, PKCS#12 ou chave" "! grep -qF -- '$SENHA' '$LOG' '$FAKE/java.log' && ! grep -qF -- '$P12B64' '$LOG' '$FAKE/java.log' && ! grep -q 'certificado_senha' '$LOG' '$FAKE/java.log'"

# ---------------------------------------------------------------------------------------------------- Git Bash
confere "o script desliga a conversao de caminho do MSYS nas variaveis de ambiente" "grep -qx \"export MSYS2_ENV_CONV_EXCL='\\*'\" '$S'"
if command -v cmd.exe > /dev/null 2>&1; then
    confere "Windows (controle): sem a exclusao, um valor que comeca com / chega alterado a um programa nativo" "[ \"\$(V=/abc+def cmd.exe //c 'echo %V%' | tr -d '\\r')\" != /abc+def ]"
    confere "Windows: com a exclusao do script, o valor chega intacto a um programa nativo" "[ \"\$(MSYS2_ENV_CONV_EXCL='*' V=/abc+def cmd.exe //c 'echo %V%' | tr -d '\\r')\" = /abc+def ]"
fi

echo
[ "$falhas" = 0 ] && { echo "RESULTADO: m365_aws OK"; exit 0; } || { echo "RESULTADO: m365_aws $falhas FALHA(S)"; exit 1; }
