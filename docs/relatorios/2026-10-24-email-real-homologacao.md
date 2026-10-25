# E-mail real da Platform em homologação: conferência, preparação e pedido de autorização

**Data:** 2026-10-24. **Branch:** `feat/email-real-homologacao` (PR para `main`). **Base:** `ops/api-v1-003-homologacao` @ `8dd22fc` (ver §1). **Ambiente de ensaio:** `ensaio-api-v1-003`.

**Nesta etapa não houve nenhuma alteração externa:** nada no Microsoft 365, no DNS, na AWS nem em cofre, e nenhuma mensagem real enviada. A única saída de rede foi a prova do proxy (§3.3): abertura de túnel TLS para três nomes, sem credencial e sem pedido autenticado. Página web e cadastro público ficam fora desta missão. Merge, implantação em produção e liberação pública também.

## 0. Estado

| Etapa da missão | Situação |
|---|---|
| 1. Conferir a implementação | **Feita** (§2). Achados: a fila usa a chave do MFA; não havia lista de destinatários; não havia leitura de segredo da AWS. Os três foram corrigidos (§3). |
| 2. Preparar a configuração externa | **Etapas 1 e 2 executadas em 2026-10-25, com autorização (§4.9).** Recursos do Microsoft 365 e da AWS criados e conferidos; `confirmado=OK`. |
| 3. Preparar o ambiente de ensaio | Imagens trocadas para `364848f` na L4 (§4.9), migrations iguais, fila 0, tentativas 0 de 20. O transporte continua o simulado até a etapa 3. |
| 4. Homologação real | **Etapa 3 executada em 2026-10-25, com autorização (§6.1):** A1, F1, F2 (403), F3, F6 e R1–R5 aprovados; **8 de 20 tentativas**; transporte real desligado no fim. |
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
| `validar-config` | **285 OK, 0 falhas**. Em 2026-10-25, com a prova do `m365-aws.sh`: **287 OK, 0 falhas** |
| `testes/test_m365_aws.sh` (W1–W3 e certificado contra uma AWS CLI falsa: recusas sem alterar nada, nenhum segredo na linha de comando, chave apagada no sucesso e na falha) | **26/26** |
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
- **o CI deste PR e o de qualquer PR devem falhar nesses dois casos até a correção do teste.**
- **Corrigido em alteração isolada (2026-10-25): PR #22**, `fix/ops-teste-prazos-mudanca-horario → main`, sem nada do e-mail. A fixture recua até a janela começar 10 dias antes da última mudança de horário de Europe/Berlin. Resultados: 6/6 no IT; `nekkora-ops` 164/164 na `main`; simulação do cálculo de 2026 a 2030, todos os instantes atravessam. Depois do merge do #22, a base deste PR precisa ser atualizada para o CI ficar verde.

### 3.5 Mudanças de 2026-10-25: chave só depois da leitura utilizável, prova de autenticação e teto global

| Arquivo | Mudança |
|---|---|
| `mail/TentativasEnvio.java` (novo) | teto **global** de tentativas: uma vaga por arquivo, criada com criação exclusiva, reservada antes de cada `sendMail`; contagem compartilhada entre a fila e a prova e entre reinícios; obrigatório em `ensaio-*`; falha fechada |
| `mail/GraphMailTransport.java` | `send` reserva a vaga e registra o desfecho; sem vaga, RECUSADO sem rede; `limitarTentativas` (uma vez); `inspecionarToken` (só reivindicações, nunca o token); `certificateSha256Hex` |
| `config/MailConfig.java`, `application.yml` | `nekkora.mail.tentativas.diretorio` e `.maximo`; o teto é conferido **antes** de ler o segredo |
| `mail/ProvaEnvioM365.java` | conta no mesmo teto; código 65 com o teto já atingido |
| `mail/ProvaAutenticacaoM365.java` (novo) | `--segredo [--esperado-sha256]`: leitura utilizável com o código da partida, sem imprimir o conteúdo nem o hash. `--token`: token pela asserção de certificado, com tenant, aplicativo e **nenhum papel de aplicação** conferidos. Não envia |
| `aws/m365-aws.sh` | `publicar` **não apaga mais** a chave. `confirmar` só a apaga com: a sessão da função; o administrador sem leitura (`AccessDenied`); a leitura utilizável pela Platform (SHA-256 igual ao publicado); o certificado igual ao `.cer` |
| `compose.m365.yml`, `homologacao.sh` | diretório `m365/tentativas` montado na Platform; máximo 20 versionado; `ligar` recusa com o teto atingido e exige o evento de teto; `prova` recusa antes de tentar; novos `tentativas` e `autenticacao` |

| Prova | Resultado |
|---|---|
| `TentativasEnvioTest` (teto, contagem entre origens e reinícios, 80 reservas simultâneas → exatamente 20 vagas distintas, falha fechada, regra de configuração) | **5/5** |
| `ProvaAutenticacaoM365Test` (leitura utilizável, conteúdo diferente, falha da AWS, outro tenant, token conferido, papel de aplicação ou outro aplicativo reprova, Entra recusa) | **6/6** |
| `GraphMailTransportTest` (+ teto sem rede, prova e fila no mesmo teto, impressão no formato do `keytool`) | **13/13** |
| Demais testes do e-mail (`M365SegredoTest`, `AwsSecretsManagerTest`, `MailConfigRestricoesTest`) | **18/18** |
| `testes/test_m365_aws.sh` (+ `publicar` mantém a chave; `confirmar` em 5 falhas mantém a chave e no sucesso a apaga) | **34/34** |
| Usuário `nekkora` do container da Platform, raiz somente leitura, diretório montado do Windows | cria a vaga; a segunda criação exclusiva da mesma vaga é recusada; o arquivo aparece no host |
| `ProvaAutenticacaoM365` real, do jar, no host (como o `confirmar` chama) | sem ambiente: código 64; sem credenciais: `leitura=FALHOU`, antes de qualquer rede |
| `email-real ensaio-api-v1-003 tentativas` (somente leitura) | `usadas=0 maximo=20` |
| `validar-config` | ver §3.6 |

### 3.6 Regressão do candidato (local, 2026-10-25, `./mvnw -pl nekkora-platform -am test -Dmaven.test.failure.ignore=true`)

| Módulo | Resultado |
|---|---|
| `nekkora-core` | 21/21 |
| `nekkora-platform` (o alterado) | **408/408**: os 394 de antes e os 14 novos |
| `nekkora-ops` (não alterado) | 174/177: as **2 falhas dependentes da data** (§3.4; corrigidas no PR #22) e **1 erro de ambiente**, `FixtureHomologacaoIT.contatosOutraLinhaAlteradaDuranteAFixtureDesfazTudo`: `BindException: Address already in use` ao **abrir** a conexão com o banco do teste, ou seja, sem porta efêmera livre no Windows durante a regressão. Repetido sozinho: `FixtureHomologacaoIT` **15/15**. Registrado como intermitente, de ambiente |
| `validar-config` | **290 OK, 0 falhas** (Docker Desktop no ar; com ele parado, a guarda de sub-rede recusa por desenho) |

## 4. Configuração externa a autorizar (item 2), em três etapas

Nenhuma ação foi executada. A autorização vem **por etapa e na ordem**: preparação e leituras; recursos externos; transporte e envios. Cada etapa só começa com a anterior concluída e com a sua autorização explícita para ela.

**Dados definidos pela titular (2026-10-25)**, sem os identificadores, porque este repositório é **público**:
- conta AWS da titular, região `sa-east-1`;
- usuário IAM administrador com MFA;
- caixa externa de destino (Gmail, com `+marcador`);
- segunda caixa do tenant para o F2;
- `dmarc@nekkora.com` existe e é acompanhada pela titular.

Os valores ficam só em `<segredos>/m365/aws-local.env` e `<segredos>/m365/destinatarios.txt`, fora do Git. Os modelos versionados levam `${CONTA_AWS}`, `${USUARIO_IAM}` e `${MFA_SERIAL}`. `aws/m365-aws.sh renderizar` gera as cópias concretas em `<segredos>/m365/aws/`, e são essas cópias que vão para a AWS.

**Estado público conferido em 2026-10-25:**

| Registro | Valor |
|---|---|
| SPF | `v=spf1 include:spf.protection.outlook.com ~all` |
| DKIM | `selector1` e `selector2` publicados, com CNAME para o Microsoft 365 |
| DMARC | **publicado pela titular**: `v=DMARC1; p=none; rua=mailto:dmarc@nekkora.com` |

A ação B (DMARC) e a ação A (caixa `dmarc@`) já estão feitas e saem do pedido.

### 4.1 Etapa 1: preparação e leituras (nada é criado fora desta máquina)

| # | Ação | Comando | Natureza |
|---|---|---|---|
| L1 | AWS CLI v2 ≥ 2.32 | `msiexec.exe /i https://awscli.amazonaws.com/AWSCLIV2.msi`; `aws --version` | instalação local |
| L2 | Perfis da CLI | preencher `MFA_SERIAL` em `aws-local.env` (IAM → usuário → Credenciais de segurança → MFA → Identificador; tem de ser `…:mfa/…`, de código); `m365-aws.sh renderizar`; acrescentar `<segredos>/m365/aws/perfil-cli.ini` a `~/.aws/config` | local |
| L3 | Sessão do console na CLI | `aws login --profile nekkora-login` (navegador, com o MFA do usuário; nenhuma chave gravada) | login, sem alteração |
| L4 | Imagens do ensaio com o código deste PR | `homologacao.sh email-real ensaio-api-v1-003 imagens` e `homologacao.sh subir ensaio-api-v1-003`. Migrations iguais; o transporte continua o simulado | local |
| R-1 a R-5 | Microsoft 365 | os comandos de `2026-10-22-identidade-002-email-real.md` §0. Exigido: tenant `ae4ed48d…` em R-1 e R-2, `nekkora.com` verificado e Authoritative, `no-reply@nekkora.com` existente, DKIM `Enabled: True` | leitura |
| R-6 | AWS | `m365-aws.sh conferir`: a sessão `nekkora-login` é a do usuário e da conta; o `MFA_SERIAL` é um dispositivo **de código** desse usuário; a função e o segredo ainda não existem | leitura |
| R-7 | Ensaio | `email-real ensaio-api-v1-003 fila` (0 pendentes) e `email-real ensaio-api-v1-003 tentativas` (0 de 20) | leitura |

**A etapa termina** com R-1 a R-7 conforme o exigido. Se algum resultado divergir, a missão para e volta para decisão.

### 4.2 Etapa 2: recursos externos (Microsoft 365 e AWS; nenhuma mensagem)

| # | Ação | Comando | Impacto |
|---|---|---|---|
| C | apresentação e respostas da no-reply | `Set-Mailbox no-reply@nekkora.com -DisplayName "Nekkora"`; `Set-Mailbox no-reply@nekkora.com -AcceptMessagesOnlyFromSendersOrMembers no-reply@nekkora.com` | o destinatário vê `Nekkora <no-reply@nekkora.com>`; respostas recusadas |
| D1 | aplicativo `nekkora-platform-mail` (um tenant, **sem** permissão de API) | Entra → Registros de aplicativo → Novo; anotar o App ID e o Object ID da Enterprise application | identidade sem permissão |
| D2 | certificado (local) | `m365-aws.sh gerar --client <App ID>` (§4.4) | — |
| D3 | só o certificado **público** no aplicativo | upload de `<segredos>/m365/certificado/nekkora-platform-mail.cer`; conferir que a impressão mostrada no Entra é a de `impressao.txt`; **nenhum** segredo de cliente | o aplicativo passa a poder obter token |
| D4–D6 | RBAC para Aplicações **restrito à no-reply** | `New-ServicePrincipal -AppId <App ID> -ObjectId <Object ID>`; `New-ManagementScope -Name "Somente no-reply" -RecipientRestrictionFilter "PrimarySmtpAddress -eq 'no-reply@nekkora.com'"`; `New-ManagementRoleAssignment -App <Object ID> -Role "Application Mail.Send" -CustomResourceScope "Somente no-reply"` | envio **somente** como no-reply |
| D7–D8 | leitura: caminho único | `Get-ManagementRoleAssignment -App <Object ID>`: exatamente uma atribuição; nenhuma permissão de aplicação do Graph consentida; `Get-ApplicationAccessPolicy` sem linha do aplicativo; `Test-ServicePrincipalAuthorization -Identity <Object ID> -Resource no-reply@nekkora.com` → `InScope=True`; `-Resource <caixa do F2>` → `InScope=False` | prova de configuração (o RBAC pode levar de 30 min a 2 h para valer no envio) |
| W1 | função `nekkora-homologacao-platform-m365-leitura` | `m365-aws.sh funcao`. Confiança: **só** o usuário, **com MFA**. Política inline: **só** `secretsmanager:GetSecretValue` nesse segredo. Nenhuma política anexada; `MaxSessionDuration` 3600 s; conferido depois | nenhuma outra permissão |
| W2 | segredo `nekkora/homologacao/platform/m365` | `m365-aws.sh publicar`. Executa `create-secret --secret-string file://…`, sem o valor na linha de comando, cifrado com `aws/secretsmanager`. Depois `validate-resource-policy` e `put-resource-policy --block-public-policy`: a leitura fica negada a qualquer principal que não seja a W1, inclusive o administrador. **A chave continua no disco** | o segredo passa a existir; cerca de US$ 0,40 por mês |
| W3a | credenciais de 1 h da função, para a confirmação | `m365-aws.sh credenciais` (pede o **código do MFA**; grava `<segredos>/m365/aws.env`) | credencial temporária, apagada pelo `confirmar` |
| W2c | **confirmação do armazenamento e da leitura utilizável** | `m365-aws.sh confirmar` (§4.5). Só ele apaga a chave do disco | leitura |

**A etapa termina** com D7–D8 conforme o exigido e `confirmado=OK`: a chave sai do disco e fica só no Secrets Manager. Se o `confirmar` falhar, a chave **fica** e nada se perde. A titular decide entre corrigir e repetir (`credenciais` e `confirmar`) ou apagar o segredo e recomeçar.

### 4.3 Etapa 3: transporte e envios (até 20 tentativas)

| # | Ação | Comando | Impacto |
|---|---|---|---|
| W3b | credenciais de 1 h para o transporte | `m365-aws.sh credenciais` (código do MFA), repetido antes de cada `ligar` ou `platform subir` | credencial temporária, apagada no `desligar` |
| E | saída de rede | `email-real ensaio-api-v1-003 ligar`: sobe o `proxy-m365` (só Entra, Graph e Secrets Manager de `sa-east-1`) e recria a Platform. A partida exige os eventos `segredo_carregado`, `destinatarios_restritos` e `teto_tentativas_configurado maximo=20`; sem eles, desliga sozinho | só no ensaio, enquanto o transporte real estiver ligado |
| A1 | **prova do caminho de autenticação real** | `email-real ensaio-api-v1-003 autenticacao` (§4.5) | um pedido de token ao Entra; **nenhuma mensagem, nenhuma tentativa** |
| F1… | envios do plano | §4.7 e §6 | até 20 tentativas |

**A etapa termina** com o roteiro do §6 concluído ou com o teto atingido, e sempre com `desligar`.

### 4.4 Certificado (D2)

```
bash ops/homologacao/aws/m365-aws.sh gerar --client <App ID do D1>
```

Em `<segredos>/m365/certificado/`, o script:
1. gera uma senha aleatória de 256 bits em `senha.txt`, que **nunca é digitada nem impressa**;
2. gera o par RSA 2048 e o certificado de 1 ano, com `CN=nekkora-platform-mail-homologacao`. Usa o `keytool` com `-storepass:file`, então a senha não aparece em linha de comando;
3. confere que há **exatamente uma** chave privada no PKCS#12;
4. exporta o `.cer` (público) e a `impressao.txt` (validade e SHA-256);
5. monta o SecretString `nekkora-m365/1` em `segredo.json`.

A chave **só** sai do disco no `confirmar` bem-sucedido, ou por `m365-aws.sh apagar`, que é uma decisão da titular.

**Limites:**
- entre o `gerar` e o `confirmar`, a chave fica no disco, no diretório de segredos do perfil do usuário e fora do Git. O tempo é o do D3 ao W2c;
- `rm` não sobrescreve o conteúdo em SSD;
- a permissão `600` não tem efeito no NTFS: vale a ACL do perfil do usuário.

### 4.5 Prova do caminho de autenticação real

O caminho tem dois trechos. A tabela diz o que já está provado e o que cada etapa prova ao vivo.

| Trecho | Provado agora (sem nada externo) | Provado ao vivo |
|---|---|---|
| **CLI → função com MFA** (`aws login` → `AssumeRole` com o código) | Leitura do código-fonte do `botocore` embarcado na AWS CLI v2, nas tags **2.32.0** e **2.38.5**: um `source_profile` sem chave é resolvido pela cadeia completa de provedores, que inclui o `LoginProvider` do `aws login`; `mfa_serial` envia `SerialNumber` e o código no `AssumeRole`, e isso faz `aws:MultiFactorAuthPresent` valer `true` na confiança. `test_m365_aws.sh` cobre o fluxo contra uma CLI falsa | Etapa 2, `credenciais` + `confirmar`: a sessão das credenciais tem de ser exatamente `assumed-role/nekkora-homologacao-platform-m365-leitura/nekkora-hml-m365` |
| **Função → segredo** (SigV4, política de recurso) | `AwsSecretsManagerTest` (assinatura pelo exemplo publicado da AWS); `ProvaAutenticacaoM365Test` (leitura utilizável, códigos de falha); `test_m365_aws.sh` (confirmar: sessão errada, administrador lendo, conteúdo diferente e certificado diferente mantêm a chave) | Etapa 2, `confirmar`: (1) a sessão é a da função; (2) o **administrador não lê** (`AccessDenied`, só o `VersionId` é pedido e descartado); (3) a Platform, com o código da partida, lê pela função, carrega o formato e a chave, e o SHA-256 do lido é **igual** ao do publicado; (4) a impressão do certificado lido é a do `.cer` enviado ao Entra. **Só então a chave sai do disco** |
| **Platform → segredo, pelo proxy** | `test_m365_aws.sh`, `validar-config` (o proxy libera só os três nomes) | Etapa 3, `ligar`: evento `mail.segredo_carregado origem=aws-secrets-manager` |
| **Platform → Entra** (asserção PS256 com o certificado) | `GraphMailTransportTest` (asserção e assinatura conferidas com a chave pública); `ProvaAutenticacaoM365Test` (token conferido; papel de aplicação reprova; Entra recusando dá código 7) | Etapa 3, `autenticacao` (`ProvaAutenticacaoM365 --token`, dentro do container e pelo proxy): token obtido; `tid` = tenant; aplicativo = `client_id` do segredo; **nenhum papel de aplicação** (o envio só pode vir do RBAC restrito à no-reply). Nenhuma mensagem, nenhuma tentativa contada |
| **Entra → Exchange (RBAC)** | — | Etapa 3, F1 (202 como no-reply) e F2 (403 como a outra caixa), mais D7–D8 na etapa 2 |

### 4.6 Custos previstos

| Item | Custo |
|---|---|
| Secrets Manager | **US$ 0,40 por segredo por mês** (proporcional), mais US$ 0,05 por 10 mil chamadas (uma por partida e uma por prova, praticamente zero) |
| KMS `aws/secretsmanager`, IAM, STS e `aws login` | sem custo |
| Microsoft 365 | caixas **compartilhadas** sem licença; aplicativo e RBAC sem custo; envio pelo Graph dentro da licença existente |
| Saída de rede | desprezível |
| **Total** | **cerca de US$ 0,40 por mês** enquanto o segredo existir. Apagar o segredo encerra o custo; a AWS mantém uma janela de recuperação de 7 a 30 dias |

### 4.7 Destinatários e teto global de 20 tentativas

**Lista fechada** (`<segredos>/m365/destinatarios.txt`): uma linha `local+*@gmail.com` da caixa da titular. Qualquer outro endereço é RECUSADO pela Platform sem rede. Cada mensagem usa `local+<caso>-<execução>@gmail.com`. No F2, a segunda caixa do tenant é o **remetente** tentado e não recebe nada.

**Teto global, aplicado pelo código (não só pelo procedimento):**
- **Onde é aplicado:** em `GraphMailTransport`, que é o único ponto por onde passa qualquer `sendMail`. Cada chamada reserva **antes** uma vaga em `<segredos>/m365/tentativas/` (`TentativasEnvio`).
- **Como a vaga é reservada:** um arquivo `tentativa-NNN` criado com criação exclusiva. Dois processos nunca ganham a mesma vaga.
- **Quem conta junto:** a fila da Platform e a `ProvaEnvioM365` rodam no mesmo container e montam o mesmo diretório. O registro sobrevive a `desligar`, `ligar` e reinícios.
- **O que conta:** **toda tentativa**, aceita, recusada ou sem conexão. O token do Entra e a `autenticacao` não contam.
- **Sem vaga:** RECUSADO **sem pedido ao Graph**, e a mensagem não é repetida.
- **Configuração obrigatória:** o máximo (20) está na sobreposição versionada (`compose.m365.yml`). Em `ensaio-*`, a Platform e a prova **não sobem sem o teto**.
- **Falha fechada:** diretório ausente ou ilegível recusa.
- **No runner:** o `ligar` recusa com o teto atingido e exige o evento `teto_tentativas_configurado maximo=20`; a `prova` recusa antes de tentar (código 65); `email-real … tentativas` mostra as usadas, por origem e por desfecho.

| Caso | Tentativas | O que comprova |
|---|---|---|
| A1 autenticação | 0 | caminho de autenticação real (§4.5) |
| F1 prova como no-reply | 1 | 202 (aceite) e `.eml` recebido; F3 (SPF, DKIM, DMARC) e F6 nos cabeçalhos |
| F2 prova como a outra caixa | 1, recusa esperada (403). Se for aceita, a homologação **para** com `desligar` | recusa pelo RBAC com a mesma credencial |
| R1 cadastro | 1 | recebida; confirmação 204; reuso recusado |
| R2 primeira entrada com o fator | 0 | — |
| R3 recuperação de senha | 2 (link e aviso de senha alterada) | consumo único; sessões revogadas; MFA preservado |
| R4 código de recuperação | 1 (aviso) | aviso recebido; reuso recusado |
| R5 falha de rede e reinício locais | 2 (uma sem conexão, uma depois do reinício) | PENDENTE sem saída; ACEITA depois do reinício; recebida |
| **Execução completa** | **8** | |
| Reserva | até 12 | até 3 repetições do F1 enquanto o RBAC se propaga, mais **uma** segunda execução completa (8), se algum caso falhar por causa externa |
| **Teto** | **20**, aplicado pelo código | |

### 4.8 Pedido de autorização por etapa

1. **Etapa 1, preparação e leituras (§4.1):** L1 a L4 (locais) e as leituras R-1 a R-7.
2. **Etapa 2, recursos externos (§4.2):**
   - Microsoft 365: C, D1, D3 a D6, mais as leituras D7 e D8; o D2 é local;
   - AWS: W1 e W2 (custo de cerca de US$ 0,40 por mês);
   - W3a e a confirmação W2c, que apaga a chave só com a leitura utilizável provada.
3. **Etapa 3, transporte e envios (§4.3):**
   - W3b e E;
   - a prova de autenticação A1;
   - até **20 tentativas** do §4.7, todas para a caixa da titular.

Depois da homologação, apagar o segredo (W2), a função (W1) e o certificado do aplicativo (D3) é outra decisão (§7).

### 4.9 Execução das etapas 1 e 2 (2026-10-25)

Os identificadores da conta, do usuário, dos aplicativos e dos destinatários ficam nas evidências locais (`ops/homologacao/saidas/ensaio-api-v1-003/email-real/`, fora do Git). Varredura das evidências: nenhuma credencial, chave, senha ou SecretString.

**Etapa 1 (preparação e leituras)**

| # | Resultado |
|---|---|
| Antes de tudo | fila total=0; tentativas 0 de 20, sem registro existente; função e segredo inexistentes |
| L1 | AWS CLI 2.38.5 (instalador assinado pela Amazon Web Services, Inc.) |
| L2 | `MFA_SERIAL`: o único MFA do usuário, virtual (de código); perfis `nekkora-login` e `nekkora-hml-m365` em `~/.aws/config` |
| L3 | `aws login` → sessão do usuário administrador da conta definida. **Armadilha:** com o relógio do Windows 77 s adiantado, a troca final falhava com `TOKEN_EXPIRED`; acertado o relógio, passou |
| L4 | imagens `364848f`, migrations 001–022 iguais, ambiente `LIBERADO`; evento `mail.transporte transporte=homologacao simulado=sim` |
| R-1/R-2 | o tenant do domínio (descoberta pública da Microsoft) é o mesmo da sessão do Exchange, `ae4ed48d…`. Não usei `Connect-MgGraph`: o primeiro login do aplicativo do Graph pode criar consentimento no tenant |
| R-3 a R-5 | `nekkora.com` Authoritative; `no-reply@nekkora.com` existe (SharedMailbox); DKIM `Enabled: True` |
| R-6 | sessão, MFA de código; função e segredo inexistentes |
| R-7 | fila 0; tentativas 0 de 20 (antes e depois da troca de imagens) |

**Etapa 2 (recursos externos; nenhuma mensagem)**

| # | Resultado |
|---|---|
| Conferência prévia | não havia aplicativo `nekkora-platform-mail`. Havia dois outros: um vazio criado em 24/10 (sem credencial nem permissão; **mantido**, decisão da titular) e um antigo, com segredo de cliente e o papel `Application SMTP.SendAsApp` num escopo por grupo (a `no-reply` não estava no grupo; SMTP AUTH desligado na organização). **O antigo foi apagado por decisão da titular**: registro no Entra (lixeira por 30 dias), atribuição e escopo no Exchange. O grupo de e-mail ficou |
| C | o nome de exibição já era "Nekkora"; `AcceptMessagesOnlyFromSendersOrMembers` = a própria no-reply |
| D1 | `nekkora-platform-mail`, um tenant, **nenhuma** permissão de API, nenhum segredo de cliente, com principal de serviço (feito pela Azure CLI, cliente da Microsoft já pré-autorizado: nenhum consentimento novo) |
| D2 | RSA 2048, validade até 2027-10-25, SHA-256 `FA:50:19:6B:…:B6:C9` |
| D3 | só o certificado público (uso `Verify`); SHA-1 no Entra igual ao local |
| D4–D6 | principal no Exchange; escopo "Somente no-reply" (`PrimarySmtpAddress -eq 'no-reply@nekkora.com'`); `Application Mail.Send` nesse escopo |
| D7 | exatamente **uma** atribuição do aplicativo; nenhuma Application Access Policy; nenhuma permissão do Graph; é a única atribuição a aplicativos do tenant |
| D8 | `InScope=True` para a no-reply; `InScope=False` para a caixa do F2 |
| W1 | função criada; confiança: só o usuário, com `aws:MultiFactorAuthPresent`; uma política inline, nenhuma anexada, 3600 s |
| W2 | segredo criado, cifra `aws/secretsmanager`, uma versão, sem rotação; política de recurso nega `GetSecretValue` a quem não é a função |
| W3a + W2c (1ª) | **`confirmado=NAO`, chave mantida**: a sessão da função e a leitura pela Platform falharam (`InvalidSignatureException`). Causa: a chave secreta sorteada começava com `/`, e o Git Bash a reescrevia como caminho do Windows ao passar o ambiente para `aws.exe` e `java.exe`. Pelo perfil da CLI a mesma credencial funcionava. Corrigido no `m365-aws.sh` (`MSYS2_ENV_CONV_EXCL='*'`), com teste e controle (`test_m365_aws.sh` 37/37). O `ligar` não é afetado: o Docker lê o `aws.env` como `env_file` |
| W3a + W2c (2ª) | `sessao_da_funcao=OK`; `administrador_le=NAO (AccessDenied)`; leitura pela Platform `formato=OK chave_privada=1 conteudo=IGUAL`, certificado = `.cer` do D3, 364 dias; **`confirmado=OK`**: chave, senha e SecretString apagados do disco (ficam o `.cer` e a impressão); credenciais da função apagadas, inclusive do cache da CLI |
| Depois | tentativas 0 de 20; fila 0; transporte simulado |

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

1. **Imagens:** já feitas na L4 (etapa 1).
2. **Credenciais:** `aws/m365-aws.sh credenciais` (W3b, pede o código do MFA) e `destinatarios.txt` (§4.7) em `<segredos>/m365/`. Repetir o W3b antes de cada `ligar` ou `platform subir`: as credenciais valem 1 h.
3. **Ligar:** `email-real ensaio-api-v1-003 ligar`. Registra a fila e as tentativas antes. Exige os eventos `mail.segredo_carregado`, `mail.destinatarios_restritos` e `mail.teto_tentativas_configurado maximo=20`; registra também `mail.fila_cifra` e `mail.certificado_vence_em`.
4. **A1, autenticação:** `email-real ensaio-api-v1-003 autenticacao` → código 0: token obtido, `tid=OK`, `aplicativo=OK`, `papeis_de_aplicacao=0`. Não envia. Um código diferente de 0 interrompe a etapa (`desligar`) antes de qualquer envio.
5. **F1:** `email-real … prova no-reply@nekkora.com <caixa controlada>` → código 0 (202, **aceite**). Você salva a mensagem como `.eml`, e `email-real … conferir-eml <arquivo>` confere o **recebimento**, o remetente e um destinatário.
6. **F2:** `email-real … prova <outra caixa do tenant> <caixa controlada>` → código 2 (403, recusa pelo RBAC). É a mesma credencial do aplicativo, e nada chega. Conta uma tentativa.
7. **F3:** no `.eml` do F1, `spf=pass`, `dkim=pass` com `d=nekkora.com` e `dmarc=pass`.
8. **R1 a R5:** `email-real … jornada`. Você salva cada mensagem recebida em `<segredos>/m365/entrada/`, e a jornada segue:
   - **R1** cadastro (pedido 202, mensagem recebida, confirmação 204 sem sessão, **reuso do token recusado**);
   - **R2** primeira entrada com o fator;
   - **R3** recuperação de senha (mensagem recebida, troca 204, **reuso recusado**, sessões revogadas, **MFA preservado**: a entrada pede o fator, e o mesmo fator entra) e o **aviso** de senha alterada recebido;
   - **R4** entrada com código de recuperação, **aviso** recebido e reuso do código recusado;
   - **R5** falha e reinício **locais**: saída cortada, tentativa sem conexão (PENDENTE, nada saiu), Platform parada com a mensagem na fila, saída religada, Platform de volta (relê o segredo), mensagem ACEITA depois do reinício e recebida. O tenant não é tocado.
   - **Fila em cada caso:** BANCO com estado 3 e `last_status` 202 = aceite. Os testes automatizados da fila (`MailOutboxIT`, 15) cobrem posse vencida, queda durante o envio e concorrência.
9. **F6:** destinatário externo controlado com `p=none`, que não reprova (no `.eml` do F1).
10. **Contagem:** `email-real … tentativas` depois de cada caso. As usadas nunca passam de 20, por origem e por desfecho.
11. **Três estados de cada mensagem:**

   | Estado | Onde aparece |
   |---|---|
   | **Enfileirada** | linha na `mail_outbox` |
   | **Aceita pelo Graph** | estado 3, 202 |
   | **Recebida** | `.eml` conferido |

   Opcionalmente, o `Get-MessageTrace` do Exchange Online. Não afirmo entrega **exatamente uma vez**: um INCERTO nunca é repetido sozinho, e um aceite não é entrega.
12. **Desligar:** `email-real ensaio-api-v1-003 desligar`. O registro de tentativas fica: ele é da homologação inteira.

### 6.1 Execução da etapa 3 (2026-10-25, 13:21–13:46 UTC)

Evidências sanitizadas em `ops/homologacao/saidas/ensaio-api-v1-003/email-real/` (fora do Git); os `.eml` originais ficam só em `<segredos>/m365/entrada/`. Varredura das evidências: nenhum endereço pessoal, token ou credencial.

| Passo | Resultado |
|---|---|
| Antes | tentativas 0 de 20; fila sem pendentes; lista fechada com um item `local+*@gmail.com` da titular |
| W3b + E (`ligar`) | eventos `segredo_carregado origem=aws-secrets-manager`, `destinatarios_restritos quantidade=1`, `teto_tentativas_configurado maximo=20 usadas=0`, `fila_cifra chave=propria`, `certificado_vence_em dias=364` |
| A1 | `token=OBTIDO tid=OK aplicativo=OK aud=https://graph.microsoft.com papeis_de_aplicacao=0`; segredo lido pela função através do proxy; 0 tentativas |
| F1 | `ACEITO status=202` como no-reply (tentativa 1) |
| F2 | `RECUSADO status=403` como a outra caixa do tenant (tentativa 2): recusa pelo RBAC com a mesma credencial |
| F1 recebido (F3, F6) | remetente `Nekkora <no-reply@nekkora.com>`, um destinatário da lista, sem cópia; `spf=pass`, `dkim=pass d=nekkora.com`, `dmarc=pass` no receptor externo (`p=none`) |
| R1–R5 (`jornada`) | **49 casos, 0 falhas**. Cada mensagem nos três estados: ENFILEIRADA, ACEITA (estado 3, 202) e RECEBIDA (SPF, DKIM e DMARC pass). R1: confirmação 204 sem sessão, reuso recusado (400). R2: entrada com o fator. R3: troca 204, reuso recusado, sessões revogadas, MFA preservado, aviso recebido sem link. R4: entrada com código, aviso recebido, reuso recusado. R5: saída cortada, mensagem PENDENTE sem saída, Platform parada e religada, ACEITA depois do reinício (2 tentativas) e recebida |
| Varredura da jornada | 63 valores vistos (senhas, tokens, códigos, endereços, credenciais, chave da fila) contra 100 linhas de log e as evidências: **0 achados**; controle positivo OK |
| Tentativas | **8 de 20**: prova 2 (ACEITO 202, RECUSADO 403), Platform 6 (ACEITO 202 ×5, RETENTÁVEL sem conexão ×1); nenhuma sem desfecho; registro preservado |
| `desligar` | proxy parado e removido (conferido pelo Docker); transporte simulado de volta; credenciais temporárias apagadas (arquivo e cache da CLI) |
| Fila no fim | ACEITO: cadastro 1, recuperação 2, aviso de senha 1, aviso de código 1; nenhuma PENDENTE, ENVIANDO ou INCERTA |

**Correções do runner encontradas na execução** (a primeira A1 retornou 1 sem enviar e sem contar tentativa):
- com o transporte real, `compose.m365.yml` exige a lista de destinatários em **todo** comando compose, mas o runner só a definia no `ligar`. A `prova`, a `autenticacao` e **a parada do proxy no `desligar`** falhariam (esta em silêncio, pelo `|| true`). Agora a lista é definida sempre que o transporte é o real, e o `desligar` confere pelo Docker que o proxy parou, parando-o pelo rótulo se o compose falhar;
- no Git Bash, o argumento `/app/app.jar` do `docker exec` era reescrito como caminho do Windows (`MSYS_NO_PATHCONV=1` na `prova` e na `autenticacao`).

Verificações estáticas novas no `validar-config`. A segunda chamada de credenciais devolveu as do cache da CLI (mesmo vencimento); apagar o cache da função antes do W3b força credenciais novas.

## 7. Procedimento para interromper os envios

Em ordem de rapidez:

0. **Teto:** ao chegar a 20 tentativas, a Platform e a prova param sozinhas, sem pedido ao Graph.
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
- **Teto de tentativas:**
  - o registro é um diretório no disco do host. Apagar os arquivos zera a contagem; isso exige acesso ao diretório de segredos e fica fora do runner;
  - a vaga é reservada **antes** do pedido. Uma vaga com `desfecho` ausente indica que o processo parou no meio, e a contagem é conservadora;
  - com o teto atingido, as mensagens que ainda estiverem na fila terminam RECUSADAS, sem rede.
- **`confirmar`:**
  - no Windows, roda pelo Git Bash: sem `MSYS2_ENV_CONV_EXCL`, um valor de ambiente que começa com `/` chega alterado a programas nativos (§4.9);
  - roda no host, com saída direta para a AWS, não pelo proxy do ensaio. O caminho pelo proxy é provado na etapa 3 (`ligar`);
  - a comparação usa o SHA-256 do arquivo publicado e o do SecretString lido. Nenhum dos dois é impresso.
- **Reivindicações do token:** são lidas do JWT sem conferir a assinatura (a Microsoft não garante o formato do token de acesso para o cliente). Se o formato mudar, a prova responde `reivindicacoes=ILEGIVEIS` (código 8) e não passa. A conferência de autorização continua com o F1 e o F2.
