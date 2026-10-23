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

### Intenção durável junto do ato (revisão 1)

Achado da revisão: a primeira versão gravava na fila **depois** do commit. Cadastro e recuperação ainda passavam pela fila em memória antes disso. Uma queda nesse intervalo deixava o ato confirmado sem mensagem recuperável, e na vinculação e em caminhos por organização uma falha de gravação respondia 500 depois do commit.

**Correção: porta `MailIntents`.** Todos os serviços registram a mensagem **dentro da transação do ato**:
- cadastro e reemissão;
- pedido e confirmação da recuperação de senha (identidade e organização);
- troca de senha (identidade e organização);
- uso de código de recuperação (identidade, organização e vinculação);
- vinculação;
- troca de fator;
- regeneração de códigos;
- reset administrativo e aviso aos demais administradores.

Comportamento por transporte:
- **`m365` (`OutboxMailIntents`):** a linha de `mail_outbox` é gravada com o mesmo cliente da transação. Ou o ato e a intenção confirmam juntos, ou nenhum. Não há mais intervalo entre o commit e a gravação, nem 500 depois do commit por falha de gravação: essa falha desfaz o ato, que pode ser repetido. O envio continua fora de transação.
- **`local` e `homologacao` (`AfterCommitMailIntents`):** o envio é agendado para depois do commit, como antes, sem persistência. A falha do transporte só vai ao log. Cadastro e recuperação da identidade continuam saindo pela fila em memória.

A única mensagem sem ato a acompanhar, o aviso de "conta já existente" do cadastro, é gravada em transação própria.

**Deduplicação persistente.** Cada intenção tem uma **chave estável do evento**, `event_key` (UNIQUE), formada pelo tipo mais o fato de negócio único da transação:

| Mensagem | Fato de negócio da chave |
|---|---|
| mensagens com token | hash do token |
| troca de senha | hash novo |
| troca de fator | fator novo |
| regeneração de códigos | hash do conjunto |
| uso de código | desafio de entrada consumido (identidade e organização) ou desafio de vinculação |
| vinculação | desafio consumido |
| reset administrativo | correlação do evento, gerada pelo servidor e gravada no evento |

`ON CONFLICT DO NOTHING`: enquanto a linha existir, o mesmo evento não vira dois trabalhos. **A garantia não é permanente:** depois da limpeza da retenção, 30 dias após o encerramento, o UNIQUE deixa de impedir a reinserção daquela chave. Isso não promete entrega exatamente uma vez. Substitui a janela no Redis do desenho §6.3, com a justificativa pedida: a posse exclusiva protege um trabalho, e a chave do evento impede dois trabalhos para o mesmo evento.

### Decisões finais depois da gravação (revisão 2)

Achado da revisão 2: a gravação da intenção é um INSERT real e pode esperar locks. Nos caminhos em que ela ficava **depois** de uma reconferência temporal, a sessão, o passo TOTP ou o step-up podiam vencer durante a espera, e a transação confirmava assim mesmo.

**Regra aplicada a todos os caminhos alterados:** a intenção é gravada **antes** da última decisão temporal da transação. Qualquer recusa posterior a ela desfaz a transação inteira, inclusive a intenção.

| Caminho | Antes | Agora |
|---|---|---|
| Troca de fator (identidade) | intenção depois da reconferência de sessão e passo | intenção antes da reconferência, que fica por último |
| Troca de senha e regeneração de códigos (identidade) | intenção depois da função | intenção antes da função, cuja prova (step-up, medida depois dos locks) é a decisão final; recusa desfaz a intenção |
| Uso de código de recuperação na entrada (identidade) | intenção depois da função | intenção antes da função (chave = desafio); se a sessão não nasce, a intenção é desfeita e o desfecho segue o mesmo |
| Vinculação, etapa 1 | intenção depois da reconferência de sessão e passo | antes da reconferência |
| Vinculação, etapa 2 | intenção depois da função | antes da função; recusa desfaz |
| Confirmação da recuperação de senha (identidade) | intenção depois da função | antes da função; recusa desfaz |
| Uso de código na entrada por organização | intenção depois do consumo do desafio | antes do consumo do desafio, que confere o prazo (chave = desafio) |
| Troca de senha por organização | intenção depois da reconferência da sessão | antes da reconferência e da troca do hash; recusa desfaz |
| Reset administrativo | intenções depois da reconferência de sessão, permissão e step-up | antes delas; recusa desfaz |
| Cadastro, reemissão, pedidos de recuperação | — | sem reconferência temporal depois da gravação (a identidade ou a conta fica travada até o commit; o prazo do token começa na própria transação) |

**Reconferência da posse no despacho.** Na revisão 1, a validade e a renovação estavam num único UPDATE com `clock_timestamp()`. Se o comando esperasse o lock da linha, a condição podia ter sido avaliada antes da espera. Agora são dois comandos na mesma transação: primeiro só o lock da linha, depois, num comando novo com o relógio lido após a espera, a conferência de estado, tentativa, posse e prazo, e a renovação.

### Fila persistente (021) e despacho (`MailDispatcher`)

- **Cifra:** destinatário e tokens vão **cifrados juntos** (AES-GCM, chaves de `nekkora.keys`, AAD = `mail|job_id|key_id`). Em claro ficam só o HMAC do destinatário, a chave do evento e metadados.
- **Ciclo** (a cada 5 s):
  1. ENVIANDO com posse vencida (queda durante o envio): **INCERTO**, sem reenvio;
  2. PENDENTE com prazo vencido: **EXPIRADO**, sem envio;
  3. depois, **um trabalho por vez** (revisão 1):
     - reivindicação com `FOR UPDATE SKIP LOCKED`;
     - montagem da mensagem;
     - **reconferência imediatamente antes do envio**: ainda ENVIANDO, mesma tentativa, posse e prazo válidos pelo relógio do banco, com a posse renovada no mesmo comando;
     - envio;
     - desfecho preso à tentativa.
- **Posse perdida antes do envio:** **não envia**. O trabalho volta a PENDENTE, sabidamente não enviado, com espera de 30 s, e o ciclo termina. Nenhum trabalho fica reivindicado esperando a vez num lote.
- **Posse perdida durante o envio** (transporte mais lento que a posse): outra instância já o levou a INCERTO, e o desfecho só vai ao log (`mail.desfecho_sem_posse`).
- **Configuração da posse:** `nekkora.mail.despacho.posse`, padrão 2 min. A partida é recusada se ela for menor que 3× o tempo máximo do transporte.
- **Terminais:** o payload cifrado é **apagado**.
- **Tentativas:** RETENTÁVEL volta a PENDENTE com o **maior** entre a espera crescente (30 s, 2 min, 10 min, 30 min) e o `Retry-After`.
  - O `Retry-After` é respeitado **por inteiro**, até 7 dias. Antes, valores acima de 1 h eram ignorados.
  - Se passar do prazo da mensagem, ela expira.
  - O máximo são 5 tentativas; esgotadas, INCERTO. Um trabalho no limite não é mais reivindicado.
- **Prazo por tipo:**

  | Tipo | Prazo |
  |---|---|
  | recuperação de senha | 30 min |
  | cadastro | 24 h |
  | avisos | 24 h |
  | avisos emergenciais | 23 h (a 016 encerra a tentativa ENFILEIRADA em 24 h) |

- **Ritmo:** 25 envios por minuto por instância, um a um.
- **Avisos da recuperação emergencial (016), revisão 1:** passam pela fila persistente com a tentativa de origem (`notice_ref`). A porta devolve ENFILEIRADO na hora, sem espera síncrona. O despacho respeita o `Retry-After` e devolve o desfecho terminal à tentativa (`EmergencyNotices.recordTransportResult`):

  | Desfecho na fila | Resultado na 016 |
  |---|---|
  | ACEITO | ACEITA |
  | RECUSADO ou EXPIRADO | FALHOU |
  | INCERTO | DESCONHECIDO |

  Isso substitui a primeira versão, que limitava a espera a 2 s.
- **Retenção, com limpeza controlada:** trabalhos terminais encerrados há mais de **30 dias** são apagados em lotes de 500, a cada hora. A trigger recusa qualquer outro DELETE (pendente, enviando ou recente). A Platform tem DELETE só dentro dessa regra. Restam apenas metadados nas linhas.
- **Proteção no banco:**
  - a origem do trabalho é imutável: id, tipo, HMAC, chave do evento, referência, correlação, criação e prazo;
  - um estado terminal não volta;
  - as tentativas só aumentam;
  - o payload só pode ser apagado;
  - TRUNCATE é recusado.

**Duplicidade.** Não há entrega "exatamente uma vez".
- O que **pode ter saído** (INCERTO) nunca é reenviado automaticamente.
- O que **com certeza não saiu** (RETENTÁVEL, ou posse perdida antes do envio) é repetido.
- Enquanto a linha existir (até 30 dias depois de encerrada), o mesmo evento não gera dois trabalhos.

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
| **Intenção durável:** cadastro real grava a intenção na transação do ato (existe na resposta, sem despacho); um despachante **novo** (o reinício) a envia; o ato não se repete (um evento de pedido); fila cifrada (destinatário e token ausentes em repouso e no log); o token do link conclui o cadastro | `MailOutboxIT.intencaoGravadaComOAtoSobreviveAoReinicioSemRepetirOAto` |
| **Atomicidade:** se a intenção não pode ser gravada (trigger de falha temporária no teste), o ato não confirma: nenhuma identidade nasce | `semIntencaoGravadaOAtoNaoConfirma` |
| **Deduplicação:** a mesma chave de evento gera um trabalho | `mesmaChaveDeEventoGeraUmTrabalho` |
| **Posse antes do envio:** posse de 1 ms vencida entre a reivindicação e o envio, então não envia e volta à fila com espera; depois sai uma vez. **Contraprova:** sem a reconferência, o teste falha | `trabalhoNaoComecaDepoisDePerderAPosse` |
| **Dois despachantes, transporte lento** (1,5 s) com posse curta (1 s): todo envio começa com o trabalho ENVIANDO e a posse válida; nenhum trabalho é enviado duas vezes; todos terminam terminais | `doisDespachantesComTransporteLentoNuncaEnviamSemPosse` |
| **Retry-After do aviso emergencial:** ENFILEIRADO sem espera; 60 s e 1 h respeitados sem tentativa antes; desfecho devolvido à tentativa | `avisoEmergencialRespeitaORetryAfterEDevolveODesfecho` |
| RETENTÁVEL volta à fila com espera e depois é aceito | `retentavelVoltaAFilaEDepoisEAceito` |
| RECUSADO e INCERTO são terminais e não se repetem | `recusaEIncertezaSaoTerminaisENaoSaoRepetidas` |
| 5 tentativas esgotadas: INCERTO | `tentativasEsgotadasViramIncerto` |
| Posse vencida depois de queda: INCERTO, sem reenvio | `posseVencidaDepoisDeQuedaViraIncertoSemReenvio` |
| Prazo vencido: EXPIRADO, sem envio | `prazoVencidoExpiraSemEnviar` |
| Ritmo por minuto | `ritmoPorMinutoLimitaOsEnvios` |
| Banco: origem e terminal imutáveis; DELETE fora da retenção recusado | `bancoProtegeOrigemTerminalEApagamento` |
| **Reconferência depois da espera pelo lock** (revisão 2). Outra transação trava a linha entre a reivindicação e a reconferência e vence durante a espera: (a) a posse; (b) o prazo da mensagem. Nenhum dos dois chama o transporte. (c) Controle: lock liberado antes do vencimento, e envia. **Contraprova:** com o UPDATE único da revisão 1, (a) envia depois de vencida | `MailOutboxIT.reconferenciaDepoisDaEsperaPeloLockNaoEnviaVencidos` |
| **Troca de fator com a gravação da intenção bloqueada** (revisão 2). A sessão restrita vence durante a espera: 401 e rollback integral (fatores, códigos, marca de recuperação, demais sessões, evento 11, intenção). **Contraprova:** com a intenção depois da reconferência, a troca confirma (200) | `IdentityIntentFinalChecksIT.trocaDeFatorComSessaoVencidaDuranteAGravacaoDaIntencaoDesfazTudo` |
| **Troca de senha com a gravação bloqueada** (revisão 2). O step-up vence durante a espera e a função recusa: 403; senha, evento 12 e intenção inalterados. **Contraprova:** com a intenção depois da função, troca (204) | `IdentityIntentFinalChecksIT.trocaDeSenhaComStepUpVencidoDuranteAGravacaoDaIntencaoNaoTroca` |
| **Retenção:** terminal recente fica; terminal com mais de 30 dias sai pela limpeza; pendente fica; a Platform não apaga fora da regra | `retencaoApagaSoTerminaisAntigos` |
| Modelos: todo tipo, link na origem com token no fragmento, avisos sem link | `MailTemplatesTest` |

**Suíte de banco** `db/tests/correio-021`:

| Caso | Resultado |
|---|---|
| A, instalação limpa 001–021 | a 021 é aplicada **sem** janela (`elevada=nao`); invariantes de 012 a 021 depois da retirada; marcador `versao=021` |
| B2, base populada 020 → 021 (80 tabelas) | dados idênticos; catálogo com exatamente 113 linhas previstas, todas acréscimos de `mail_outbox` e da sua proteção (inclui a chave do evento, a referência da tentativa e o índice de retenção); igual à instalação limpa |
| B3, reaplicação | recusada |

**Regressão completa, E2E e CI:** registradas no PR.

## 4. Limitações e divergências declaradas

- **Homologação real não executada:** bloqueio externo (§5). O transporte foi provado contra o Entra e o Graph **falsos**. A chamada real, o recebimento e os cabeçalhos de autenticação dependem das ações autorizadas.
- **Deduplicação:** o desenho §6.3 previa uma janela no Redis. Ela foi substituída por uma chave estável do evento, UNIQUE no banco (§2). Mudança para conferência da responsável.
- **Retenção:** 30 dias depois de encerrado, com limpeza em lotes. O prazo é proposta desta revisão, para conferência.
- **Posse perdida antes do envio, com a varredura chegando primeiro:** se outra instância encerrar o trabalho como INCERTO antes da reconferência, ele não é enviado e fica INCERTO, mesmo sabidamente não enviado. Isso exige que esta instância trave por mais que a posse inteira entre a reivindicação e o envio. A posse padrão (2 min) e a partida recusada abaixo de 3× o tempo do transporte tornam o caso excepcional, e o log registra `mail.envio_cancelado_sem_posse`.
- **Ritmo por instância:** com mais de uma instância da Platform, a soma dos ritmos precisa caber em 30 por minuto. Isso é configuração, não coordenação.
- **Avisos ainda inexistentes:** a recuperação de senha **por organização** não envia aviso de senha trocada na confirmação. É um fluxo antigo, mantido como está.

## 5. Ações externas para autorização

Nenhuma foi executada. A ordem é obrigatória.

### 0. Reconciliação do tenant, antes de qualquer alteração (revisão 1)

**Divergência apontada na revisão.** Este relatório usa o tenant `facfe491-5e10-4f17-bb17-7c2115baea8f`, que é o `Guid` de `Get-OrganizationConfig` na conferência de 2026-10-11. Uma captura do Entra enviada anteriormente mostrava `ae4ed48d-e7d8-4f24-83f2-443e997c5a65`.

Pode haver explicação legítima: `Get-OrganizationConfig.Guid` é o identificador da organização no Exchange Online e **não** é necessariamente o Tenant ID do Entra. Mas isso só se resolve lendo o tenant atual. O nome `radiantsai` sozinho não identifica o tenant. **Nenhuma ação das seções A a F deve ser executada antes disso.**

Leituras, sem nenhuma alteração:

| # | Leitura | Onde |
|---|---|---|
| R-1 | Tenant ID e nome do tenant | Entra ID → Visão geral; ou `Connect-MgGraph -Scopes Organization.Read.All; Get-MgOrganization \| Format-List Id, DisplayName, VerifiedDomains` |
| R-2 | Organização do Exchange conectada | `Get-ConnectionInformation \| Format-List TenantID, Organization, UserPrincipalName` (Exchange Online PowerShell) |
| R-3 | `nekkora.com` verificado e `Authoritative` **nesse** tenant | `Get-AcceptedDomain nekkora.com \| Format-List DomainName, DomainType`; e `nekkora.com` em `VerifiedDomains` de R-1 |
| R-4 | A caixa `no-reply@nekkora.com` **nesse** tenant | `Get-Recipient no-reply@nekkora.com \| Format-List Name, RecipientTypeDetails, PrimarySmtpAddress, ExternalDirectoryObjectId` |
| R-5 | DKIM do domínio **nesse** tenant | `Get-DkimSigningConfig -Identity nekkora.com \| Format-List Enabled, Selector1CNAME, Selector2CNAME` |

**Resultado exigido:**
- **R-1 e R-2 apontam para o mesmo Tenant ID**, e o domínio, a caixa e o DKIM estão nele. Esse Tenant ID passa a ser o de `NEKKORA_M365_TENANT` e o do aplicativo de D1; o relatório é atualizado com ele.
- **R-1 e R-2 divergem**, ou `nekkora.com` está em outro tenant: a missão para aqui e volta para decisão.

### A. `dmarc@nekkora.com` (L-8), antes do registro DMARC

| Item | Valor |
|---|---|
| Destino | Exchange Online do tenant confirmado na seção 0 (R-1 e R-2) |
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
