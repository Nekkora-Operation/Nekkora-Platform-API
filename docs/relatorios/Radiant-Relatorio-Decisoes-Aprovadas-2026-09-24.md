# Radiant — Relatório de decisões aprovadas

**Data:** 24/09/2026  
**Escopo:** Phase 01 — Platform Data Foundation  
**Situação:** baseline conceitual aprovada em conversa; modelagem física e migrations ainda não concluídas.

## 1. Finalidade e limites deste relatório

Consolidar as decisões aprovadas pela fundadora sobre a próxima arquitetura da Radiant, incluindo as revisões feitas durante a discussão. Este relatório orienta o ERD, o dicionário de dados e a preparação gradual das migrations. Não é uma declaração de implementação ou de segurança já testada.

Os nomes de tabelas e campos abaixo representam o modelo discutido. Tipos SQL, nulabilidade, índices, constraints, relações completas e detalhes transacionais ainda precisam de validação. Exemplos de planos, perfis e permissões não constituem catálogo comercial definitivo.

A baseline atual foi informada pela fundadora. O arquivo SQL e o executor de migrations não foram inspecionados nesta revisão.

## 2. Diretrizes arquiteturais

- Modelar primeiro; implementar depois.
- Manter a Radiant atual em desenvolvimento, teste e uso como POC e base comercial.
- Não reescrever módulos apenas para uniformizar tecnologia.
- PostgreSQL é a direção para persistência central; Java e Spring Boot são a direção futura da Platform API.
- Core e Understanding permanecem na implementação atual; migração exige benefício demonstrado, contratos estáveis e validação comparativa.
- Studio futuro orientado a Java; Swing permanece sujeito a estudo de viabilidade, não a adoção automática.
- Studio e Recorder poderão integrar um produto/repositório, com separação semântica interna: Recorder registra o ocorrido; Studio estrutura o que deveria ocorrer.
- Evitar microservices e infraestrutura adicional sem necessidade demonstrada. A separação de execução e identidade do serviço de credenciais foi aprovada por uma necessidade específica de segurança.
- Agent executa e observa; Platform decide e conhece o estado operacional. Não colocar lógica comercial, PDD, SDD, Semantic ou IA de processo no Agent.

Ordem preservada: fundação de dados da Platform → consolidação do Agent → Platform API → fundação de dados do Studio → Studio pelo domínio/terminal → experiência de produto.

**Gate da Phase 01:** ERD + Data Dictionary + Ownership + Lifecycle + Isolation Rules + Migration Strategy. Este gate ainda está aberto.

## 3. Baseline operacional atual declarada

| Tabela | Conteúdo informado |
|---|---|
| `workers` | WorkerId, AgentId, nome, hash do token, registro, heartbeat, estado e versão do Agent |
| `automations` | AutomationId, nome e `command_json` JSONB |
| `executions` | ExecutionId, WorkerId, AutomationId, snapshot do comando, estado, PID, tempos, duração, exit code e erro |
| `events` | EventId, ExecutionId, sequência, nome, horários, payload JSONB e fingerprint |
| `logs` | LogId, WorkerId, ExecutionId opcional, canal, horários, payload JSONB e fingerprint |
| `schema_migrations` | Versão, checksum e data de aplicação |

Garantias declaradas a preservar:

- Uma execução ativa por Worker, protegida por índice único parcial.
- `UNIQUE(execution_id, sequence)` para sequência de eventos.
- Eventos e logs append-only, com triggers bloqueando alteração, exclusão e truncamento.
- Foreign keys e canais `AGENT`, `STDOUT`, `STDERR`.
- Apenas `001_initial.sql`, aplicada pelo executor `src/migrations.ts` em transação, com lock e checksum SHA-256.
- Não existe descoberta automática de migrations 002, 003 etc.; evolução do executor será necessária.

## 4. Organizações, planos e assinatura

Organization representa a empresa responsável pela operação, com UUID próprio, independente de usuários e plano contratado.

| Estrutura | Conteúdo/responsabilidade |
|---|---|
| `organizations` | `organization_id`, nome de exibição, razão social quando necessária, status, criação e atualização |
| `plans` | Identidade dos planos comerciais |
| `subscriptions` | Contratação da organização, versão de plano, vigência e situação |

O plano será apresentado junto ao cadastro na experiência do produto, mas armazenado separadamente. Trocas não apagam o histórico de contratação. Organização admite situações como ativa, suspensa e encerrada; efeitos completos ainda serão detalhados.

## 5. Identidade corporativa e cadastro mínimo

**Decisão vigente:** cada conta interna pertence a uma única organização. A mesma pessoa pode ter contas distintas em empresas diferentes.

Exemplo: `nicolly.carvalho@radiant.com` acessa Radiant; `nicolly.carvalho@teste.com` acessa Teste. Contas têm UUIDs, credenciais, sessões e papéis próprios. Não ocorre unificação automática por pessoa, e-mail semelhante ou CPF.

- Não coletar CPF inicialmente nem buscar comprovar identidade civil com o cadastro do produto.
- Manter nome de exibição, e-mail corporativo, verificação, status e identificadores necessários.
- Controle de e-mail não equivale a comprovação de identidade civil.
- Exigir e-mail verificado, domínio autorizado e vínculo aprovado por convite/provisionamento.
- O domínio sozinho não concede acesso.
- Sessão restrita à organização da conta; outra empresa exige a conta correspondente.
- A restrição de uma organização por conta interna deve ser protegida no banco, além da aplicação. Unicidade apenas do par organização/usuário não basta.

| Estrutura | Responsabilidade |
|---|---|
| `users` | Identidade mínima da conta |
| `user_credentials` | Autenticação separada do cadastro |
| `organization_memberships` | Vínculo e situação de acesso da conta interna |
| `organization_domains` | Domínios autorizados e sua verificação |

Sessões, convites e recuperação de conta precisam constar no dicionário final, mas seus schemas e ciclos não foram fechados. As regras específicas de identidade externa também precisam completar o modelo; não se deve impor ao cliente externo o domínio da consultoria por inferência.

## 6. Papéis, permissões e isolamento

| Estrutura | Responsabilidade |
|---|---|
| `roles` | Papéis de acesso |
| `permissions` | Ações por recurso |
| `role_permissions` | Composição dos papéis |
| `membership_roles` | Papéis do vínculo com a organização |
| `workspace_memberships` | Acesso interno explícito a um Workspace |
| `workspace_membership_roles` | Papéis naquele escopo |

Diretrizes aprovadas:

- Negar por padrão e validar toda operação no backend.
- Verificar identidade/sessão, vínculo ativo, organização do recurso, Workspace e ação.
- Conhecer UUID, rota ou nome não concede autorização.
- Permissões distintas para visualizar, criar, editar, excluir, executar, cancelar, exportar e administrar acesso.
- Impedir concessão de poderes acima do autorizado para delegação.
- Papéis organizacionais não se replicam automaticamente em todos os Workspaces; poderes administrativos devem ser explícitos.
- Auditar concessões, alterações e revogações.
- Uma permissão não elimina regras do domínio, como proteção do histórico.
- Respostas da API contêm apenas campos permitidos; ocultar elementos na tela não protege dados já enviados ao navegador.

Papéis predefinidos são a direção inicial. Matriz exata e regras de combinação de papéis ainda pendentes.

## 7. Clientes e acesso externo supervisionado

Cliente é cadastro separado da organização e das pessoas que o acessam. Uma organização atende N clientes; um cliente pode ter N contas externas individuais. Acesso externo não torna a conta membro interno da organização.

| Estrutura | Responsabilidade |
|---|---|
| `clients` | Cliente atendido, pertencente a uma organização |
| `client_memberships` | Conta externa vinculada ao cliente |
| `client_access_grants` | Aprovação, escopo, validade e situação do acesso |
| `access_reviews` | Revisões e decisões administrativas |

Fluxo: convite/solicitação → verificação de e-mail → aprovação administrativa de ambientes, escopo e prazo → acesso de leitura sujeito às verificações do backend.

Dados previstos para concessão: início, vencimento opcional, aprovador/data, último acesso, próxima revisão e revogador/data. Posicionamento desses campos entre vínculo e concessão será refinado.

- Acesso sem vencimento continua sujeito a revisão periódica.
- Inatividade gera alerta; administrador decide manter, suspender ou revogar.
- Login não renova concessão vencida nem substitui revisão.
- Expiração, suspensão e revogação bloqueiam novas consultas inclusive em sessão aberta.
- Cliente consulta relatórios publicados e execuções do próprio escopo, com campos autorizados.
- Sem criação, edição, exclusão, execução ou cancelamento de automações.
- Comandos internos, credenciais e logs brutos bloqueados por padrão; exportações exigem permissão específica.
- Rota dedicada é organização da experiência, não mecanismo de isolamento.

Prazos e consequência de revisão atrasada ainda pendentes.

## 8. Workspaces e propriedade dos recursos

**Uma única tabela `workspaces`**, substituindo a proposta preliminar de `client_workspaces`.

Campos iniciais: UUID, organização proprietária, cliente opcional, nome, descrição, status e datas.

- Organização possui N Workspaces; cada Workspace pertence a exatamente uma organização.
- Workspace atende no máximo um cliente; cliente pode ter N Workspaces.
- Cliente e Workspace devem pertencer à mesma organização, com integridade protegida no banco.
- Espaço interno pode não ter cliente.
- Associar cliente não publica dados automaticamente no portal.
- Automações pertencem ao Workspace; execuções preservam o contexto em que ocorreram; eventos/logs da execução seguem esse escopo.
- Relatórios dependem de publicação e autorização explícitas.
- Arquivar preserva histórico e impede novas operações; não implica apagar execuções, eventos ou auditoria em cascata.
- Transferência de recursos entre Workspaces exige tratamento explícito, pois altera acesso; não é simples troca de ID.

**Revisão posterior aprovada:** desenvolvimento ocorre localmente no cliente e a operação gerenciada será apenas produção inicialmente. Não criar DEV/HML hospedados como requisito desta primeira versão. A proposta anterior de múltiplos ambientes internos não será implementada automaticamente.

## 9. Worker, Agent e vínculos

Worker é a unidade lógica durável de execução. Agent é a instalação autenticada que executa e observa processos.

| Estrutura | Responsabilidade |
|---|---|
| `workers` | UUID, organização, nome, situação administrativa e datas |
| `agents` | UUID, organização, versão, situação, registro e heartbeat |
| `worker_agent_assignments` | Histórico do vínculo com início e fim |
| `agent_credentials` | Autenticação, validade e revogação separadas do cadastro |
| `worker_workspace_assignments` | Workspaces em que o Worker pode executar |

- Modelo inicial: vínculo ativo um para um entre Worker e Agent, da mesma organização.
- Reinstalação/substituição pode criar novo Agent sem perder identidade e histórico do Worker.
- Execução registra Worker e Agent daquela tentativa; trocas não reatribuem histórico.
- Preservar exclusividade de execução ativa por Worker.
- Worker pode atender Workspaces autorizados da mesma organização; isso não comprova isolamento da máquina, dos arquivos ou das credenciais.
- Estado administrativo é distinto de presença observada: autorizado não significa online.
- Heartbeat vencido não comprova término do processo nem libera reexecução.
- Troca/revogação exige reconciliação de operações incertas; preserva histórico.

Na migração, separar AgentId, heartbeat, versão e credencial hoje agrupados em workers, sem inventar histórico ausente.

## 10. Automation, versões e Execution

| Estrutura | Responsabilidade |
|---|---|
| `automations` | UUID, organização, Workspace, nome, descrição, status e datas |
| `automation_versions` | Versão publicada imutável, número, comando, schema de entrada e publicação |
| `executions` | Uma tentativa concreta vinculada à versão utilizada |

- Alterar comando publicado cria outra versão.
- Rascunhos e edição pelo Studio ficam para sua fase própria.
- Suspender/arquivar automação impede novas execuções, sem encerrar automaticamente processos iniciados.
- Execution preserva organização, Workspace, automação, versão, snapshot sem segredos, solicitante, origem, idempotência, Worker/Agent quando alocados, PID, tempos, resultado e erro sanitizado.
- Valores secretos são referenciados, não embutidos em comandos ou snapshots.
- Reenvio da mesma solicitação retorna a execução existente.
- Reexecução intencional cria novo UUID e referência `retry_of_execution_id`.
- Cada Execution representa uma tentativa de iniciar/acompanhar um processo; retry de transmissão não cria nova tentativa.

| Fase | Estado | Significado |
|---|---|---|
| Início | `PENDING` | Solicitação aceita aguardando preparação |
| Início | `STARTING` | Agent preparando/tentando iniciar |
| Executando | `RUNNING` | Processo iniciado confirmado |
| Fim | `SUCCEEDED` | Sucesso conforme critério definido |
| Fim | `FAILED` | Falha de partida ou execução |
| Fim | `CANCELLED` | Cancelamento confirmado; inclui cancelamento anterior ao despacho |

Desconexão não implica término. Pedido de cancelamento não implica cancelamento confirmado. Critério de sucesso não será inferido indiscriminadamente de texto de log ou exit code.

## 11. Events, Logs e sincronização

Events descrevem fatos; Logs são diagnóstico. Mensagem em STDERR não determina falha automaticamente.

| Estrutura | Campos conceituais discutidos |
|---|---|
| `events` | ID, organização/Workspace, execução, Agent quando origem, source, sequência, nome, schema_version, occurred_at, received_at, payload e fingerprint |
| `logs` | ID, organização, Workspace quando aplicável, Agent/Worker, execução opcional, canal, severidade quando conhecida, horários, payload e fingerprint |

- Agent é o único produtor da sequência operacional por execução; eventos da Platform não disputam esse contador.
- A unicidade atual será preservada para eventos sequenciados do Agent; tratamento SQL dos eventos da Platform ainda será definido.
- Logs gerais do Agent não são atribuídos automaticamente a clientes ou Workspaces.
- Estado operacional é atualizado por regras da Platform a partir de fatos; não por interpretação arbitrária de logs.

Sincronização: sanitizar → persistir localmente → enviar com IDs originais → validar e persistir na Platform → ACK → marcar sincronizado e aplicar retenção local.

- Mesmo ID/conteúdo: reenvio, sem duplicação.
- Mesmo ID/conteúdo diferente: conflito rejeitado e registrado.
- Fingerprint auxilia consistência; não autentica origem.
- Detectar lacunas e entrega fora de ordem; impedir regressão indevida do estado.
- ACK confirma persistência, não necessariamente aplicação do evento ao estado.
- Eventos/logs append-only para a aplicação; correções geram registros adicionais.
- Redaction antes de gravar localmente, com validação adicional central.
- Limites de payload, quotas e retenção separados.
- Expiração controlada e auditada deve coexistir com os triggers atuais; sem exclusão livre pela aplicação.
- Portal externo não recebe registros brutos automaticamente.

## 12. Auditoria

`audit_records` registra autor, ação, contexto, recurso, horário e resultado, separado de logs e eventos de execução.

Campos discutidos: UUID, organização, Workspace opcional, actor_type, actor_id quando conhecido, action, resource_type/resource_id, outcome, reason_code, occurred_at do backend, correlation_id e changes_json sanitizado.

- Autores: usuário, Agent ou sistema; tentativas não autenticadas podem não ter autor identificado.
- Organização desconhecida com confiança: escopo de segurança da Platform; não confiar em UUID enviado pelo solicitante.
- Resultados: realizado, negado ou falhou.
- Auditar acesso, permissões, Workspaces, publicação, pedidos operacionais, Agents e exportações/consultas sensíveis.
- Pedido de cancelamento é auditado como pedido; término confirmado é fato operacional distinto.
- Alterações críticas e sua auditoria são confirmadas na mesma transação de banco.
- Backend determina identidade, horário e resultado.
- Aplicação e administradores da organização não editam/excluem registros individualmente.
- Guardar somente alterações necessárias, sem senhas, tokens ou payloads completos indiscriminados.
- Permissões específicas para consulta/exportação e retenção própria.

Auditoria de falhas/negações, efeitos externos e operações com provedor de segredos precisa de desenho transacional complementar; não se presume transação distribuída com o provedor.

## 13. Parâmetros e serviço de credenciais

Cadastro manual e importação de `.env` pela Platform. `.env` é formato de entrada: chaves são revisadas e classificadas antes da confirmação; não há necessidade de reter o arquivo original.

O usuário usa nomes simples, como `SAP_PASSWORD`. Referência interna sugerida para SSM:

```text
/radiant/prod/organizations/{organization_id}/workspaces/{workspace_id}/NOME_VARIAVEL
```

IDs podem ser conhecidos e nomes podem ser renomeados; nenhum deles serve como credencial. Não adotar uma chave permanente geral `Secret_Org` para liberar todos os segredos.

| Componente | Responsabilidade aprovada |
|---|---|
| Platform | Nomes, metadados, vínculos, permissões e execução |
| Serviço de credenciais | Validar acesso e resolver valores no provedor |
| Agent | Solicitar somente segredos necessários à execução atribuída |
| PostgreSQL | Cadastro, controles e referências, sem segunda cópia dos valores secretos |
| Frontend | Cadastro e metadados autorizados; sem revelação por padrão |

Para impedir leitura técnica pela Platform, o serviço de credenciais terá execução e identidade/permissões próprias. Não basta separar classes ou tabelas.

Estruturas discutidas: `configuration_sets`, `configuration_entries`, `secrets` e `secret_versions`. Metadados de segredo incluem organização, Workspace, nome lógico, provider, provider_reference e status. Versões apontam para versões do provedor; não duplicam o valor secreto no PostgreSQL. A consolidação do namespace de parâmetros/configuration_sets ainda precisa ser refinada.

Direção de provedores: Parameter Store para parâmetros, SecureString para configurações sensíveis e Secrets Manager preferencialmente para credenciais/rotação. A escolha final por tipo permanece pendente.

Regras aprovadas:

- Agent usa API, sem query direta no banco nem caminho arbitrário AWS fornecido pelo cliente.
- Verificar identidade do Agent, execução atribuída, organização/Workspace e dependências autorizadas da versão.
- Listar metadados, alterar e obter valor têm permissões distintas.
- Pertencer ao Workspace não libera todos os segredos dele.
- Chave temporária, se adotada, terá escopo por Agent/execução/segredos e validade curta; protocolo ainda pendente.
- Registrar versões/revisões utilizadas pela execução sem valores em logs, snapshots ou auditoria.
- Revogação impede reutilização automática em reexecução.
- Desenvolvimento local poderá consultar recursos/segredos via API sob autorização específica; produção não é liberada automaticamente a desenvolvedores.
- Depois que um segredo é entregue à máquina autorizada, não é possível garantir que seu controlador não o copie.
- Na criação manual, o navegador necessariamente conhece o valor digitado; a ausência de leitura posterior não elimina esse momento.
- Autorização do provedor deve considerar leitura por caminho, histórico e descriptografia, além da autorização da Radiant.

Segurança geral preservada: senha da Radiant com hash irreversível e salt, direção Argon2id; criptografia em trânsito/em repouso; chaves gerenciadas separadamente; nenhum hash de senha/token de recuperação em resposta de cadastro.

## 14. Licenciamento e direitos contratados

Direitos pertencem à organização; permissões pertencem às contas/vínculos. Contratar capacidade não concede poderes administrativos.

| Estrutura | Responsabilidade |
|---|---|
| `plan_versions` | Composição imutável do plano |
| `entitlement_definitions` | Catálogo dos direitos/limites |
| `plan_entitlements` | Direitos incluídos por versão |
| `subscription_entitlements` | Adicionais/condições específicas com vigência |
| `builder_seat_assignments` | Licenças nominais para contas internas |

- Planos/subscriptions preservam histórico; novas condições não alteram contratos anteriores silenciosamente.
- Tipos suportados: disponibilidade, quantidade, capacidade e consumo periódico.
- Exemplos: Studio habilitado, Builder seats, Workers habilitados, Client Workspaces e franquia de Hosting. Quantidades/preços não fechados.
- Distinguir zero de ilimitado.
- Builder nominal; revogação libera vaga e preserva trabalhos; não concede acesso a todos os Workspaces.
- Contas externas de consulta não consomem Builder seats.
- Platform aplica regras comerciais, inclusive proteção contra ultrapassagem por atribuições concorrentes.
- Downgrade não apaga recursos; uso acima do novo limite exige regularização.
- Franquia esgotada não encerra abruptamente processos em andamento.
- Separar direito contratado, alocação, consumo medido e consumo faturável.
- Falha/duração não determinam cobrança sozinhas; critérios de excedente/reexecução/arredondamento ainda pendentes.

## 15. Hosting, capacidade e bancos de automação

Começar com configuração básica dimensionada conforme processo, com perfis ajustáveis. CPU/memória, concorrência, arquivos, crescimento de logs, banco e continuidade compõem a análise.

Separações aprovadas:

- Banco da Platform: domínio e controles internos.
- Eventos/logs: diagnóstico e operação com retenção própria; separação lógica não obriga servidor separado.
- Banco da automação: dados de negócio opcionais, sem acesso direto das automações ao banco interno da Platform.

| Estrutura | Responsabilidade |
|---|---|
| `resource_profiles` | Perfis de capacidade |
| `hosting_allocations` | Recursos disponibilizados, organização, Workspace atendido, capacidade e vigência |
| `automation_datastores` | Banco opcional gerenciado pela Radiant ou fornecido pelo cliente, escopo e referência de conexão |
| `resource_usage_samples` | Amostras de utilização com retenção limitada |
| `usage_records` | Consumo consolidado para franquias/eventual cobrança |

- Worker é capacidade lógica; Agent executa; alocação identifica infraestrutura.
- Preservar infraestrutura utilizada por execução ao trocar máquina.
- Banco de negócio separado não significa obrigatoriamente servidor físico dedicado por cliente.
- Limites separados para arquivos, logs e banco.
- Alertas antecipados e expansão autorizada, sem aumento silencioso de custo.
- Backup para bancos gerenciados.
- Nenhuma exclusão automática de dados de negócio para liberar espaço.
- Credenciais ficam no serviço de segredos.

Tamanhos, topologia, quotas, medição faturável, política de backup e compartilhamento físico ainda serão definidos. A compatibilidade entre Worker multi-Workspace e alocações de infraestrutura deve ser fechada no ERD.

## 16. Agendamento e fila

| Estrutura | Responsabilidade |
|---|---|
| `schedules` | Identidade, automação, Workspace e situação |
| `schedule_revisions` | Regra de horário, fuso, versão da automação e políticas |
| `schedule_occurrences` | Cada ocorrência prevista e encaminhamento |
| `execution_queue` | Execuções pendentes, prioridade, reserva e condições de despacho |

- Início com agendamento único, diário e semanal.
- Fuso explícito e instantes calculados em UTC.
- Versão da automação fixa; atualização gera revisão auditada.
- Ocorrência única evita duplicação do mesmo disparo.
- Solicitação aceita cria Execution PENDING e entrada na fila.
- Execução manual usa a fila sem ocorrência de agendamento.
- Fila durável no PostgreSQL; sem mensageria adicional inicialmente.
- Prioridade e ordem de entrada dentro da mesma prioridade.
- Worker indisponível: aguardar até prazo limite.
- Atraso por indisponibilidade: tolerância configurada; fora dela, ocorrência perdida.
- Por padrão não sobrepor execução anterior da mesma automação; ocorrência ignorada registra motivo.
- Ocorrência perdida/ignorada não é uma execução que falhou.
- Suspender agendamento impede novas ocorrências; solicitações já aceitas precisam de cancelamento separado.
- Sem reexecução automática inicialmente; reexecução autorizada tem novo UUID.
- Reserva exclusiva impede dois despachantes de ocupar o mesmo Worker.
- Reenvio conserva execution_id; Agent consulta registro local durável antes de iniciar processo.
- Após envio incerto, não redistribuir sem reconciliação.
- Prazo expirado antes de qualquer despacho: CANCELLED com motivo QUEUE_DEADLINE_EXCEEDED.
- A fila não substitui os estados da execução; STARTING é confirmado pela preparação no Agent e RUNNING pelo início do processo.

## 17. Decisões substituídas durante a discussão

| Proposta anterior | Decisão vigente |
|---|---|
| Mesma conta interna em várias organizações | Conta interna restrita a uma organização; outra empresa exige conta própria |
| Possível deduplicação civil por CPF | Não coletar CPF inicialmente; não unir permissões por pessoa |
| `client_workspaces` separado | `workspaces` único com client_id opcional |
| DEV/HML/PRD hospedados por Workspace | Desenvolvimento local; operação gerenciada somente produção inicialmente |
| Valores secretos criptografados no PostgreSQL | Provedor especializado; PostgreSQL guarda referências/metadados |
| Platform resolve diretamente todos os segredos | Serviço de credenciais com identidade e execução separadas |
| Secret_Org como chave geral de acesso | Identidade autenticada e autorização restrita; IDs não autorizam |

## 18. Inventário consolidado das estruturas discutidas

Inventário das estruturas lógicas discutidas. Não implica uma nova tabela para cada nome: algumas já existem e outras poderão ser consolidadas após revisão física.

| Grupo | Estruturas |
|---|---|
| Organização e identidade | organizations, users, user_credentials, organization_memberships, organization_domains |
| Autorização | roles, permissions, role_permissions, membership_roles, workspace_memberships, workspace_membership_roles |
| Cliente e Workspace | clients, client_memberships, client_access_grants, access_reviews, workspaces |
| Execução e Agents | workers, agents, worker_agent_assignments, agent_credentials, worker_workspace_assignments, automations, automation_versions, executions |
| Evidência operacional | events, logs, audit_records |
| Configuração | configuration_sets, configuration_entries, secrets, secret_versions |
| Comercial | plans, plan_versions, entitlement_definitions, plan_entitlements, subscriptions, subscription_entitlements, builder_seat_assignments |
| Hosting | resource_profiles, hosting_allocations, automation_datastores, resource_usage_samples, usage_records |
| Agendamento | schedules, schedule_revisions, schedule_occurrences, execution_queue |
| Controle técnico existente | schema_migrations |

## 19. Pendências antes do schema definitivo

1. ERD completo, tipos/nulabilidade, chaves compostas de isolamento, unicidades, índices e política de exclusão.
2. Modelo completo das contas externas, sessões, convites, recuperação e validação de domínios.
3. Matriz RBAC, escopos e delegação; publicação de relatórios e concessões externas.
4. Ciclos de vida e efeitos de suspensão/arquivamento/revogação em operações pendentes ou em andamento.
5. Definição exata de execução ativa para o índice parcial: fila deve admitir múltiplas pendências sem reservar previamente o mesmo Worker.
6. Reserva, despacho, cancelamento concorrente, recovery e reconciliação; inclusive crash entre criação do processo e persistência do PID. Nenhuma garantia de execução exatamente uma vez foi demonstrada.
7. Sequenciamento dos eventos da Platform, lacunas, replay e projeções operacionais.
8. Retenção, quotas, payloads máximos, exclusão controlada e comportamento de buffer/disco cheio.
9. Provedor e protocolo de credenciais; autorização de cadastro, consistência PostgreSQL/provedor, rotação, cache, indisponibilidade e acesso local.
10. Composição/vigência dos direitos, offline do Studio, downgrades e limites concorrentes.
11. Isolamento físico/lógico da infraestrutura, backups/restore, dados de consumo e regras de cobrança.
12. Calendários: horário inexistente/repetido, tolerâncias, prioridade e escopo exato da regra de sobreposição.
13. Ownership por módulo e classificação: transacional, evento imutável, projeção, configuração e auditoria.
14. Migração real, rollback/convivência e evidências dos testes.
15. Solicitações/incidentes futuros previstos na missão permanecem sem modelagem detalhada; não foram aprovadas novas funcionalidades nessa área.

## 20. Preparação gradual das migrations

**Aprovado pela fundadora:** preparar as migrations aos poucos. Nenhuma migration nova é criada ou aplicada por este relatório.

A sequência abaixo é recomendação de trabalho para revisão, não numeração definitiva de arquivos:

| Lote sugerido | Conteúdo | Evidência antes de avançar |
|---|---|---|
| 0 — Diagnóstico | Inspecionar 001_initial.sql, executor e estado real; mapear divergências | Baseline verificável e caminho de recuperação |
| 1 — Identidade e isolamento | Organização, contas, vínculos, RBAC, clientes e Workspaces | Relações consistentes e testes negativos entre organizações |
| 2 — Núcleo operacional | Worker/Agent, versões, execuções e vínculos | Preservação de IDs/snapshots e exclusividade operacional |
| 3 — Eventos e auditoria | Ajustar escopos, sequência, deduplicação e auditoria | Reenvios, conflitos e histórico protegidos |
| 4 — Configuração | Metadados de parâmetros/segredos e referências | Sem valores secretos indevidos e autorização de uso validada |
| 5 — Comercial e Hosting | Direitos, licenças, recursos e consumo | Limites concorrentes e vínculos auditáveis |
| 6 — Agendamento | Revisões, ocorrências, fila e reservas | Sem duplicação e sem redistribuição de execução incerta |

Dependências podem alterar os lotes. Por exemplo, auditoria mínima deve estar disponível antes de ativar alterações críticas em produção.

Recomendações para cada lote: expansão compatível, backfill explícito, validação, ativação progressiva e remoção de legado apenas quando segura. Não alterar silenciosamente migration já aplicada; evolução do executor deve preservar checksum, lock e transações. Não atribuir todos os registros antigos a uma organização fictícia sem mapeamento aprovado. Não inferir versões históricas pelo comando atual.

## 21. Critérios de validação consolidados

- Trocar IDs, listar, exportar e consultar relações não atravessa organizações/clientes/Workspaces.
- Revogação bloqueia novas consultas mesmo com sessão aberta.
- Dados sensíveis não aparecem em respostas, logs, auditoria ou snapshots indevidos.
- Agent não acessa segredos de outra execução/organização nem por simples troca de referência.
- Mesma solicitação/evento/log reenviado não duplica registros; conteúdo conflitante é detectado.
- Eventos atrasados/lacunas não causam regressão incorreta de estado.
- Uma reserva/execução ativa por Worker; duas instâncias de despacho não duplicam processo.
- Desconexão e crash exigem reconciliação antes de nova tentativa.
- Alteração crítica de banco não é confirmada sem auditoria correspondente.
- Mudança de versão/plano/Agent não altera fatos históricos.
- Downgrade não apaga dados; consumo esgotado não encerra processo abruptamente.
- Migração preserva baseline e oferece convivência/recuperação verificável.

## 22. Encaminhamento

O conjunto acima constitui a base conceitual aprovada. Próximo trabalho: consolidar ERD, dicionário de dados, ownership e ciclos de vida; inspecionar a migration atual; resolver as pendências do primeiro lote; então preparar SQL incremental revisável. APIs definitivas e avanço para implementação continuam sujeitos aos gates da missão.
