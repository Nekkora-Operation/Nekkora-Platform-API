# E-mail transacional com `no-reply@nekkora.com`: desenho (Missão 2)

**ESTADO: PROPOSTA — AGUARDA DECISÃO DA RESPONSÁVEL.** Nenhuma alteração de código, configuração, DNS ou tenant foi feita. Nenhum stage, commit ou push.

**ESTADO: DIREÇÃO APROVADA COM CONDIÇÕES (2026-10-11). IMPLEMENTAÇÃO NÃO AUTORIZADA.** A conferência somente de leitura da §1.2 foi executada pela responsável e o resultado está na §1.3. Nada no Microsoft 365, na Cloudflare, no código ou na configuração foi alterado por esta sessão.

| Condição da responsável | Onde ficou |
|---|---|
| Graph com permissão de aplicação, reutilizando `no-reply@nekkora.com` | DC-M2.2 e DC-M2.1, ratificada |
| RBAC para Aplicações, envio só pela `no-reply`, com teste negativo | DC-M2.3, reescrita |
| **Evitar a concessão ampla: permissões do Entra e do RBAC se somam** | **DC-M2.3, §3.3.1 nova** |
| Certificado, chave privada fora do repositório, procedimento de renovação | DC-M2.4, condição acrescentada |
| DMARC em `p=none` com relatórios em `dmarc@nekkora.com` | DC-M2.5, endereço fixado |

**Marca e remetente:** produto, domínio e remetente são **Nekkora**, com `no-reply@nekkora.com` (decisão da responsável em 2026-10-11).

**Data:** 2026-10-11.

**Objetivo da missão:** configurar o remetente no Microsoft 365 e integrá-lo ao envio de e-mails do backend.

**Evidências de DNS:** coleta própria por `dig @1.1.1.1` em 2026-10-11T04:59Z, registrada na §1. Nada no tenant foi consultado: exige credencial administrativa que esta sessão não tem.

---

## 1. Estado de `nekkora.com`: o que o DNS público mostra

| Registro | Valor encontrado | Leitura |
|---|---|---|
| `NS` | `bethany.ns.cloudflare.com`, `micah.ns.cloudflare.com` | DNS autoritativo na **Cloudflare**. É ali que os registros desta missão entram. |
| `MX` | `0 nekkora-com.mail.protection.outlook.com` | Recebimento no **Exchange Online**. Domínio já roteado para o Microsoft 365. |
| `TXT` | `v=spf1 include:spf.protection.outlook.com ~all` | **SPF já publicado** e correto para envio pelo Microsoft 365. |
| `TXT` | `MS=ms38126768` | **Registro de verificação de propriedade publicado no DNS.** A §1.3 confirma que `nekkora.com` está configurado no tenant; falta só confirmar `Authoritative` (P-1). |
| `CNAME selector1._domainkey` | `selector1-nekkora-com._domainkey.radiantsai.n-v1.dkim.mail.microsoft` | **CNAME de DKIM publicado e conferido** contra o valor que o Microsoft 365 fornece: **igual** (§1.3). Não se mexe nele. |
| `CNAME selector2._domainkey` | `selector2-nekkora-com._domainkey.radiantsai.n-v1.dkim.mail.microsoft` | idem |
| `CNAME autodiscover` | `autodiscover.outlook.com` | Autodiscover apontado para o Microsoft 365. |
| `TXT _dmarc` | **ausente** | **Lacuna.** Sem DMARC, SPF e DKIM não têm política nem relatório. |
| `TXT _mta-sts`, `TXT _smtp._tls` | ausentes | Lacuna menor: sem MTA-STS e sem relatório de TLS. |
| `A`/`AAAA` do ápice | ausentes | O domínio não serve web hoje. Não afeta o envio. |

**Conclusão do que o DNS mostra:** o domínio aponta para o Microsoft 365 para **receber**; SPF está publicado; dois CNAME de DKIM estão publicados; DMARC falta.

### 1.1 O que esta coleta de DNS NÃO prova

Correção de uma conclusão indevida da primeira versão deste documento. Os três itens abaixo foram afirmados além da evidência e ficam registrados como **pendentes**:

| Afirmação da primeira versão | O que a evidência sustenta de fato |
|---|---|
| "propriedade já verificada no tenant" | existe um registro `MS=…` **publicado no DNS**. O estado da verificação dentro do tenant não foi consultado. |
| "o tenant é `radiantsai`" | o destino do CNAME de DKIM **contém o rótulo** `radiantsai`. Rótulo em destino de DNS não identifica tenant. |
| "DKIM publicado" (como se resolvido) | os CNAME existem. Se eles apontarem para destinos de **outra** estrutura, a assinatura de `nekkora.com` não valida — e o erro seria invisível no DNS. |

O risco concreto: configurar o remetente no tenant errado, ou manter apontamento de DKIM de uma estrutura antiga. Os dois produzem uma configuração que parece pronta e não autentica.

### 1.2 Conferência somente de leitura, antes de qualquer configuração

Pedido da responsável em 2026-10-11. **Nenhuma alteração** no Microsoft 365, na Cloudflare, no código ou na configuração até que o resultado desta conferência esteja registrado. Ela é o que identifica **onde** o remetente deve ser configurado.

**No Microsoft 365 da Nekkora** (portal de administração ou Exchange Online PowerShell, tudo leitura):

| # | O que obter | Onde |
|---|---|---|
| M-1 | **Tenant ID** e nome do tenant | Microsoft Entra ID → Visão geral; ou `Get-MgContext` / `Get-MgOrganization` |
| M-2 | `nekkora.com` presente, **verificado** e `Authoritative` | Configurações → Domínios; `Get-AcceptedDomain nekkora.com` |
| M-3 | **Destinos de DKIM que o Microsoft 365 fornece para `nekkora.com`** — os dois nomes de CNAME e os dois destinos, como o portal os apresenta | Defender → Políticas → DKIM; `Get-DkimSigningConfig -Identity nekkora.com \| Format-List *Cname*, Enabled, Selector*` |
| M-4 | Assinatura DKIM ligada ou desligada (`Enabled`) | mesma origem de M-3 |
| M-5 | Domínio inicial `*.onmicrosoft.com` do tenant | Configurações → Domínios |
| M-6 | `no-reply@nekkora.com` existe? de que tipo? | `Get-Mailbox no-reply@nekkora.com`; `Get-Recipient no-reply@nekkora.com` |

**Na Cloudflare** (zona `nekkora.com`, tudo leitura):

| # | O que obter |
|---|---|
| C-1 | registros da zona: `MX`, `TXT` do ápice, `TXT _dmarc`, os dois `CNAME _domainkey`, `CNAME autodiscover` |
| C-2 | para cada registro: valor, estado de proxy e data da última alteração, se a conta expuser |
| C-3 | se há mais de uma zona `nekkora.com`, ou zona de domínio parecido, que possa explicar divergência |

**Comparação, que é o objetivo da conferência:**

| Comparação | Conclusão a registrar |
|---|---|
| M-3 **igual** a C-1 | os CNAME publicados são os do tenant correto. `radiantsai` é identificador técnico da estrutura atual e **não** se mexe neles. |
| M-3 **diferente** de C-1 | os CNAME publicados são apontamento a corrigir. A correção usa **os valores de M-3**, exatamente como o Microsoft 365 os fornece. |
| M-2 não verificado ou não autoritativo | a premissa de recebimento e envio cai; a missão para e volta para decisão. |

**Regra que vale em qualquer resultado:** `radiantsai` **não** é substituído por `nekkora` à mão nesses destinos. Destino de DKIM não é texto editável — é o valor que o Microsoft 365 emite para aquele domínio naquele tenant. Trocar o rótulo à mão quebra a assinatura silenciosamente.

A terceira pergunta da responsável — se `radiantsai` é identificador técnico da estrutura atual ou apontamento antigo a corrigir — é respondida **exatamente** pela comparação entre M-3 e C-1, e por M-5. Não é respondível pelo DNS público, e não está respondida neste documento.

### 1.3 Resultado da conferência (2026-10-11)

Executada pela responsável no Exchange Online PowerShell, no macOS. Saída registrada:

```
Get-OrganizationConfig | Format-List Name, Guid
  Name : radiantsai.onmicrosoft.com
  Guid : facfe491-5e10-4f17-bb17-7c2115baea8f

Get-DkimSigningConfig -Identity nekkora.com | Format-List Domain, Enabled, Selector1CNAME, Selector2CNAME
  Domain         : nekkora.com
  Enabled        : True
  Selector1CNAME : selector1-nekkora-com._domainkey.radiantsai.n-v1.dkim.mail.microsoft
  Selector2CNAME : selector2-nekkora-com._domainkey.radiantsai.n-v1.dkim.mail.microsoft
```

**Comparação M-3 × C-1:** os dois destinos que o Microsoft 365 fornece são **idênticos**, caractere por caractere, aos publicados na Cloudflare (coleta autoritativa de 2026-10-11T06:35Z, `dig @bethany.ns.cloudflare.com +norecurse`).

**Resposta à pergunta sobre `radiantsai`:** é **identificador técnico da estrutura atual**, não apontamento antigo. O domínio inicial do tenant é `radiantsai.onmicrosoft.com`, e o Microsoft 365 **deriva** o destino do CNAME de DKIM desse domínio inicial. O rótulo vai aparecer ali sempre, por construção. **Nada a corrigir, nada a editar.**

O que isso **não** afeta, e é o ponto que importa para o produto:

| Item | Valor | Visível ao destinatário? |
|---|---|---|
| Remetente | `no-reply@nekkora.com` | sim |
| Domínio da assinatura DKIM | `d=nekkora.com` | sim, no cabeçalho |
| Domínio do SPF | `nekkora.com` | sim, no cabeçalho |
| Destino do CNAME de DKIM | contém `radiantsai` | **não**. É encanamento de DNS, nunca viaja na mensagem |
| Domínio inicial do tenant | `radiantsai.onmicrosoft.com` | não |

O domínio inicial `*.onmicrosoft.com` de um tenant **não é renomeável**: trocá-lo exigiria tenant novo e migração completa. O **nome de exibição** da organização no Microsoft Entra, esse sim, pode ser alterado para Nekkora, e é alteração independente desta missão. Nenhuma das duas coisas muda o remetente, a assinatura ou qualquer cabeçalho que o destinatário vê.

**Lacunas fechadas por esta conferência:** L-0a (tenant identificado: `radiantsai.onmicrosoft.com`, `facfe491-5e10-4f17-bb17-7c2115baea8f`), L-0c (destinos conferidos e iguais) e L-2 (`Enabled : True`, assinatura DKIM ligada).

**L-3 fechada por informação da responsável**, não por verificação desta sessão: a caixa compartilhada `no-reply@nekkora.com` já existe. O tipo da caixa fica como P-2.

**Leituras complementares, também executadas pela responsável em 2026-10-11:**

```
Get-AcceptedDomain nekkora.com | Format-List DomainName, DomainType
  DomainName : nekkora.com
  DomainType : Authoritative

Get-Recipient no-reply@nekkora.com | Format-List Name, RecipientTypeDetails, PrimarySmtpAddress
  Name                 : no-reply20261011063234
  RecipientTypeDetails : SharedMailbox
  PrimarySmtpAddress   : no-reply@nekkora.com
```

**P-1 fechada:** `Authoritative`. O Exchange Online é a autoridade do domínio, que é a condição que o desenho supõe. Não é `InternalRelay`.

**P-2 fechada:** `SharedMailbox`, no endereço certo. DC-M2.1 passa a ser ratificação do que já existe, não criação.

**Achado F-1, do nome da caixa — conferido e resolvido.** O `Name` é `no-reply20261011063234`, gerado automaticamente com data e hora da criação, mas `Name` é identificador interno e não viaja na mensagem. A leitura do campo vizinho confirmou `DisplayName : no-reply`, então **não havia defeito**: o destinatário nunca veria o sufixo. A responsável decidiu usar `Nekkora` como nome de exibição (DC-M2.1), o que é melhoria de apresentação, não correção de erro.

---

## 2. Lacunas a fechar

Estado em 2026-10-11, depois da conferência da §1.3.

| # | Lacuna | Estado | Onde se resolve |
|---|---|---|---|
| L-0a | Tenant não identificado | **fechada** (§1.3) | — |
| L-0b | Verificação de `nekkora.com` no tenant | **fechada**: `Authoritative` (§1.3) | — |
| L-0c | Destinos de DKIM não conferidos | **fechada**: idênticos aos fornecidos | — |
| **L-1** | **DMARC ausente** | **aberta** | Cloudflare, registro TXT novo |
| L-2 | Assinatura DKIM não ligada | **fechada**: `Enabled : True` | — |
| L-3 | Caixa do remetente inexistente | **fechada**: existe e é `SharedMailbox` (§1.3) | — |
| **L-4** | **Nenhum mecanismo de envio no backend** além de local e homologação | **aberta** | código, §5 |
| L-5 | MTA-STS e TLS-RPT ausentes | aberta, baixa | Cloudflare, depois do DMARC |
| L-6 | `~all` no SPF em vez de `-all` | aberta, baixa | só depois de observar relatórios de DMARC |
| L-7 | Nome de exibição do remetente | **fechada** como risco: era `no-reply`, sem o sufixo. Resta aplicar `Nekkora` por decisão de apresentação | `Set-Mailbox` (DC-M2.1) |
| **L-8** | **`dmarc@nekkora.com` ainda proposto**, sem confirmação de criação nem de recebimento. Precisa receber **antes** do registro `_dmarc` entrar, senão os relatórios voltam | **aberta** | Microsoft 365 (DC-M2.5) |

Sobram **duas lacunas de peso**, DMARC e o mecanismo de envio no backend, mais a **L-8**, sem a qual a fase de observação do DMARC não produz nada.

---

## 3. Decisões propostas

### DC-M2.1 — Recurso do remetente: caixa compartilhada **[esta é a ratificação restante]**

**Resposta à pergunta da responsável sobre a contagem.** Oito decisões carregavam a marca `[precisa de aprovação]`: DC-17.1, DC-17.2, DC-17.3, DC-M2.1, DC-M2.2, DC-M2.3, DC-M2.4 e DC-M2.5. **DC-M2.1 é a ratificação restante**, porque a caixa compartilhada já existe no endereço certo (§1.3): não há escolha a fazer, só registrar que o que foi feito é o que o desenho pedia. Sobraram as **sete** escolhas que a mensagem enumerou. A contagem correta é sete escolhas mais uma ratificação.

**Recomendado:** caixa **compartilhada** `no-reply@nekkora.com`.

Motivo: caixa compartilhada não consome licença, **não tem senha** e não aceita entrada interativa. O remetente de serviço deixa de ser uma credencial de pessoa, que é o principal risco de um `no-reply` montado como usuário comum. Configurar para rejeitar a entrada de mensagens, já que ninguém lê a caixa.

**Estado em 2026-10-11:** **já atendida.** A caixa `no-reply@nekkora.com` existe e é `SharedMailbox` (§1.3). Esta decisão é a ratificação do que já está feito.

**Nome de exibição (L-7), conferido e decidido em 2026-10-11:** o `DisplayName` é `no-reply`; o sufixo numérico está **somente** no campo `Name`, que é interno e não viaja na mensagem. Não havia defeito. Decisão da responsável: usar **`Nekkora`** como nome de exibição, mantendo o endereço `no-reply@nekkora.com`. O destinatário passa a ver `Nekkora <no-reply@nekkora.com>`.

```powershell
Set-Mailbox no-reply@nekkora.com -DisplayName "Nekkora"
```

Resta configurar a caixa para rejeitar entrada de mensagens, já que ninguém a lê.

**Alternativas consideradas e por que não:**

| Opção | Por que não |
|---|---|
| Caixa de usuário licenciada | consome licença e cria uma senha a proteger, sem ganho nenhum para um remetente de serviço |
| SMTP AUTH com OAuth2 (XOAUTH2) | a Microsoft vem desligando o SMTP AUTH e a submissão SMTP de cliente é a via mais limitada em taxa; funciona, mas é o caminho que o fornecedor está encerrando |
| High Volume Email (HVE) | foi feito para volume **interno**; não é o caminho para transacional a destinatário externo |
| Azure Communication Services Email | é a resposta da Microsoft para transacional de volume, porém **não é Microsoft 365** e exige configuração de domínio própria. Fica registrado como saída se o volume passar dos limites do Exchange Online (§7), não como a escolha desta missão, que pede Microsoft 365 |

### DC-M2.2 — Mecanismo de envio: Microsoft Graph, só aplicação **[decidida em 2026-10-11]**

**Decidido pela responsável em 2026-10-11:** Microsoft Graph com permissão de aplicação, **reutilizando a caixa `no-reply@nekkora.com` que já existe**.

`POST https://graph.microsoft.com/v1.0/users/no-reply@nekkora.com/sendMail`, com token **só de aplicação** (client credentials) obtido no Entra ID, aplicativo de **um único tenant**.

- `saveToSentItems: false` — nada acumula numa caixa que ninguém lê.
- Sem nenhuma permissão delegada: não há usuário no fluxo.
- `202 Accepted` do Graph é **aceite pelo transporte**, nunca prova de entrega. Esse já é o contrato da porta `MailSender.Outcome` e a §6 o mantém.

### DC-M2.3 — Permissão mínima e seu escopo **[decidida em 2026-10-11]**

**Decidido pela responsável em 2026-10-11:** RBAC para Aplicações, envio permitido somente pela `no-reply`, com teste negativo para outra caixa.

A permissão de aplicação `Mail.Send` do Graph, sozinha, permite enviar **como qualquer caixa do tenant**. Conceder isso sem escopo é o pior resultado possível desta missão.

#### 3.3.1 Concessões somadas, e a correção de 2026-10-11

**O alerta da responsável estava certo e a minha formulação estava errada em dois pontos.** Ambos corrigidos aqui, antes da ratificação, não deixados para a execução.

**Correção 1 — o Graph NÃO exige `Mail.Send` consentido no Entra neste modelo.** A revisão anterior afirmava que o token só carregaria a autorização pelo consentimento no Entra e que, por isso, a permissão ampla era inevitável. **Isso está incorreto.** O RBAC para Aplicações do Exchange Online concede autorização **independentemente** das permissões do Entra, e a orientação da Microsoft é **remover** o consentimento no Entra ao migrar para RBAC. Eu havia declarado esse ponto como não verificado e, ainda assim, construí o desenho sobre a hipótese errada. A consequência é boa: a configuração correta é estritamente mais segura do que a que eu havia proposto.

**Correção 2 — ausência de política clássica não concede acesso.** A revisão anterior dizia que "a ausência de política já equivale a acesso amplo". Impreciso. A ausência de política **não concede nada por si só**: ela deixa **irrestrita** uma permissão ampla que tenha sido concedida no Entra. Sem concessão no Entra, ausência de política não abre nada. A distinção importa porque é justamente ela que torna o desenho abaixo seguro.

**Desenho corrigido, que é o aprovado:**

| Item | Definição |
|---|---|
| Aplicativo | **dedicado**, criado só para este envio. Nunca reutilizado |
| Autenticação | **certificado** (DC-M2.4) |
| Autorização | papel **`Application Mail.Send`** do Exchange Online, por RBAC para Aplicações, com escopo de gerenciamento restrito à caixa `no-reply@nekkora.com` |
| Entra | **nenhuma concessão de `Mail.Send`**. Nenhuma permissão de aplicação do Graph consentida para este aplicativo |
| Política clássica de acesso de aplicativo | **não usada**. Ela existe para restringir concessão do Entra; sem concessão do Entra, não há o que restringir |

**Por que isso elimina o problema da soma.** O acesso efetivo é a união dos caminhos de concessão. Com **um único** caminho, o do RBAC restrito, não há segundo termo na união. A permissão ampla nunca é concedida, então nunca precisa ser contida.

**Verificação, por escrito, antes do primeiro envio:**

```powershell
# 1. o caminho de RBAC existe e o escopo e a caixa unica
Get-ManagementRoleAssignment -App <appId> | Format-List Name, Role, CustomResourceScope, RecipientReadScope

# 2. nao existe NENHUM outro caminho de RBAC para este aplicativo
#    (a lista acima tem de ter exatamente uma atribuicao)

# 3. nao existe politica classica para este aplicativo (esperado: vazio)
Get-ApplicationAccessPolicy | Where-Object AppId -eq <appId>

# 4. no portal do Entra, em Permissoes de API do aplicativo: NENHUMA permissao de aplicacao
#    do Microsoft Graph consentida. Se houver Mail.Send consentido, REMOVER o consentimento.
```

**Invariante a registrar:** este aplicativo tem **exatamente um** caminho de autorização, o do RBAC, e esse caminho resolve para `no-reply@nekkora.com`. Nenhuma permissão de aplicação do Graph consentida no Entra.

**Prova obrigatória, com chamadas reais:**

| Teste | Resultado exigido |
|---|---|
| teste de autorização do Exchange para a caixa autorizada | permitido |
| teste de autorização do Exchange para outra caixa do tenant | negado |
| chamada real ao Graph enviando pela `no-reply@nekkora.com` | **envio aceito** |
| chamada real ao Graph enviando por outra caixa do tenant | **recusado** |

As duas últimas linhas são a prova que vale. Teste de autorização confere a configuração; só a chamada real confere o efeito. **Sem as quatro passando, o transporte não é ligado.**

### DC-M2.4 — Credencial: certificado **[decidida em 2026-10-11]**

**Recomendado:** credencial de **certificado** no aplicativo, em vez de segredo de cliente. Nenhum segredo compartilhado trafega na obtenção do token, e a chave privada pode viver no cofre sem nunca aparecer em variável de ambiente legível.

**Decidido pela responsável em 2026-10-11:** certificado, com chave privada fora do repositório e procedimento de renovação.

**Em qualquer caso, a regra do repositório não muda:** segredo só por ambiente ou serviço de segredos, nada versionado, como já está declarado no cabeçalho de `api/nekkora-platform/src/main/resources/application.yml`.

**Procedimento de renovação, pedido pela responsável:**

| Item | Definição |
|---|---|
| Validade | 1 ano. Mais curto multiplica a operação; mais longo esconde o problema |
| Guarda da chave privada | cofre, **nunca** no repositório, nunca em imagem de container, nunca em variável de ambiente com o material em claro |
| Sobreposição | o par novo é adicionado ao aplicativo **antes** de o antigo ser removido. O Entra aceita mais de uma credencial, então a troca é sem janela de indisponibilidade |
| Ordem | gerar o par novo, carregar no aplicativo, publicar a referência nova, confirmar um envio real bem-sucedido, **só então** remover o antigo |
| Aviso de vencimento | alarme em 60 e em 30 dias antes. Certificado vencido sem aviso derruba confirmação de cadastro e avisos de MFA ao mesmo tempo |
| Registro | cada troca deixa data, impressão digital do certificado novo e quem executou. Nunca a chave privada |

O vencimento silencioso é o risco real desta decisão, e é por isso que o alarme faz parte dela, não da operação.

Configuração nova, toda com valor vindo do ambiente:

```
nekkora.mail.transport          = m365                (ao lado de local e homologacao)
nekkora.mail.remetente          = ${NEKKORA_MAIL_REMETENTE}
nekkora.mail.m365.tenant-id     = ${NEKKORA_M365_TENANT}
nekkora.mail.m365.client-id     = ${NEKKORA_M365_CLIENT}
nekkora.mail.m365.certificado   = ${NEKKORA_M365_CERT}        (caminho ou referência de cofre)
nekkora.mail.m365.timeout       = 5s
```

O token de acesso fica **só em memória**, renovado antes do vencimento, e **nunca** vai para log, resposta ou banco.

### DC-M2.5 — Registros de autenticação do domínio **[decidida em 2026-10-11]**

Entram na Cloudflare, nesta ordem:

1. **DMARC, fase de observação** — primeiro registro a criar:
   ```
   _dmarc.nekkora.com  TXT  "v=DMARC1; p=none; rua=mailto:dmarc@nekkora.com; fo=1; pct=100"
   ```
   Alinhamento **relaxado** (o padrão) nesta fase, de propósito: começar em `adkim=s; aspf=s` arrisca reprovar envio legítimo antes de haver relatório para enxergar o problema.

   **Endereço decidido pela responsável em 2026-10-11:** `dmarc@nekkora.com`, a criar ou configurar como endereço de recebimento acompanhado. Três observações que vêm com essa escolha:

   - o endereço **precisa existir e receber** antes de o registro entrar, senão os relatórios voltam e ninguém vê nada;
   - por estar no **mesmo domínio** do registro, não é necessário nenhum registro de autorização de destino externo. Se um dia o destino mudar para outro domínio, aí sim passa a ser necessário;
   - os relatórios são XML diário de cada provedor receptor e chegam em volume. Caixa compartilhada acompanhada por alguém, ou um serviço de leitura, é o que torna a fase de observação útil. Relatório que ninguém abre não habilita o endurecimento para `quarantine`.

2. **DKIM** — depende de L-0c. Se a comparação M-3 × C-1 der **igual**, não há registro novo: falta só ligar a assinatura no tenant (L-2) e conferir que a mensagem sai com `d=nekkora.com`. Se der **diferente**, os dois CNAME são republicados **com os valores que o Microsoft 365 fornecer**, sem nenhuma edição manual do destino.

3. **SPF** — fica como está. `include:spf.protection.outlook.com` já cobre o envio pelo Graph, porque a saída é pelo próprio Exchange Online.

4. **Endurecimento, só depois de observar relatórios** — `p=quarantine` e então `p=reject`; `~all` → `-all`; e, se desejado, MTA-STS com TLS-RPT (L-5).

A caixa que recebe `rua=` precisa existir e ser lida por alguém. Qual endereço recebe os relatórios é parte desta decisão.

### DC-M2.6 — MFA continua no aplicativo autenticador

Registrado como a responsável determinou: os códigos TOTP ficam no aplicativo autenticador. O e-mail transporta **avisos** de cadastro, redefinição e confirmação ou contestação. **Código por e-mail como segundo fator não entra nesta missão** e seria decisão própria.

---

## 4. O que o e-mail passa a transportar

| `MailSender.Kind` | Situação | Leva token? | Quando |
|---|---|---|---|
| `SIGNUP_CONFIRMATION` | **novo** (Missão 1) | sim, no fragmento | nesta missão |
| `SIGNUP_EXISTING_ACCOUNT_NOTICE` | **novo** (Missão 1) | não | nesta missão |
| `NOTICE_PASSWORD_CHANGED` | existe | não | nesta missão |
| `NOTICE_RECOVERY_CODE_USED` | existe | não | nesta missão |
| `NOTICE_MFA_RESET`, `NOTICE_MFA_RESET_ADMIN_TARGET` | existem | não | nesta missão |
| `NOTICE_MFA_EMERGENCY_*` | existem (016) | sim, dois | transporte pronto; a liberação segue o gate da 016, não esta missão |
| `PASSWORD_RECOVERY` | existe | sim | **transporte pronto**, conteúdo em missão posterior |
| Convite | não existe | sim | **transporte pronto**, fluxo em missão posterior |

**Token sempre no fragmento da URL, nunca na query.** O fragmento não vai para log de servidor nem para o cabeçalho `Referer`. É a disciplina que os avisos da 016 já seguem, e o link de confirmação de cadastro a herda: a página estática lê o fragmento e envia o token por `POST /api/v1/signup/confirm`. Abrir o link, por si, não confirma nada.

Todo link aponta para a **origem pública configurada** (`nekkora.public-origin`). Nenhum conteúdo de e-mail monta URL a partir de cabeçalho da requisição.

---

## 5. Integração no backend

A porta `MailSender` **não muda de forma**. Entra uma implementação nova, escolhida por configuração, exatamente como o transporte de homologação foi acrescentado:

```
nekkora.mail.transport = local        -> LocalMailSender          (padrão, só registra a intenção)
                       = homologacao  -> CaixaHomologacaoMailSender (ensaio-*, simulado)
                       = m365         -> GraphM365MailSender        (novo)
```

`PlatformConfig.mailSender` ganha o terceiro caso e as validações de partida que o transporte de homologação já tem como precedente: sem tenant, sem client, sem certificado ou sem remetente, a Platform **não sobe**. Falhar na partida é melhor do que descobrir em produção que o e-mail nunca saiu.

`simulated()` devolve `false` no transporte novo — é o que separa, no banco e no log, um aceite real de um simulado.

---

## 6. Falhas, tentativas e duplicidade

### 6.1 Mapeamento dos desfechos

A porta já distingue quatro desfechos e a semântica deles é mantida sem invenção:

| Resultado do Graph | `Outcome` |
|---|---|
| `202 Accepted` | `ACEITO_PELO_TRANSPORTE` — aceite, **não** entrega |
| `400`, `401`, `403`, `404` | `RECUSADO_DEFINITIVO` — erro de configuração ou permissão |
| `429`, `503`, `504` | nova tentativa; esgotadas, `INCERTO` |
| timeout, conexão cortada, queda após envio | `INCERTO` — pode ter saído |

### 6.2 Tentativas

- **Repetir só** em `429`, `503`, `504` e falha de rede. `429` respeita o `Retry-After` da resposta.
- **Nunca repetir** `400`/`401`/`403`: repetir erro de configuração só multiplica o erro.
- No máximo 3 tentativas, com espera crescente e teto de poucos segundos — o envio acontece **depois do commit**, fora da transação, e não pode prender a requisição.
- `INCERTO` **não** é repetido automaticamente: a mensagem pode ter saído, e repetir é justamente a duplicidade a evitar. Fica registrado para decisão operacional.

### 6.3 Duplicidade

O Graph não oferece chave de idempotência. Então:

1. **Janela curta no Redis:** chave `nk:mail:<kind>:<hmac do destinatário>:<sha256 do token>`, com `SET NX` e expiração de 10 min. Um reenvio lógico dentro da janela não vira segunda mensagem.
2. **Registro de tentativa para auditoria:** tabela `mail_attempts` (migration `018`, desta missão): `attempt_id`, `kind`, `recipient_hmac`, `outcome`, `provider_status`, `correlation_id`, `created_at`, append-only. Sem destinatário em claro, sem token, sem hash de token.
3. A confirmação de cadastro é de **uso único** e o reenvio **revoga** a anterior (Missão 1, §6.2). Uma mensagem duplicada é, no pior caso, incômodo — nunca um segundo token válido.

### 6.4 O que o log registra

`kind`, `attempt_id`, desfecho, status do provedor, `correlation_id` e destinatário **só como HMAC**. Nunca token, nunca link, nunca endereço em claro, nunca a credencial ou o token de acesso. É a regra que `LocalMailSender` e `CaixaHomologacaoMailSender` já cumprem, e o transporte novo não abre exceção.

---

## 7. Limites de taxa do Exchange Online

Enviar transacional por Exchange Online tem teto por mensagens, destinatários e taxa, e esses números variam por plano e mudam com o tempo. A execução precisa **confirmá-los na documentação vigente para o plano deste tenant** e registrar o valor conferido — não vale repetir número de memória.

O que o desenho já acomoda:

- o volume de confirmação de cadastro cresce com o cadastro público, que é exatamente o que a Missão 1 liga;
- se o teto for alcançado, a saída registrada em DC-M2.1 é o Azure Communication Services Email, **sem alterar a porta `MailSender`** — só mais um valor de `nekkora.mail.transport`;
- `429` com `Retry-After` já é tratado como estrangulamento, não como erro.

---

## 8. Validação

Separada em duas colunas que **não** se confundem, conforme a missão pede.

### 8.1 Aceite pelo provedor

- `202` do Graph, registrado com `attempt_id` e `correlation_id`;
- **caminho único de autorização** comprovado por escrito: uma atribuição de RBAC, escopo na caixa única, nenhuma política clássica e **nenhuma** permissão de aplicação do Graph consentida no Entra (§3.3.1);
- **chamadas reais** com o token do aplicativo: envio pela `no-reply` aceito, envio por outra caixa do tenant recusado;
- partida da Platform recusada quando falta qualquer item de configuração.

### 8.2 Entrega comprovada

Numa **caixa controlada** (endereço a definir em DC-M2.5):

| Verificação | Como |
|---|---|
| Recebimento | mensagem presente na caixa controlada |
| Autenticação | cabeçalho `Authentication-Results` com `spf=pass`, `dkim=pass` e `dmarc=pass` |
| Assinatura do domínio certo | `DKIM-Signature` com `d=nekkora.com` (não o domínio `onmicrosoft.com`) |
| Remetente | `From: no-reply@nekkora.com`; `Reply-To` ausente ou apontando a endereço válido |
| Destinatário | exatamente um, o esperado; nenhum `Cc`/`Bcc` |
| Link | aponta para a origem pública; token no **fragmento**; abrir o link não confirma nada; o token funciona **uma** vez e a segunda tentativa falha |
| Conteúdo | sem segredo, sem código TOTP, sem dado de outra organização |
| Registro no provedor | `Get-MessageTrace` mostrando o trânsito |
| Fora da caixa controlada | um envio a destinatário externo controlado, para confirmar que DMARC em `p=none` não produz reprovação |

Um `202` **sem** a linha de `Authentication-Results` conferida não encerra a validação. Essa separação é o item central desta missão.

---

## 9. Ordem de trabalho

**Passo 1 — a única atividade liberada hoje:**

1. **Conferência somente de leitura da §1.2** (M-1 a M-6 no Microsoft 365, C-1 a C-3 na Cloudflare) e registro do resultado, incluindo a resposta sobre `radiantsai`. Nada é alterado neste passo. O resultado volta para decisão da responsável.

**Passos seguintes, só após o passo 1 e a aprovação dos DC-M2.x:**

2. ~~Corrigir os CNAME de DKIM~~: **desnecessário** (§1.3, destinos idênticos).
3. Conferir e corrigir o `DisplayName` do remetente (L-7).
4. Criar `dmarc@nekkora.com` e confirmar que **recebe** (L-8).
5. Só então criar `_dmarc` em `p=none` com `rua` e **esperar** os primeiros relatórios.
6. Registrar o **aplicativo dedicado**, **sem nenhuma** permissão de aplicação do Graph no Entra; atribuir `Application Mail.Send` por RBAC com escopo na caixa única; comprovar o caminho único; **provar a recusa** em outra caixa com chamada real (§3.3.1).
7. Gerar o certificado, guardar a chave no cofre, publicar as variáveis de ambiente e registrar o alarme de vencimento (DC-M2.4).
8. Implementar `GraphM365MailSender`, os dois `Kind` novos, a janela de deduplicação e a `mail_attempts` (migration `018`).
9. Validar as duas colunas da §8 e registrar as evidências.
10. Endurecer: `p=quarantine`, depois `p=reject`; `-all`; MTA-STS, se aprovado.
11. **Só então** ligar `nekkora.cadastro.enabled` (DC-17.7 da Missão 1).

---

## 10. Riscos e limites declarados

- **As concessões se somam, e por isso o desenho usa um caminho só.** Com RBAC restrito e nenhuma concessão no Entra, não há segundo termo na união. O risco deixou de ser estrutural e passou a ser de execução: se alguém consentir `Mail.Send` no Entra para este aplicativo, o caminho amplo reaparece e o escopo do RBAC não o contém. Daí a verificação explícita de "nenhuma permissão consentida" na §3.3.1, e a prova por chamada real.
- **Erro meu, corrigido antes da ratificação.** A revisão anterior afirmava que o Graph obrigava o consentimento no Entra, hipótese que eu havia declarado não verificada e sobre a qual construí o desenho de todo modo. A correção está na §3.3.1 e deixa a configuração mais segura, não menos.
- **A estrutura do Microsoft 365 está conferida e não bloqueia mais nada** (§1.3): tenant identificado, domínio `Authoritative`, DKIM ligado com destinos corretos, caixa compartilhada no endereço certo. O que falta é DMARC, o nome de exibição do remetente e o código.
- **Conclusão indevida corrigida.** A primeira versão deste documento tratou um rótulo de destino de DNS como identificação de tenant e um registro `MS=…` publicado como verificação concluída. As duas correções estão na §1.1, e a §1.3 traz o que a leitura do tenant de fato sustentou. O resultado confirmou a estrutura, mas a correção vale como método: rótulo de DNS não identifica tenant, e só a leitura no tenant respondeu.
- **DMARC em `p=none` não protege.** Ele só observa. A proteção começa em `quarantine` e os relatórios precisam ser lidos antes disso.
- **Exchange Online não é plataforma de transacional de volume.** Para o volume atual serve; o teto da §7 é um limite real, com saída já registrada.
- **`INCERTO` não é resolvido automaticamente.** Essa é uma escolha: duplicar aviso de segurança é pior do que atrasar um. Fica para decisão operacional, com o registro de tentativa como base.
