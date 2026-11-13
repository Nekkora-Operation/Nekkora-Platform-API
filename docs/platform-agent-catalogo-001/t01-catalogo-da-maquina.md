# PLATFORM-AGENT-CATALOGO-001 · T01 — catálogo de metadados da máquina

Contrato publicado: `api/openapi/nekkora-agent-v1.yaml`, rotas `GET /agent/api/v1/catalog` e
`GET /agent/api/v1/catalog/automations/{automationId}`. Migration: `db/migrations/036_catalogo_da_maquina.sql`.
Implementação: `api/nekkora-agent-gateway/.../MachineCatalog.java`. Testes: `MachineCatalogIT` (gateway real, papel
`nekkora_ingestion`, PostgreSQL e Redis reais) e `GatewayContractTest`.

## 1. Modelo (o que já existia)

| Conceito | Onde | Relação |
|---|---|---|
| Organização | `organizations` | Tudo é por organização (RLS da 012 pelo contexto `nekkora.organization_id`). |
| Worker | `workers` (status 1 ativo, 2 suspenso, 3 aposentado) | Recurso lógico de execução da organização. |
| Máquina (Agent) | `agents` (status 2 ativo, 4 revogado) | Criada no pareamento; um Agent novo a cada pareamento. |
| Vínculo | `worker_agent_assignments` (status 1 ativo) | Liga o Agent a um Worker. |
| Credencial | `agent_credentials` | Prova do Agent; revogável (administração, rotação, desvínculo pela máquina). |
| Autorização do Worker no workspace | `worker_workspace_assignments` (status 1 ativo, 2 revogado) | Sem ela, nenhuma autorização local chega à máquina. |
| Autorização local de execução | `local_run_authorizations` | Por versão (pacote: Studio ou externo) ou por automação (monitoramento); vigente = não revogada e `valid_until` no futuro; no máximo uma vigente por escopo (033). |

A autenticação (`MachineAuth.authenticate`, dentro da transação de cada ato) exige credencial não revogada e no prazo,
Agent ativo e vínculo ativo; toda falha é o mesmo 401. Revogar a credencial, encerrar o vínculo (pela administração ou
pela própria máquina, 035) ou suspender o Agent faz o próximo ato ser recusado.

## 2. Identidades de projeto e pacote

| Nome | Campo | O que é |
|---|---|---|
| Automação (projeto) | `automationId` | Identidade estável; o nome pode mudar e pode repetir entre workspaces e organizações. |
| Versão da Platform | `automationVersionId`, `versionNumber` | Número interno inteiro (1, 2, …) alocado pelo banco; **não é semver** e não é a versão do pacote. |
| Versão declarada | `packageVersion` | `MAIOR.MENOR.CORRECAO` do manifesto `nekkora-external.json` (pacote externo); nula para pacote do Studio. |
| SHA-256 do ZIP | (não exposto) | Bytes do arquivo; muda com a recompressão. Fica fora do catálogo. |
| Impressão canônica | `packageFingerprint` + `packageFingerprintRule` | Regra `external-python-fingerprint/1` ou `package-fingerprint/1`; a externa inclui o descritor (nome, versão, entrada, Python, dependências) e o conteúdo, não a compressão. |

## 3. Política de visibilidade

- **Identidade só da credencial.** Organização, Worker e Agent vêm da credencial autenticada. Nenhum identificador
  enviado pelo cliente é autoridade; o identificador da rota direta só escolhe o que consultar.
- **Worker ativo.** Worker suspenso ou aposentado: `403` (a credencial vale, o Worker não).
- **Automação visível** se, e somente se, existe AGORA ao menos uma autorização local vigente do Worker para ela (o mesmo
  critério de `GET /authorizations`, com a autorização do Worker no workspace ativa), em workspace e automação ativos.
- **Versão visível** se, e somente se, está PUBLICADA e tem uma autorização de **pacote** vigente para o Worker.
- **Visível sem versão elegível:** automação com só autorização de monitoramento aparece com `versions: []`. Isso não
  expõe nada novo: o identificador já chegava à máquina por `GET /authorizations`.
- **Fora, sempre:** rascunhos, versões publicadas sem autorização vigente para este Worker, automações só de outros
  Workers ou de workspaces sem a autorização do Worker, e qualquer coisa de outra organização (RLS).
- **Subconjunto, não lista da organização:** `coverage: WORKER_AUTHORIZED_SUBSET`. Catálogo vazio, ou a ausência de
  um projeto, **não prova** que ele não existe.
- **Lacuna declarada (não ampliada):** uma automação da organização sem nenhuma autorização vigente para este Worker
  (por exemplo, recém-criada na Web, sem versão publicada) não aparece. Para a máquina "usar projeto existente" nesse
  caso, uma pessoa precisa autorizar uma versão a este Worker. Listar todas as automações dos workspaces do Worker
  ampliaria a visibilidade; isso exigiria decisão explícita e não foi feito.

## 4. Campos e origem

| Campo | Origem |
|---|---|
| `organization.organizationId`, `organization.displayName` | `organizations.organization_id`, `organizations.name` (não a razão social) |
| `machine.agentId`, `machine.workerId` | Credencial autenticada (para o Agent conferir que a resposta é do vínculo atual) |
| `workspaceId`, `workspaceName` | `workspaces` |
| `automationId`, `automationName` | `automations` |
| `automationVersionId`, `versionNumber`, `publishedAt` | `automation_versions` |
| `sourceKind`, `packageFingerprintRule`, `packageFingerprint` | A autorização local vigente |
| `packageName`, `packageVersion` | `automation_artifacts` com a mesma impressão e regra (nulos sem artefato recebido) |
| `observedAt` | Hora do servidor na consulta |

Não saem: comando da versão (entrada, argumentos, referência do artefato), descrições, razão social, pertinências e
autoria, chave de armazenamento, SHA-256 dos bytes, dependências, requisito de Python, credenciais, códigos, logs,
arquivos. A 036 só concede leitura das colunas acima (e verifica, ao ser aplicada, que as demais continuam ilegíveis).

## 5. Paginação, ordem, limites, cache e erros

- **Ordem:** `(workspaceId, automationId)`, estável diante de renomeação. Versões: maior `versionNumber` primeiro, até 20
  por automação (`versionsTruncated` diz se havia mais; a mais recente visível está sempre na lista).
- **"Mais recente"** é entre as versões visíveis a este Worker, não a última da organização.
- **Página:** `limit` 1–100 (padrão 50); lê `limit + 1` e devolve `nextCursor` só se havia mais itens no momento da
  consulta (o mesmo critério das rotas paginadas da Platform).
- **Cursor:** opaco, só posição, preso à organização, ao Worker e ao Agent da credencial. Outro vínculo ou cursor
  ilegível: `400` com o mesmo corpo. Ele **não guarda permissão:** cada página autentica e filtra de novo.
- **Sem foto consistente entre páginas:** uma revogação, um vencimento ou uma autorização nova entre duas páginas pode
  tirar ou incluir uma automação. O Agent deve tratar a lista como observação no `observedAt` de cada página.
- **Consulta direta:** `GET /catalog/automations/{id}` aplica a mesma política a uma automação. `404` se ela não é
  visível agora; o corpo é o mesmo para inexistente, de outra organização, de outro Worker, revogada ou vencida.
- **Cache:** `Cache-Control: no-store`. Nada é gravado (nem presença).
- **Cota:** leituras do catálogo por Agent, `nekkora.limits.catalog-reads-per-minute` (padrão 60;
  `NEKKORA_GATEWAY_CATALOGO_POR_MINUTO`), `429` com `Retry-After`. Nenhum limite existente mudou.
- **Erros:** os do canal. `400` (contrato, limite, cursor ou identificador inválido), `401` (credencial inválida,
  revogada, vencida, vínculo encerrado; sempre o mesmo corpo), `403` (Worker não ativo), `404` (só na rota direta),
  `429`, `503`.

## 6. O que o catálogo não faz

Consultar não autoriza publicar, baixar, aprovar nem executar: essas rotas continuam conferindo por si (registro de
execução exige a autorização e a impressão exatas). Nenhuma rota de cadastro, publicação ou reserva de versão foi
criada. Duas máquinas podem preparar o mesmo número de versão sugerido: não há colisão remota nesta entrega, porque
nada é publicado; uma publicação futura terá de conferir a disponibilidade.

## 7. Compatibilidade

Rotas aditivas. `GET /authorizations`, o heartbeat e os demais atos não mudaram (o `MachineCatalogIT` confere os campos
de `/authorizations`). Um Agent anterior não chama o catálogo. Uma API anterior responde `404` para `/catalog`; a rota
de lista nunca responde `404` nesta versão, então o Agent pode reconhecer a API sem catálogo.

## 8. Migration 036

Só privilégios de leitura de coluna para `nekkora_ingestion` e uma política de RLS `FOR SELECT` em
`automation_artifacts` (a 028 só tinha política para `nekkora_platform`; sem a nova, a máquina veria zero linhas). Sem
papel novo, função, `SECURITY DEFINER`, dono novo ou escrita. As invariantes no fim da migration recusam a aplicação se
a RLS faltar, se houver escrita ou leitura de tabela inteira, ou se uma coluna fora do catálogo ficar legível.
