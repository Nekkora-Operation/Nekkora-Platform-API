# Migration 073: operação de licenças pelo migrador, organização interna e bloqueio da Nekkora

NEKKORA-LICENCIAMENTO-002, decisões da titular de 22/11/2026 (itens 2 a 5). **Candidata, só laboratório.** Nada disto foi
aplicado em homologação ou produção. Procedimento do host: `licenciamento-002/operador/operador.sh` e
`PROCEDIMENTO-OPERADOR.md`.

## 1. O que a 073 entrega (sem elevação)

| Decisão | Entregue na 073 |
|---|---|
| 2. Opção B: o migrador com só o acesso necessário | Políticas `TO migrador_nekkora` (`migrator_procedure*`) para escrita em `license_operators`, `nekkora_internal_organization_designations`, `license_rollout` (só SELECT e UPDATE) e `organization_nekkora_holds`. Leitura `operator_check` restrita à organização declarada em `nekkora.operador_org`, só para as conferências do procedimento: organização sem uso, conta interna ativa e segundo fator confirmado. **Nenhuma** permissão nova para `nekkora_platform`, `nekkora_portal`, `nekkora_provisioning`, `nekkora_ingestion` ou para os donos. Sem BYPASSRLS, sem papel novo e sem `SECURITY DEFINER`. A pré-condição recusa rodar elevada e a conferência final recusa política fora do migrador. |
| 2. Recusas dos papéis | Os quatro papéis da aplicação recebem `permission denied` ao designar, cadastrar, revogar, mudar a D7, bloquear ou levantar (`OperacaoPeloMigrador073IT`, `teste.sh` caso 2). |
| 2. O migrador só faz o previsto | Gatilhos: designação, cadastro e bloqueio nascem vigentes. Só a retirada, a revogação ou o levantamento é aceito, **uma vez**, com quem e motivo, e nada é apagado (`prevent_delete`/`prevent_truncate`). A D7 só pode ser atualizada, não inserida. Fora dessas tabelas o migrador continua sem alcance: por exemplo, não lê nem grava licenças. |
| 3. Designação formal e auditada | Tabela `nekkora_internal_organization_designations` com quem, chamado, motivo e quando, e a retirada com os mesmos campos. **Uma vigente por vez.** Só o migrador escreve, pelo procedimento. Nunca pelo nome da organização nem por opção do usuário. A aplicação não lê esta tabela. |
| 3. Coerência com `NEKKORA_LICENCAS_ORGANIZACAO_OPERADORA` | O banco só aceita cadastro de operador **na organização designada vigente** e recusa retirar a designação enquanto houver operador vigente nela. A Platform não confere a tabela, porque isso exigiria uma permissão nova para ela. Se a variável apontar para outra organização, nenhum operador daquela organização está cadastrado: tudo é recusado, então falha fechado. O `operador.sh` grava a variável a partir da designação e o `estado` mostra "coerente" ou "DIVERGENTE". |
| 5. Recadastro de operador revogado | A chave passa a ser o cadastro (`registration_id`), com no máximo um vigente por conta. Recadastrar é um **cadastro novo**, explícito (`operador.sh recadastrar`; `cadastrar` recusa conta já revogada), com chamado e justificativa, e o procedimento confere de novo o segundo fator. A revogação anterior fica intacta. Nada é restaurado (seção 4). |
| 4. Registro do bloqueio | `organization_nekkora_holds` já tinha responsável, motivo e data. A 073 acrescenta o **motivo do levantamento** (`lift_reason`, obrigatório) e limita o levantamento a uma vez. |

## 2. O que NÃO está na 073, e por quê (decisões pendentes)

> **Atualização de 23/11:** as duas decisões foram tomadas pela titular e implementadas em laboratório: a **074** (com
> elevação só na aplicação) e a **075** (leitura mínima, sem elevação). Ver `074-075-organizacao-interna-e-bloqueio.md`. O texto
> abaixo fica como registro da análise que levou a elas.

### 2.1 Organização interna fora do limite de vazias: exige elevação

A contagem é feita por `identity_empty_organizations(uuid)`, uma função do dono `nekkora_identity_owner` (067). Ela é usada
por `identity_org_limit_reason` (criação e aprovação de pedido), pela reativação e por `identity_org_creation_state` (tela).
Mudar o corpo dela exige ser o dono, ou seja, a **janela de SUPERUSER**. A regra do brief manda parar e registrar; não
eleve nada.

**Proposta: 074, com elevação.** Não está em `db/migrations`; o texto vai abaixo para revisão.

```sql
-- 074 (ELEVACAO: substitui uma funcao do dono nekkora_identity_owner)
GRANT SELECT (organization_id, withdrawn_at) ON public.nekkora_internal_organization_designations TO nekkora_identity_owner;
CREATE POLICY identity_owner_read ON public.nekkora_internal_organization_designations AS PERMISSIVE FOR SELECT
    TO nekkora_identity_owner USING (true);
CREATE OR REPLACE FUNCTION public.identity_empty_organizations(p_identity UUID) RETURNS UUID[]
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT coalesce(pg_catalog.array_agg(o.organization_id ORDER BY o.organization_id), ARRAY[]::uuid[])
      FROM public.organization_responsibility r
      JOIN public.organizations o ON o.organization_id = r.organization_id AND o.status = 1
     WHERE r.responsible_identity_id = p_identity
       AND NOT EXISTS (SELECT 1 FROM public.automations a WHERE a.organization_id = o.organization_id)
       -- a organizacao DESIGNADA (vigente) como interna da Nekkora nao conta; retirada a designacao, volta a contar
       AND NOT EXISTS (SELECT 1 FROM public.nekkora_internal_organization_designations d
                        WHERE d.organization_id = o.organization_id AND d.withdrawn_at IS NULL);
$$;
ALTER FUNCTION public.identity_empty_organizations(uuid) OWNER TO nekkora_identity_owner;
```

- **Retirar a designação:** a organização volta a contar. Se o limite passar a ser ultrapassado, a 067 já recusa **criações
  novas** (`identity_org_limit_reason` → `EMPTY_LIMIT`) e não apaga nem encerra nada. É o comportamento pedido.
- **Sem a 074:** a interna conta para a responsável. Na produção ela já é responsável por 2 organizações ativas, então
  chegaria a 3 de 3 se as duas estiverem vazias.

### 2.2 Bloqueio da Nekkora com efeito real: exige uma permissão nova de leitura para os serviços

Hoje o bloqueio só impede a reativação pelo responsável. Para ter efeito nos fluxos, quem cria o trabalho precisa **ler** o
bloqueio:
- a Platform: operações, autorizações, pareamento, pedidos e solicitações;
- o canal das máquinas (`nekkora_ingestion`): resgate do pareamento e início de tentativa.

A decisão de 22/11 proíbe, nesta migration, "novas permissões aos serviços da aplicação". **Não implementei.**

A proposta abaixo dispensa elevação: os gatilhos envolvidos são do migrador. Ela dá a esses dois papéis leitura **só das
colunas** `organization_id` e `lifted_at`, sem motivo nem autor, e só da organização em contexto.

| Fluxo | Onde | Efeito com bloqueio aberto |
|---|---|---|
| Operações novas da organização (criar ou alterar automação, versão, convite, agendamento, máquina, licença, compartilhamento...) | Platform: um filtro único nas rotas `/api/v1/o/{org}/**` de escrita | `403 organization_held` com mensagem de suporte. Leitura, download de resultados, saída da sessão e cancelar trabalho continuam. |
| Autorização local nova (execução e monitoramento, Agent anterior) | gatilho `trg_local_run_authorizations_license` (064, do migrador) | recusada: `ORGANIZATION_HELD` |
| Pedido ao Agent anterior e solicitação remota nova | `trg_run_requests_license`, `trg_remote_executions_license` (064) | recusados |
| Tentativa remota ainda não iniciada | `remote_attempt_blocker` (064, do migrador) | não é oferecida: "sem lançar novos" |
| Pareamento novo (código e resgate) | `trg_agent_enrollments_on_insert/update` (025, do migrador) | recusado |
| Trabalho já iniciado | sem gatilho novo | termina, entrega registros e resultados, e o Agent anterior registra execuções tardias. **Os dados ficam preservados.** |
| Desfazer o bloqueio | só o migrador (073) | o administrador não consegue: não tem permissão na tabela e a tela não oferece a ação |

- **Interface:** a Web mostra "Organização bloqueada pela Nekkora. Novas operações estão suspensas. Fale com o suporte" na
  barra da organização e nos botões recusados. Isso exige um campo `heldByNekkora` na leitura da organização (`/me` ou o
  resumo), que vem da mesma leitura proposta.
- **Sessões existentes:** continuam válidas e em modo **somente leitura** para a organização. Não há desconexão forçada. O
  operador da Nekkora não opera a organização interna (ela não pode ser bloqueada por aqui).
- **Limite do Agent anterior:** as autorizações locais já emitidas valem **offline até a validade**, que nunca passa do fim
  da cobertura de licença (064). O servidor não impede na hora uma execução offline já autorizada. O registro dela chega
  depois e é aceito como trabalho autorizado antes do bloqueio. Na próxima conexão, a máquina não recebe autorização nova.

**Decisão necessária:**
- (a) autorizar esta leitura mínima, colunas `organization_id` e `lifted_at`, para `nekkora_platform` e `nekkora_ingestion`,
  e eu implemento a 075 com os gatilhos, o filtro, o erro, a Web e os testes; ou
- (b) outra forma de entregar o estado aos serviços.

## 3. Aplicação e conferência

- A 073 roda **sem elevação**: não entra em `NEKKORA_ELEVAR` nem em `elevacao.txt`. A pré-condição falha se o papel estiver com
  SUPERUSER, BYPASSRLS ou CREATEROLE, ou se alguma tabela tocada não for do migrador.
- Na produção, todas as tabelas tocadas são do migrador. O `license_operators` está vazio, e a mudança de chave não altera
  nenhum dado.
- **Reversão:** manual, sem elevação, enquanto não houver designação, recadastro nem revogação gravados por ela. O texto está
  no cabeçalho da migration.

## 4. Sessões e credenciais na revogação e no recadastro

- **Revogar:** o próximo ato do operador é recusado pelo banco (`license_op_is_operator` = false) e pela Platform, com 403/404
  conforme a rota. A sessão dele continua válida como sessão comum da organização interna.
- **Recadastrar:** cria só um cadastro novo. Nenhuma sessão, confirmação reforçada ou credencial é recriada ou reativada. Cada
  ato do operador continua exigindo sessão **com segundo fator** e confirmação reforçada pelo **código do autenticador**
  (`requireStepUp`, janela de 60 min). Recadastrar não devolve nenhum ato anterior.
- **Limite:** uma sessão aberta antes do recadastro, com confirmação reforçada ainda na janela de 60 min, volta a poder operar
  assim que o recadastro é gravado. Nada é restaurado, mas também não se exige uma sessão nova.
  - Exigir "sessão posterior ao cadastro" dependeria de a Platform ler o momento do cadastro, o que é uma permissão nova.
  - No procedimento, o recadastro só é feito depois de a pessoa sair, e a confirmação reforçada é refeita na hora.

## 5. Testes

- `api/nekkora-core/src/test/java/com/nekkora/core/db/OperacaoPeloMigrador073IT.java`, em PostgreSQL 16 real (001–073 pelo
  migrador suportado). Cobre:
  - aplicação sem elevação, com o migrador terminando `false false false`;
  - recusas dos quatro papéis em 9 ataques cada;
  - leitura restrita à organização declarada;
  - designação: uma vigente, imutável, sem apagar;
  - cadastro, revogação, recadastro e retirada;
  - D7;
  - bloqueio e levantamento.
- Ajustes: `LicenseOperatorIT` (designação + cadastro com chamado e justificativa, revogação com quem e motivo) e
  `OrganizationProfilesIT` (levantamento com motivo).
- `licenciamento-002/operador/teste/teste.sh` contra 001–073 (procedimento real como o migrador).
- **Resultado (22/11):**
  - `OperacaoPeloMigrador073IT`: 6/6;
  - `LicenseOperatorIT`: 4/4;
  - `OrganizationProfilesIT`: 5/5;
  - `teste/teste.sh`: 88 passam, 0 falham. A 073 foi aplicada `elevada=nao` e o migrador terminou `0|0|0`.
- **Achado:** um `DELETE` do migrador não dá erro. Por não haver política de DELETE, a RLS filtra e 0 linhas saem. Os testes
  conferem as 0 linhas.
