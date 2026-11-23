# LICENCIAMENTO-002: migrations 074 e 075

Esta página cobre duas mudanças:
- a organização interna designada fica fora do limite de organizações vazias (074);
- o bloqueio da Nekkora passa a ter efeito real (075).

**Decisões da titular**
- **22/11, itens 3 e 4:** o que cada mudança deve fazer.
- **23/11:** autorizou preparar e testar as duas, só em laboratório:
  - **074:** elevação temporária só durante a aplicação. A exceção vale só para a organização formalmente designada.
  - **075:** leitura mínima pela Platform e pelo canal das máquinas. Só `organization_id` e `lifted_at`; sem escrita, motivo,
    chamado ou cadastro de operador.

**Não autoriza aplicar em produção.** As duas dependem da 073; o procedimento é o `licenciamento-002/operador/PROCEDIMENTO-OPERADOR.md`.

## 1. 074: organização interna fora do limite de vazias (com elevação)

- **O que muda.** `identity_empty_organizations(uuid)`, do dono `nekkora_identity_owner` (067), deixa de contar a organização
  com designação vigente (073: `withdrawn_at` nulo). Mudar o corpo dela exige ser o dono, daí a janela de SUPERUSER **só
  nesta versão**. A 074 entrou na lista padrão `NEKKORA_ELEVAR` do `db/bootstrap/aplicar_como_migrador.sh`.
- **Só a designação conta.**
  - A designação é o ato privilegiado e auditado da 073: quem, chamado, motivo e quando, feito pelo migrador no procedimento.
  - Nome da organização não muda nada: o teste tem uma organização com o nome "Nekkora Operacoes" sem designação, e ela conta.
  - Não há opção para o usuário.
- **Retirada.**
  - A organização volta a contar no ato.
  - Se a responsável passar do limite, a 067 recusa **novas** criações, aprovações de pedido e reativações (`EMPTY_LIMIT`).
  - Nenhuma organização é apagada, encerrada ou desativada.
- **Leitura.** O dono da identidade passa a ler, da designação, só `organization_id` e `withdrawn_at`. Os serviços da aplicação
  continuam sem nada (conferido nos invariantes e no teste).
- **Falha durante a aplicação.** O migrador suportado retira **todos** os atributos e confere `0|0|0` também quando a migration
  falha, antes de abortar:
  - linhas 104 a 108 do script: `N_RC`, `n_revoga` e só então `n_falha`;
  - o `migrar.sh` de produção propaga a falha: `set -euo pipefail` com `| tee`.

  Prova em laboratório: `licenciamento-002/evidencias-074-075/falha-074.txt`.
  - **Falha proposital.** Uma cópia da 074 com `SELECT 1/0` antes do `COMMIT`, elevada:
    - `ERROR: division by zero`, depois `SUPERUSER revogado, estado 0|0|0`, depois `FALHA`, com saída 1;
    - o migrador fica `false false false` e `0|0|0`;
    - nada da 074 ficou: a função continua a da 067 e não há a política nova;
    - o banco fica **sem marcador**, como em toda falha do migrador. A recuperação segue o RESTAURACAO.md: reaplicar a versão
      correta ou restaurar o dump `antes-de-NNN`.
  - **074 correta depois.** Aplicou e fechou em `0|0|0`.
  - **Sem a janela.** A pré-condição recusa: `074 exige a janela de elevacao`.

## 2. 075: o bloqueio da Nekkora com efeito real (sem elevação)

### 2.1 Leitura mínima

- **Quem lê, e como.**

  | Papel | Pode ler | Não pode |
  |---|---|---|
  | `nekkora_platform`, `nekkora_ingestion` | `SELECT (organization_id, lifted_at)`, por privilégio de coluna, **só da organização em contexto** (política `tenant_hold_context`, `nekkora.organization_id`) | motivo, quem bloqueou, quem levantou, motivo do levantamento, `hold_id`, `SELECT *`; INSERT, UPDATE, DELETE; designação; cadastros de operador |
  | `nekkora_portal`, `nekkora_provisioning` | nada | — |

- **Quando a organização está bloqueada.** `organization_held(org)` é verdadeira se existe **ao menos um bloqueio com `lifted_at`
  nulo**. Com histórico, vale o bloqueio ainda não levantado. A função é SECURITY INVOKER e, com outra organização em contexto
  ou sem contexto, responde falso: os serviços não descobrem bloqueios de outras organizações.
- **A organização interna designada** não é bloqueada por aqui: o gatilho recusa, porque isso tiraria o operador do ar. Retire a
  designação antes. O bloqueio nasce aberto.

### 2.2 O que é recusado (trabalho novo)

O banco reconfere tudo por gatilho; o erro é `ORGANIZATION_HELD`, com SQLSTATE 23514.

| Fluxo | Onde | Resposta |
|---|---|---|
| Escritas sob `/api/v1/o/{org}` | filtro de sessão da Platform | 403 `{"error":"forbidden","reason":"organization_held"}` |
| Solicitação remota e pedido ao Agent anterior | gatilhos `trg_075_remote_executions_hold` e `trg_075_run_requests_hold` | recusa |
| Autorização de execução ou monitoramento (Agent anterior e remoto) | `trg_075_local_run_authorizations_hold` | recusa |
| Código de pareamento novo | `trg_075_agent_enrollments_hold` | recusa |
| Resgate de código emitido antes do bloqueio | gateway (403 `organization_held`) e `trg_075_agent_enrollments_redeem_hold` | nada é consumido |
| Habilitação remota nova | `trg_075_agent_remote_enablements_hold` | recusa |
| Tentativa remota ainda não iniciada | `remote_attempt_blocker` → `ORGANIZATION_HELD` na criação, na oferta e antes de iniciar | não é lançada |
| Renomear automação pelo aplicativo desktop | `DesktopNavigationService` | 403 `organization_held` |
| Caminho que esqueceu de conferir | o gatilho recusa e o `GenericErrorHandler` traduz | 403 `organization_held` (não vira 500) |

O que continua aceito com a organização bloqueada:
- todas as leituras, incluindo download de resultados e registros;
- sair e revogar sessões;
- autenticar e confirmar identidade;
- cancelar, revogar, suspender, retirar, reduzir, recusar e reconciliar.

São atos que reduzem acesso ou encerram o que já começou.

### 2.3 O que é preservado (o que já começou)

Nenhum gatilho novo foi posto nos fluxos de entrega.
- **Execução remota.** A tentativa já iniciada termina, envia registros e resultados e fecha a entrega.
- **Agent anterior.**
  - A execução registrada continua entregando eventos.
  - O registro de uma execução feita com autorização já emitida é aceito, como entrega tardia.
  - A presença das máquinas continua.
- **Dados.** Ficam todos. A situação da organização (`organizations.status`) não muda: o bloqueio é separado da desativação
  voluntária (067).

### 2.4 Sessões existentes

- Continuam válidas, sem desconexão forçada, e passam a **somente leitura** na organização bloqueada.
- A pessoa vê o estado e a orientação: `/me` traz `organizationHeldByNekkora`, e a Web mostra um aviso no topo da área.
- **O administrador não desfaz o bloqueio.** O papel `nekkora_platform` não tem UPDATE na tabela (teste: 42501), e não há rota.
  Só o migrador levanta, pelo procedimento, com quem e motivo (073).

### 2.5 Limitação do Agent anterior (offline)

- **O que a autorização permite.** Uma autorização local já entregue vale **sem conexão até a validade**. O servidor não
  impede na hora uma execução feita offline com ela.
- **Quando o registro chega.** É aceito como trabalho autorizado. Com a leitura mínima decidida (sem a data do bloqueio), o
  servidor não distingue uma execução offline iniciada antes do bloqueio de uma iniciada depois. O instante de início vem do
  relógio da máquina, o mesmo limite da 064.
- **Na próxima conexão** a máquina não recebe autorização nova, e o pareamento novo é recusado.
- **Para encurtar a janela:** revogue as autorizações locais da organização pela Platform. Revogar continua aceito com a
  organização bloqueada.

## 3. Testes (laboratório, PostgreSQL 16 real)

| Teste | O que prova | Resultado |
|---|---|---|
| `OrganizacaoInternaEBloqueio074075IT` (nekkora-core) | as quatro provas descritas abaixo | 4/4 |
| `RemoteExecutionIT#bloqueioDaNekkora…` (nekkora-platform) | o efeito do bloqueio pela API e pelos papéis reais, descrito abaixo | 1/1 |
| `AgentGatewayIT#organizacaoBloqueadaPelaNekkora…` (gateway) | o efeito no canal das máquinas, descrito abaixo | 1/1 |
| `OperacaoPeloMigrador073IT`, `LicenseOperatorIT`, `OrganizationProfilesIT`, `AccessContractTest`, `GatewayContractTest`, `RemoteDeliveryIT` | regressão | 6/6, 4/4, 5/5, 2/2, 1/1, 20/20 |
| `licenciamento-002/evidencias-074-075/falha-074.txt` | falha proposital da 074 com retirada conferida; 074 correta depois; recusa sem a janela | ver §1 |
| `licenciamento-002/operador/teste/teste.sh` contra 001–075 | procedimento real; bloqueio com efeito; limite de vazias | ver PROCEDIMENTO-OPERADOR §7 |

**`OrganizacaoInternaEBloqueio074075IT`**
- A 074 aplicou com SUPERUSER só na janela dela e a 075 sem elevação.
- Limite de vazias:
  - pelo nome, nada muda;
  - designada, a organização sai da contagem;
  - retirada a designação, volta a contar e o limite trava;
  - nada é encerrado.
- Leitura mínima: colunas, papéis, isolamento e histórico.
- A interna não é bloqueada, e o bloqueio nasce aberto.

**`RemoteExecutionIT#bloqueioDaNekkora…`**
- `/me` mostra o bloqueio e a leitura continua.
- São recusados com 403: solicitação, autorização, pareamento, tentativa nova, ambiente e dependências. Nada é gravado.
- Os gatilhos recusam com 23514: solicitação e resgate.
- A tentativa na fila fica `ORGANIZATION_HELD`.
- A execução iniciada conclui e entrega o resultado, e cancelar funciona.
- O administrador não levanta o bloqueio nem lê o motivo.
- Outra organização não enxerga o bloqueio.
- A situação da organização não muda.
- Depois de levantado, tudo volta.

**`AgentGatewayIT#organizacaoBloqueadaPelaNekkora…`**
- O resgate dá 403 `organization_held` e nada é consumido.
- A presença, os eventos e o supervisor continuam.
- A entrega tardia é aceita.
- Nenhuma autorização nova é criada.
- Depois de levantado, o mesmo código pareia.

**Suíte completa da API, interrompida em 23/11.** O Claude Code a encerrou por falta de memória na máquina durante
`nekkora-platform`.
- Até ali: `nekkora-core` 31/0 e `nekkora-ops` 182/0.
- `nekkora-platform` e `nekkora-agent-gateway` não completaram; só os testes dirigidos da tabela rodaram.
- Pela regra da titular, registrei e parei; falta repetir com memória livre.
- Saída: `licenciamento-002/evidencias-074-075/api-suite-completa.txt`.

**A validar na integração**, com os Agents reais (§4).

## 4. Validação com os Agents reais (para a validação integrada)

Laboratório integrado com a API desta branch, a Web da PR do bloqueio e os pacotes candidatos (Agent 0.2.0 `8445367`,
Agent Client 0.1.0 `b483cce`).

1. **Agent Client, execução remota.**
   - Inicie uma execução longa e espere `RUNNING`; deixe outra na fila em outra máquina.
   - `operador.sh bloqueio abrir <org> …`.
   - A execução em andamento termina e os resultados aparecem em Execuções.
   - A da fila vira recusada com "a organização foi bloqueada pela Nekkora antes do início".
   - Solicitar de novo dá a mensagem do bloqueio, que não é a de permissão nem a de licença.
   - Um código novo é recusado na geração, e um código gerado antes é recusado no Client sem consumir.
2. **Agent anterior (0.2.0), online.**
   - Com uma execução em andamento, abra o bloqueio.
   - A execução termina e é registrada.
   - Uma execução nova feita com a autorização vigente também é registrada: este é o limite documentado.
   - Nenhuma autorização nova chega.
   - Revogar a autorização pela Platform (continua aceito) faz a próxima execução ser recusada.
3. **Agent anterior, offline.**
   - Desconecte a máquina, execute com a autorização vigente e abra o bloqueio.
   - Reconecte: o registro tardio é aceito e entregue, e nada novo é autorizado.
   - Registrar a hora de cada passo, porque o servidor não distingue antes e depois do bloqueio (§2.5).
4. **Web.**
   - Com a organização bloqueada, o aviso "Organização bloqueada pela Nekkora" aparece no topo.
   - Os atos recusados mostram a orientação de suporte.
   - O administrador não tem como desfazer o bloqueio.
5. **Levantar** (`operador.sh bloqueio levantar …`): o aviso some e o trabalho novo volta.

Testes de tela sem Robot: o que precisar de clique no aplicativo vai para o roteiro de aceite manual.
