# Capacidade inicial e ativação das organizações (missão PLATFORM-API-V1-003, item 5)

**Data:** 2026-10-23. **Migration nova:** `022_ativacao_organizacoes.sql` (candidata). **Origem:** proposta da §5 de `2026-10-21-identidade-002-base-e-plano.md` (opções 1+2+3), agora pedida pela missão: capacidade inicial zero como regra, estado explícito "aguardando ativação", indicação na API e ativação por operação autorizada e auditada. **Depende de:** PR da homologação (lista padrão de elevação do modelo de aplicação e H02).

## 1. Comportamento final

| Situação | Antes | Agora |
|---|---|---|
| Organização criada (qualquer caminho) | limites 1–3 FINITE 0 "provisórios" | nasce **AGUARDANDO_ATIVACAO** (gatilho em `organizations`); 1–3 FINITE 0 como **regra** |
| Capacidade de execução antes da ativação | qualquer caminho com `provision_resource_limit` podia abrir | recursos 1–3 diferentes de FINITE 0 **recusados** (42501) até ATIVADA. Exceção: INSERT feito diretamente por superusuário |
| Ativação | inexistente | `org_activation_activate`: só operador `op_*` do grupo `nekkora_org_activation`, com certificado registrado; limites **explícitos**; estado, limites e auditoria numa transação |
| API | — | `activationState` em `GET /api/v1/me/organizations` e `organizationActivationState` em `GET /api/v1/o/{org}/me` |

**Ciclo separado da situação administrativa.** `ops_activation.organization_states.state` (1 = AGUARDANDO_ATIVACAO, 2 = ATIVADA) é independente de `organizations.status` (1 ativa, 2 suspensa, 3 encerrada). Aguardando ativação, suspensa e desativada continuam distinguíveis: o par (status, estado) é conferido em teste (suspensa e ativada = `2|2`; suspensa e aguardando continua `estado 1`).

**Não há valor comercial no banco.** O ato recebe os cinco valores:
- recursos 1–3 (`WORKERS_REGISTERED`, `CONCURRENT_EXECUTIONS`, `PENDING_EXECUTIONS`): inteiros ≥ 1;
- limiares de alerta 4–5 (logs e eventos por dia): inteiro ≥ 1 ou "sem limiar".

Os números de partida da proposta da §5 (1 Worker, 1 simultânea, 10 pendentes, 1 GiB e 256 MiB) **não** são padrão em lugar nenhum. A escolha continua decisão de produto.

## 2. O ato de ativação

`public.org_activation_activate(org, responsável, motivo, workers, simultâneas, pendentes, alerta_logs, alerta_eventos)` devolve `(resultado, motivo da recusa, evento)`. Na CLI do operador:

```
nekkora-ops ativar-organizacao <org> <responsavel> <workers> <simultaneas> <pendentes> <alerta-logs|-> <alerta-eventos|-> <motivo...>
```

**Autorização operacional:**
- Chamador: papel `op_*` membro de `nekkora_org_activation`. A participação é concedida pelo DBA, como em `nekkora_ops_mfa`; o grupo novo não tem atributos e só aceita `op_*` sem ADMIN OPTION (invariante da §10).
- Autenticação: `ops.autorizar` da 016. O certificado de cliente é lido pelo servidor, está registrado pela CA operacional, não foi revogado e está dentro da validade; o operador está ativo; o CN é igual a `session_user`.
- **Vínculo impeditivo** (`ops.tem_vinculo`): o operador com conta da Platform ou contato autorizado na organização alvo é recusado.
- **Responsável:** a autoria das revisões de limite (`provisioned_by_user_id`, "autoria do provisionamento operacional da Nekkora", 010) é uma conta da Platform que o operador **provou** controlar (`ops.ops_operator_platform_accounts`, prova emitida na sessão da própria conta, 016 §4), de outra organização. Não é parâmetro livre.
- **Administrador da organização não ativa.** O ORG_ADMIN do cliente atua pela Platform (`nekkora_platform`), que não tem EXECUTE (42501, testado), e a API não tem rota de ativação.

**Atomicidade, repetição e concorrência:**
- Locks na ordem global: organização (FOR UPDATE) → estado → operador (FOR SHARE). A hora é medida **depois** dos locks.
- Efeitos na mesma transação: estado → ATIVADA, as cinco revisões por `provision_resource_limit` e o evento OK com operador, papel da sessão, emissor e serial do certificado, responsável, motivo, os cinco valores e os ids das cinco revisões.
- Repetição: recusa `JA_ATIVADA`, sem efeito. Mudar a capacidade depois da ativação é outro ato, fora desta entrega.
- Duas ativações simultâneas: a segunda espera o lock da organização e recebe `JA_ATIVADA` (testado com o intercalamento controlado).
- Resultado incerto na CLI (conexão perdida na chamada ou no commit): DESCONHECIDO, com alerta, sem nova tentativa automática. Repetir é seguro pelo estado: a resposta é OK ou `JA_ATIVADA`.

**Recusas** (registradas em `ops_activation.activation_events` com `result=RECUSA` e devolvidas, sem efeito):
- da autorização: `SEM_CERTIFICADO`, `CERTIFICADO_*`, `OPERADOR_*`;
- do alvo e do pedido: `ORGANIZACAO_INEXISTENTE`, `VINCULO_IMPEDITIVO`, `RESPONSAVEL_NAO_VINCULADO`, `PARAMETRO_INVALIDO`, `ORGANIZACAO_NAO_ATIVA` (suspensa ou encerrada) e `JA_ATIVADA`.

## 3. Mudanças de banco (022)

**Por que um schema próprio e funções sem o prefixo `ops_`.** A §19 da 016 é reexecutada em toda base, depois de todas as migrations (`db/bootstrap/aplicar_como_migrador.sh`, usado pelas suítes, pelas bases Java e pelo bootstrap de desenvolvimento). Ela fecha o inventário do schema `ops` (21 tabelas), a matriz **exata** de `public.ops_*` e as 9 políticas `ops_function_context`. A 022 não toca nada disso: a §19 da 016 roda depois da 022 **sem adaptação** (suíte, caso A).

O dono das funções é `nekkora_ops_mfa_owner`: é o único papel com USAGE em `ops`, onde vivem o cadastro dos operadores, os certificados e `ops.autorizar`.

| Objeto | O que é |
|---|---|
| papel `nekkora_org_activation` | NOLOGIN, sem atributos; grupo dos operadores habilitados |
| schema `ops_activation` | sem PUBLIC; USAGE só de `nekkora_ops_mfa_owner` |
| `ops_activation.organization_states` | uma linha por organização; transição única 1 → 2, só pelo dono das funções, com instante e evento; não volta, não é apagada |
| `ops_activation.activation_events` | auditoria append-only (UPDATE, DELETE e TRUNCATE recusados) de cada tentativa |
| gatilho em `organizations` (AFTER INSERT) | cria o estado AGUARDANDO_ATIVACAO de toda organização nova |
| gatilho em `organization_resource_limit_revisions` (BEFORE INSERT) | recusa 1–3 diferente de FINITE 0 sem ATIVADA. SECURITY INVOKER de propósito: `current_user` é quem insere (dentro de `provision_resource_limit`, o dono do provisionamento) |
| `public.org_activation_activate` | o ato (EXECUTE: `nekkora_org_activation`) |
| `public.org_activation_states(uuid[])` | só o estado (EXECUTE: `nekkora_platform`) |
| `public.org_activation_state_of(uuid)` | estado para o gatilho (EXECUTE: donos do provisionamento e da identidade) |
| EXECUTE de `provision_resource_limit` | passa a incluir `nekkora_ops_mfa_owner` |

**Organizações existentes** (origem 2):
- ATIVADA, se algum recurso 1–3 tem revisão vigente diferente de FINITE 0 (capacidade já provisionada por caminho operacional anterior);
- senão, AGUARDANDO_ATIVACAO.

A §10 confere ainda que nenhuma organização aguardando ativação tem capacidade vigente.

**Elevação:** SUPERUSER na janela, por CREATE ROLE, `ALTER FUNCTION … OWNER TO nekkora_ops_mfa_owner` (papel sem membros), GRANT em função de outro dono e SET de `nekkora.fn_organization_id` na configuração da função. Sem a janela, a 022 é recusada por privilégio e nada muda (suíte, B1).
- `db/bootstrap/aplicar_como_migrador.sh` inclui a 022 na lista padrão.
- A linha em `ops/homologacao/implantacao/elevacao.txt` **não** foi proposta: a homologação desta missão foi feita com 001–021. A 022 entra num ambiente depois desta revisão, com linha, vínculo e pré-voo próprios.

## 4. Mudanças de contrato HTTP

- `IdentityOrganizationList.organizations[].activationState` (obrigatório): `AGUARDANDO_ATIVACAO` | `ATIVADA`.
- `Me.organizationActivationState` (obrigatório), em `GET /api/v1/o/{org}/me`.
- Schema novo `OrganizationActivationState`, com a semântica: só indicação para a interface; os valores dos limites não são expostos e a admissão continua decidida no banco.

Organização sem estado (não deveria existir) aparece como AGUARDANDO_ATIVACAO, o lado que não promete capacidade.

## 5. Testes executados

| Prova | Onde | Resultado |
|---|---|---|
| A: instalação limpa 001–022; invariantes de 012 a 022 depois da retirada; §19 da 016 sem adaptação | `db/tests/ativacao-022` | OK |
| B1: 022 sem janela recusada por privilégio, sem efeito | idem | OK |
| B2: base populada 021 → 022, com a identidade de backup em `pg_read_all_data`: dados de `public` e `ops` idênticos; classificação (3 com capacidade → ATIVADA; zero e sem revisão → AGUARDANDO); diff de catálogo de `public` exatamente o previsto (14 linhas, `esperado/B23_diff_esperado.txt`); catálogos = instalação limpa | idem | OK |
| B3: reaplicação recusada pela pré-condição | idem | OK |
| C: organização nova AGUARDANDO; provisionamento barrado (recursos 1 e 2), FINITE 0 e 4–5 aceitos; superusuário direto não barrado; estado protegido (UPDATE, DELETE, TRUNCATE, volta); ato sem certificado → `SEM_CERTIFICADO` auditado; Platform, provisionamento e op_* fora do grupo sem EXECUTE; Platform lê só o estado | idem | 11 recusas conferidas |
| Ativação real com operador, certificado e responsável provado: estado, cinco limites e evento completo, revisões com o responsável como autoria; provisionamento depois da ativação liberado | `OrgActivationIT.ativacaoAutorizadaAplicaEstadoLimitesEAuditoriaAtomicamente` | OK |
| Nasce aguardando; provisionamento real recusado (42501) | `organizacaoNovaNasceAguardandoAtivacaoComCapacidadeZero` | OK |
| Repetição → `JA_ATIVADA`, sem revisões novas | `repeticaoERecusadaComJaAtivadaSemNovoEfeito` | OK |
| Concorrência: A segura o lock sem commit; B **espera** (`wait_event_type=Lock`, observado); A confirma; B recebe `JA_ATIVADA` | `ativacaoConcorrenteEsperaAPrimeiraERecebeJaAtivada` | OK |
| Recusas auditadas e sem efeito: responsável não provado, parâmetros (3), suspensa, inexistente, vínculo impeditivo | `recusasAuditadasESemEfeito` | OK |
| Fora do grupo (com as seis habilitações da 016), Platform, Portal e provisionamento não executam; Platform não lê as tabelas | `foraDoGrupoServicosEAdministradorDaOrganizacaoNaoAtivam` | OK |
| API: criada → AGUARDANDO_ATIVACAO na lista e em `/o/{org}/me`; limites 1–3 FINITE 0; ATIVADA (estado preparado no banco) refletida; suspensa sai da lista e o par (status, estado) continua distinguível | `IdentityOrganizationJourneysIT.organizacaoCriadaInformaAguardandoAtivacaoEDepoisAtivada` | OK |
| Corpo exato da lista (contrato novo) | `IdentityOrgSessionIT`, `OrganizationCreationIT` (atualizados) | OK |

**Defeito encontrado no próprio desenvolvimento:** a primeira versão da invariante da 022 usava `has_schema_privilege` e acusou `pg_read_all_data` e `pg_write_all_data`, a mesma armadilha da revisão 1 da 021. Corrigida para conferir a ACL, com a identidade de backup presente na base populada da suíte para manter a regressão.

**Ponto de desenho corrigido pelo teste C2:** isentar a sessão de superusuário (`session_user`) também isentava quem fazia `SET ROLE` para o papel de provisionamento a partir dela. Agora só o INSERT feito diretamente por superusuário (`current_user`) passa. O gatilho é SECURITY INVOKER, com a leitura do estado por função de concessão restrita.

Regressão completa do candidato final: registrada no PR.

## 6. Limitações e decisões para a responsável

- **Valores da ativação:** decisão de produto. O comando os exige explicitamente.
- **Mudança de capacidade depois da ativação:** ato não criado. Hoje, depois de ATIVADA, o caminho de provisionamento existente (`nekkora_provisioning`) volta a poder alterar limites, sem o motivo e a autoria operacional desta trilha. Se a decisão for exigir o mesmo rigor, o próximo passo é um ato `org_activation_revise` com a mesma auditoria.
- **Isenção do superusuário:** o INSERT direto por superusuário (fixtures declaradas de teste e administração do cluster) não é barrado. Um superusuário pode tudo de qualquer forma; a isenção não se estende a nenhuma função.
- **Reuso do dono operacional da 016:** `nekkora_ops_mfa_owner` passa a ser também dono do ato de ativação, por ser o único papel com USAGE em `ops` (onde está a autenticação do operador). A alternativa, um dono próprio, exigiria mudar a §19 da 016, reexecutada em toda base.
- **Homologação no ambiente:** a ativação foi demonstrada nas suítes e nos testes Java com papéis e certificados reais, não no `ensaio-api-v1-002` (001–021). Levá-la ao ambiente exige a linha de elevação da 022 vinculada ao id e um pré-voo, para revisão.
- **Leitura da Platform:** `org_activation_states` devolve o estado de qualquer organização cujo id a Platform conheça. A API só pergunta pelas organizações já devolvidas pela função da identidade ou pela do contexto da sessão. O dado exposto é só o estado.
