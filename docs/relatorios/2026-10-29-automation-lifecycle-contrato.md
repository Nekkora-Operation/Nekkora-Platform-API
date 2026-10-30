# PLATFORM-AUTOMATION-LIFECYCLE-001 — contrato do ciclo de vida das automações e auditoria L0

Missão de 29/10/2026. A API base é a `main` `cdc0aec9154a47d3a507e94138d14acdebb36918` (com os PRs #32–#35). O Agent é
a `main` `fdaae197bee17f65e241d92fd6b7baa3fccde747`, árvore `src` `2ab110d1…`. O Studio disponível é o `da7bb94` (local,
`feat/studio-lean-001`): são 22 commits que não estão na `main` remota `1cbf669` (CODEGEN-QUALITY, SPEC-OPTIONAL-COLUMNS e
STUDIO-LEAN, encerrados só localmente).

**Decisão D-EAP2 (titular, 29/10):** fica a contabilização técnica interna que já existe (recurso 6 em
`organization_usage_daily`). Nesta fase não há limite comercial, indicador, dashboard nem alarme, e não há migration nem
funcionalidade nova para consumo.

## 1. Responsabilidades (inalteradas)

| Componente | Faz | Não faz |
|---|---|---|
| Studio | desenvolve, valida e gera o pacote | não fala com a Platform (sem canal novo) |
| Platform API | cadastra workspace, automação e versão; **recebe e confere** o pacote; publica (pessoa com step-up); autoriza a execução local | não executa, não despacha, não manda comando ao Agent, não entrega pacote ao Agent |
| Agent | confere o pacote recebido pela pessoa, baixa as autorizações, executa localmente e transmite os fatos | não baixa pacote nem versão; publicar não o faz executar nada |

O artefato chega à API por uma **operação humana autenticada**: a pessoa envia o ZIP que o Studio gerou. A API não busca
nada por URL nem por caminho.

## 2. Auditoria L0: autoridade declarada × conferida

Legenda: **C** = conferido (constraint, gatilho, RLS ou Java); **D** = só declarado (comentário ou documento).

| Tema | Situação antes desta missão | Fonte |
|---|---|---|
| `workspaces` | status 1/2/3 por CHECK (C); nenhuma rota de criação; `nekkora_platform` com INSERT/UPDATE e RLS (C) | 001:33-46, 003:22-28, 012:913 |
| `automations` | status 1/2/3 (C); origem imutável, sem DELETE (C); "reversível" só declarado (D) | 005:89-138 |
| `automation_versions` | numeração MAX+1 com a automação travada (C, 005:175-189); nasce DRAFT (C, 008:58-70); PUBLISHED imutável e terminal (C, 005:195-216); vínculos de parâmetro e segredo conferidos na publicação (C, 008:807-855) | 005, 008 |
| `command_json` | `is_valid_command_contract` só quando status=2 (C): `contract_version` = número 1, `artifact.reference` não vazio, `artifact.sha256` hex64, `runtime`, `entrypoint` relativo, `arguments` lista de texto, `parameters` e `secret_bindings` objetos. **`agent_package` não é conferido pelo banco** | 005:53-85 |
| `automation.manage` / `automation.publish` | **provisionadas** para ORG_ADMIN e WORKSPACE_ADMIN (005:14-23), mas **não conferidas em lugar nenhum** (nenhuma rota usava) | grep |
| `workspace.lifecycle` | só ORG_ADMIN (003:82, 97-100); **não conferida** | grep |
| Step-up | chamada explícita `AccessActions.requireStepUp`, janela de 5 min na mesma sessão (C) | AccessActions:131, application.yml:114 |
| Auditoria | `agent_admin_events`, `access_events`, `workspace_configuration_events`; **nenhuma** cobria workspace, automação, versão ou publicação | 025, 023, 008 |
| `local_run_authorizations` | versão PUBLISHED com `agent_package` regra /1 (Java e gatilho, C); Worker autorizado no workspace (C); `valid_until` em (0, 366 dias] só no gatilho. No Java, validade fora da faixa ou automação inativa viravam **500** (23514 sem mapeamento) | AgentAdminService:226-257, 025:401-432 |
| Artefato | **não existia** tabela nem código: o contrato dizia "a API não recebe o pacote" | contrato Platform–Agent §6.4 |
| Regra `package-fingerprint/1` | especificada no §6.4; implementação só em teste do gateway | PackageFingerprintVectorTest |

### Divergências encontradas e o que foi feito

| # | Divergência | Correção |
|---|---|---|
| 1 | OpenAPI (autorização local): "packageFingerprint = command_json.artifact.sha256". A implementação e o banco usam `command_json.agent_package.fingerprint` com a regra /1 | texto da OpenAPI corrigido. `AutomationLifecycleContractTest` impede a volta do texto antigo |
| 2 | Contrato Platform–Agent §6.1: autorização "até 365 dias". Banco, OpenAPI e testes: 366 | contrato corrigido para 366 (o banco é a autoridade) |
| 3 | Validade fora da faixa ou automação/workspace inativo na autorização local: 500 | Java confere antes: 400 (validade) e 409 (inativo). Prova vermelho/verde no `AutomationLifecycleIT` (500 → 400) |
| 4 | `contract_version` do `command_json` é o NÚMERO 1. O canal da máquina usa o cabeçalho `"1.0"` | registrado: são coisas distintas. A versão nasce com o número 1 |
| 5 | Comentário da 025:399 ("artefato igual ao pacote") e comentário de coluna da 005:170 sem `agent_package` | **não editados** (migrations aplicadas). Esta seção registra a leitura correta |
| 6 | §6.4: "A API não recebe o pacote" | agora recebe, pelo fluxo deste contrato. A regra da impressão é a mesma |

### Parecer: estruturas reutilizadas × migration aditiva

- **Reutilizadas sem mudança:** `workspaces`, `automations` e `automation_versions` (grants, RLS, numeração, nascimento
  em DRAFT, imutabilidade e validação do contrato) e `local_run_authorizations`. As permissões `automation.manage`,
  `automation.publish` e `workspace.lifecycle` **já estavam provisionadas**: nenhuma permissão nova.
- **Migration 028 (aditiva, sem elevação):** `automation_artifacts` (artefato recebido, imutável), `automation_admin_events`
  (auditoria, só INSERT) e um gatilho na transição 1→2. Ele confere, só para versões com artefato recebido
  (`nekkora-artifact:`), o vínculo exato com o artefato e a autoridade de quem publica. As referências externas de antes
  (fixtures de teste) mantêm a regra de antes.

## 3. Rotas (tag "Ciclo de vida das automacoes")

| Rota | Autoridade | Efeito |
|---|---|---|
| `POST /api/v1/o/{org}/workspaces` | `workspace.lifecycle` (organização) | workspace ACTIVE. Ato 1 |
| `POST /api/v1/o/{org}/workspaces/{workspaceId}/automations` | `automation.manage` no workspace; workspace ativo | automação ACTIVE. Ato 2 |
| `POST /api/v1/o/{org}/automations/{automationId}/versions` | `automation.manage`; automação e workspace ativos | versão DRAFT, número do banco. Ato 3 |
| `GET /api/v1/o/{org}/automation-versions/{version}` | `automation.read` | estado, comando, artefato e impressão |
| `PATCH /api/v1/o/{org}/automation-versions/{version}` | `automation.manage`; só DRAFT | runtime e argumentos. Ato 4 |
| `PUT /api/v1/o/{org}/automation-versions/{version}/artifact` (`application/zip`, ≤ 50 MiB) | `automation.manage`; só DRAFT | pacote conferido, bytes guardados, versão referencia o artefato. Ato 5 |
| `GET …/artifact/content` | `automation.manage` | bytes preservados, conferidos pelo sha256 |
| `POST /api/v1/o/{org}/automation-versions/{version}/publish` | `automation.publish` + **step-up** | DRAFT → PUBLISHED. Ato 6 |

Toda rota de escrita exige sessão da organização e CSRF. Sem acesso ao workspace: 404, como nas leituras. Nenhuma rota
aceita credencial de máquina: o gateway da máquina é outro processo, sem essas rotas.

## 4. Artefato e integridade

**Duas identidades, nunca comparadas entre si:**
- `artifact.sha256`: SHA-256 dos bytes do ZIP recebido, calculado pelo servidor.
- `agent_package.fingerprint`: impressão `nekkora-agent/package-fingerprint/1` (contrato Platform–Agent §6.4), calculada
  pelo servidor sobre o manifesto e os arquivos listados.

Um ZIP novo do mesmo pacote muda o sha256 e mantém a impressão. Os vetores sintético (`cada6a64…`) e real (`5664f1a5…`,
pacote do Studio `da7bb94`) são reproduzidos em `PackageInspectorTest`.

**Conferência (sem executar nada):**
- O ZIP é lido pelo diretório central, e cada entrada é conferida contra o cabeçalho local: mesmo nome, método armazenado
  ou deflate, tamanho e CRC conferidos ao descompactar com teto.
- Recusas:
  - cifragem, ZIP64 e multi-disco;
  - ligação simbólica;
  - caminho absoluto, com `..`, `//`, `\`, `:` ou caractere de controle;
  - nome repetido, inclusive só na caixa;
  - mais de 500 arquivos ou 1000 entradas;
  - mais de 50 MiB, comprimido ou descomprimido;
  - bomba de descompressão (mais bytes que o declarado) e CRC adulterado.
- O manifesto precisa ser `nekkora-studio/codegen-manifest` 1.4+ (as mesmas regras do Agent: modo, framework, opções de
  linha de comando, `files` com `sha256:` e `main.py`). JSON com chave repetida é recusado.
- Cada arquivo listado precisa existir e ter o hash declarado. Arquivos fora da lista não contam (como no Agent).
- Pacote que pede segredo (`steps[].credential = true`) é recusado.

A recusa responde `422 {"error":"package_rejected","reason":"<motivo>"}`, com motivo de vocabulário fechado (lista na
OpenAPI, `PackageRejected`). Nenhum texto do pacote vai para a resposta ou para o log.

**Procedência:** `declaredGenerator` vem do manifesto e só é guardado se estiver no formato. `provenance = DECLARED`: não
é prova criptográfica de origem.

**Armazenamento (laboratório):** pasta local `NEKKORA_ARTEFATOS_DIR`, com `<organização>/<sha256>.zip`. Sem a variável, as
rotas de artefato respondem 503. Garantias:
- **durabilidade:** o temporário recebe `force` antes da troca;
- **atomicidade:** a troca é atômica;
- **isolamento:** pasta por organização; a chave é só o sha256 validado; ligação simbólica não é seguida;
- **recuperação:** conteúdo endereçado por hash. Um arquivo que sobra sem linha no banco, por falha entre o arquivo e a
  transação, é inofensivo: nunca é servido como artefato de versão e é reaproveitado por um envio igual. Um arquivo
  corrompido nunca volta como válido (a leitura confere o hash), e um envio igual o regrava.

Nenhum AWS ou S3: a interface `ArtifactStore` permite outro armazenamento depois.

**Referência:** a versão guarda `artifact.reference = "nekkora-artifact:<artifactId>"`, um identificador interno, nunca
caminho ou URL do cliente.

## 5. Publicação

A pessoa envia `expectedArtifactSha256` e `expectedPackageFingerprint`, isto é, o que ela conferiu. A publicação segue
estes passos:
1. Confere `automation.publish` no workspace.
2. Relê os bytes guardados e confere de novo o hash e a impressão: ausentes ou corrompidos dão 409.
3. Na transação: sessão revalidada, permissão, step-up recente, versão travada (`FOR UPDATE`) e ainda DRAFT, conteúdo
   igual ao conferido, automação e workspace ativos.
4. `status = 2`, com autoria e instante do servidor, e auditoria do ato 6.
5. O gatilho da 028 reconfere o vínculo com o artefato e a autoridade de quem publica: 23514 ou 42501, mesmo para quem
   gravasse direto com o papel da aplicação.

**Repetição** com a mesma expectativa (resposta perdida): 200 com a versão já publicada, sem nova auditoria. Outra
expectativa: 409. **Concorrência:** uma publica, as demais veem a mesma publicação (um único ato 6).

**Publicar não autoriza executar.** A autorização de execução local continua sendo o ato da 025, e nenhum registro de
execução nasce da publicação (provado no IT).

## 6. Limites e fora do escopo

- Não há comunicação Studio → API, download de pacote pelo Agent, despacho, cancelamento ou agendamento remoto, interface
  web, segredo de automação, limite comercial nem expurgo.
- O armazenamento é local (laboratório). Produção precisa de outro `ArtifactStore`, decisão própria.
- Arquivar ou suspender workspace e automação não ganhou rota nesta missão: os estados continuam como no banco.
