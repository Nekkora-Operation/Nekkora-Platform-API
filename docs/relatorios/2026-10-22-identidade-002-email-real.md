# E-mail real: transporte Microsoft Graph e fila persistente (missão 002, PR 5)

**Depende de:** PR #12 (020) e, por ele, #11 e #10. **Migration nova:** `021_correio_fila.sql`. **Desenho seguido:** `2026-10-11-email-transacional-m365-desenho.md`, com a direção aprovada e as decisões DC-M2.1 a DC-M2.6.

**Estado da entrega.** O código está pronto para revisão, com testes. **A homologação real NÃO foi feita.** Ela depende das ações externas da §5, que precisam de autorização da responsável:
- Microsoft 365 (aplicativo, RBAC e caixa de DMARC);
- Cloudflare (registro DMARC);
- cofre (certificado);
- rede de saída da homologação.

Este PR **não** declara a entrega pública do e-mail concluída.

## 1. Conferências antes de configurar

**Documentação vigente** (Microsoft Learn, lida em 2026-10-22):
- **RBAC para Aplicações no Exchange Online** (atualizado em 2026-08-21):
  - papel `Application Mail.Send` com escopo de gerenciamento;
  - `New-ServicePrincipal` com o AppId e o ObjectId da página **Enterprise applications**, não de App registrations;
  - as permissões do Entra e do RBAC se **somam**: o consentimento de `Mail.Send` no Entra deve ser removido ou nunca concedido;
  - `Test-ServicePrincipalAuthorization` ignora o cache;
  - mudanças de permissão têm cache de 30 min a 2 h;
  - o RBAC "substitui" as Application Access Policies.
- **`sendMail`** (atualizado em 2026-06-19): sucesso é `202 Accepted`, que "não indica que o processamento terminou". Ou seja, aceite não é entrega. `saveToSentItems` é opcional.
- **Credenciais de certificado do Entra** (atualizado em 2026-06-15):
  - cabeçalho `alg=PS256`, `typ=JWT`, `x5t#S256`;
  - reivindicações `aud` (endpoint de token v2), `iss`=`sub`=client id, `jti`, `nbf`/`iat`, `exp` de 5 a 10 min;
  - `client_assertion_type` fixo e `scope=https://graph.microsoft.com/.default`;
  - os exemplos da própria documentação ainda mostram RS256 e `x5t`, mas o texto normativo pede PS256 e `x5t#S256`, e foi este o implementado.
- **Limites do Exchange Online** (atualizado em 2026-10-19):
  - 30 mensagens por minuto;
  - 10.000 destinatários por dia;
  - limite de destinatários externos por tenant, que depende de licenças.
- **Limites do Graph para o Outlook:** 10.000 pedidos a cada 10 min e 4 pedidos concorrentes por aplicativo e caixa.

**DNS público de `nekkora.com`**, consultado em 2026-10-22 por DNS sobre HTTPS (`cloudflare-dns.com`). O `dig` direto deste ambiente foi recusado ou expirou, e o resultado não foi usado.

| Registro | Valor | Leitura |
|---|---|---|
| `MX` | `0 nekkora-com.mail.protection.outlook.com` | recebimento no Exchange Online |
| `TXT` ápice | `v=spf1 include:spf.protection.outlook.com ~all` e `MS=ms38126768` | **SPF publicado**, correto para o envio pelo Graph (a saída é o Exchange Online). `~all` fica até observar o DMARC (L-6) |
| `CNAME selector1/2._domainkey` | `selector1/2-nekkora-com._domainkey.radiantsai.n-v1.dkim.mail.microsoft` | iguais aos fornecidos pelo tenant (conferência de 2026-10-11). **Os dois destinos publicam chave** (`v=DKIM1; k=rsa; p=…`). Assinatura `Enabled: True` no tenant em 2026-10-11 |
| `TXT _dmarc` | **ausente** (NXDOMAIN) | **lacuna L-1, aberta** |

**`dmarc@nekkora.com` precisa ser criado e confirmado recebendo ANTES do registro `_dmarc`** (L-8). Sem isso, os relatórios voltam e a fase de observação não produz nada.

## 2. Comportamento entregue

### Porta e configuração

`nekkora.mail.transport=m365` liga o transporte real. A porta `MailSender` passa a ser a **fila persistente** (`OutboxMailSender`).

`local` e `homologacao` não mudam.

A Platform **não sobe** em `m365` sem:
- tenant;
- cliente;
- certificado legível e com a senha certa;
- remetente.

Isso está em `GraphMailTransportTest.partidaRecusadaSemConfiguracao`.

Variáveis, todas por ambiente ou cofre e nada versionado:

| Variável | Uso |
|---|---|
| `NEKKORA_MAIL_TRANSPORTE` | `m365` |
| `NEKKORA_MAIL_REMETENTE` | remetente |
| `NEKKORA_M365_TENANT` | tenant |
| `NEKKORA_M365_CLIENT` | cliente |
| `NEKKORA_M365_CERT` | arquivo PKCS#12 montado |
| `NEKKORA_M365_CERT_SENHA` | senha do PKCS#12 |
| `NEKKORA_M365_PROXY` | opcional, `host:porta` |

### Transporte Graph (`GraphMailTransport`)

- **Token de aplicação** por client credentials com **asserção de certificado** PS256 e `x5t#S256`. Nenhum segredo de cliente é usado. O token fica só em memória, renovado 5 min antes de vencer, e nunca vai para log, resposta ou banco.
- **Envio:** `POST /v1.0/users/{remetente}/sendMail` com:
  - um destinatário, sem cópia nem cópia oculta;
  - `saveToSentItems=false`;
  - corpo em texto;
  - `client-request-id` igual à correlação.
- **Saída por proxy** opcional, para redes sem saída direta.

**Desfechos** (desenho §6.1):

| Resposta | Desfecho |
|---|---|
| 202 | ACEITO (aceite, **não** entrega) |
| 400/401/403/404 e demais 4xx | RECUSADO; 401 também descarta o token em cache |
| 429/503/504 | RETENTÁVEL, com `Retry-After` |
| 500, 502 e outros 5xx | INCERTO |
| conexão recusada ou sem conexão | RETENTÁVEL (nada saiu) |
| resposta depois do prazo ou conexão cortada | INCERTO (pode ter saído) |
| Entra recusa o token (4xx) | RECUSADO, sem envio |
| Entra fora (rede ou 5xx) | RETENTÁVEL |

### Fila persistente (021) e despacho (`MailDispatcher`)

- **Gravação.** `send()` grava um trabalho **depois do commit** da alteração de segurança, como todas as portas. Destinatário e token vão **cifrados juntos**: AES-GCM, chaves de `nekkora.keys`, AAD = `mail|job_id|key_id`. Em claro ficam só o HMAC do destinatário (`IdentityHmac.global`) e metadados.
- **Ciclo de despacho** (a cada 5 s, chave `nekkora.mail.despacho.enabled`):
  1. ENVIANDO com posse vencida, ou seja, queda durante o envio: **INCERTO**, sem reenvio;
  2. PENDENTE com prazo vencido: **EXPIRADO**, sem envio;
  3. reivindicação com `FOR UPDATE SKIP LOCKED`: duas instâncias nunca pegam o mesmo trabalho;
  4. envio fora de transação;
  5. desfecho preso à tentativa reivindicada.
- **Terminais** (ACEITO, RECUSADO, INCERTO, EXPIRADO): o payload cifrado é **apagado** no mesmo UPDATE.
- **Tentativas:** RETENTÁVEL volta a PENDENTE com espera crescente (30 s, 2 min, 10 min, 30 min) ou o `Retry-After`. O máximo são **5 tentativas**; esgotadas, INCERTO (desenho §6.1).
- **Prazo por tipo:** recuperação de senha 30 min, confirmação de cadastro 24 h, avisos 24 h. O teto do banco é de 7 dias.
- **Ritmo:** no máximo 25 envios por minuto por instância (`nekkora.mail.despacho.por-minuto`), abaixo do limite de 30 do Exchange Online. Envio um a um, abaixo do limite de 4 concorrentes do Graph.
- **Proteção no banco** (trigger da 021):
  - a origem do trabalho é imutável;
  - um estado terminal não volta;
  - as tentativas só aumentam;
  - o payload só pode ser apagado;
  - DELETE e TRUNCATE são recusados.

  A Platform tem só SELECT, INSERT e UPDATE por coluna, e nenhum DELETE.
- **Avisos da recuperação emergencial (016):** já têm persistência própria (`EmergencyNotices`). Vão direto ao transporte com até 3 tentativas curtas, só para RETENTÁVEL, e o desfecho é mapeado para `MailSender.Outcome`.

**Duplicidade.** Não há entrega "exatamente uma vez": o Graph não tem chave de idempotência.
- O que **pode ter saído** (INCERTO) nunca é reenviado automaticamente (desenho §6.2).
- O que **com certeza não saiu** (RETENTÁVEL) é repetido.
- Um aviso duplicado é, no pior caso, incômodo. Um token de cadastro ou de recuperação tem uso único, e o pedido seguinte revoga o anterior.
- A janela de deduplicação no Redis, prevista no desenho §6.3, **não** foi implementada. A fila persistente com reivindicação exclusiva já impede o envio duplo pela própria aplicação. Fica registrada como divergência do desenho, para decisão.

**A alteração de segurança nunca depende do e-mail.**
- A gravação na fila acontece depois do commit.
- Capturam a falha e registram em log:
  - os serviços desta missão (troca de fator, troca de senha, regeneração de códigos e recuperação de senha);
  - a verificação com código de recuperação;
  - o cadastro, cuja fila assíncrona já existia.
- A vinculação (P3) e os caminhos por organização chamam a porta sem captura, como antes. Neles, uma falha **de gravação na fila** (banco) produz 500 depois do commit. A alteração fica, mas a resposta engana.
- A falha do **transporte** nunca chega à requisição.

**Observabilidade.** Os eventos de log registram o job, o tipo, a tentativa, o desfecho, o status HTTP e a correlação:
- `mail.enfileirado`;
- `mail.desfecho`;
- `mail.posse_vencida` (alerta);
- `mail.prazo_vencido`;
- `mail.desfecho_sem_posse` (alerta);
- `mail.graph`;
- `mail.token_falhou`;
- `mail.certificado_vence_em`: diário; alerta a 60 dias e crítico a 30 dias (DC-M2.4).

Nunca são registrados destinatário, token, link, conteúdo, token de acesso ou senha. Isso é conferido em `MailOutboxIT` com captura da saída.

**Modelos de mensagem** (`MailTemplates`), em português e texto simples, para todos os tipos:
- confirmação de cadastro;
- aviso de cadastro existente;
- recuperação de senha (identidade e organização);
- senha trocada;
- uso de código de recuperação;
- fator substituído;
- códigos regenerados;
- vinculação;
- reset administrativo;
- avisos da recuperação emergencial.

Links só na origem pública, com o **token no fragmento** (`#token=`). Os avisos não têm link nem token. Os caminhos das páginas são contrato com a interface web, que está fora desta missão:
- `/cadastro/confirmar`;
- `/recuperar-senha`;
- `/o/{org}/recuperar-senha`;
- `/o/{org}/mfa-emergencia/confirmar` e `/contestar`.

### Comando de prova de homologação (`ProvaEnvioM365`)

Envia **uma** mensagem de teste por execução, pelo remetente **informado**, com o aplicativo e o certificado do ambiente. Imprime só o desfecho, o status e o identificador; nunca o token de acesso ou a senha. É o instrumento dos testes F1 e F2 da §5.

```
java -cp app.jar -Dloader.main=com.nekkora.platform.mail.ProvaEnvioM365 org.springframework.boot.loader.launch.PropertiesLauncher \
     --remetente no-reply@nekkora.com --para <caixa controlada>
```

Códigos de saída: 0 aceito, 2 recusado, 3 retentável, 4 incerto, 64 uso incorreto.

## 3. Evidências

| Garantia | Teste |
|---|---|
| Asserção PS256 com `x5t#S256` e reivindicações da documentação; **assinatura conferida** com a chave pública do certificado; formulário sem segredo de cliente | `GraphMailTransportTest.assercaoDeCertificadoSegueADocumentacaoEAAssinaturaConfere` |
| Pedido de envio: remetente na URL, um destinatário, sem cópia, `saveToSentItems=false` | `pedidoDeEnvioPeloRemetenteComUmDestinatarioSemCopiaNemItensEnviados` |
| Token em cache; 401 o descarta | `tokenEmCacheEInvalidadoPor401` |
| Mapeamento dos desfechos | `desfechosSeguemODesenho`, `semConexaoERetentavelETokenRecusadoNaoEnvia` |
| Partida recusada sem tenant, remetente ou certificado, ou com senha errada | `partidaRecusadaSemConfiguracao` |
| Saída por proxy | `saidaPorProxyConfigurado` |
| Comando de prova: aceito (0), recusa a outro remetente (2), uso incorreto (64), sem segredo na saída | `comandoDeProvaDeHomologacao` |
| Cadastro real → fila **cifrada** (destinatário e token ausentes do payload em repouso e do log) → despacho → 202 → ACEITO com payload apagado → o token do link conclui o cadastro | `MailOutboxIT.cadastroEnfileiraCifradoEODespachoEntregaEApagaOPayload` |
| RETENTÁVEL volta à fila com espera e depois é aceito | `retentavelVoltaAFilaEDepoisEAceito` |
| RECUSADO e INCERTO são terminais e não são repetidos | `recusaEIncertezaSaoTerminaisENaoSaoRepetidas` |
| 5 tentativas esgotadas: INCERTO | `tentativasEsgotadasViramIncerto` |
| Recuperação após queda: posse vencida vira INCERTO, sem reenvio | `posseVencidaDepoisDeQuedaViraIncertoSemReenvio` |
| Prazo vencido: EXPIRADO, sem envio | `prazoVencidoExpiraSemEnviar` |
| Dois despachos concorrentes: cada trabalho enviado uma vez | `despachosConcorrentesEnviamCadaTrabalhoUmaVez` |
| Ritmo por minuto | `ritmoPorMinutoLimitaOsEnvios` |
| Avisos emergenciais: tentativas curtas e mapeamento | `avisoEmergencialVaiDiretoAoTransporteComTentativasCurtas` |
| Banco: origem imutável, terminal imutável, sem apagamento, Platform sem DELETE | `bancoProtegeOrigemTerminalEApagamento` |
| Modelos: todo tipo, link na origem com token no fragmento, avisos sem link | `MailTemplatesTest` |

**Suíte de banco** `db/tests/correio-021`:

| Caso | Resultado |
|---|---|
| A, instalação limpa 001–021 | a 021 é aplicada **sem** janela (`elevada=nao`); invariantes de 012 a 021 depois da retirada; marcador `versao=021` |
| B2, base populada 020 → 021 (80 tabelas) | dados idênticos; catálogo com exatamente 87 linhas previstas, todas acréscimos de `mail_outbox` e da sua proteção; igual à instalação limpa |
| B3, reaplicação | recusada |

**Regressão completa, E2E e CI:** registradas no PR.

## 4. Limitações e divergências declaradas

- **Homologação real não executada:** bloqueio externo (§5). O transporte foi provado contra o Entra e o Graph **falsos**. A chamada real, o recebimento e os cabeçalhos de autenticação dependem das ações autorizadas.
- **Janela de deduplicação no Redis** (desenho §6.3) não implementada, como explicado na §2. Decisão da responsável.
- **Retenção da fila:** as linhas terminais guardam só metadados e nunca são apagadas; a Platform não tem DELETE. A política de retenção fica para decisão própria.
- **Ritmo por instância:** com mais de uma instância da Platform, a soma dos ritmos precisa caber em 30 por minuto. Isso é configuração, não coordenação.
- **Vinculação e caminhos por organização:** uma falha de **gravação** na fila produz 500 depois do commit. É o comportamento de antes com qualquer falha da porta; a alteração de segurança fica.
- **Avisos ainda inexistentes:** a recuperação de senha **por organização** não envia aviso de senha trocada na confirmação. É um fluxo antigo, mantido como está.

## 5. Ações externas para autorização

Nenhuma foi executada. A ordem é obrigatória.

### A. `dmarc@nekkora.com` (L-8), antes do registro DMARC

| Item | Valor |
|---|---|
| Destino | Exchange Online, tenant `radiantsai.onmicrosoft.com` (`facfe491-5e10-4f17-bb17-7c2115baea8f`) |
| Ação | caixa compartilhada `dmarc@nekkora.com`, acompanhada por pessoa a definir pela responsável |
| Comandos | `New-Mailbox -Shared -Name "dmarc" -DisplayName "Relatorios DMARC" -PrimarySmtpAddress dmarc@nekkora.com`<br>`Add-MailboxPermission dmarc@nekkora.com -User <pessoa> -AccessRights FullAccess` |
| Prova | um e-mail externo a `dmarc@nekkora.com` chega à caixa |
| Impacto | nenhum no envio. Sem a caixa, os relatórios voltam |

### B. DMARC em observação (L-1)

| Item | Valor |
|---|---|
| Destino | Cloudflare, zona `nekkora.com` |
| Registro | `_dmarc` `TXT` `"v=DMARC1; p=none; rua=mailto:dmarc@nekkora.com; fo=1; pct=100"` |
| Impacto | só observação; nada é recusado. O endurecimento (`quarantine`, `reject`, `-all`) só vem depois de ler relatórios |

### C. Remetente

| Item | Valor |
|---|---|
| Destino | Exchange Online |
| Comandos | `Set-Mailbox no-reply@nekkora.com -DisplayName "Nekkora"` (decidido)<br>`Set-Mailbox no-reply@nekkora.com -AcceptMessagesOnlyFromSendersOrMembers no-reply@nekkora.com` (a caixa não recebe mensagens) |
| Impacto | o destinatário vê `Nekkora <no-reply@nekkora.com>`; respostas são recusadas |

### D. Aplicativo dedicado e RBAC para Aplicações (DC-M2.3)

| Passo | Destino | Ação | Impacto |
|---|---|---|---|
| D1 | Entra ID | registrar o aplicativo `nekkora-platform-mail`, **de um único tenant**, **sem nenhuma** permissão de API consentida | identidade nova, sem permissão |
| D2 | cofre | gerar RSA 2048 e certificado de 1 ano; a chave privada fica no cofre e é montada como PKCS#12 só no container da Platform | — |
| D3 | Entra ID | carregar **só o certificado público**; nenhum segredo de cliente | o aplicativo obtém token |
| D4 | Exchange Online | `New-ServicePrincipal -AppId <Application ID> -ObjectId <Object ID> -DisplayName "nekkora-platform-mail"`, com os dois IDs da página **Enterprise applications** | ponteiro para o principal de serviço |
| D5 | Exchange Online | `New-ManagementScope -Name "Somente no-reply" -RecipientRestrictionFilter "PrimarySmtpAddress -eq 'no-reply@nekkora.com'"` | escopo de uma caixa (conferir o filtro na lista de propriedades filtráveis antes de aplicar) |
| D6 | Exchange Online | `New-ManagementRoleAssignment -App <Object ID> -Role "Application Mail.Send" -CustomResourceScope "Somente no-reply"` | envio **somente** como `no-reply` |
| D7 | Exchange Online e Entra | `Get-ManagementRoleAssignment -App <Object ID>` com exatamente uma atribuição; `Get-ApplicationAccessPolicy` sem linha do aplicativo; no Entra, **nenhuma** permissão de aplicação do Graph consentida | caminho único de autorização: as concessões se **somam** |
| D8 | Exchange Online | `Test-ServicePrincipalAuthorization -Identity <Object ID> -Resource no-reply@nekkora.com` → `InScope=True`; para outra caixa → `InScope=False` | prova de configuração; o teste ignora o cache |

Esperar o cache (30 min a 2 h) antes das chamadas reais.

### E. Ambiente de homologação

- Variáveis da §2, com o certificado de D2 montado como segredo de arquivo.
- **Rede de saída:** a Platform da homologação roda numa rede `internal: true`, sem saída. O transporte precisa alcançar `login.microsoftonline.com` e `graph.microsoft.com` por HTTPS. As opções, para decisão:
  - **(a, recomendada)** um proxy de saída que só libere esses dois nomes, apontado por `NEKKORA_M365_PROXY`;
  - **(b)** uma rede de saída exclusiva da Platform.

  Nenhuma das duas foi feita.
- **Linhas de elevação:** 017–020 (#9, #11, #12). A 021 não precisa.

### F. Homologação real, depois de A a E

Destinatário: caixa controlada, a definir pela responsável. Uma mensagem por item, sem reenvio indiscriminado.

| Teste | Exigido |
|---|---|
| F1 | `ProvaEnvioM365 --remetente no-reply@nekkora.com` responde 202 **e** a mensagem é recebida |
| F2 | `ProvaEnvioM365 --remetente <outra caixa do tenant>` é recusado (código 2, status 403) |
| F3 | cabeçalhos da mensagem recebida: `spf=pass`, `dkim=pass` com `d=nekkora.com`, `dmarc=pass`; `From: Nekkora <no-reply@nekkora.com>`; um destinatário |
| F4 | um fluxo real (confirmação de cadastro): link na origem pública, token no fragmento, abrir não confirma, uso único |
| F5 | `Get-MessageTrace` registra o trânsito |
| F6 | um envio a destinatário externo controlado, para confirmar que `p=none` não reprova |

Um 202 sem F3 conferido **não** encerra a homologação. Até F1–F6 passarem, `nekkora.cadastro.enabled` e `nekkora.recuperacao-senha.enabled` continuam desligadas fora dos ambientes descartáveis.
