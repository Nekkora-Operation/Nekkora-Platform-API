# E-mail real: reconciliação e ações externas preparadas (missão PLATFORM-API-V1-003, item 6)

**Data:** 2026-10-23. **Base:** relatório do transporte (`2026-10-22-identidade-002-email-real.md`, §5) e desenho aprovado (`2026-10-11-email-transacional-m365-desenho.md`). **Nenhuma alteração** foi feita no Microsoft 365, no DNS ou em cofre, e **nenhuma mensagem real** foi enviada. Tudo abaixo depende da autorização específica já combinada.

## 1. Reconciliação por leitura

### 1.1 Feita nesta missão (leituras públicas, sem autenticação)

| Leitura | Fonte | Resultado |
|---|---|---|
| Tenant dono de `nekkora.com` | `GET https://login.microsoftonline.com/nekkora.com/v2.0/.well-known/openid-configuration` | `issuer` = `https://login.microsoftonline.com/ae4ed48d-e7d8-4f24-83f2-443e997c5a65/v2.0`; `tenant_region_scope` = `SA`; nuvem `microsoftonline.com` |
| Tipo de namespace do domínio | `GET https://login.microsoftonline.com/getuserrealm.srf?login=no-reply@nekkora.com&json=1` | `NameSpaceType` = `Managed`; `DomainName` = `nekkora.com`; `FederationBrandName` = `Nekkora` |
| `MX` | DNS sobre HTTPS (`cloudflare-dns.com`) | `0 nekkora-com.mail.protection.outlook.com.` |
| `TXT` ápice | idem | `v=spf1 include:spf.protection.outlook.com ~all` e `MS=ms38126768` |
| DKIM | idem | `selector1/2._domainkey` → CNAME `selector1/2-nekkora-com._domainkey.radiantsai.n-v1.dkim.mail.microsoft.`, os dois publicando `v=DKIM1; k=rsa; p=…` |
| DMARC | idem | `_dmarc.nekkora.com`: **NXDOMAIN** (lacuna L-1 continua aberta) |

**Distinção resolvida:**
- **`ae4ed48d-e7d8-4f24-83f2-443e997c5a65` é o Tenant ID do Entra** dono de `nekkora.com`. É o valor de `NEKKORA_M365_TENANT` e o do aplicativo dedicado.
- `facfe491-5e10-4f17-bb17-7c2115baea8f`, usado no relatório de 22/10, é o `Guid` de `Get-OrganizationConfig`: identificador da **organização do Exchange Online**, não o Tenant ID. A captura do Entra mencionada na revisão mostrava `ae4ed48d…`, o mesmo valor da descoberta pública.
- O seletor DKIM aponta para `radiantsai`, o domínio inicial (`*.onmicrosoft.com`) do mesmo tenant. O nome sozinho não identifica o tenant; a descoberta OpenID identifica.

### 1.2 Ainda depende de leitura autenticada (só leitura, sem alteração)

Essas leituras exigem login interativo da responsável no tenant. Comandos prontos (PowerShell 7 com os módulos `ExchangeOnlineManagement` e `Microsoft.Graph.Identity.DirectoryManagement`):

```powershell
Connect-MgGraph -Scopes Organization.Read.All -TenantId ae4ed48d-e7d8-4f24-83f2-443e997c5a65
Get-MgOrganization | Format-List Id, DisplayName, @{n='Verificados';e={$_.VerifiedDomains.Name -join ','}}      # R-1
Connect-ExchangeOnline -ShowBanner:$false
Get-ConnectionInformation | Format-List TenantID, Organization                                                   # R-2
Get-AcceptedDomain nekkora.com | Format-List DomainName, DomainType                                               # R-3
Get-Recipient no-reply@nekkora.com | Format-List RecipientTypeDetails, PrimarySmtpAddress, ExternalDirectoryObjectId  # R-4
Get-DkimSigningConfig -Identity nekkora.com | Format-List Enabled, Selector1CNAME, Selector2CNAME                  # R-5
Get-OrganizationConfig | Format-List Guid, Name                                                                   # o GUID do Exchange, para o registro
Disconnect-ExchangeOnline -Confirm:$false; Disconnect-MgGraph
```

**Resultado exigido:**
- R-1 `Id` = R-2 `TenantID` = `ae4ed48d-e7d8-4f24-83f2-443e997c5a65`;
- `nekkora.com` verificado e `Authoritative`;
- `no-reply@nekkora.com` existe nesse tenant;
- DKIM `Enabled: True`.

Qualquer divergência para a missão e volta para decisão.

## 2. Ações externas preparadas (para autorização)

Ordem obrigatória; nenhuma executada. Cada linha diz o destino e o impacto.

| # | Ação | Destino | Comando / artefato | Impacto | Prova |
|---|---|---|---|---|---|
| A | Caixa `dmarc@nekkora.com` e responsável pelo acompanhamento | Exchange Online (tenant `ae4ed48d…`) | `New-Mailbox -Shared -Name "dmarc" -DisplayName "Relatorios DMARC" -PrimarySmtpAddress dmarc@nekkora.com`; `Add-MailboxPermission dmarc@nekkora.com -User <pessoa> -AccessRights FullAccess` | nenhum no envio. **Decisão necessária:** quem acompanha | e-mail externo chega à caixa |
| B | DMARC em observação | Cloudflare, zona `nekkora.com` | `_dmarc TXT "v=DMARC1; p=none; rua=mailto:dmarc@nekkora.com; fo=1; pct=100"` | só observação, nada é recusado | DoH devolve o registro |
| C | Apresentação do remetente | Exchange Online | `Set-Mailbox no-reply@nekkora.com -DisplayName "Nekkora"`; `Set-Mailbox no-reply@nekkora.com -AcceptMessagesOnlyFromSendersOrMembers no-reply@nekkora.com` | destinatário vê `Nekkora <no-reply@nekkora.com>`; respostas recusadas | `Get-Mailbox` |
| D1 | Aplicativo dedicado `nekkora-platform-mail` | Entra ID | registro de um único tenant, **sem** permissão de API consentida | identidade nova sem permissão | `Get-MgApplication` |
| D2 | Certificado e armazenamento | cofre (**decisão: qual cofre**) e máquina da homologação | RSA 2048, 1 ano. A chave privada nunca sai do cofre; na homologação é montada como PKCS#12 só no container da Platform (`compose.m365.yml`, segredo de arquivo); a senha vai no `platform.env` do diretório de segredos | — | thumbprint registrado |
| D3 | Só o certificado público no aplicativo | Entra ID | upload do `.cer`; **nenhum** segredo de cliente | o aplicativo obtém token | token emitido (prova F1) |
| D4–D6 | RBAC para Aplicações, restrito à caixa | Exchange Online | `New-ServicePrincipal -AppId <App ID> -ObjectId <Object ID da Enterprise application>`; `New-ManagementScope -Name "Somente no-reply" -RecipientRestrictionFilter "PrimarySmtpAddress -eq 'no-reply@nekkora.com'"`; `New-ManagementRoleAssignment -App <Object ID> -Role "Application Mail.Send" -CustomResourceScope "Somente no-reply"` | envio **somente** como `no-reply` | — |
| D7–D8 | Conferência do caminho único | Exchange Online e Entra | `Get-ManagementRoleAssignment -App <Object ID>` (exatamente uma); `Get-ApplicationAccessPolicy` (nenhuma linha do aplicativo); nenhuma permissão de aplicação do Graph consentida; `Test-ServicePrincipalAuthorization -Identity <Object ID> -Resource no-reply@nekkora.com` → `InScope=True`; para outra caixa → `InScope=False` | prova de configuração (ignora o cache de 30 min a 2 h) | saída dos comandos |
| E1 | Saída de rede pelo proxy previsto | homologação (`ensaio-*`) | **preparado:** `ops/homologacao/proxy/squid.conf` e `ops/homologacao/compose.m365.yml` (§3) | a Platform continua sem saída; só o proxy alcança os dois nomes | política conferida (§3) |
| E2 | Configuração do ambiente | homologação | `compose.m365.yml`: `NEKKORA_MAIL_TRANSPORTE=m365`, remetente, tenant `ae4ed48d…`, `HOMOLOG_M365_CLIENT` (App ID), certificado como segredo, proxy. No lugar de `compose.caixa.yml` | transporte real ligado **só** nesse ambiente | a Platform sobe (a partida recusa sem tenant, cliente, certificado legível e remetente) |

## 3. Saída de rede preparada (E1/E2)

- **`ops/homologacao/proxy/squid.conf`:** só túneis `CONNECT` na porta 443 para `login.microsoftonline.com` e `graph.microsoft.com`; tudo o mais negado. Sem cache, sem inspeção TLS (tokens e conteúdo seguem cifrados ponta a ponta), sem `Via`/`X-Forwarded-For`, log só de destino e resultado.
- **`ops/homologacao/compose.m365.yml`:** o serviço `proxy-m365` (imagem fixada por digest, somente leitura) fica na rede interna e numa rede de saída própria; a Platform continua só na interna e recebe `NEKKORA_M365_PROXY=proxy-m365:3128`. **Não está ligada ao runner**, e a validação estática confere isso.
- **Política conferida nesta missão** (squid isolado numa rede de teste, removido em seguida): `login.microsoftonline.com:443` → CONNECT 200; `graph.microsoft.com:443` → CONNECT 200; `example.com:443`, `www.google.com:443` e `graph.microsoft.com:80` → **403**. Os 200 foram seguidos de um `GET /` sem autenticação (302/301 dos endpoints), sem credencial.
- **Validação estática** (`validar-config`): a sobreposição renderiza; a Platform não está na rede de saída; o tenant e o proxy estão nas variáveis; a política tem uma única regra de permissão.

## 4. Homologação real, depois das ações (para quando autorizada)

| Teste | Exigido | Instrumento |
|---|---|---|
| F1 | envio permitido por `no-reply`: 202 **e** recebimento na caixa controlada | `ProvaEnvioM365 --remetente no-reply@nekkora.com --para <caixa controlada>` |
| F2 | envio por outra caixa recusado (código 2, status 403) | `ProvaEnvioM365 --remetente <outra caixa do tenant>` |
| F3 | cabeçalhos: `spf=pass`, `dkim=pass` com `d=nekkora.com`, `dmarc=pass`; `From: Nekkora <no-reply@nekkora.com>`; um destinatário | mensagem recebida |
| F4 | jornada real pela API: cadastro (ou recuperação). Token no fragmento do link, uso único | `homologacao.sh jornadas-api` J1/J5 no ambiente com `compose.m365.yml`, com o token lido **da caixa real** (a leitura pela caixa simulada não se aplica) |
| F5 | aceite × entrega: `202` é aceite do Graph (estado ACEITO na fila), a entrega é o recebimento e o `Get-MessageTrace` | `mail_outbox` (estado e tentativas) + `Get-MessageTrace` |
| F6 | destinatário externo controlado, com `p=none` não reprovando | idem F1 |
| Obs | observabilidade sem tokens, destinatários nem credenciais nos logs; falhas visíveis (`mail.token_falhou`, `mail.desfecho` RECUSADO/INCERTO, `mail.certificado_vence_em`) | varredura do `jornadas-api` (controle positivo) e uma falha provocada (por exemplo, certificado errado → partida recusada; destinatário inválido → RECUSADO) |

**Separado por depender de página inexistente:** abrir o link do e-mail no navegador e confirmar pela página `/cadastro/confirmar` ou `/recuperar-senha`. A prova da API (F4) não precisa da página: o token vem do link recebido e é enviado à API.

## 5. Decisões necessárias da responsável

1. Autorizar as ações A–E, na ordem (cada uma é externa e publicável).
2. Quem acompanha `dmarc@nekkora.com`.
3. Qual cofre guarda a chave privada do aplicativo.
4. A caixa controlada para os testes F1, F3, F4 e F6.
5. A opção de rede: (a) o proxy preparado (recomendado) ou (b) uma rede de saída exclusiva da Platform.
