# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 · T00 — Diagnóstico e plano de contratos

Revisão R1 da missão. Este documento é o plano revisável: nada dele está implementado. Ele diz o que já existe, o que será
reaproveitado, o que precisa ser criado e quais decisões são da titular antes do T01.

Base conferida em 15/11/2026: API `dev` `6db8519`, Web `dev` `dc61507`, Client `dev` `290a0c6`. Tudo o que está em "existe"
foi lido no código e nas migrations, não em relatórios.

## 1. Resumo

- A API e a Web **já têm** um fluxo de "pedido de execução pela Platform", mas ele foi feito para o Nekkora Agent antigo e
  não atende à missão: exige o pacote já presente e aprovado na máquina, não tem download pela máquina, tentativas, lease,
  cancelamento depois do aceite, logs de texto nem resultados.
- O Client **proíbe por teste** tudo o que a missão pede (credencial de máquina, pareamento, Worker, rede fora do login,
  fila). Essas regras serão reescritas, mantendo a que importa: a execução local nunca fala com a Platform.
- O pareamento, a credencial `nka1`, o heartbeat, o isolamento por organização e a auditoria do canal da máquina são
  reaproveitáveis como estão.
- Proponho um contrato novo e separado ("execução remota"), lado a lado com o do Agent antigo, que não muda.

## 2. Inventário

### 2.1 Existe e será reaproveitado

| Recurso | Onde | Como entra |
|---|---|---|
| Worker, agente, vínculo Worker↔agente, Worker autorizado no ambiente | migrations 004 e 025; `AgentAdminService` | modelo de máquina da missão; nenhum Worker duplicado |
| Código de pareamento `nkp1` (5 a 60 min, só o hash guardado), criado por pessoa com permissão e confirmação reforçada | `POST /o/{org}/agent-enrollments` | o ato de autoridade da habilitação |
| Credencial de máquina `nka1`: o segredo nasce na máquina, o servidor guarda só o SHA-256; revogável | gateway `enrollments/redeem`, `MachineAuth` | credencial do Client |
| Desvínculo pela máquina, idempotente | migration 035, `link/unlink` | "revogar/desvincular" do Client |
| Heartbeat com versão, contrato e capacidades; "conectada" = batimento há menos de 180 s | `heartbeat`, `agent_presence` | elegibilidade e capacidade negociada |
| Isolamento por organização (RLS forçada), papel `nekkora_ingestion` sem escrita administrativa | 025, 030, `OrgTransactions` | todas as tabelas novas seguem o mesmo padrão |
| Limite de taxa (Redis) e 429 com `Retry-After` | `RedisLimiter`, `Limits` | consultas do Client e atualização do inventário |
| Auditoria do canal (`agent_admin_events`, atos 1 a 14) | 025, 030, 035 | atos novos a partir do 15 |
| Permissões `automation.execute`, `logs.read`, `worker_workspace.authorize`, `agent.manage` | `PermissionCatalog` | sem permissão nova (ver seção 4) |
| Armazenamento de pacotes por organização e SHA-256 | `ArtifactStore` | origem do download pela máquina |
| Motor local do Client: uma execução por vez, dono da árvore de processos (PID + início + digest), diário atômico, reconciliação após reinício sem relançar | `RunController`, `RunStore`, `ProcessOwner` | motor da tentativa remota |
| Descoberta limitada de Python, validação de dependências sem importar o projeto, leitor de `requirements.txt` | `PythonSources`, `PythonEnvironments`, `RequirementsFile` | inventário e validação |

### 2.2 Existe, mas só serve ao Agent antigo (não será alterado)

| Recurso | Por que não serve |
|---|---|
| `run_requests` (030): pedido → entrega → decisão → execução ligada | pede uma autorização por versão já aprovada na máquina e o pacote já presente; sem tentativas, sem lease, sem cancelar depois do aceite |
| `agent_runs`, `agent_run_events`, estados do supervisor 1 a 9 | espelham o código do Agent antigo; os eventos são envelopes estruturados de 4 KB, e stdout/stderr nunca são expostos, por desenho |
| Catálogo da máquina (036) | só metadados; deliberadamente sem a chave de armazenamento |
| Limites de execuções simultâneas e pendentes (010) | valem só para a tabela antiga `executions`, que não tem quem a escreva |

### 2.3 Não existe

- Download do pacote autenticado pela máquina.
- Envio de resultados pela máquina, e onde guardá-los.
- Linhas de log de texto (stdout/stderr) no servidor.
- Tentativas, lease, token de atribuição, cancelamento de execução em andamento.
- Renovação da credencial sem um código criado por uma pessoa (hoje: 90 dias, e depois só com código de rotação).
- Checagem de presença ou de capacidade ao criar um pedido.
- No Client: credencial de máquina, pareamento, heartbeat, consulta de pedidos, captura de stdout/stderr (hoje a saída é
  drenada e descartada: só os bytes são contados).
- Sessão do Client com noção de máquina: o token é da pessoa, dura 15 minutos, fica só em memória e não renova.

### 2.4 Lacuna encontrada fora da missão

O seletor de máquina do diálogo "Executar" da Web usa a lista de agentes, que exige `agent.manage`. Um operador com
`automation.execute` não vê máquina nenhuma. A rota nova de máquinas elegíveis (seção 5.3) resolve para o fluxo novo; o
diálogo antigo fica como está.

## 3. Decisões de desenho

| # | Decisão | Motivo |
|---|---|---|
| D1 | Contrato **novo e separado** (`/agent/api/v1/remote/*`, tabelas `remote_*`), sem tocar em `run_requests`/`agent_runs` | o fluxo antigo tem outra semântica e um consumidor em uso; misturar exigiria mudar a máquina de estados dele |
| D2 | A habilitação reaproveita o **código de pareamento**: a pessoa com autoridade cria na Web um código marcado "execuções pela Platform"; no Client, a pessoa lê a consequência e digita o código | a autoridade é conferida no servidor (permissão + confirmação reforçada + gatilho do banco); o Client sozinho não concede nada |
| D3 | Depois de habilitada, a máquina executa versões **publicadas** dos ambientes em que o Worker está autorizado, sem confirmação presencial a cada pedido — **e sem contornar as autorizações que já existem**: continua valendo a autorização vigente daquele Worker para aquela versão (a mesma que o fluxo antigo exige), e uma autorização só de monitoramento não serve para executar | decisão da titular (15/11); a habilitação remota é uma condição a mais, nunca um atalho |
| D4 | Solicitação lógica e **tentativas** em tabelas separadas; toda tentativa tem máquina, época, token e histórico | repetição em outra máquina sem perder histórico |
| D5 | Consulta de saída iniciada pelo Client (sem porta de entrada na máquina), com lease e token de atribuição | exigência da missão; lease vencido vira "comunicação perdida", nunca redistribuição |
| D6 | Logs em tabela própria de linhas de texto, com ACK por sequência | o envelope estruturado do canal antigo não comporta texto livre |
| D7 | Pacote novo `remote` no Client, único lugar com rede de máquina; `pack`, `run` e `nav` continuam sem rede | manter a prova, por teste, de que a execução local não transmite nada |
| D8 | Tudo atrás de uma chave no servidor (`NEKKORA_EXECUCAO_REMOTA`, padrão desligada) e de capacidade negociada | publicação controlada e desativável |

## 4. Matriz ação → credencial → permissão → escopo → origem

Três atores distintos: **quem solicita** (pessoa na Web), **quem habilitou** (pessoa que criou o código) e **a máquina**.

| Ação | Credencial | Permissão | Escopo | Origem / registro |
|---|---|---|---|---|
| Criar código de habilitação | sessão da pessoa + confirmação reforçada | `agent.manage`, `agent_credential.manage`, `worker_agent.assign` (+ `worker.manage` se criar o Worker) | organização | ato 1; fica gravado quem habilitou |
| Autorizar o Worker no ambiente | sessão + confirmação reforçada | `worker_workspace.authorize` | organização → ambiente | ato 8 (existente) |
| Resgatar o código no Client | o código `nkp1` | (a de quem criou, reconferida pelo gatilho) | organização do código | ato 3; nasce a credencial `nka1` |
| Renovar a credencial | `nka1` vigente | nenhuma de pessoa | o próprio agente | ato novo 15 |
| Pausar / retomar recebimento | `nka1` | — | o próprio agente | ato novo 16/17 |
| Desvincular pela máquina | `nka1` | — | o próprio agente | ato 14 (existente) |
| Revogar a máquina pela Web | sessão + confirmação reforçada | `agent.manage` | organização | ato 6 (existente) |
| Ver máquinas elegíveis | sessão | `automation.execute` | ambiente | leitura |
| Declarar dependências da automação | sessão | `automation.manage` | ambiente | ato novo (trilha de automação) |
| Escolher o Python da máquina | sessão | `automation.manage` | ambiente (por máquina e automação) | ato novo |
| **Solicitar execução** | sessão + confirmação reforçada | `automation.execute` | ambiente | `remote_executions`; origem `PLATFORM` |
| Cancelar | sessão | `automation.execute` | ambiente | ato novo |
| Executar de novo em outra máquina | sessão + confirmação reforçada | `automation.execute` | ambiente | nova tentativa na mesma solicitação |
| Ver estado e tentativas | sessão | `automation.read` | ambiente | leitura |
| Ver e baixar logs e resultados | sessão | `logs.read` | ambiente | leitura; download auditado |
| Receber tentativa, baixar pacote, reportar estado, enviar logs e resultados | `nka1` + token da atribuição | — | só a tentativa atribuída a este agente | escrita de máquina |
| **Executar neste computador (Client)** | nenhuma de máquina | a da tela local | local | origem `LOCAL`; **nada é enviado** |

Sem permissão nova. Negação de escopo responde 404, como no resto da API.

## 5. Contratos

### 5.1 Capacidade negociada

Capacidades novas no heartbeat: `REMOTE_RUN_1_0`, `REMOTE_LOGS_1_0`, `REMOTE_RESULTS_1_0`, `PY_INVENTORY_1_0`. O heartbeat
ganha `receiving` (`ENABLED`, `PAUSED`), `busy` e `diskPressure`.

- Máquina sem `REMOTE_RUN_1_0` **não aparece** como apta e nunca recebe tentativa. O Agent antigo, portanto, fica de fora.
- Client novo contra API sem a chave ligada: o resgate do código de habilitação responde `remote_unavailable`, e o Client
  mostra "A Platform desta organização ainda não aceita execuções remotas"; o uso local continua.
- Client antigo contra API nova: nada muda para ele; não conhece as rotas.

### 5.2 Estados da tentativa

```
QUEUED → OFFERED → RECEIVED → PREPARING → STARTING → RUNNING → SUCCEEDED | FAILED | CANCELLED | TIMED_OUT
   └──────────┴─────────┴──────────┴──────────→ REFUSED (com motivo)      └→ ENDED_UNKNOWN
QUEUED | OFFERED → EXPIRED | CANCELLED
```

- `REFUSED` tem motivo fechado: `BUSY`, `PAUSED`, `PACKAGE_INVALID`, `ENVIRONMENT_GONE`, `ENVIRONMENT_INCOMPATIBLE`,
  `DEPENDENCIES_MISSING`, `DISK_PRESSURE`, `AUTHORIZATION_REVOKED`, `CLIENT_INCOMPATIBLE`.
- `ENDED_UNKNOWN`: a máquina voltou e confirmou que o processo **não está mais rodando**, mas não há prova do resultado.
- **Comunicação** é um eixo à parte: `OK` ou `LOST` (lease vencido). `LOST` não muda o estado da tentativa nem a encerra.
- **Entrega de logs/resultados** e **limpeza** são eixos à parte: `PENDING`, `DONE`, `LIMIT_REACHED`, `FAILED`. Uma
  tentativa pode estar `SUCCEEDED` com envio ou limpeza pendentes.
- O resultado depende do código de saída e do contrato de resultado; uma linha de log em nível de erro não o altera.

### 5.3 Rotas da pessoa (Platform API)

| Rota | Função |
|---|---|
| `GET /o/{org}/workspaces/{ws}/remote-machines?automation=` | máquinas do ambiente com estado (`AVAILABLE`, `BUSY`, `PAUSED`, `OFFLINE`, `INCOMPATIBLE`) e o Python escolhido; indicativo |
| `GET`/`PUT /o/{org}/automations/{a}/runtime-requirements` | declaração de dependências (seção 5.8) |
| `GET /o/{org}/agents/{agent}/python-environments`, `POST …/refresh` | inventário e atualização sob demanda |
| `PUT /o/{org}/automations/{a}/agents/{agent}/python-selection` | Python escolhido para esta automação nesta máquina |
| `POST /o/{org}/workspaces/{ws}/remote-executions` | solicita; `requestKey` idempotente; fixa versão, impressão digital e a revisão das dependências |
| `GET …/remote-executions`, `GET …/{id}` | solicitação, tentativas, eixos de comunicação/entrega/limpeza |
| `POST …/{id}/cancel` | pede o cancelamento da tentativa atual |
| `POST …/{id}/attempts` | "Executar novamente em outra máquina"; recusado enquanto a tentativa anterior não tiver término confirmado |
| `GET …/attempts/{t}/logs?after=&level=&channel=` | leitura incremental |
| `GET …/attempts/{t}/results`, `GET …/results/{r}/content` | lista e download |

A reserva final é atômica no servidor: no máximo uma tentativa não terminada por agente (índice único parcial). O estado
exibido antes disso é só indicativo.

### 5.4 Rotas da máquina (gateway)

| Rota | Função |
|---|---|
| `POST /agent/api/v1/credential/renew` e `/confirm` | renovação em duas fases (seção 5.5) |
| `POST /agent/api/v1/remote/receiving` | pausar / retomar |
| `PUT /agent/api/v1/remote/python-environments` | inventário (só depois de pareada) |
| `GET /agent/api/v1/remote/next` | consulta: entrega no máximo uma tentativa, um pedido de cancelamento ou um pedido de verificação de ambiente |
| `POST …/attempts/{t}/ack` | recebimento confirmado (depois de gravar a intenção no diário) |
| `GET …/attempts/{t}/package` | bytes do pacote da versão fixada; só para o agente atribuído, com o token |
| `POST …/attempts/{t}/start-check` | revalidação imediatamente antes de lançar |
| `POST …/attempts/{t}/state` | estado, renovação do lease, código de saída; devolve `cancelRequested` e `revoked` |
| `POST …/attempts/{t}/logs` | lote de linhas; resposta com `storedThrough` |
| `PUT …/attempts/{t}/results/{n}`, `POST …/results/complete` | envio idempotente por SHA-256 e fechamento |
| `POST …/attempts/{t}/cleanup` | limpeza confirmada ou pendente, com motivo |

Toda escrita de tentativa leva o **token de atribuição** e a **época**. Token ou época antigos recebem 409 e não alteram
nada: um consumidor obsoleto não aceita trabalho nem sobrescreve o estado.

### 5.5 Credencial da máquina

- Mesma credencial `nka1`, guardada no Client com DPAPI do usuário do Windows; nunca em ZIP, log ou argumento.
- Validade de 90 dias (configuração existente). O Client renova sozinho quando faltarem 30 dias: gera o segredo novo,
  chama `renew`, grava, usa a nova em `confirm`; só então a antiga é revogada. Resposta perdida é repetível (idempotente
  pelo hash do segredo novo).
- Falha transitória de renovação: nova tentativa com espera crescente, limitada; a credencial atual continua valendo até
  expirar.
- Expirada ou revogada: **nunca aceita**. O Client para de pedir trabalho, mostra "Habilitação expirada/revogada" e pede
  nova habilitação. Não há atalho pelo token da pessoa.
- Trabalho em andamento quando a credencial é revogada: o Client encerra o processo de forma controlada, registra o
  estado verdadeiro no diário e guarda logs e resultados na fila até o limite de retenção. Eles **não podem** ser enviados
  sem credencial válida; se a máquina for habilitada de novo na mesma organização e Worker, são entregues; senão são
  descartados no prazo, e a Web mostra a lacuna.

A sessão da pessoa (15 min, sem renovação) não muda. Expirar ou terminar a sessão tira o acesso às telas da conta; o canal
da máquina continua, e a tela de entrada diz isso.

### 5.6 Entrega, lease e deduplicação

- Consulta: a cada 5 s com a máquina livre; espera crescente até 30 s sem trabalho; 429 respeita `Retry-After`.
- Lease de 60 s, renovado a cada 20 s por `state`. Lease vencido marca comunicação `LOST`; **não prova que o processo
  parou e não redistribui nada**.
- Sequência no Client: gravar a intenção no diário → `ack` → baixar e conferir → `start-check` → gravar "lançando" →
  criar o processo → gravar o dono do processo → `state RUNNING`.
- Janela entre "lançando" e o dono gravado: ao reiniciar, o Client **não relança**. Se não achar um processo que
  comprovadamente seja o da tentativa, reporta `ENDED_UNKNOWN` com o motivo `LAUNCH_NOT_CONFIRMED`.
- Uma tentativa já vista no diário nunca é iniciada de novo, mesmo que o servidor a entregue outra vez.
- Duas instâncias do Client: a trava de instância única da pasta de dados já existe; o índice único do servidor cobre o
  resto.
- Isto não garante "exatamente uma vez" em sistemas de terceiros nem desfaz efeitos externos.

### 5.7 Pacote, diretório e resultados

- Download só da versão fixada, conferindo tamanho, SHA-256 e impressão digital; depois, o leitor seguro de ZIP e o
  validador de pacote que já existem. Download parcial nunca é executável.
- Raiz gerenciada: `<dados>\remoto\<organização>\<tentativa>\` com `pacote\` (código, apagado no fim), `saida\`
  (resultados) e `fila\` (logs e resultados a enviar). A posse é gravada **antes** de usar a pasta.
- **A pasta não é uma sandbox**: o código roda com os poderes do usuário do Windows. Não há isolamento de VM.
- Resultados: só o que a automação gravar em `saida\` (variável de ambiente já existente). Sem varrer outras pastas, sem
  seguir links.
- Credenciais de robôs não viajam no pacote nem são copiadas entre máquinas. Não há cofre nesta missão: uma automação que
  precise de segredo depende do que já estiver configurado na máquina, e isso fica declarado como limite.

### 5.8 Dependências declaradas na Platform

- Origens, uma por revisão: `PACKAGE` (as do manifesto), `FILE` (`requirements.txt` enviado), `TEXT` (campo de texto) ou
  `NONE_CONFIRMED` ("Sem dependências externas", confirmado por uma pessoa).
- Leitura estática, com as **mesmas regras** do `RequirementsFile` do Client, portadas para a API e presas por vetores de
  teste comuns. Só `nome==versão` (com extras) é representável; faixa, sem versão, marcador, endereço, inclusão, opção e
  hash são ditos por linha e impedem salvar aquela origem. Nenhuma versão é inventada.
- Efetivo = dependências do manifesto ∪ acréscimos da Platform. Mesma biblioteca com versão diferente da do manifesto é
  conflito: exige resolução antes de executar e nunca enfraquece o manifesto.
- Cada revisão tem número, hash, autor e data. A solicitação congela a revisão e a lista efetiva; novas tentativas da
  mesma solicitação usam o mesmo retrato. Editar depois só vale para solicitações novas.
- O pacote publicado não muda. Solicitar execução remota exige uma revisão efetiva; gerar pacote e executar localmente
  continuam como estão.

### 5.9 Inventário e escolha do Python

- Só depois do pareamento, o Client publica: identificador opaco (derivado localmente, estável para a máquina), versão,
  tipo, instante da observação. **Sem caminhos** e sem lista de pacotes.
- Compatibilidade com uma automação é verificada sob demanda: a Platform pede, a máquina confere **só** as dependências
  daquela automação naquele ambiente e responde. Limite: uma verificação por minuto por máquina e automação.
- Sem resposta ou 429: "Não foi possível verificar", nunca "Incompatível".
- A escolha é por máquina e automação. O servidor guarda só o identificador; **nunca** envia caminho. O Client revalida
  antes de lançar; ambiente removido ou trocado recusa com `ENVIRONMENT_GONE`, sem escolher outro. Outra máquina exige a
  própria escolha.

### 5.10 Logs

- Evento: tentativa, origem, sequência, instante de captura, instante declarado (se houver), canal (`STDOUT`, `STDERR`,
  `EXECUTOR`), nível (explícito ou `UNKNOWN`), mensagem.
- `print` é "Saída". `STDERR` é um canal, não um nível. Nenhuma classificação por palavra no texto.
- Nível só com evidência: linha no formato estruturado documentado (uma linha JSON com marca própria), emitido por um
  formatador opcional de `logging` de poucas linhas. Robô existente não precisa de biblioteca.
- Traceback: linhas consecutivas do mesmo canal chegam em ordem; a Web as mostra agrupadas.
- A ordem entre `STDOUT` e `STDERR` é a observada pelo capturador, não uma ordem global.
- Redação antes de gravar: credencial de máquina, códigos de pareamento, cabeçalhos de autorização. Não há promessa sobre
  dados pessoais que o próprio robô imprima.
- Origem local: capturado e guardado **só localmente**; nenhuma rota de máquina aceita tentativa de origem local.

### 5.11 Limites (configuráveis; valores iniciais propostos)

| Limite | Valor | Ao atingir |
|---|---|---|
| Linha de log | 8 KiB | truncada, com marca visível |
| Logs por tentativa | 50.000 linhas ou 20 MiB | a captura **não para em silêncio**: grava um evento "limite de logs atingido", continua lendo a saída do processo (para ele não travar) e descarta o excedente contando as linhas; o processo segue e **o resultado do robô não muda**; a Web mostra o truncamento e o estado final verdadeiro |
| Lote de logs | 200 linhas ou 256 KiB | — |
| Fila local (todas as tentativas) | 200 MiB | novos trabalhos recusados com `DISK_PRESSURE` |
| Espaço livre mínimo no disco de dados | 1 GiB | idem |
| Resultados por tentativa | 50 arquivos, 20 MiB cada, 50 MiB no total | o excedente não é enviado e é listado como "não enviado: limite" |
| Retenção na fila local sem conseguir enviar | 7 dias | descarte com registro; a Web mostra a lacuna |
| Retenção de **logs** no servidor | 30 dias | o texto das linhas é redigido (como na 026); fica o registro de que existiram e quantas eram |
| Retenção de **resultados** no servidor | 30 dias | os arquivos são removidos; ficam nome, tamanho, SHA-256 e a data da remoção |
| Retenção do **histórico de execução** (solicitação, tentativas, estados, quem pediu, máquina, auditoria) | não expira por esta regra | segue a política de auditoria da Platform; nenhuma regra desta missão o apaga |
| Limpeza do código temporário | imediata; novas tentativas com espera crescente por 24 h | "limpeza pendente", com o motivo |
| Tempo máximo da tentativa | 60 min (o do executor local) | `TIMED_OUT` |

Os valores são **provisórios de laboratório**: todos configuráveis, cada um com o comportamento ao ser atingido escrito
acima. A titular aceitou quatro como ponto de partida (logs por tentativa, fila local, resultados por tentativa e retenção de
30 dias); a tabela inteira ainda precisa ser conferida antes de valer fora do laboratório.

A limpeza depende de o Client voltar a rodar: com a máquina desligada, nada é apagado. Só são apagadas pastas de posse
comprovada dentro da raiz gerenciada, conferindo caminho real e links. Não há apagamento forense.

### 5.12 Client

- Pacote novo `com.nekkora.client.remote`: credencial (DPAPI), heartbeat, consulta, download, fila e relatórios.
- `run` continua sem rede. O executor ganha a origem da execução como dado do registro (`LOCAL` ou `PLATFORM`), e o
  diário recusa misturar: uma execução `LOCAL` nunca entra na fila nem em rota de máquina.
- **Captura de stdout e stderr é funcionalidade nova.** Hoje o Client drena a saída do processo e a descarta (só conta
  os bytes): não existe log nem da execução local. A missão cria a captura, com leitura incremental que nunca bloqueia o
  processo. Os logs de uma execução **local** ficam só no Client; os de uma execução **remota** são consultados só na
  Platform.
- Os testes de arquitetura são reescritos, não removidos: rede de máquina só em `remote`; `pack`, `run` e `nav` sem
  rede; rotas de pessoa continuam só em `/api/v1/desktop/`; e um teste novo prova que uma execução local não gera
  nenhuma chamada de máquina.
- Telas: "Execuções da Platform habilitadas/pausadas", organização e Worker, execução ativa e origem, pendências de envio
  e limpeza, "Ver logs na Platform". Sem visualizador de logs remotos.

## 6. Migrations

Sequência real: a última é a **041**. Números propostos, a confirmar no momento de cada PR:

| Nº | Conteúdo | Elevação |
|---|---|---|
| 042 | habilitação remota no vínculo, capacidades novas, pausa, renovação de credencial pela máquina, atos 15 a 17 | não prevista |
| 043 | `remote_executions`, `remote_execution_attempts`, índice de uma tentativa ativa por agente, atos de solicitar/cancelar/repetir | não prevista |
| 044 | declaração de dependências por automação (revisões) e o retrato na solicitação | não prevista |
| 045 | inventário de Python e escolha por máquina e automação | não prevista |
| 046 | linhas de log da tentativa, uso e retenção | **a confirmar**: a retenção da 026 é de donos que exigiram elevação |
| 047 | resultados da tentativa (metadados) | não prevista |

As migrations do canal (025, 030, 033, 035, 036, 040, 041) não precisaram de elevação. Se a 046 precisar, o PR traz a
justificativa, o diagnóstico, a reversão e o plano de operação. Nada é aplicado em produção nesta missão.

**Impacto de operação já conhecido:** o gateway da máquina precisará **ler** o armazenamento de pacotes e **gravar**
resultados. Hoje só o serviço da Platform acessa esse armazenamento.

## 7. PRs e ordem

| Tarefa | API | Web | Client |
|---|---|---|---|
| T00 | este plano | — | — |
| T01a habilitação e elegibilidade | 042 + rotas | tela de habilitação e Workers | pacote `remote`, pareamento, pausa, revogação |
| T01b continuidade da credencial | renovação | — | renovação e estados |
| T01c inventário de Python e dependências | 044, 045 | dependências e seleção de Python | inventário e verificação |
| T02 solicitação e entrega | 043 | — | consulta, ack, diário |
| T03 download, execução, resultados | 047, download | — | execução e envio |
| T04 recuperação, limpeza, outra máquina | repetição | — | reconciliação e limpeza |
| T05 logs | 046 | — | captura, fila, envio |
| T06 interface | — | solicitar, supervisão, logs, resultados | estado operacional |

Ordem de integração: API e migrations de cada tarefa antes da Web e do Client que dependem delas. PRs empilhados em
linha, por repositório, com a dependência escrita em cada um.

**A promoção Web #46 (`dev` → `main`) está aberta.** Qualquer PR da Web desta missão que entrar na `dev` antes de ela ser
mesclada ou fechada passa a fazer parte daquele pacote.

## 8. Publicação futura e reversão (não executadas nesta missão)

1. Migrations e API com a chave desligada: nenhum comportamento muda.
2. Web com as telas condicionadas à chave.
3. Client novo: sem habilitação, comporta-se como o atual.
4. Ligar a chave por ambiente; habilitar uma máquina de ensaio.

Reversão: desligar a chave faz o servidor **parar de entregar** tentativas novas; as que estão em `QUEUED` ou `OFFERED`
são expiradas, as em andamento terminam e reportam, e logs e resultados continuam sendo aceitos. Nenhuma solicitação
antiga é reentregue ao religar. Resultados e logs não são apagados na reversão.

## 9. O que não será provado sem recurso adicional

- **Desligamento abrupto real:** precisa de uma VM descartável ou máquina de ensaio autorizada. Sem ela, só será provado
  o encerramento abrupto do processo do Client, e o relatório dirá isso. O computador pessoal não será desligado.
- **Dois Workers:** serão dois perfis e processos isolados **no mesmo computador**. Isso comprova o isolamento e o
  encaminhamento entre Workers; **não comprova** portabilidade entre computadores com instalações diferentes (outro
  Python, outras bibliotecas, outros caminhos).
- **Desktop desbloqueado:** só é validável o que o Windows informa sobre a sessão; automação que exige tela desbloqueada
  fica como limite declarado.
- **Isolamento:** a pasta por tentativa não isola o processo.

## 10. Decisões da titular (15/11/2026)

| # | Decisão |
|---|---|
| 1. Habilitação | Reutilizar o pareamento por código, **explicitamente marcado para execução remota**, com confirmação no Client. O servidor verifica a permissão para administrar o vínculo e o Worker envolvido. |
| 2. Contrato separado | Sim: fluxo novo ao lado do antigo, que mantém o comportamento. Componentes comuns são reaproveitados quando adequado. |
| 3. Alcance | Versões publicadas dos ambientes autorizados, **respeitando as autorizações vigentes de automação e de monitoramento que já existirem**. Habilitar a execução remota não contorna essas regras. Uma lista adicional por máquina fica para depois. |
| 4. Limites | Os quatro valores apresentados servem como ponto de partida **no laboratório**. Todos configuráveis, com comportamento explícito ao atingir cada um. A tabela completa ainda precisa ser conferida antes de aprovar todos os valores. |
| 5. Web #46 | Não mesclar nem fechar automaticamente. A missão pode desenvolver e abrir PRs da Web, mas as mudanças ficam **fora da `dev` da Web** até a promoção anterior ser resolvida. Não impede a API. |
| 6. VM | Não presumir que exista. Seguir com testes isolados de encerramento do processo; o desligamento real fica identificado como **pendente**. O computador pessoal nunca é desligado. |

Condições acrescentadas pela titular, já incorporadas acima:

- **Logs:** atingir o limite não interrompe a captura em silêncio nem transforma o resultado do robô em falha; a Platform
  indica o truncamento e preserva o estado final (seção 5.11).
- **Retenção:** logs, resultados e histórico de execução têm regras distintas (seção 5.11).
- **Troca de máquina:** dois perfis no mesmo computador não comprovam portabilidade entre computadores (seção 9).
- **Captura de stdout/stderr** é funcionalidade nova e fica explícita na entrega (seção 5.12).

### Como a decisão 3 foi lida

Para solicitar uma execução remota, o servidor exige **todas** as condições:

1. o Worker está autorizado no ambiente (regra existente);
2. a máquina foi habilitada para execução remota e a habilitação não foi revogada nem está pausada;
3. existe uma **autorização vigente daquele Worker para aquela versão**, com a mesma impressão digital — a autorização
   de execução que o fluxo antigo já exige, criada por quem tem `automation.execute`, com confirmação reforçada e prazo;
4. essa autorização não é só de monitoramento (essas não servem para executar, como hoje);
5. a pessoa que solicita tem `automation.execute` no ambiente.

A habilitação remota dispensa a confirmação **na máquina** a cada pedido; não dispensa a autorização da versão na
Platform. Se a intenção era outra (por exemplo, dispensar a autorização por versão), isto precisa ser dito antes do T02,
que é onde a solicitação é implementada.
