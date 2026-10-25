# E-mail real da Platform em homologação: conferência, preparação e pedido de autorização

**Data:** 2026-10-24. **Branch:** `feat/email-real-homologacao` (PR para `main`). **Base:** `ops/api-v1-003-homologacao` @ `8dd22fc` (ver §1). **Ambiente de ensaio:** `ensaio-api-v1-003`.

**Nesta etapa não houve nenhuma alteração externa:** nada no Microsoft 365, no DNS, na AWS nem em cofre, e nenhuma mensagem real enviada. A única saída de rede foi a prova do proxy (§3.3): abertura de túnel TLS para três nomes, sem credencial e sem pedido autenticado. Página web e cadastro público ficam fora desta missão. Merge, implantação em produção e liberação pública também.

## 0. Estado

| Etapa da missão | Situação |
|---|---|
| 1. Conferir a implementação | **Feita** (§2). Achados: a fila usa a chave do MFA; não havia lista de destinatários; não havia leitura de segredo da AWS. Os três foram corrigidos (§3). |
| 2. Preparar a configuração externa | **Preparada, não executada.** Dados definidos pela titular em 2026-10-25. Políticas finais, comandos (`aws/m365-aws.sh`), certificado, custos e o plano de até 20 mensagens no §4. **Depende da autorização do §4.8.** |
| 3. Preparar o ambiente de ensaio | Commit, migrations e fila conferidos (§5). A troca de imagens e de transporte fica para depois da autorização. |
| 4. Homologação real | **Não iniciada.** Roteiro pronto (§6). |
| 5. Entrega | PR aberto (sem merge). Este relatório será completado com as evidências da execução real. |

## 1. Base dos PRs: #17 e #19 não chegaram à `main`

O #16 e o #18 foram mesclados na `main` (`98c8242`, `b036dc0`). O #17 e o #19, não. Cada um foi mesclado na **base empilhada** dele:
- o #17 em `fix/021-acesso-papeis-predefinidos` (`b3edf6e`);
- o #19 em `ops/api-v1-003-homologacao` (`8dd22fc`).

Por isso a `main` não tem a 022, nem a ação `atualizar`, nem a CLI de ativação: são 29 commits e 179 arquivos de diferença. O `ensaio-api-v1-003` roda a 022.

Esta branch parte de `ops/api-v1-003-homologacao`, que contém o #17 e o #19. Abri o PR de consolidação `ops/api-v1-003-homologacao → main`, só com o que já foi revisado. Este PR do e-mail também aponta para a `main`. Até o PR de consolidação ser mesclado, o diff do PR do e-mail mostra também aqueles commits; depois, mostra só os três commits do e-mail.

## 2. Conferência da implementação (item 1)

### 2.1 Certificado aceito pelo Java

| Item | Valor |
|---|---|
| Formato | **PKCS#12** (`KeyStore.getInstance("PKCS12")`), com senha |
| Conteúdo | **exatamente uma** entrada de chave privada e o certificado X.509 dela. Duas chaves ou nenhuma recusam a partida |
| Algoritmo | RSA. A asserção é **PS256** (RSASSA-PSS, SHA-256, MGF1-SHA-256, sal 32), com `x5t#S256` = SHA-256 do DER do certificado |
| Origem | antes, só arquivo montado (`NEKKORA_M365_CERT` + `NEKKORA_M365_CERT_SENHA`). **Agora também o AWS Secrets Manager** (§3.1): o PKCS#12 em base64 dentro do segredo, carregado em memória |
| Vencimento | alarme no log a 60 dias (`alerta=sim`) e a 30 dias (`alerta=critico`), `evento=mail.certificado_vence_em`, verificado uma vez por dia |
| Proposta | RSA 2048, validade de 1 ano, `CN=nekkora-platform-mail-homologacao`, gerado com `keytool` (§4.3, D2) |

### 2.2 Configuração do transporte (valores reais do código)

| Parâmetro | Valor |
|---|---|
| Token | client credentials com **asserção de certificado**, sem segredo de cliente. `POST https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token`, escopo `https://graph.microsoft.com/.default`, token só em memória, renovado 5 min antes de vencer |
| Envio | `POST https://graph.microsoft.com/v1.0/users/no-reply@nekkora.com/sendMail`, texto simples, **um** destinatário, `saveToSentItems=false`, `client-request-id` = correlação |
| Tenant | `ae4ed48d-e7d8-4f24-83f2-443e997c5a65` (Entra, descoberta OpenID pública, 23/10). O segredo tem de trazer o mesmo `tenant_id` |
| Remetente | `no-reply@nekkora.com`, na configuração versionada do ambiente, nunca no segredo |
| Tempo máximo | 5 s por chamada; posse de um trabalho 2 min (a partida recusa posse menor que 3× o tempo máximo) |
| Despacho | ciclo a cada 5 s, até 25 mensagens por minuto, retenção a cada hora |
| Novas tentativas | 30 s, 2 min, 10 min, 30 min; no máximo 5 tentativas. Esgotadas, a mensagem fica `INCERTO` |
| Prazo por mensagem | recuperação de senha 30 min; cadastro 24 h; avisos 24 h; avisos da recuperação emergencial 23 h |
| Desfechos | 202 → ACEITO (aceite, não entrega); outros 4xx → RECUSADO; 429/503/504 e sem conexão → RETENTÁVEL; 5xx e queda depois do envio → INCERTO, nunca repetido sozinho |
| Saída de rede | só pelo `proxy-m365` (CONNECT 443); a Platform fica na rede `interna` sem saída |

### 2.3 Segredo `nekkora/homologacao/platform/m365`

`SecretString` JSON no formato **`nekkora-m365/1`**, com exatamente estes cinco campos. Campo a mais, a menos ou vazio recusa a partida:

```json
{
  "formato": "nekkora-m365/1",
  "tenant_id": "ae4ed48d-e7d8-4f24-83f2-443e997c5a65",
  "client_id": "<Application (client) ID do aplicativo nekkora-platform-mail>",
  "certificado_pkcs12": "<PKCS#12 em base64: uma chave RSA e o certificado>",
  "certificado_senha": "<senha do PKCS#12>"
}
```

- **Ficam de fora do segredo:**
  - o remetente, que fica na configuração revisada;
  - a chave da fila (§2.4);
  - as credenciais da AWS.
- **Cifra em repouso:** a chave KMS gerenciada `aws/secretsmanager`, sem custo. Uma chave KMS própria não traz ganho aqui e teria custo.
- **Montagem:** o SecretString é montado por `lib/email_real.py montar-segredo`. A senha vem de um arquivo, e o arquivo gerado tem permissão 600 e é apagado depois.

### 2.4 Chave de criptografia da fila

- **Achado:** a fila (021) cifrava o destinatário e o token com a **mesma chave AES do MFA** (`NEKKORA_MFA_CHAVES`). O AAD `mail|job|key_id` separa os domínios, mas uma rotação ou um vazamento de uma chave afetaria as duas coisas.
- **Correção:** chave **própria e opcional** da fila (`NEKKORA_FILA_CHAVE_ID` e `NEKKORA_FILA_CHAVES`, no mesmo formato).
  - Sem ela, nada muda.
  - Com ela, a fila cifra só com a chave própria e **continua decifrando o que a chave do MFA cifrou**, porque o `key_id` de cada linha escolhe a chave. As mensagens já enfileiradas continuam legíveis.
  - Um id repetido nas duas listas recusa a partida.
- **Na homologação:** o runner gera a chave em `m365/fila.env` e nunca a sobrescreve.
- **Hoje:** a fila do `ensaio-api-v1-003` tem **0 linhas** (§5). O transporte simulado não usa a fila.

### 2.5 Adaptações para carregar os segredos da AWS

- A Platform lê o segredo **uma vez, na partida** (`GetSecretValue`, Signature Version 4, sem SDK). A leitura sai pelo mesmo cliente HTTP e pelo mesmo proxy do transporte.
- Credenciais **somente temporárias**: `AWS_SESSION_TOKEN` é obrigatório, e chaves `AKIA…` são recusadas pelo runner.
- Com o segredo configurado, `client-id`, `certificado` e `certificado-senha` precisam ficar vazios. Não há mistura de origens.
- Depois da partida, o certificado e a senha são apagados da memória.
  - **Limite:** o texto JSON do segredo é uma `String` Java e não pode ser apagado; fica até a coleta de lixo.
- Falhas levam só o status HTTP e o tipo de erro da AWS (`AccessDeniedException`, …), nunca o ARN, a mensagem da AWS ou a credencial.
- Se as credenciais vencerem com a Platform no ar, nada muda: o segredo só é lido na partida. Um reinício exige credenciais novas, e o runner confere isso.

## 3. O que mudou (PR) e as provas

### 3.1 Código (`134597b`)

| Arquivo | Mudança |
|---|---|
| `secrets/AwsSecretsManager.java` (novo) | `GetSecretValue` assinado com SigV4; só credenciais temporárias; HTTP/1.1; erros sem conteúdo |
| `mail/M365Segredo.java` (novo) | leitura e conferência do formato `nekkora-m365/1`; vínculo ao tenant; `apagar()` |
| `mail/GraphMailTransport.java` | aceita o PKCS#12 em memória; cliente HTTP público para a leitura do segredo |
| `mail/DestinatariosPermitidos.java` (novo) | lista fechada: endereço exato ou `local+*@domínio` (o próprio endereço e as variações `+marcador` da mesma caixa). Fora dela, RECUSADO sem rede e `evento=mail.destinatario_fora_da_lista` |
| `config/MailConfig.java` | origem do segredo (arquivo ou AWS); cifra da fila (§2.4); lista **obrigatória em `ensaio-*`** com m365 |
| `mail/ProvaEnvioM365.java` | lê o mesmo segredo; respeita a mesma lista; em `ensaio-*` sem lista, não envia |
| `auth/crypto/SecretCipher.java` | `parseKeys` com recusa de id repetido |

### 3.2 Operação (`13e4a84`, `b105b53` e o commit deste relatório)

- **`homologacao.sh email-real <id>`**, só em `ensaio-*` com 021/022:

  | Subcomando | O que faz |
  |---|---|
  | `fila` | somente leitura |
  | `imagens` | imagens do commit atual sem mudar migrations |
  | `ligar` | credenciais temporárias por ≥ 20 min, lista válida, ambiente LIBERADO, fila **sem PENDENTE/ENVIANDO**, sobreposição que renderiza, tudo **antes** de mudar; se a Platform não subir com os eventos esperados, desliga sozinho |
  | `desligar` | para o proxy **primeiro**, volta ao transporte simulado e apaga as credenciais |
  | `prova`, `conferir-eml`, `jornada` | execução e conferência da homologação real (§6) |
  | `saida cortar\|religar`, `platform parar\|subir` | falha e reinício locais (R5) |

- **`compose.m365.yml`:**
  - segredo da AWS (`sa-east-1`), `m365/aws.env` e `m365/fila.env`;
  - lista obrigatória;
  - nenhum arquivo de chave privada no host;
  - **correção:** o proxy não subia, porque os `tmpfs` eram do root e a raiz é somente leitura. Agora os `tmpfs` usam uid/gid 13 (§3.3).
- **`proxy/squid.conf`:** acrescentei `secretsmanager.sa-east-1.amazonaws.com`.
- **`aws/`:** política de leitura só desse segredo, confiança da função só com MFA, política de recurso do segredo e modelo dos perfis da CLI (`perfil-cli.ini`). Todos levam `${CONTA_AWS}`, `${USUARIO_IAM}` e `${MFA_SERIAL}`.
- **`aws/m365-aws.sh`** (2026-10-25): `renderizar`, `conferir` (R-6), `funcao` (W1), `gerar` (D2), `publicar` (W2), `apagar` e `credenciais` (W3), §4.3 e §4.4. Prova estática: `testes/test_m365_aws.sh`, contra uma AWS CLI falsa que registra as chamadas.
- **`lib/email_real.py`, `testes/jornada_email_real.py`, `testes/test_email_real.py`, `testes/prova_proxy_m365.sh`.**

### 3.3 Provas desta etapa (sem nada externo)

| Prova | Resultado |
|---|---|
| `AwsSecretsManagerTest` (assinatura = **exemplo publicado da AWS**, IAM `ListUsers`; pedido, recusa, sem conexão, só temporárias) | **6/6** |
| `M365SegredoTest` | **4/4** |
| `MailConfigRestricoesTest` (lista obrigatória em `ensaio-*`, `local+*`, chave da fila decifra o legado do MFA, ids repetidos) | **8/8** |
| `GraphMailTransportTest` (+ transporte pelo segredo, + prova com a lista) | **10/10** |
| `IdentityIntentFinalChecksIT`, `CaixaHomologacaoConfigIT`, `CaixaHomologacaoMailSenderIT`, `MailOutboxIT` | **34/34** |
| Regressão do módulo `nekkora-platform` no candidato | ver §3.4 |
| `testes/test_email_real.py` (credenciais, lista, chave da fila, montagem do segredo, cabeçalhos do Exchange Online e do Gmail, caixa real) | **36/36** |
| `validar-config` | **285 OK, 0 falhas** |
| Proxy isolado (`docker run`, a política) | `login.microsoftonline.com`, `graph.microsoft.com` e `secretsmanager.sa-east-1.amazonaws.com` → **200**; `secretsmanager.us-east-1`, `sts.amazonaws.com`, `example.com` e a porta 80 → **403** |
| Proxy pela **composição do runner** (`testes/prova_proxy_m365.sh`) | sobe com os `tmpfs` corrigidos; os três nomes **200**; outra região e outro destino **403**; saída direta pela rede interna **impossível** (`000`); projeto removido |

### 3.4 Regressão (local, candidato `5cae2c8`, `./mvnw -pl nekkora-platform -am test`)

| Módulo | Resultado |
|---|---|
| `nekkora-core` | 21/21 |
| `nekkora-platform` (o alterado) | **394/394**: os 374 de antes e os 20 novos |
| `nekkora-ops` (não alterado) | 175/177: **2 falhas que já existiam e dependem da data**, `Revisao4PrazosEImpedimentosIT.item1CarenciaDe720HorasNosLimitesEEmQualquerFuso` e `item1JanelasDe168E720HorasNosLimites` |

**As 2 falhas do `nekkora-ops` não vêm deste PR:**
- falham **igualmente no commit-base `8dd22fc`**, sem nenhuma mudança deste PR. No CI de 24/10, o mesmo código passou (584/0);
- causa provável, pela leitura do teste: a fixture cria o canal "agora", e o teste afirma, como controle, que `agora + 30 dias` atravessa uma mudança de horário em `Europe/Berlin` (`(… + interval '30 days') <> (… + interval '720 hours')`). A Europa saiu do horário de verão em 2026-10-25 01:00 UTC. A partir daí, os 30 dias seguintes não cruzam nenhuma mudança, e a afirmação de controle falha. É um defeito de desenho do teste (depende da data de execução), não do produto;
- **o CI deste PR e o de qualquer PR devem falhar nesses dois casos até a correção do teste.** A correção, fixar o instante da fixture antes de uma mudança de horário, fica fora do escopo desta missão e vai como pendência separada.

## 4. Configuração externa a autorizar (item 2)

Nenhuma ação foi executada. A ordem é obrigatória. Cada linha marcada como **externa** altera algo fora desta máquina e exige a sua autorização explícita.

**Dados definidos pela titular (2026-10-25)**, sem os identificadores, porque este repositório é **público**:
- conta AWS da titular, região `sa-east-1`;
- usuário IAM administrador com MFA;
- caixa externa de destino (Gmail, com `+marcador`);
- segunda caixa do tenant para o F2;
- `dmarc@nekkora.com` existe e é acompanhada pela titular.

Os valores ficam só em `<segredos>/m365/aws-local.env` e `<segredos>/m365/destinatarios.txt`, fora do Git. Os modelos versionados levam `${CONTA_AWS}`, `${USUARIO_IAM}` e `${MFA_SERIAL}`. `aws/m365-aws.sh renderizar` gera as cópias concretas em `<segredos>/m365/aws/`, e são essas cópias que vão para a AWS.

### 4.1 Leituras antes de qualquer alteração (R-1 a R-6)

São só leituras.
- **R-1 a R-5:** com o seu login interativo no Microsoft 365. Os comandos estão em `2026-10-22-identidade-002-email-real.md` §0. Resultado exigido:
  - tenant `ae4ed48d…` em R-1 e R-2;
  - `nekkora.com` verificado e Authoritative;
  - `no-reply@nekkora.com` existe;
  - DKIM `Enabled: True`.
- **R-6 (AWS):** `aws/m365-aws.sh conferir`. Exige:
  - a sessão `nekkora-login` é a do usuário e da conta de `aws-local.env`;
  - o `MFA_SERIAL` é um dispositivo **de código** desse usuário;
  - a função e o segredo ainda não existem.

  O script imprime só o resultado das conferências.

**Leitura pública de DNS (2026-10-25):**

| Registro | Valor |
|---|---|
| SPF | `v=spf1 include:spf.protection.outlook.com ~all` |
| DKIM | `selector1` e `selector2` publicados, com CNAME para o Microsoft 365 |
| DMARC | **publicado** pela titular: `v=DMARC1; p=none; rua=mailto:dmarc@nekkora.com`. A ação B está feita, e não há nada a alterar no DNS |

### 4.2 Microsoft 365 (tenant `ae4ed48d…`)

A caixa `dmarc@nekkora.com` já existe e é acompanhada pela titular. **A ação A sai do pedido.**

| # | Ação | Comando | Impacto |
|---|---|---|---|
| C | **externa**: apresentação e respostas da no-reply | `Set-Mailbox no-reply@nekkora.com -DisplayName "Nekkora"`; `Set-Mailbox no-reply@nekkora.com -AcceptMessagesOnlyFromSendersOrMembers no-reply@nekkora.com` | o destinatário vê `Nekkora <no-reply@nekkora.com>`; respostas recusadas |
| D1 | **externa**: aplicativo `nekkora-platform-mail` (um tenant, **sem** permissão de API) | Entra → Registros de aplicativo → Novo; anotar o App ID e o Object ID da Enterprise application | identidade sem permissão |
| D2 | local: certificado | §4.3, **depois** do D1, porque o SecretString leva o App ID | — |
| D3 | **externa**: só o certificado **público** no aplicativo | upload de `<segredos>/m365/certificado/nekkora-platform-mail.cer`; conferir que a impressão mostrada no Entra é a de `impressao.txt`; **nenhum** segredo de cliente | o aplicativo passa a obter token |
| D4–D6 | **externa**: RBAC para Aplicações **restrito à no-reply** | `New-ServicePrincipal -AppId <App ID> -ObjectId <Object ID>`; `New-ManagementScope -Name "Somente no-reply" -RecipientRestrictionFilter "PrimarySmtpAddress -eq 'no-reply@nekkora.com'"`; `New-ManagementRoleAssignment -App <Object ID> -Role "Application Mail.Send" -CustomResourceScope "Somente no-reply"` | envio **somente** como no-reply |
| D7–D8 | leitura: conferência do caminho único | `Get-ManagementRoleAssignment -App <Object ID>`: exatamente uma atribuição; nenhuma permissão de aplicação do Graph consentida; `Get-ApplicationAccessPolicy` sem linha do aplicativo; `Test-ServicePrincipalAuthorization -Identity <Object ID> -Resource no-reply@nekkora.com` → `InScope=True`; `-Resource <caixa do F2>` → `InScope=False` | prova de configuração (o RBAC pode levar de 30 min a 2 h para valer no envio) |

### 4.3 Certificado (D2), na máquina da homologação

```
bash ops/homologacao/aws/m365-aws.sh gerar --client <App ID do D1>
```

O script faz, em `<segredos>/m365/certificado/`:
1. uma senha aleatória de 256 bits em `senha.txt`, que **nunca é digitada nem impressa**;
2. o par RSA 2048 e o certificado de 1 ano, com `CN=nekkora-platform-mail-homologacao`. Usa o `keytool` do JDK 21 com `-storepass:file`, então a senha não aparece em linha de comando;
3. a conferência de **exatamente uma** chave privada no PKCS#12, a mesma exigência da Platform;
4. a exportação de `nekkora-platform-mail.cer` (público, para o D3) e de `impressao.txt` (validade e SHA-256);
5. a montagem do SecretString `nekkora-m365/1` em `segredo.json`, por `lib/email_real.py montar-segredo`.

A chave sai do disco no `publicar` (W2), **em qualquer desfecho**, ou com `m365-aws.sh apagar`. Um segundo `gerar` com uma chave não publicada é recusado.

**Limites:**
- entre o `gerar` e o `publicar`, a chave fica no disco, no diretório de segredos do perfil do usuário e fora do Git. O tempo é o do D3;
- `rm` não sobrescreve o conteúdo em SSD;
- a permissão de arquivo `600` não tem efeito no NTFS: vale a ACL do perfil do usuário.

### 4.4 AWS (conta da titular, `sa-east-1`)

Acesso **sem chave de acesso de longo prazo**:
- as ações de administração (W1, W2) usam a sessão do console aberta por `aws login`, já com o MFA do usuário;
- a Platform só recebe credenciais de 1 h da função de leitura, obtidas com o **código do MFA** a cada sessão.

| # | Ação | Comando | Impacto |
|---|---|---|---|
| L1 | local: AWS CLI v2 ≥ 2.32 (exigida para `aws login`) | `msiexec.exe /i https://awscli.amazonaws.com/AWSCLIV2.msi`; `aws --version` | instalação nesta máquina |
| L2 | local: perfis da CLI | `aws-local.env` com `CONTA_AWS`, `USUARIO_IAM` e `MFA_SERIAL` (IAM → usuário → Credenciais de segurança → MFA → Identificador); `m365-aws.sh renderizar`; acrescentar `<segredos>/m365/aws/perfil-cli.ini` a `~/.aws/config`; `aws login --profile nekkora-login` | sessão local do console, sem chave gravada |
| R-6 | leitura | `m365-aws.sh conferir` | — |
| W1 | **externa**: função `nekkora-homologacao-platform-m365-leitura` | `m365-aws.sh funcao`. Confiança `aws/confianca-funcao-leitura.json`: **só** o usuário, **com MFA**. Política inline `aws/politica-leitura-segredo.json`: **só** `secretsmanager:GetSecretValue` nesse segredo. Nenhuma política anexada. `MaxSessionDuration` 3600 s. O script confere depois: 1 inline e 0 anexadas | nenhuma outra permissão |
| W2 | **externa**: segredo `nekkora/homologacao/platform/m365` | `m365-aws.sh publicar`. Executa `create-secret --secret-string file://…`, sem o valor na linha de comando, cifrado com `aws/secretsmanager`. Depois executa `validate-resource-policy` e `put-resource-policy --block-public-policy` com `aws/politica-recurso-segredo.json`, que **nega a leitura a qualquer principal que não seja a W1**, inclusive o administrador. Em seguida apaga a chave do disco | o segredo passa a existir; custo §4.6 |
| W3 | **externa** (emite credencial): credenciais de 1 h | `m365-aws.sh credenciais`. A CLI pede o **código do MFA** e grava direto em `<segredos>/m365/aws.env`. O script confere `ASIA…` e a expiração, e recusa e apaga chaves `AKIA…` | credenciais apagadas no `desligar` |

**Mudanças nas políticas em relação à versão anterior deste PR:**
- **Leitura:** removida a condição `secretsmanager:VersionStage = AWSCURRENT`. A Platform não envia `VersionStage` no `GetSecretValue`, e a condição negaria a própria leitura. Sem ela, a leitura continua restrita a esse segredo; o segredo só tem uma versão.
- **Confiança:**
  - removida a condição `aws:MultiFactorAuthAge`: no `AssumeRole` feito com o código do MFA, a presença dessa chave no contexto não é garantida, e a ausência negaria a entrada. A sessão já é limitada a 1 h pela própria função;
  - o principal passa a ser o **usuário** (`arn:aws:iam::${CONTA_AWS}:user/${USUARIO_IAM}`).

**Condições a confirmar na primeira execução:**
- **MFA de código:** a CLI só envia **código** (aplicativo autenticador ou TOTP de hardware) no `AssumeRole`. Se o único MFA do usuário for chave de segurança ou passkey (FIDO), o W3 não funciona. Seria preciso acrescentar um MFA virtual ao usuário, que é outra alteração externa a autorizar. O `renderizar` e o `conferir` recusam um `MFA_SERIAL` que não seja `…:mfa/…`.
- **Perfil `nekkora-login` como origem do `AssumeRole`:** se a CLI instalada não aceitar a sessão do `aws login` como `source_profile`, a alternativa local é um perfil intermediário com `credential_process = aws configure export-credentials --profile nekkora-login --format process`, usado como origem. A confiança e o MFA não mudam.

### 4.5 DNS e rede

| # | Ação | Detalhe | Impacto |
|---|---|---|---|
| B | DMARC em observação | **já publicado pela titular** (§4.1). Nada a alterar | habilita `dmarc=pass` nas mensagens alinhadas |
| E | **externa**: saída de rede | `proxy-m365` (§3.3), só os três nomes; a Platform continua sem saída direta | só no `ensaio-api-v1-003`, enquanto o transporte real estiver ligado |

### 4.6 Custos previstos

| Item | Custo |
|---|---|
| Secrets Manager | **US$ 0,40 por segredo por mês** (proporcional), mais US$ 0,05 por 10 mil chamadas. É uma chamada por partida da Platform, praticamente zero |
| KMS `aws/secretsmanager`, IAM, STS e `aws login` | sem custo |
| Microsoft 365 | caixas **compartilhadas** sem licença; aplicativo e RBAC sem custo; envio pelo Graph dentro da licença existente |
| Saída de rede | desprezível (poucos KB por mensagem) |
| **Total** | **cerca de US$ 0,40 por mês** enquanto o segredo existir. Apagar o segredo depois da homologação encerra o custo; a AWS mantém uma janela de recuperação de 7 a 30 dias |

### 4.7 Destinatários e plano de até 20 mensagens

- **Lista fechada** (`<segredos>/m365/destinatarios.txt`): uma linha `local+*@gmail.com` da caixa da titular.
  - Aceita só essa caixa e as variações `+marcador` dela. Qualquer outro endereço é RECUSADO pela Platform sem rede.
  - Cada mensagem usa `local+<caso>-<execução>@gmail.com`.
- **F2:** a segunda caixa do tenant é o **remetente** tentado; não recebe nada.
- **F6:** o destinatário é externo e o domínio está em `p=none`. O `.eml` do F1 já mostra o veredito do Gmail, sem mensagem adicional.

| Caso | Mensagens | O que comprova |
|---|---|---|
| F1 prova como no-reply | 1 | 202 (aceite) e `.eml` recebido; F3 e F6 nos cabeçalhos |
| F2 prova como a outra caixa | 0 esperada (403). Se for aceita, conta como 1, e a homologação **para** com `desligar` | recusa pelo RBAC com a mesma credencial |
| R1 cadastro | 1 | recebida; confirmação 204; reuso recusado |
| R2 primeira entrada com o fator | 0 | — |
| R3 recuperação de senha | 2 (link e aviso de senha alterada) | consumo único; sessões revogadas; MFA preservado |
| R4 código de recuperação | 1 (aviso) | aviso recebido; reuso recusado |
| R5 falha de rede e reinício locais | 1 | PENDENTE sem saída; ACEITA depois do reinício; recebida |
| **Execução completa** | **6** | |
| Reserva | até 14 | repetição do F1 enquanto o RBAC se propaga (até 3 tentativas) e **uma** segunda execução completa (6) se algum caso falhar por causa externa |
| **Teto** | **20** chamadas de envio | |

**Contagem do teto:** são contadas todas as chamadas de envio feitas ao Graph, aceitas ou recusadas. A conta vem das linhas da `mail_outbox` (`email-real … fila`) e dos registros de `prova` em `saidas/<id>/email-real/`. Ao chegar a 20, `desligar`, mesmo com um caso pendente.

### 4.8 Pedido de autorização

Na ordem, uma autorização para cada bloco (ou uma única, citando os blocos):
1. **L1 e L2** (locais: AWS CLI e perfis) e as leituras **R-1 a R-6**.
2. **C, D1, D3 a D6** no Microsoft 365, mais as leituras D7 e D8. O D2 é local, entre o D1 e o D3.
3. **W1 e W2** na AWS (função e segredo; custo de cerca de US$ 0,40 por mês).
4. **E e W3**: saída de rede pelo proxy e credenciais de 1 h, no momento do `ligar`.
5. **Até 20 mensagens** do §4.7, todas para a caixa da titular.

Depois da homologação, apagar o segredo (W2) e a função (W1) é outra decisão. A proposta está no §7.

## 5. Ambiente de ensaio (item 3)

| Conferência | Resultado (2026-10-25T01:50Z) |
|---|---|
| Estado | `LIBERADO`: versões **001–022** com sucesso, `flyway validate` OK, migrador livre |
| Migrations implantadas × repositório | **22 de 22 iguais**: 021 rev3 `153c595a…`, 022 `b752c7aa…` |
| Commit das imagens | `6280314` (022, antes do #19 final). **Não tem** a leitura do segredo da AWS, e o `ligar` recusa. Passo previsto: `email-real ensaio-api-v1-003 imagens`, depois `subir`, sem mudar migrations |
| Fila (`email-real … fila`) | **total=0**, pendentes ou enviando = 0. O transporte simulado não usa a fila: nada antigo pode sair |
| Contas existentes do ensaio | endereços `*@homologacao.nekkora.test` e semelhantes, não controlados. **A lista fechada impede o envio para eles:** mesmo que uma jornada antiga peça mensagem a um deles, o desfecho é RECUSADO sem rede |
| Logs e evidências | varredura da `jornada`: senhas, tokens, códigos, endereços, credenciais temporárias e chave da fila contra os logs da Platform, do Portal, do gateway e do proxy e contra a evidência, com controle positivo. As saídas do runner não imprimem o destinatário, o token nem o conteúdo |

O ambiente foi deixado como estava (parado).

## 6. Roteiro da homologação real (item 4, depois da autorização)

1. **Imagens:** `email-real ensaio-api-v1-003 imagens`, depois `subir ensaio-api-v1-003`.
2. **Credenciais:** `aws/m365-aws.sh credenciais` (W3, pede o código do MFA) e `destinatarios.txt` (§4.7) em `<segredos>/m365/`. Repetir o W3 antes de cada `ligar` ou `platform subir`: as credenciais valem 1 h.
3. **Ligar:** `email-real ensaio-api-v1-003 ligar`. Registra a fila antes e os eventos `mail.segredo_carregado`, `mail.fila_cifra`, `mail.destinatarios_restritos` e `mail.certificado_vence_em`.
4. **F1:** `email-real … prova no-reply@nekkora.com <caixa controlada>` → código 0 (202, **aceite**). Você salva a mensagem como `.eml`, e `email-real … conferir-eml <arquivo>` confere o **recebimento**, o remetente e um destinatário.
5. **F2:** `email-real … prova <outra caixa do tenant> <caixa controlada>` → código 2 (403, recusa pelo RBAC). É a mesma credencial do aplicativo, e nada chega.
6. **F3:** no `.eml` do F1, `spf=pass`, `dkim=pass` com `d=nekkora.com` e `dmarc=pass`.
7. **R1 a R5:** `email-real … jornada`. Você salva cada mensagem recebida em `<segredos>/m365/entrada/`, e a jornada segue:
   - **R1** cadastro (pedido 202, mensagem recebida, confirmação 204 sem sessão, **reuso do token recusado**);
   - **R2** primeira entrada com o fator;
   - **R3** recuperação de senha (mensagem recebida, troca 204, **reuso recusado**, sessões revogadas, **MFA preservado**: a entrada pede o fator, e o mesmo fator entra) e o **aviso** de senha alterada recebido;
   - **R4** entrada com código de recuperação, **aviso** recebido e reuso do código recusado;
   - **R5** falha e reinício **locais**: saída cortada, tentativa sem conexão (PENDENTE, nada saiu), Platform parada com a mensagem na fila, saída religada, Platform de volta (relê o segredo), mensagem ACEITA depois do reinício e recebida. O tenant não é tocado.
   - **Fila em cada caso:** BANCO com estado 3 e `last_status` 202 = aceite. Os testes automatizados da fila (`MailOutboxIT`, 15) cobrem posse vencida, queda durante o envio e concorrência.
8. **F6:** destinatário externo controlado com `p=none`, que não reprova.
9. **Três estados de cada mensagem:**

   | Estado | Onde aparece |
   |---|---|
   | **Enfileirada** | linha na `mail_outbox` |
   | **Aceita pelo Graph** | estado 3, 202 |
   | **Recebida** | `.eml` conferido |

   Opcionalmente, o `Get-MessageTrace` do Exchange Online. Não afirmo entrega **exatamente uma vez**: um INCERTO nunca é repetido sozinho, e um aceite não é entrega.
10. **Desligar:** `email-real ensaio-api-v1-003 desligar`.

## 7. Procedimento para interromper os envios

Em ordem de rapidez:

1. **Na hora, sem perder a fila:** `homologacao.sh email-real ensaio-api-v1-003 saida cortar`. O proxy para, e nada mais sai para o Entra, o Graph ou a AWS. As mensagens ficam PENDENTES e expiram no prazo.
2. **Encerrar o transporte real:** `homologacao.sh email-real ensaio-api-v1-003 desligar`. Proxy parado e removido, transporte simulado de volta e credenciais temporárias apagadas.
3. **No tenant, para qualquer cópia da credencial:** `Remove-ManagementRoleAssignment` da atribuição D6, ou remover o certificado do aplicativo (D3). Sem token, nada sai.
4. **Na AWS:** revogar as sessões da função W1 (IAM → Funções → `nekkora-homologacao-platform-m365-leitura` → Revogar sessões ativas) e, se for o caso, apagar o segredo W2.
5. **Ao encerrar a homologação** (decisão à parte):
   - `aws secretsmanager delete-secret --profile nekkora-login --region sa-east-1 --secret-id nekkora/homologacao/platform/m365 --recovery-window-in-days 7`;
   - `aws iam delete-role-policy --profile nekkora-login --role-name nekkora-homologacao-platform-m365-leitura --policy-name ler-segredo-m365`;
   - `aws iam delete-role --profile nekkora-login --role-name nekkora-homologacao-platform-m365-leitura`;
   - remover o certificado do aplicativo (D3).

## 8. Limitações conhecidas

- O texto do segredo fica numa `String` Java até a coleta de lixo. O certificado e a senha são apagados.
- As credenciais temporárias aparecem no `docker inspect` do container da Platform, que é local ao host. Elas valem 1 h e são apagadas no `desligar`.
- Os links das mensagens apontam para `https://homologacao.nekkora.test:55763`, que não resolve. A página não existe nesta missão, e a jornada envia o token à API. Algum filtro pode mandar a mensagem para o lixo eletrônico; recebimento nessa pasta conta como recebido, registrado como tal.
- O RBAC do Exchange pode levar de 30 min a 2 h para valer depois do D6. Um F1 recusado nesse intervalo é repetido depois, dentro do volume.
- A leitura de cabeçalhos depende do `.eml` salvo com os cabeçalhos completos (no Gmail, "Mostrar original" → "Fazer o download"; no Outlook, "Salvar como").
- O R5 prova o caminho local de falha e reinício. Falhas do lado do tenant (429, 5xx) ficam só nos testes automatizados, com o Entra e o Graph falsos.
