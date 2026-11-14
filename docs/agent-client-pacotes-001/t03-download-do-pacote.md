# AGENT-CLIENT-PACOTES-001 · T03 — download autorizado do pacote de uma versão publicada

Contrato `desktop-navigation/3`. Sucede `docs/agent-client-navegacao-002/t01-organizacoes-atuais.md` (contrato `/2`,
PR #61), do qual este PR nasce.

## 1. O que já existia

| Peça | Estado antes desta tarefa |
|---|---|
| Armazenamento | `ArtifactStore`: os bytes do ZIP ficam no armazenamento **da própria API**, pela chave `sha256`, por organização. Não há bucket público nem URL assinada. |
| Download pela Web | `GET /api/v1/o/{org}/automation-versions/{version}/artifact/content` (028): sessão de organização, exige `automation.manage`, relê e confere os bytes pelo `sha256`. Sem auditoria própria. |
| Aplicativo desktop | Só metadados: a lista de versões informa nome, versão e impressão do pacote. Não havia rota de bytes. |
| Permissões no catálogo | `automation.read`, `automation.execute`, `automation.manage`, `automation.publish`. Não existe permissão de download. |

## 2. O que entra

### Escopo próprio: `identity navigation packages`

O aplicativo passa a poder pedir um terceiro escopo, com o texto exato acima. Ele inclui a navegação e acrescenta o
download. É pedido, mostrado na página de confirmação e aprovado como tal.

- Sessão emitida com `identity navigation` **não** baixa nada (`403`): um token anterior não ganha o download.
- Sessão emitida com `identity` continua sem navegar.
- O formato da sessão no Redis não muda (o escopo já era um campo de texto).

### Quem pode baixar

Ver metadados, baixar o código e executar são coisas diferentes:

| Permissão no workspace da automação | Vê versões e metadados | Baixa o pacote |
|---|---|---|
| só `automation.read` (leitura) | sim | **não** (`403`) |
| `automation.execute` (operação) | sim | sim |
| `automation.manage` (administração do workspace ou da organização) | sim | sim |

**Por que este mapeamento, e não uma permissão nova:** `automation.manage` já é a permissão do download pela Web; e
quem tem `automation.execute` é quem vai executar o pacote na própria máquina, que é o uso desta rota. Uma permissão
nova no catálogo de papéis exigiria decidir, papel por papel, quem a recebe — decisão de produto que não cabe aqui. O
mapeamento é testado (`DesktopNavigationIT`) e fica num único ponto do código (`authorizeDownload`).

A lista de automações passa a informar a capacidade `download`: verdadeira só se a sessão tem o escopo de pacotes **e**
a pessoa tem uma das duas permissões no workspace.

### A rota

`GET /api/v1/desktop/organizations/{org}/automations/{automation}/versions/{version}/package`

- `Authorization: Bearer <token do aplicativo>`; cookies ignorados; limitador das rotas do aplicativo.
- A cada requisição, na mesma transação: sessão do aplicativo e sessão de identidade de origem ativas; conta da pessoa na
  organização resolvida agora (039); vínculo ativo; leitura da automação; permissão de download; automação e workspace
  ativos; a versão pertence à automação, está **publicada** e tem pacote. Nada além dos ids do caminho vem do aplicativo.
- Resposta `200 application/zip`: os bytes guardados, relidos e conferidos pelo `sha256`, **direto desta rota**. Sem
  redirecionamento, sem URL temporária, sem endereço informado pelo pacote. `Content-Length`, `Cache-Control: no-store`,
  `X-Artifact-Sha256`, `X-Package-Fingerprint`, `Content-Disposition: attachment; filename="<sha256>.zip"`.
- A lista de versões passa a informar `package.sha256` e `package.sizeBytes`: é contra eles (e a impressão) que o
  aplicativo confere o que baixou.

| Situação | Resposta |
|---|---|
| Sem token, token inválido, sessão ou sessão de origem encerrada | `401` |
| Sessão sem o escopo de pacotes; ou automação visível sem `automation.execute` nem `automation.manage` | `403` |
| Organização, automação ou versão fora do alcance ou inexistente; versão de outra automação; papel revogado | `404` |
| Automação ou workspace inativo; versão em rascunho; versão sem pacote; arquivo ausente ou corrompido | `409`, sem bytes |
| Id malformado | `400` |
| Armazenamento não configurado ou indisponível | `503` |

O corpo do erro é o fixo de sempre (`{"error":"conflict"}` etc.); o motivo exato vai para o log do servidor, com a
correlação.

### Auditoria

Cada download **autorizado** grava o ato `9 = PACKAGE_DOWNLOADED` em `automation_admin_events` (quem, workspace,
automação, versão, artefato, instante, correlação). Não grava token, URL nem conteúdo. Recusas não gravam o ato. O
registro é feito na autorização, antes da leitura dos bytes: se o arquivo estiver ausente, fica o ato e a resposta é `409`.

**Migration `040_auditoria_do_download_de_pacote.sql`, sem elevação:** só amplia o `CHECK` do ato (1–8 → 1–9) e obriga o
ato 9 a dizer automação, versão e artefato. Nenhuma tabela, função, papel ou privilégio novo. Exige a 039 (PR #61).

## 3. O que não muda

- O download pela Web (028) e a sua permissão.
- Publicação, versões, artefatos, manifestos e impressões: a rota só lê.
- Duração e não renovação da sessão do aplicativo; consentimento por escopo, não por organização.
- Nenhuma rota de execução, de monitoramento ou do canal das máquinas.

## 4. Limites

- **Sem URL temporária:** o download é sempre autenticado nesta rota. A revogação (logout, papel retirado, vínculo
  retirado) vale na requisição seguinte; um download já em curso termina.
- **Tamanho:** até 50 MiB (limite do artefato, 028), lido inteiro em memória pela API, como no download pela Web.
- **Corpo de erro sem motivo:** o aplicativo distingue `403`, `404`, `409` e `503`, não as variantes de cada um.

## 5. Compatibilidade e ordem

| Combinação | Efeito |
|---|---|
| Client anterior (pede `identity navigation`) × esta API | Tudo como antes; `download` vem falso e é ignorado |
| Client novo (pede o escopo de pacotes) × API anterior | A API anterior recusa o escopo desconhecido: a entrada não abre. **A API vem antes do Client** |
| Esta API × Web anterior | A página de confirmação valida a resposta contra o contrato fixado e não reconhece o escopo novo. **A Web vem antes do Client** |
| Esta API × banco sem a 040 | O download autorizado falha ao auditar (`500`); o resto funciona. A migration vem antes |

## 6. Testes

- `DesktopNavigationIT.baixarPacoteExigeEscopoPermissaoEVersaoPublicada`: download com os cabeçalhos; auditoria;
  escopo de navegação e `identity` recusados; leitora recusada; operadora baixa e deixa de baixar com o papel revogado;
  ids de organização, automação e versão trocados; rascunho; versão sem pacote; arquivo ausente e corrompido; nada muda
  em versões e artefatos.
- `DesktopLoginContractTest`: rota, respostas, escopos e esquemas publicados.
