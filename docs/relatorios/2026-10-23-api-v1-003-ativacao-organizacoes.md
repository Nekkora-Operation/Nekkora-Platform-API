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
- **Recusas:** `NAO_ATIVADA` (a revisão não substitui a ativação); `SEM_MUDANCA` quando os limites pedidos são os do último ato OK auditado. Isso torna seguras a repetição de um resultado incerto e a concorrência: a segunda revisão igual espera a primeira e recebe `SEM_MUDANCA`. Com valores diferentes, ver §7.5.
- **Gatilho dos limites:** com a organização ATIVADA, nenhuma revisão dos recursos 1 a 5 é aceita fora de um ato em curso. O ato grava em `ops_activation.open_acts` a organização e a transação corrente (`pg_current_xact_id`) e apaga a linha antes de terminar; nada fica confirmado, e a invariante confere a tabela vazia. Um GUC de sessão não serviria, porque qualquer papel pode defini-lo. Só o INSERT direto de superusuário continua isento.
- **Sem ampliar privilégio:** a comparação do `SEM_MUDANCA` usa a auditoria, não `organization_resource_limit_revisions` (o dono das funções não tem acesso a ela; a primeira versão da revisão tentou ler e o teste a pegou).
- **CLI:** `revisar-capacidade` (mesmos argumentos de `ativar-organizacao`) e `vincular-conta <código>`. Nos dois atos de capacidade, a saída mostra o identificador do ato (`evento=`, o evento de auditoria gravado pelo banco) e os limites efetivamente aplicados (`limites_aplicados=trabalhadores=…,simultaneas=…,pendentes=…,alerta_logs=…|-,alerta_eventos=…|-`) (§7.8). Os limites só aparecem quando o ato termina OK; recusa, falha ou resultado desconhecido saem com `limites_aplicados=-` e **nunca são apresentados como limites aplicados com sucesso**. A CLI não tinha como registrar o vínculo da conta, que a ativação exige; agora o operador vincula com o código que o titular emite na própria sessão (`POST /api/v1/o/{org}/me/operational-link-code`, da 016). Só o sha256 vai ao banco.

### 7.2 Acesso efetivo também na 022

A invariante da 022 conferia só a ACL de `ops_activation`, a mesma escolha da 021 revisão 2. Passou a conferir o **acesso efetivo** ao schema e às três tabelas (`has_schema_privilege`, `has_table_privilege`, `has_any_column_privilege`), com o mesmo critério da 021 revisão 3: participação em papel com acesso, e o backup como única exceção, só leitura e só por `pg_read_all_data`. A conferência da ACL continua, para nomear PUBLIC e o privilégio.

### 7.3 Provas

| Prova | Resultado |
|---|---|
| `OrgActivationIT` | **10/10**, teste dirigido no candidato (§7.6): os 6 anteriores (o da ativação agora confere que o provisionamento direto é recusado depois de ATIVADA) + revisão auditada, recusas da revisão sem efeito, revisão concorrente **igual** com espera observada e revisão concorrente com **valores diferentes** (§7.5). O vínculo da conta passa pela CLI, e o reuso do código é recusado |
| `db/tests/ativacao-022` | **OK** (execução `2026-10-24T040432Z`): C2 com os cinco recursos recusados pelo provisionamento direto numa organização ATIVADA; C4 e C5 nos dois atos; **C7**, 7 contraprovas de acesso efetivo; diff de catálogo 021 → 022 com exatamente 21 linhas previstas |
| Regressão Maven completa | **Local:** 580 testes, 0 falhas, 0 erros, 0 ignorados no commit anterior `a24e181` (`regressao_maven.sh`, fontes estáveis): 570 da base, 9 do `OrgActivationIT` e 1 da API. Não é uma execução local do HEAD final. **CI:** regressão inteira (`./mvnw -B -ntp test`) no HEAD `f52b9a7`, **581 testes, 0 falhas** (21 + 174 + 374 + 12 por módulo): os 580 mais o 10º caso do `OrgActivationIT` (§7.6) |
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

### 7.5 Revisões simultâneas com valores diferentes

Comportamento definido:
- **Serialização:** as duas revisões travam a organização (`FOR UPDATE`, na ordem organização → estado → operador) antes de qualquer decisão. A segunda espera o commit ou o rollback da primeira; nenhuma decide sobre um estado que a outra ainda pode mudar.
- **Valores absolutos:** cada ato substitui os cinco limites pelos que pediu; não soma nem mescla com o outro, e cada um deixa a própria auditoria. Vale a última revisão **aplicada com sucesso na ordem de serialização**, não necessariamente a última requisição recebida.
- **Decisão depois do lock:** autorização, conta responsável e `SEM_MUDANCA` são avaliados depois dos locks, com o último ato OK já confirmado. Se a segunda pede os mesmos valores da primeira, recebe `SEM_MUDANCA`, auditada e sem efeito. Se pede valores diferentes, aplica.
- **Auditoria consistente:** cada ato OK grava o próprio evento (`act = 2`, operador, sessão, certificado, responsável, motivo, os cinco valores) e as próprias 5 revisões de limite. A ordem dos eventos (`created_at`, gravado depois do lock) é a ordem do lock. Os limites vigentes são sempre os do último ato OK auditado.
- **Atualização perdida:** a segunda revisão não sabe que a primeira mudou os valores no intervalo. Fica registrada na auditoria, com os dois atos, mas não é recusada. Exigir a versão esperada (`LIMITES_ALTERADOS`) ficou **fora**, por decisão da revisão final: a concorrência permanece como implementada.
- **O que o operador vê:** a CLI mostra o identificador do ato e os limites que aquele ato aplicou (§7.8). Numa corrida, cada operador vê os próprios valores; os vigentes são os do último ato OK na ordem de serialização.

Prova com intercalamento controlado no `OrgActivationIT` (`revisaoConcorrenteDiferenteSerializaNaOrdemDoLockEAuditaAsDuas`):
- dois operadores do grupo, cada um com a própria conta responsável;
- A revisa para 3/2/20 e segura a transação;
- B pede 5/4/50 com alerta de logs de 1 GiB pela CLI e fica esperando o lock, observado em `pg_stat_activity`;
- antes do commit de A, os limites continuam os da ativação;
- depois do commit, B termina OK e os limites ficam os de B; o resultado devolvido a B traz o próprio evento e os limites aplicados 5/4/50, alerta de logs 1 GiB;
- dois eventos OK, cada um com o próprio operador, responsável, motivo, valores e 5 revisões, na ordem A → B, e nenhuma recusa;
- as revisões de limite vigentes são exatamente as do evento de B, e há 10 revisões novas;
- repetir os valores de A em seguida não é `SEM_MUDANCA` (a referência é o último ato, de B): aplica e volta a A;
- `open_acts` termina vazia.

A homologação (J6) prova a concorrência da ativação entre dois processos reais, sem controlar o intercalamento. A das revisões fica no teste, onde o intercalamento é controlado.

### 7.6 Estado final do `ensaio-api-v1-003` e resultados por commit candidato

**O `ensaio-api-v1-003` terminou na 022.** Conferência somente leitura em 2026-10-24T12:3xZ (`evidencias/ensaio-api-v1-003/estado-final.txt`):
- `ambiente.env`: `HOMOLOG_ATE=022`, `HOMOLOG_COMMIT=6280314`;
- histórico do Flyway 001–022, todas com sucesso;
- a cópia imutável das migrations do ambiente é byte a byte igual a `db/migrations` do candidato deste PR (001–022), do #17 e do #16 (001–021);
- migrador em 0|0|0, `open_acts` vazia;
- 3 organizações AGUARDANDO_ATIVACAO e 4 ATIVADA, nenhuma ATIVADA sem ato de ativação OK auditado;
- Platform e Portal em execução com as imagens `:6280314`;
- lista de elevação do candidato com o mesmo sha256 (`bf040a47…`) usado na implantação da 022.

**Linha do tempo** (UTC, 2026-10-24; commits do runner e das migrations na worktree):

| Instante | Etapa | Commit | Resultado |
|---|---|---|---|
| 04:11 | criação (parada antes da 016) | HEAD `b9b6619`, imagens `a39f684` | OK |
| 04:13 | implantação 016–021 | `b9b6619` | janelas 016–020 retiradas em 0\|0\|0; LIBERADO |
| 04:15 | preparar-016 | `b9b6619` | OK |
| **04:15** | **jornadas pela API, antes da 022** | `b9b6619` | **96 casos, 0 falhas** |
| 04:18 e 04:25 | testar-016 | `b9b6619` | B5–B8a OK; B10 e B11 PARCIAIS; B12 OK na 2ª execução |
| 04:32 | atualizar 021 → 022 | HEAD `c4c6b3e`, imagens `6280314` | cópia anterior preservada |
| 04:32 | implantação da 022 | `c4c6b3e` | pré-voo OK; janela retirada em 0\|0\|0; LIBERADO; 8 tabelas idênticas |
| **04:33 e 04:40** | **jornadas pela API, depois da 022** | `c4c6b3e` | **96 casos, 0 falhas**, nas duas rodadas |
| 04:36 e 04:38 | jornada da ativação, tentativas 1 e 2 | `c4c6b3e` | FALHA por defeitos do roteiro (§7.3), preservadas |
| **04:43** | **jornada da ativação, final** | `c4c6b3e` + o roteiro corrigido, commitado em `a24e181` | **11 OK, 0 falha** |

**Do implantado ao candidato:**
- o `jornada_ativacao.sh` é idêntico de `a24e181` até o candidato;
- de `c4c6b3e` ao candidato, `db/migrations` e `implantacao/` não mudam;
- no runner, só o `atualizar` passa a aceitar a lista alternativa de laboratório (§7.7). O ambiente usou a lista revisada, e o caminho dela não mudou;
- nas fontes das imagens, de `6280314` ao candidato, só muda o `OrgActivationIT.java`. Esse teste não entra nas imagens da Platform e do Portal, e o job de operação copia só `FerramentaHomologacao`, `IdpSimulado` e `Pki`.

**Candidato anterior: `1a9351e`. Candidato atual: `91ea1d1`** (revisão final, §7.8). Os commits entre os dois só acrescentam este relatório e evidências.

**O que a revisão final muda em relação ao que rodou nos ambientes** (§7.8):
- `db/migrations`, `implantacao/` e as linhas de elevação: **nada** (sha256 da 022 `b752c7aa…` e lista revisada iguais);
- runner: o `atualizar` só reordena conferências somente leitura, todas antes de qualquer mudança;
- CLI (`nekkora-ops/src/main`, que entra na imagem do job de operação): a linha de saída dos atos de capacidade ganha `limites_aplicados=`, depois dos campos de antes. A jornada da ativação (11/11) e o laboratório j022 rodaram com a CLI anterior; os roteiros só leem `resultado=` e `motivo=`, que não mudam. Não repeti a jornada no ambiente: a linha nova está provada no `OrgActivationIT` e no `OpsCliSaidaCapacidadeTest`.

| Prova | Commit | Resultado |
|---|---|---|
| `OrgActivationIT` (teste dirigido de concorrência) | `8d9af05`; o Java é idêntico em `1a9351e` | **10 testes, 0 falhas, 0 erros, 0 ignorados** |
| Regressão Maven completa, local | `a24e181` (commit anterior ao candidato) | **580 testes, 0 falhas, 0 erros, 0 ignorados**. Não reexecutei localmente no candidato: até ele, o Java só ganha o 10º caso do `OrgActivationIT` |
| CI (`./mvnw -B -ntp test`, regressão inteira) | HEAD `f52b9a7` (o Java é o do candidato `1a9351e`) | **verde, 581 testes, 0 falhas, 0 erros** (21 + 174 + 374 + 12) |
| `validar-config` | `1a9351e`, worktree limpa | ver a tabela da descrição do PR |
| `db/tests/ativacao-022` | execução `2026-10-24T040432Z`; a 022 é a mesma desde então (sha `b752c7aa…`) | OK |
| Janela da 022 em recusa, falha e sucesso | HEAD `b10d88f`, imagens `8d9af05` (§7.7) | 3 de 3 como esperado |

### 7.7 Janela de elevação da 022: retirada em falha e em sucesso

Faltava à 022 a prova da retirada em falha, que a 016–020 tinham no laboratório das janelas. Foi feita num laboratório descartável novo, o `ensaio-api-v1-003-j022` (sub-redes 10.252.88/89, porta 55764). Usou uma lista alternativa com 016–020 e 022 vinculadas ao próprio id, e o roteiro `testes/janela_022.sh`, do mesmo desenho das janelas 016–020.

1. Criação até a 021 pelo caminho real, LIBERADO.
2. O `atualizar` sem a lista do laboratório foi **recusado antes de qualquer mudança**: a linha da 022 na lista revisada é do `ensaio-api-v1-003`. Para isso, o `atualizar` passou a usar a mesma lista que o `implantar` usará: a revisada ou, só em `ensaio-*`, a alternativa `HOMOLOG_ELEVACAO_ARQ`, como no `criar` e no `implantar`. O registro da atualização grava qual lista e o sha256.
3. Com a lista do laboratório, o `atualizar` acrescentou a 022.
4. Resultado, 0 linhas fora do esperado:

| Caso | Provocação | Resultado |
|---|---|---|
| P, recusa pelo pré-voo | `nekkora_org_activation` criado por fora com `CREATEDB` | conferência 7 em FALHA, `PREFLIGHT_FALHOU`; código 2; **nenhuma concessão**; migrador 0\|0\|0; 022 fora do histórico |
| F, falha dentro da janela | conexões do migrador em `default_transaction_read_only` | SUPERUSER concedido às 12:50:36.340; o servidor recusou ("cannot execute CREATE ROLE in a read-only transaction"); **revogado e conferido em 0\|0\|0 \[falha\]** às 12:50:41.224; código 2; 022 fora do histórico; nenhuma linha `success=false` |
| S, sucesso | implantar sem parada | concedido às 12:50:50.936 e **revogado e conferido em 0\|0\|0 \[fim da versão 022\]** às 12:50:55.905; 022 com sucesso; LIBERADO; `open_acts` vazia |

Evidências: `ops/homologacao/evidencias/ensaio-api-v1-003-j022/` (LEIA-ME, varredura com 25 valores e 0 achados). O laboratório fica preservado.

**Elevação, resumo final:**
- 016–020 e 022 vinculadas ao `ensaio-api-v1-003`, com o sha256 dos arquivos finais;
- retirada provada em sucesso (`ensaio-api-v1-003`) e em falha e recusa do pré-voo (016–020 no `ensaio-api-v1-002-janelas`, 022 no `ensaio-api-v1-003-j022`);
- os arquivos 016–020 são os mesmos desde aquele laboratório;
- as linhas continuam propostas, para a sua aprovação.

### 7.8 Revisão final (2026-10-24): lista alternativa no `atualizar` e saída da CLI

**Lista alternativa de elevação no `atualizar`.** É mudança funcional nova do runner (§7.7). Cada controle pedido, com a prova:

| Controle | Prova | Resultado |
|---|---|---|
| Fora de `ensaio-*`, a opção é recusada antes de qualquer alteração | `testes/test_atualizar_lista.sh` (novo, estático, roda o `homologacao.sh` real contra ambientes fictícios e compara arquivos e bytes antes e depois); no `criar` e no `implantar`, `validar-config` (casos `lista alternativa fora de ensaio-*` e `lista_recusada`) | **2 de 2** no `atualizar` (com e sem a lista): `atualizar so em ambiente descartavel`, nada alterado |
| A lista alternativa passa pelas verificações de hash, versão e vínculo ao ambiente | `test_atualizar_lista.sh` | **10 de 10** recusas, nenhuma com alteração: lista inexistente; linha fora do formato; 022 sem vínculo; 022 `tipo=bypassrls`; sha256 diferente do arquivo; vinculada a outro id; sem linha da 022; sha da 022 sob a versão 021; alvo 023; lista revisada (vinculada ao `ensaio-api-v1-003`). Controle positivo: a lista correta passa as conferências de arquivo e só para na liberação, que lê o banco |
| … e ao pré-voo | `janela_022.sh` no `ensaio-api-v1-003-j022`, com a lista alternativa (§7.7, caso P); `validar-config` (conferências estáticas do código): pré-voo oficial da 022 existente, e o runner só aceita pré-voo da pasta da lista alternativa para versões sintéticas 9xx sem pré-voo oficial | pré-voo recusou, **nenhuma concessão** |
| O registro identifica a lista usada | `atualizacao/registro.txt` do j022: `lista_de_elevacao=elevacao_lab.txt (ALTERNATIVA DE ENSAIO) sha256 381282c3…`; os três registros do runner na janela da 022: `lista de elevacao: elevacao_lab.txt ALTERNATIVA DE ENSAIO sha256 381282c3…` | presente nos 4 registros |
| Uma falha continua retirando a elevação | `janela_022.sh`, caso F, com a lista alternativa (§7.7) | **revogado e conferido em 0\|0\|0 \[falha\]**, 022 fora do histórico |

Para o teste estático alcançar as conferências da lista, o `atualizar` passou a fazê-las **antes** da conferência de liberação e da cópia do ambiente, que leem o banco e a cópia. As duas ordens são somente leitura e anteriores a qualquer mudança; as mensagens e as recusas são as mesmas.

**Saída da CLI.** `ativar-organizacao` e `revisar-capacidade` passam a imprimir, depois dos campos de antes (os roteiros que leem `resultado=` e `motivo=` não mudam), os limites efetivamente aplicados. Exemplo de revisão OK: `organizacao=<uuid> resultado=OK evento=<uuid> motivo=- limites_aplicados=trabalhadores=5,simultaneas=4,pendentes=50,alerta_logs=1073741824,alerta_eventos=-`. O `evento` é o identificador do ato. Recusa, falha ou resultado desconhecido saem com `limites_aplicados=-`: **não são apresentados como limites aplicados com sucesso** (no desconhecido, a CLI não sabe se o ato foi confirmado e não mostra valores). Os limites saem do ato confirmado: as funções da 022 gravam exatamente esses cinco valores, com as cinco revisões e o evento na mesma transação, e o `OrgActivationIT` confere que eles são os vigentes no banco e os do evento. A 022 não muda.

**Provas desta rodada** (dirigidas; sem nova rodada ampla):

| Prova | Resultado |
|---|---|
| `OrgActivationIT` (com as asserções dos limites aplicados na ativação, na revisão, na recusa `SEM_MUDANCA` e na revisão concorrente com valores diferentes) | **10/10** |
| `OpsCliSaidaCapacidadeTest` (novo, linha da CLI em OK, recusa e desconhecido) | **3/3** |
| `OpsCliContinuidadeIT` | **4/4** |
| `testes/test_atualizar_lista.sh` | **14 de 14** (13 recusas sem alteração e o controle positivo) |
| `validar-config` completo, já com o teste novo e a conferência estática do pré-voo 9xx | **274 OK, 0 falhas** |
| CI (regressão inteira) | HEAD `2b8546b`: **verde, 584 testes, 0 falhas, 0 erros** (21 + 177 + 374 + 12) |

Numa primeira execução, o `validar-config` falhou em casos de Docker e de caminhos: o Docker Desktop estava parado e eu tinha exportado `MSYS2_ARG_CONV_EXCL` com `/tmp/`, o que só serve para as suítes de `db/tests`. Depois, um caso isolado do `guarda_fontes_imagens` (repositório sintético, "registro sem arvore api/") falhou uma vez e passou 3 de 3 sozinho e na execução completa seguinte: **falha intermitente, causa não determinada**. Passar nas repetições não demonstra, sozinho, que não há relação com a mudança. O resultado registrado acima é o da execução completa limpa.
