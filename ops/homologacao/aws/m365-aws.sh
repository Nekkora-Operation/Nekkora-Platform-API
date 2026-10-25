#!/usr/bin/env bash
# AWS e certificado do e-mail real em homologacao (docs/relatorios/2026-10-24-email-real-homologacao.md, secao 4).
# Cada subcomando que altera algo fora da maquina (funcao, publicar, credenciais) e UMA acao autorizada do relatorio;
# nada roda sozinho. Os identificadores da conta (numero, usuario, MFA) ficam so em <segredos>/m365/aws-local.env, fora
# do Git (o repositorio e publico); os modelos versionados levam ${CONTA_AWS}, ${USUARIO_IAM} e ${MFA_SERIAL}.
#
#   renderizar                     modelos de aws/ -> <segredos>/m365/aws/ com os valores de aws-local.env      (local)
#   conferir    [--perfil P]       leituras: identidade da sessao = conta e usuario; MFA de codigo (TOTP); funcao e
#                                  segredo ainda inexistentes                                              (R-6, leitura)
#   funcao      [--perfil P]       cria a funcao so de leitura (confianca com MFA, politica inline)        (W1)
#   gerar       --client <guid>    par de chaves RSA 2048 + certificado de 1 ano (keytool) e o SecretString; senha
#                                  aleatoria em arquivo, nunca digitada nem impressa                       (D2, local)
#   publicar    [--perfil P]       cria o segredo e a politica de recurso; a chave CONTINUA no disco ate o confirmar (W2)
#   credenciais                    credenciais de 1 h da funcao (pede o codigo do MFA) -> <segredos>/m365/aws.env (W3)
#   confirmar   [--perfil P]       prova o armazenamento e a LEITURA UTILIZAVEL antes de apagar a chave: a sessao e a da
#                                  funcao; o administrador NAO le (politica de recurso); a Platform (ProvaAutenticacaoM365,
#                                  o codigo da partida) le pela funcao, carrega o formato e a chave, e o SHA-256 do lido e o
#                                  do publicado; a impressao do certificado e a do .cer. So entao apaga a chave e o aws.env.
#                                  Em qualquer falha a chave FICA (nada se perde) e o desfecho e informado    (leitura)
#   apagar                         apaga o PKCS#12, a senha e o SecretString (o .cer publico fica)          (local)
#
# Prova estatica: testes/test_m365_aws.sh (AWS CLI falsa; M365_AWS_PROVA_JAVA troca a chamada Java so nesse teste).
# Saidas: so situacao, ARN de recursos criados e impressao SHA-256 do certificado PUBLICO. Nunca senha, chave,
# SecretString ou credencial (a AWS CLI escreve as credenciais direto no arquivo).
set -euo pipefail
AQUI="$(cd "$(dirname "$0")" && pwd)"
HOMOLOG_ID="${HOMOLOG_ID:-ensaio-api-v1-003}"
M="${HOMOLOG_SEGREDOS:-$HOME/.nekkora/homologacao/$HOMOLOG_ID}/m365"
NOME_SEGREDO="nekkora/homologacao/platform/m365"
FUNCAO="nekkora-homologacao-platform-m365-leitura"
REGIAO="sa-east-1"
TENANT="ae4ed48d-e7d8-4f24-83f2-443e997c5a65"
export AWS_PAGER=""
# Git Bash: um valor de ambiente que comeca com "/" (a chave secreta da AWS pode comecar assim) seria reescrito como caminho
# do Windows ao chamar aws.exe e java.exe, e a assinatura falharia (SignatureDoesNotMatch); sem efeito fora do MSYS
export MSYS2_ENV_CONV_EXCL='*'
umask 077

falha() { echo "ERRO: $*" >&2; exit 1; }
mixed() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
perfil() { local p="nekkora-login"; while [ $# -gt 0 ]; do case "$1" in --perfil) p="$2"; shift 2;; *) falha "opcao desconhecida: $1";; esac; done; echo "$p"; }

carrega_local() {
    [ -f "$M/aws-local.env" ] || falha "$M/aws-local.env ausente (CONTA_AWS, USUARIO_IAM, MFA_SERIAL; fora do Git)"
    CONTA_AWS=""; USUARIO_IAM=""; MFA_SERIAL=""
    while IFS='=' read -r k v; do
        v="${v%$'\r'}"
        case "$k" in CONTA_AWS) CONTA_AWS="$v";; USUARIO_IAM) USUARIO_IAM="$v";; MFA_SERIAL) MFA_SERIAL="$v";; ""|\#*) ;; *) falha "aws-local.env: chave desconhecida";; esac
    done < "$M/aws-local.env"
    [[ "$CONTA_AWS" =~ ^[0-9]{12}$ ]] || falha "CONTA_AWS deve ter 12 digitos"
    [[ "$USUARIO_IAM" =~ ^[A-Za-z0-9+=,.@_-]{1,64}$ ]] || falha "USUARIO_IAM invalido"
    # so dispositivo de codigo (virtual ou TOTP de hardware): a AWS CLI nao envia chave de seguranca FIDO no AssumeRole
    [[ "$MFA_SERIAL" =~ ^arn:aws:iam::${CONTA_AWS}:mfa/[A-Za-z0-9+=,.@_/-]{1,128}$ ]] || falha "MFA_SERIAL deve ser arn:aws:iam::<conta>:mfa/<dispositivo> (MFA de codigo)"
}

aws_cli() {
    command -v aws > /dev/null || falha "AWS CLI v2 ausente"
    local v; v="$(aws --version 2>&1 | sed -n 's#^aws-cli/\([0-9]*\)\.\([0-9]*\)\..*#\1 \2#p')"
    [ -n "$v" ] && { set -- $v; [ "$1" -gt 2 ] || { [ "$1" -eq 2 ] && [ "$2" -ge 32 ]; }; } || falha "AWS CLI >= 2.32 exigida (aws login)"
}

# a sessao do perfil tem de ser a do usuario e da conta de aws-local.env; nada e impresso alem do resultado
confere_sessao() {
    local arn; arn="$(aws sts get-caller-identity --profile "$1" --query Arn --output text 2>/dev/null)" || falha "sem sessao no perfil $1 (aws login --profile $1)"
    [ "$arn" = "arn:aws:iam::$CONTA_AWS:user/$USUARIO_IAM" ] || falha "a sessao do perfil $1 nao e a do usuario e da conta de aws-local.env"
    echo "sessao=OK perfil=$1"
}

renderizar() {
    carrega_local
    mkdir -p "$M/aws"
    for f in confianca-funcao-leitura.json politica-leitura-segredo.json politica-recurso-segredo.json perfil-cli.ini; do
        CONTA_AWS="$CONTA_AWS" USUARIO_IAM="$USUARIO_IAM" MFA_SERIAL="$MFA_SERIAL" python - "$(mixed "$AQUI/$f")" "$(mixed "$M/aws/$f")" <<'PY'
import json, os, re, sys
txt = open(sys.argv[1], encoding='utf-8').read()
for k in ('CONTA_AWS', 'USUARIO_IAM', 'MFA_SERIAL'):
    txt = txt.replace('${' + k + '}', os.environ[k])
if re.search(r'\$\{[A-Z_]+\}', txt):
    sys.exit('modelo com variavel nao resolvida: ' + os.path.basename(sys.argv[1]))
if sys.argv[1].endswith('.json'):
    json.loads(txt)
open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(txt)
PY
    done
    echo "renderizado=OK destino=<segredos>/m365/aws (4 arquivos)"
}

conferir() {
    local p; p="$(perfil "$@")"; carrega_local; aws_cli; confere_sessao "$p"
    local mfas; mfas="$(aws iam list-mfa-devices --profile "$p" --user-name "$USUARIO_IAM" --query 'MFADevices[].SerialNumber' --output text)"
    echo "mfa_dispositivos=$(wc -w <<< "$mfas") de_codigo=$(tr '\t' '\n' <<< "$mfas" | grep -c ':mfa/' || true)"
    tr '\t' '\n' <<< "$mfas" | grep -qx "$MFA_SERIAL" && echo "mfa_serial=OK (de codigo, do usuario)" || falha "MFA_SERIAL nao esta entre os dispositivos do usuario"
    if aws iam get-role --profile "$p" --role-name "$FUNCAO" > /dev/null 2>&1; then echo "funcao=EXISTE"; else echo "funcao=INEXISTENTE"; fi
    local n; n="$(aws secretsmanager list-secrets --profile "$p" --region "$REGIAO" --filters Key=name,Values="$NOME_SEGREDO" --query 'length(SecretList)' --output text)"
    [ "$n" = 0 ] && echo "segredo=INEXISTENTE" || echo "segredo=EXISTE ($n)"
}

funcao() {
    local p; p="$(perfil "$@")"; carrega_local; aws_cli; confere_sessao "$p"
    [ -f "$M/aws/confianca-funcao-leitura.json" ] || falha "rode renderizar antes"
    aws iam get-role --profile "$p" --role-name "$FUNCAO" > /dev/null 2>&1 && falha "funcao $FUNCAO ja existe; nada alterado"
    local arn
    arn="$(aws iam create-role --profile "$p" --role-name "$FUNCAO" --max-session-duration 3600 \
        --description "Homologacao: so GetSecretValue do segredo do e-mail real, com MFA" \
        --assume-role-policy-document "file://$(mixed "$M/aws/confianca-funcao-leitura.json")" --query Role.Arn --output text)"
    aws iam put-role-policy --profile "$p" --role-name "$FUNCAO" --policy-name ler-segredo-m365 \
        --policy-document "file://$(mixed "$M/aws/politica-leitura-segredo.json")"
    local pol; pol="$(aws iam list-role-policies --profile "$p" --role-name "$FUNCAO" --query 'PolicyNames' --output text)"
    local anex; anex="$(aws iam list-attached-role-policies --profile "$p" --role-name "$FUNCAO" --query 'length(AttachedPolicies)' --output text)"
    [ "$pol" = ler-segredo-m365 ] && [ "$anex" = 0 ] || falha "funcao com politicas diferentes das esperadas (inline=$pol anexadas=$anex)"
    echo "funcao=CRIADA arn=$arn politicas_inline=1 anexadas=0 duracao_max=3600"
}

gerar() {
    local client=""; while [ $# -gt 0 ]; do case "$1" in --client) client="$2"; shift 2;; *) falha "opcao desconhecida: $1";; esac; done
    [[ "$client" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || falha "--client <Application (client) ID em minusculas>"
    local C="$M/certificado"
    [ -e "$C/m365.p12" ] || [ -e "$C/segredo.json" ] && falha "ja existe um certificado nao publicado em $C (publicar ou apagar)"
    mkdir -p "$C"
    python -c "import secrets,sys; sys.stdout.write(secrets.token_urlsafe(32))" > "$C/senha.txt"
    keytool -J-Duser.language=en -genkeypair -alias nekkora-platform-mail -keyalg RSA -keysize 2048 -validity 365 \
        -dname "CN=nekkora-platform-mail-homologacao" -storetype PKCS12 -keystore "$(mixed "$C/m365.p12")" \
        -storepass:file "$(mixed "$C/senha.txt")" > /dev/null 2>&1 || { apagar > /dev/null; falha "keytool -genkeypair falhou (nada criado)"; }
    local chaves; chaves="$(keytool -J-Duser.language=en -list -v -storetype PKCS12 -keystore "$(mixed "$C/m365.p12")" -storepass:file "$(mixed "$C/senha.txt")" | grep -c 'Entry type: PrivateKeyEntry' || true)"
    [ "$chaves" = 1 ] || { apagar > /dev/null; falha "PKCS#12 com $chaves chaves (exigida exatamente 1)"; }
    keytool -J-Duser.language=en -exportcert -rfc -alias nekkora-platform-mail -storetype PKCS12 -keystore "$(mixed "$C/m365.p12")" \
        -storepass:file "$(mixed "$C/senha.txt")" -file "$(mixed "$C/nekkora-platform-mail.cer")" > /dev/null 2>&1
    keytool -J-Duser.language=en -printcert -file "$(mixed "$C/nekkora-platform-mail.cer")" \
        | sed -n 's/^[[:space:]]*\(Valid from:.*\|SHA256: .*\)$/\1/p' > "$C/impressao.txt"
    python "$(mixed "$AQUI/../lib/email_real.py")" montar-segredo --p12 "$(mixed "$C/m365.p12")" --senha-arquivo "$(mixed "$C/senha.txt")" \
        --tenant "$TENANT" --client "$client" --saida "$(mixed "$C/segredo.json")"
    echo "certificado=GERADO publico=<segredos>/m365/certificado/nekkora-platform-mail.cer (para o D3)"
    sed 's/^/  /' "$C/impressao.txt"
}

apagar() {
    local C="$M/certificado"
    rm -f "$C/m365.p12" "$C/senha.txt" "$C/segredo.json"
    [ ! -e "$C/m365.p12" ] && [ ! -e "$C/senha.txt" ] && [ ! -e "$C/segredo.json" ] || falha "nao consegui apagar os arquivos do certificado"
    echo "chave_privada_no_disco=APAGADA (ficam o .cer publico e a impressao)"
}

publicar() {
    local p; p="$(perfil "$@")"; carrega_local; aws_cli; confere_sessao "$p"
    local C="$M/certificado"
    [ -f "$C/segredo.json" ] || falha "rode gerar antes (segredo.json ausente)"
    [ -f "$M/aws/politica-recurso-segredo.json" ] || falha "rode renderizar antes"
    aws iam get-role --profile "$p" --role-name "$FUNCAO" > /dev/null 2>&1 || falha "funcao $FUNCAO inexistente: W1 antes do W2"
    local n; n="$(aws secretsmanager list-secrets --profile "$p" --region "$REGIAO" --filters Key=name,Values="$NOME_SEGREDO" --query 'length(SecretList)' --output text)"
    [ "$n" = 0 ] || falha "o segredo ja existe; nada alterado (troca do valor e outra acao)"
    # a chave fica no disco ate o "confirmar" provar que o segredo armazenado e lido e utilizavel
    local arn
    arn="$(aws secretsmanager create-secret --profile "$p" --region "$REGIAO" --name "$NOME_SEGREDO" \
        --description "Homologacao: transporte Graph da Platform (nekkora-m365/1)" \
        --secret-string "file://$(mixed "$C/segredo.json")" --query ARN --output text)"
    local ok; ok="$(aws secretsmanager validate-resource-policy --profile "$p" --region "$REGIAO" --secret-id "$NOME_SEGREDO" \
        --resource-policy "file://$(mixed "$M/aws/politica-recurso-segredo.json")" --query PolicyValidationPassed --output text)"
    [ "$ok" = True ] || falha "politica de recurso recusada na validacao; o segredo ficou SEM politica de recurso (so a W1 e administradores leem); a chave continua no disco"
    aws secretsmanager put-resource-policy --profile "$p" --region "$REGIAO" --secret-id "$NOME_SEGREDO" --block-public-policy \
        --resource-policy "file://$(mixed "$M/aws/politica-recurso-segredo.json")" > /dev/null
    echo "segredo=CRIADO arn=$arn politica_recurso=APLICADA cifra=aws/secretsmanager chave_privada_no_disco=MANTIDA (proximo: credenciais e confirmar)"
}

# a Platform le o segredo pelo mesmo codigo da partida (AwsSecretsManager + M365Segredo + GraphMailTransport), no host,
# com as credenciais da funcao; so as linhas de conferencia vao para a saida
prova_java() {
    if [ -n "${M365_AWS_PROVA_JAVA:-}" ]; then "$M365_AWS_PROVA_JAVA" "$@"; return; fi
    local REPO; REPO="$(cd "$AQUI/../../.." && pwd)"
    local JAR="$REPO/api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar"
    if ! { [ -f "$JAR" ] && unzip -l "$JAR" 2>/dev/null | grep -q 'com/nekkora/platform/mail/ProvaAutenticacaoM365.class'; }; then
        (cd "$REPO/api" && ./mvnw -B -ntp -q -pl nekkora-platform -am -DskipTests package) > /dev/null 2>&1 || falha "build da Platform falhou (./mvnw -pl nekkora-platform -am package)"
    fi
    java -Dloader.main=com.nekkora.platform.mail.ProvaAutenticacaoM365 -cp "$(mixed "$JAR")" org.springframework.boot.loader.launch.PropertiesLauncher "$@" 2>&1 \
        | tr -d '\r' | grep -E '^autenticacao_|^uso:|obrigatorios' || true
}

confirmar() {
    local p; p="$(perfil "$@")"; carrega_local; aws_cli
    local C="$M/certificado"
    [ -f "$C/segredo.json" ] && [ -f "$C/impressao.txt" ] || falha "sem certificado a confirmar em $C (gerar e publicar antes)"
    [ -f "$M/aws.env" ] || falha "credenciais da funcao ausentes: rode credenciais (W3) antes"
    python "$(mixed "$AQUI/../lib/email_real.py")" credenciais "$(mixed "$M/aws.env")" --minimo 10 > /dev/null || falha "credenciais da funcao vencidas ou invalidas: rode credenciais de novo"
    local falhou=""
    # 1. a sessao das credenciais e a da funcao (assumida com o codigo do MFA), nao a do administrador
    local quem
    quem="$(set -a; . "$M/aws.env"; set +a; unset AWS_PROFILE; aws sts get-caller-identity --query Arn --output text 2>/dev/null || true)"
    if [ "$quem" = "arn:aws:sts::$CONTA_AWS:assumed-role/$FUNCAO/nekkora-hml-m365" ]; then echo "sessao_da_funcao=OK"; else echo "sessao_da_funcao=FALHOU"; falhou=1; fi
    # 2. o administrador nao le o valor (politica de recurso); so o VersionId seria pedido, e a saida e descartada
    if aws secretsmanager get-secret-value --profile "$p" --region "$REGIAO" --secret-id "$NOME_SEGREDO" --query VersionId --output text > /dev/null 2> "$M/.adm.err"; then
        echo "administrador_le=SIM (a politica de recurso nao nega)"; falhou=1
    elif grep -q 'AccessDenied' "$M/.adm.err"; then
        echo "administrador_le=NAO (AccessDenied)"
    else
        echo "administrador_le=INDETERMINADO"; falhou=1
    fi
    rm -f "$M/.adm.err"
    # 3. leitura utilizavel pela funcao, com o codigo da Platform: formato, tenant, uma chave, SHA-256 igual ao publicado
    local esperado; esperado="$(sha256sum < "$C/segredo.json" | cut -c1-64)"
    local linha
    linha="$(set -a; . "$M/aws.env"; set +a; unset AWS_PROFILE; NEKKORA_M365_TENANT="$TENANT" NEKKORA_M365_SEGREDO_AWS="$NOME_SEGREDO" \
        NEKKORA_M365_AWS_REGIAO="$REGIAO" prova_java --segredo --esperado-sha256 "$esperado")"
    echo "$linha"
    local cer; cer="$(sed -n 's/^SHA256: //p' "$C/impressao.txt")"
    if [[ -n "$cer" && "$linha" == *"leitura=OK"*"formato=OK"*"chave_privada=1"*"certificado_sha256=$cer "*"conteudo=IGUAL"* ]]; then
        echo "leitura_utilizavel=OK (conteudo igual ao publicado; certificado = .cer do D3)"
    else
        echo "leitura_utilizavel=FALHOU"; falhou=1
    fi
    rm -f "$M/aws.env"
    if [ -n "$falhou" ]; then
        echo "confirmado=NAO chave_privada_no_disco=MANTIDA credenciais_da_funcao=APAGADAS (corrigir e repetir credenciais + confirmar)"
        exit 1
    fi
    apagar
    echo "confirmado=OK credenciais_da_funcao=APAGADAS"
}

credenciais() {
    aws_cli
    local tmp="$M/aws.env.novo"
    aws configure export-credentials --profile nekkora-hml-m365 --format env-no-export > "$tmp" || { rm -f "$tmp"; falha "nao obtive credenciais da funcao (MFA, sessao do nekkora-login ou confianca)"; }
    tr -d '\r' < "$tmp" > "$M/aws.env"; rm -f "$tmp"
    python "$(mixed "$AQUI/../lib/email_real.py")" credenciais "$(mixed "$M/aws.env")" || { rm -f "$M/aws.env"; falha "credenciais recusadas (apagadas)"; }
}

[ $# -ge 1 ] || { sed -n '2,26p' "$0"; exit 2; }
acao="$1"; shift
case "$acao" in
    renderizar|conferir|funcao|gerar|publicar|confirmar|apagar|credenciais) "$acao" "$@";;
    *) falha "subcomando desconhecido: $acao";;
esac
