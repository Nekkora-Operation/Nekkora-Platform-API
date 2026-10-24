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
- **Mudança de capacidade depois da ativação:** decidida na revisão (D-ATV-2) e implementada: só pela revisão autorizada e auditada (§7.1).
- **Isenção do superusuário:** o INSERT direto por superusuário (fixtures declaradas de teste e administração do cluster) não é barrado. Um superusuário pode tudo de qualquer forma; a isenção não se estende a nenhuma função.
- **Reuso do dono operacional da 016:** `nekkora_ops_mfa_owner` passa a ser também dono do ato de ativação, por ser o único papel com USAGE em `ops` (onde está a autenticação do operador). A alternativa, um dono próprio, exigiria mudar a §19 da 016, reexecutada em toda base.
- **Homologação no ambiente:** feita na revisão, no `ensaio-api-v1-003` atualizado de 021 para 022 pelo runner (§7.3). A linha de elevação da 022 e o pré-voo são propostas para revisão.
- **Leitura da Platform:** `org_activation_states` devolve o estado de qualquer organização cujo id a Platform conheça. A API só pergunta pelas organizações já devolvidas pela função da identidade ou pela do contexto da sessão. O dado exposto é só o estado.

## 7. Revisão de 2026-10-24

### 7.1 Mudança de capacidade depois da ativação (D-ATV-2)

Decisão da revisão: alterações posteriores também exigem autorização e auditoria.

- **`public.org_activation_revise`:** mesmos controles da ativação (grupo `nekkora_org_activation`, certificado registrado, conta responsável provada e de outra organização, limites explícitos dos cinco recursos, motivo). Auditoria na mesma tabela, com `act = 2` (a ativação é `act = 1`).
- **Recusas:** `NAO_ATIVADA` (a revisão não substitui a ativação); `SEM_MUDANCA` quando os limites pedidos são os do último ato OK auditado. Isso torna seguras a repetição de um resultado incerto e a concorrência: a segunda revisão igual espera a primeira e recebe `SEM_MUDANCA`.
- **Gatilho dos limites:** com a organização ATIVADA, nenhuma revisão dos recursos 1 a 5 é aceita fora de um ato em curso. O ato grava em `ops_activation.open_acts` a organização e a transação corrente (`pg_current_xact_id`) e apaga a linha antes de terminar; nada fica confirmado, e a invariante confere a tabela vazia. Um GUC de sessão não serviria, porque qualquer papel pode defini-lo. Só o INSERT direto de superusuário continua isento.
- **Sem ampliar privilégio:** a comparação do `SEM_MUDANCA` usa a auditoria, não `organization_resource_limit_revisions` (o dono das funções não tem acesso a ela; a primeira versão da revisão tentou ler e o teste a pegou).
- **CLI:** `revisar-capacidade` (mesmos argumentos de `ativar-organizacao`) e `vincular-conta <código>`. A CLI não tinha como registrar o vínculo da conta, que a ativação exige; agora o operador vincula com o código que o titular emite na própria sessão (`POST /api/v1/o/{org}/me/operational-link-code`, da 016). Só o sha256 vai ao banco.

### 7.2 Acesso efetivo também na 022

A invariante da 022 conferia só a ACL de `ops_activation`, a mesma escolha da 021 revisão 2. Passou a conferir o **acesso efetivo** ao schema e às três tabelas (`has_schema_privilege`, `has_table_privilege`, `has_any_column_privilege`), com o mesmo critério da 021 revisão 3: participação em papel com acesso, e o backup como única exceção, só leitura e só por `pg_read_all_data`. A conferência da ACL continua, para nomear PUBLIC e o privilégio.

### 7.3 Provas

| Prova | Resultado |
|---|---|
| `OrgActivationIT` | **9/9**: os 6 anteriores (o da ativação agora confere que o provisionamento direto é recusado depois de ATIVADA) + revisão auditada, recusas da revisão sem efeito e revisão concorrente com espera observada. O vínculo da conta passa pela CLI, e o reuso do código é recusado |
| `db/tests/ativacao-022` | **OK** (execução `2026-10-24T040432Z`): C2 com os cinco recursos recusados pelo provisionamento direto numa organização ATIVADA; C4 e C5 nos dois atos; **C7**, 7 contraprovas de acesso efetivo; diff de catálogo 021 → 022 com exatamente 21 linhas previstas |
| Regressão Maven completa | **580 testes, 0 falhas, 0 erros, 0 ignorados** no `a24e181` (`regressao_maven.sh`, fontes estáveis): 570 da base, 9 do `OrgActivationIT` e 1 da API. O commit seguinte (`64b46ea`) só toca ferramentas de teste da homologação |
| Recusa da atualização numa base com a 021 anterior | `homologacao.sh atualizar ensaio-api-v1-002` recusou antes de qualquer mudança: `copia de migrations do ambiente diferente do repositorio: 021(ambiente bf85b95bdc83... repositorio 153c595a7ef9...)`. O ambiente continuou LIBERADO, com a cópia e o `ambiente.env` byte a byte iguais |
| **Atualização 021 → 022 de base populada, pelo runner** | `ensaio-api-v1-003`, populado com as jornadas e a 016. O `atualizar` preservou a cópia 001–021, gravou a nova com a 022 e reconstruiu as imagens do commit `6280314`. O `implantar` passou no pré-voo da 022 (`PREFLIGHT_OK`, 10 conferências); a janela SUPERUSER durou de 04:32:53 a 04:32:57 e foi retirada e conferida em 0\|0\|0; resultado **LIBERADO** (001–022, `flyway validate` OK). **Dados idênticos:** contagens e md5 do conteúdo de 8 tabelas iguais antes, depois do `atualizar` e depois da 022. Classificação: as 3 organizações anteriores ficaram AGUARDANDO_ATIVACAO, origem 2, todas com capacidade zero; nenhuma organização sem estado. O ramo de existentes com capacidade (ATIVADA, origem 2) é provado na suíte (B2) |
| Jornadas pela API depois da 022 | **96 casos, 0 falhas**, em duas rodadas, com varredura sem achados. As organizações criadas depois nascem AGUARDANDO_ATIVACAO, origem 1 |
| **Jornada da ativação** (`homologacao.sh jornada-ativacao`) | **11 OK, 0 falha** (`op_hml_atc`): J1 operador (DBA → CADASTRAR → grupo); J2 vínculo pela CLI com o código emitido pelo titular na sessão dele pelo gateway, reuso recusado; J3 recusas sem efeito (fora do grupo, responsável não vinculado, parâmetro inválido, revisão antes da ativação), auditadas; J4 ativação com auditoria completa e 5 revisões de autoria do responsável; J5 `JA_ATIVADA`; J6 duas ativações paralelas, uma OK e uma `JA_ATIVADA` 28 ms depois, limites só da vencedora; J7 revisão `act=2` e `SEM_MUDANCA`; J8 provisionamento direto recusado; J9 estados pela função da Platform; J10 9 eventos de auditoria, código fora da evidência. Duas tentativas anteriores, preservadas e explicadas: uma por erro de ordem no roteiro (o grupo antes do cadastro; a 016 só cadastra papel com participação exata em `nekkora_ops_mfa`) e outra porque dois jobs `operacao` não sobem juntos (IP fixo do job). Varredura das evidências: 777 valores, 0 achados |

**Como a base foi populada:** jornadas pela API (96 casos), preparação e cenários da 016 no próprio ambiente, sem dados reais. As organizações criadas antes da 022 foram classificadas por ela; as criadas depois nascem AGUARDANDO_ATIVACAO.

**Limite da prova de concorrência na homologação:** as duas ativações paralelas (J6) partem de dois jobs da CLI, sem controlar o intercalamento. O resultado (uma OK, uma `JA_ATIVADA`, limites só da vencedora) vale para qualquer ordem. A espera pelo lock, com intercalamento controlado, é provada no `OrgActivationIT`.

### 7.4 Elevação da 022 (proposta)

Linha `022 <sha256> tipo=superuser ambiente=ensaio-api-v1-003` em `implantacao/elevacao.txt`, com a 022 em `VINCULO_OBRIGATORIO` e o pré-voo oficial `implantacao/preflight/022.sql`, somente leitura, com 10 conferências:
- histórico exatamente 001–021;
- migrador sem atributo elevado e dono do banco;
- 021 presente e objetos da ativação ausentes;
- grupo ausente ou sem atributo;
- dono das funções sem participação;
- assinaturas de `provision_resource_limit` e `get_effective_resource_limit`;
- backup só em `pg_read_all_data`.

O `criar` com `HOMOLOG_ATE=021` exige só as linhas até a 021. A 022 entra pelo `atualizar`, que confere a linha antes de mudar qualquer coisa.
