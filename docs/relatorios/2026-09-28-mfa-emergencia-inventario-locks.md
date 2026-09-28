# Recuperação emergencial de MFA — inventário de locks implícitos (v3)

> **v3 (2026-09-28):**
> - I-5 aprovado com o desenho do §11.1;
> - esquema detalhado das tabelas novas e inventário delas no §10, feitos **antes** da implementação;
> - implementação candidata autorizada na ordem: documentos → esquema e inventário → correções e desenhos → testes e pacote;
> - continuam fora: commit, integração, bases persistentes, e-mail real e frontend.

**Data:** 2026-09-28. **Base:** `effa516` (016 candidata), contrato `ca4033b`.
**Natureza:** inspeção estática de código e migrations. **Nada foi executado**; nenhum arquivo de código, migration ou teste foi alterado. Sem commit.
**Versões:**
- **v1:** achados I-1 a I-4.
- **v2 (esta):**
  - registra as decisões sobre I-1 a I-4;
  - refaz o desenho de I-1 (organização → conta → token);
  - corrige a descrição do reset administrativo, que também pede a conta do **ator**, e acrescenta o achado **I-5** (reset administrativo, avisos da 016 e resets cruzados);
  - acrescenta a regra de ordem entre contas (§5);
  - documenta a retirada pontual da FK de auditoria (§6).

**Escopo:** a 016 atual; as tabelas das 001–015 que ela ou os fluxos concorrentes tocam; os fluxos Java da Platform que travam contas ou organizações. As tabelas novas dos itens 4, 5 e 10 entram quando o esquema for detalhado (§7).
**Limites:**
- mostra inversões **encontradas**; não prova que não haja outras;
- os ensaios concorrentes (§8) complementam, e ausência de `40P01` em algumas rodadas não prova ausência de ciclo;
- as funções das 010–015 foram cobertas por busca (quem trava organização ou conta), não por leitura de cada chamador.
**Estado:** o desenho de concorrência **não** está encerrado, e a implementação completa **não** está autorizada.

## 1. Decisões da revisão de 2026-09-28

| Achado | Decisão | Onde |
|---|---|---|
| I-1 | Aprovado corrigir a Platform. O lock da organização vem **antes do consumo do token**, não só antes do `UPDATE users` | §4.1 |
| I-2 | Aprovada a ordem organização → conta → solicitação → operador, **com reconferência** dos dados depois dos locks | §4.2 |
| I-3, I-4 | Aprovada a retirada **pontual** da FK `ops_registry_events.organization_id → organizations`, preservando o identificador histórico e sua origem validada. **Não** vira regra para tabelas novas; as demais FKs ficam | §4.3, §6 |
| I-5 (novo) | Proposta nesta v2 (§4.4) | — |

## 2. Referência: ordens explícitas (corrigida)

| Caminho | Ordem, explícita + implícita relevante | Fonte |
|---|---|---|
| Atos da 016 sobre a solicitação | organização `FOR UPDATE` → conta alvo `FOR UPDATE` → solicitação `FOR UPDATE` → operadores `FOR SHARE`. **Pré-aprovação e execução** ainda inserem avisos aos demais `ORG_ADMIN` (`ops.criar_avisos`) → `KEY SHARE` na conta de **cada destinatário**, depois de tudo | 016; matriz §5.2 |
| Avisos (Platform, 016) | organização → conta alvo → solicitação → aviso ou token | 016 |
| Cadastro de operadores (016) | operadores primeiro; sem organização, conta ou solicitação (contrato §7) | 016 |
| Contatos (016) | organização → contatos → operador | 016 |
| Registro de falha (016) | solicitação `KEY SHARE` → operador → **organização e conta alvo** `KEY SHARE`, pelas FKs do evento (I-2) | 016 |
| Suspensão, reativação | organização → conta | `AdminService.java:54–59`, `:72–77` |
| **Reset administrativo** | sessão do ator **sem lock** (`validate(…, false)`, `SessionRepository.java:41`) → **conta alvo** `FOR UPDATE` (`AdminService.java:151`) → desafios, fator, códigos e sessões do alvo → `INSERT mfa_events` → `KEY SHARE` na conta do alvo **e na conta do ator** (014:222–223) | `AdminService.java:137–163` |
| Autenticação (login, cadastro, verificação, step-up, senha, recuperação) | conta do próprio usuário primeiro; depois só linhas dessa conta | `AuthService.java`; `AccountRepository.java:72` |
| `confirmEmail` (atual) | token `UPDATE` → `UPDATE users` → trigger → organização `FOR UPDATE` (I-1) | `AuthService.java:420–437` |

A v1 dizia "reset administrativo trava só a conta alvo". **Estava incompleto:** o evento do reset pede também a conta do ator.

## 3. Locks implícitos por tabela

| Tabela escrita | Lock implícito | Observação |
|---|---|---|
| `users` (`UPDATE` de qualquer coluna) | **`trg_users_admin_presence`** (003:537, por comando) → `organizations` **`FOR UPDATE`** (003:500) | Idem para `organization_memberships` (003:531) e `organization_role_assignments` (003:525) |
| `user_sessions` (`INSERT`) | `KEY SHARE` em `users` (002:237) | Sem FK para `organizations` |
| `auth_challenges`, `user_mfa_factors` (`INSERT`) | `KEY SHARE` em `users` (014:169, :61) | — |
| `mfa_events` (`INSERT`) | `KEY SHARE` em `users` do **alvo** (014:222) **e do ator** (014:223); na solicitação, se tipo 4 (016:588) | Fonte do achado I-5 |
| `user_mfa_recovery_codes` | `KEY SHARE` no fator (014:116) | — |
| `password_reset_tokens`, `email_verification_tokens`, `user_credentials` | `KEY SHARE` em `users` (002:212, :192, :62) | — |
| `ops.ops_mfa_recovery_events` | `KEY SHARE` em `organizations` (016:359), `users` (016:380), solicitação (016:379) e operador (016:367) | I-2 |
| `ops.ops_registry_events` | `KEY SHARE` em `organizations` (016:407), operadores, certificado e contato | I-3, I-4 (FK a retirar: §6) |
| `ops.ops_account_link_proofs` | `KEY SHARE` em `users` (016:145) | — |
| `ops.ops_mfa_notices` | `KEY SHARE` em `users` do **destinatário** (016:322) e na solicitação | I-5 |
| `ops.org_authorized_contacts` | `KEY SHARE` em `organizations` (016:172) e `users` (016:182) | O caminho trava a organização antes |
| Tabelas das 010/012 | triggers que travam `organizations` (010:85, :149, :260, :333, :422, :589; 012:126, :551) | Não travam contas, solicitações nem operadores da 016 antes |

- **Busca de escritas em `users`, `organization_memberships` e `organization_role_assignments`:** no Java, só `confirmEmail` (`AuthService.java:434`) e `updateStatus` (`AccountRepository.java:96`, usado só em `suspend`/`reactivate`); nas migrations, nenhuma função.
- **Emissão e revogação de tokens de verificação de e-mail:** a busca em `api/**/src/main` e em `db/migrations` **não** encontrou nenhum emissor nem revogador. Só `confirmEmail` consome.
- **Exclusão de organizações e contas:** nenhuma migration faz `DELETE` em `organizations` ou `users`.

## 4. Achados e correções

### 4.1 I-1 — `confirmEmail` (decisão: organização → conta → token)

- **Problema atual:** `UPDATE email_verification_tokens` (token) → `UPDATE users` (conta, `FOR NO KEY UPDATE`) → trigger → organização `FOR UPDATE`. Ordem: token → conta → organização.
- **Desenho corrigido:**
  1. **Leitura sem lock:** busca o token pelo hash e obtém `user_id` e `target_email`. Se não existe, recusa genérica, sem lock nenhum.
  2. **Locks:** organização `FOR UPDATE` (`accounts.lockOrganization`) → conta do `user_id` lido `FOR UPDATE` (`accounts.lock`). O `FOR UPDATE` na organização não aumenta a contenção de hoje, porque o trigger já o toma ao fim do `UPDATE users`.
  3. **Reconferência e consumo atômicos, depois dos locks:** `UPDATE email_verification_tokens SET consumed_at = clock_timestamp() WHERE token_hash = ? AND user_id = <conta travada> AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp() RETURNING target_email`. Sem linha, a recusa é genérica e nada muda.
  4. `UPDATE users SET email_verified_at = coalesce(…) WHERE user_id = <conta travada> AND email = <target_email devolvido>`. O trigger encontra a organização já travada.
  - Ordem resultante: **organização → conta → token → conta (`UPDATE`)**, sem nada depois.
- **Disputas:**

| Concorrente | Resultado esperado |
|---|---|
| Suspensão ou reativação da mesma conta (organização → conta) | Serializam na organização; quem chega depois vê o estado do primeiro |
| Outro `confirmEmail` com o mesmo token | Serializam na organização e na conta; o segundo não encontra token não consumido → recusa genérica |
| Emissão ou revogação do token (**nenhuma no código hoje**) | Regra para o emissor e o revogador, quando existirem: **organização → conta → token**. Com isso, revogação e consumo se serializam na conta; um consumo depois de uma revogação confirmada recusa |
| Autenticação da própria conta (conta primeiro, nada depois) | `confirmEmail` espera a conta; a autenticação não espera nada que ele segure → espera simples |
| Atos da 016 com o alvo nessa conta (organização → conta) | Serializam na organização |
| Execução do item 5 com a conta como considerada (organização → contas crescentes) | Serializam na organização |

- **Testes:**
  - as disputas acima, nas duas ordens;
  - **ensaio determinístico da versão atual**, com a sessão A seguindo os passos de hoje até o `UPDATE users`, e a sessão B executando um ato da 016 sobre a mesma conta: `40P01` antes da correção (contraprova), espera simples depois;
  - token de outro usuário, vencido, revogado ou consumido → recusa genérica sem lock na conta errada.

### 4.2 I-2 — `ops_mfa_record_failure` (decisão: ordem canônica com reconferência)

- **Desenho:**
  1. leitura sem lock da solicitação (organização e alvo);
  2. organização `FOR KEY SHARE` → conta alvo `FOR KEY SHARE` → solicitação `FOR KEY SHARE` → operador `FOR SHARE`;
  3. **reconferência depois dos locks:** a solicitação existe, e organização e alvo lidos batem com a linha travada (imutáveis pelo trigger da 016; a conferência documenta e protege contra erro de leitura); `v_agora` e `ops.autorizar` depois dos locks;
  4. `INSERT` do evento. As FKs encontram os locks já obtidos.
- **Modos:** `KEY SHARE` na organização e na conta só espera um ato com `FOR UPDATE` em curso, e o ato não espera o registro de falha. Sem inversão **encontrada**.
- **Testes:** ensaio determinístico (registro de falha segurando a solicitação × ato segurando a organização) com `40P01` antes e espera simples depois; reconferência com solicitação inexistente → recusa.

### 4.3 I-3 e I-4 — retirada pontual da FK de auditoria (decisão)

- **Retira-se somente** `ops_registry_events.organization_id → organizations` (016:407). A coluna e o valor continuam gravados.
- Com isso, os vínculos do operador (operador → prova → evento) e a prova de conta (`KEY SHARE` na conta → evento) deixam de pedir a organização.
- Documentação exigida: §6.

### 4.4 I-5 — reset administrativo, avisos da 016 e resets cruzados (novo)

**Caminhos que formam ciclo:**

| # | Entrelaçamento | Ciclo |
|---|---|---|
| a | **Dois resets cruzados** na Platform: R1 (ator A, alvo B) e R2 (ator B, alvo A) | R1 segura B `FOR UPDATE` e pede `KEY SHARE` em A (evento); R2 segura A `FOR UPDATE` e pede `KEY SHARE` em B. **Preexistente**, sem a 016 |
| b | **016 atual**, pré-aprovação ou execução com alvo A × reset de B cujo ator é A | O ato segura organização → A `FOR UPDATE` → solicitação e pede `KEY SHARE` em B (aviso ao destinatário B); o reset segura B `FOR UPDATE` e pede `KEY SHARE` em A (evento, ator A) |
| c | **Proposta do item 5**: execução segurando A e pedindo B `FOR SHARE` × reset de B por A | Igual a (b), com B como considerado |
| d | **Proposta do item 10**: reenvio a B (organização → A → solicitação → `KEY SHARE` em B) × reset de B por A | Igual a (b) |

- (b) a (d) exigem que o alvo A da emergência tenha sessão válida com step-up recente, o que o cenário C torna improvável, mas o banco não pode supor. (a) só exige dois administradores ativos.
- **Achado adicional (TOCTOU):** o reset valida a sessão do ator **antes** dos locks, sem trava, e não reconfere depois (`AdminService.java:142–153`).
  - Em (a), sem impasse (R1 confirma antes de R2 começar os locks), R2 pode prosseguir com um ator cuja sessão e cujo fator R1 acabou de revogar e desabilitar.
  - Os dois administradores terminariam sem MFA, e o segundo reset seria feito por uma sessão já revogada.

**Coordenação proposta:** a regra de contas do §5 aplicada a cada caminho.

| Caminho | Contas envolvidas | Novo desenho |
|---|---|---|
| Reset administrativo | Alvo (`FOR UPDATE`) e ator (`FOR KEY SHARE`, o modo que o evento exige) | Validação preliminar da sessão (como hoje) → **as duas contas em ordem crescente de `user_id`** → **reconferência, depois dos locks**: sessão do ator ainda válida (`validate` de novo), step-up recente, permissão e alvo ≠ ator → efeitos |
| Pré-aprovação e execução (016 atual) | Alvo (`FOR UPDATE`) e **destinatários dos avisos** aos demais `ORG_ADMIN` (`FOR KEY SHARE`) | organização → **alvo e destinatários juntos, em ordem crescente** → solicitação → operadores. O conjunto de destinatários é lido de novo depois dos locks; se mudou, repete-se a aquisição ou se recusa o ato (a definir na implementação, mas nunca inserindo aviso para conta não travada) |
| Atos do item 5 | Alvo e considerados | Já previsto: organização → contas em ordem crescente → … Os considerados incluem os destinatários |
| Reenvio do item 10 | Alvo e destinatário | organização → **alvo e destinatário, em ordem crescente** → solicitação → tentativas → tokens |

- **Por que fecha os ciclos (a)–(d)** (condicionado ao inventário das tabelas novas):
  - todos esses caminhos travam as contas envolvidas **juntas e em ordem crescente**, antes das escritas que pedem `KEY SHARE`;
  - o reset não trava a organização, mas nenhum caminho segura uma conta e depois pede a organização (com I-1 corrigido);
  - a autenticação trava uma única conta e nada depois.
  - Ordens parciais consistentes (organização antes de contas; contas em ordem crescente) não formam ciclo **entre esses caminhos**.
- **Testes:**
  - **dois resets cruzados concorrentes:**
    - ensaio determinístico da versão atual → `40P01` (contraprova);
    - depois da correção → um conclui e o outro, ao obter os locks, reconfere a sessão do ator (revogada pelo primeiro) e é recusado (`SESSION`), sem efeito;
    - variante com os dois começando depois do commit do primeiro → recusa pela reconferência (cobre o TOCTOU);
  - (b), (c) e (d) nas duas ordens, com espera conferida em `pg_locks`;
  - reset com ator de `user_id` menor e maior que o alvo, para exercitar a ordem crescente dos dois lados.

### 4.5 Resumo

| Achado | Onde | Correção | Estado |
|---|---|---|---|
| I-1 | Platform `confirmEmail` | organização → conta → reconferência e consumo do token → `UPDATE users` | **Aprovado** (desenho v2) |
| I-2 | 016 `ops_mfa_record_failure` | organização → conta → solicitação → operador, com reconferência | **Aprovado** |
| I-3, I-4 | 016 `ops_registry_events` | Retirada pontual da FK de organização | **Aprovado** (§6) |
| I-5 | Platform `resetMfa`; 016 avisos (pré-aprovação, execução); propostas dos itens 5 e 10 | Contas envolvidas travadas juntas em ordem crescente; reconferência do ator depois dos locks | **Proposto** |

**Textos a corrigir depois da implementação:** matriz §5.1, §5.2 e §5.3; relatório §4, item 3–4. Não editados nesta rodada.

## 5. Regra de ordem entre contas (proposta)

**Todo caminho que precisa de mais de uma conta** trava **todas** essas contas **juntas, em ordem crescente de `user_id`**, **antes** de qualquer escrita que peça lock nelas. "Precisa" inclui `FOR UPDATE`/`FOR SHARE` explícito e `KEY SHARE` implícito por FK: evento com ator, aviso ao destinatário, fotografia de considerados. Cada conta vai no modo que o caminho exige.
- Quando o caminho trava a organização, as contas vêm **depois** dela.
- Nenhum caminho pede a organização depois de travar uma conta. Isso exige I-1 corrigido e vale para todo caminho futuro que escreva em `users`, `organization_memberships` ou `organization_role_assignments`.
- Os caminhos de uma conta só (autenticação) cumprem a regra trivialmente.

## 6. Retirada pontual da FK de auditoria (I-3, I-4)

- **Escopo:** somente `ops_registry_events.organization_id → organizations`. Continuam:
  - as FKs de `ops_registry_events` para operadores, certificado e contato (016:403–408);
  - todas as FKs de `ops_mfa_recovery_events` (016:359, :367, :379, :380).
- **Não** é regra para tabelas novas: cada FK nova é decidida no inventário do esquema detalhado (§7).
- **Funções que gravam `ops_registry_events` com organização, e de onde cada uma a deriva** (as demais gravam `NULL`):

| Função | Evento | Origem da organização | Como impede identificador arbitrário |
|---|---|---|---|
| `ops_account_proof_issue` (016:2024–2026) | `PROVA_CONTA_EMITIDA` | Parâmetro `p_org` | Exige `nekkora.organization_id` (contexto da requisição autenticada da Platform) igual a `p_org` e conta ativa com `organization_id = p_org` em `users` (FK de `users` para `organizations`) |
| `ops_link_platform_account` (016:1675) | `VINCULO_CONTA` | `organization_id` da prova consumida | A prova foi gravada com FK composta `(organization_id, user_id) → users` (016:145) e é travada `FOR UPDATE` no caminho |
| `ops_link_contact` (016:1754) | `VINCULO_CONTATO` | `organization_id` da prova de contato | FK composta `(organization_id, contact_id) → contatos` (016:219), e o contato tem FK para `organizations` (016:172) |
| `ops_contact_link_code_issue` (016:1709, :1716) | `PROVA_CONTATO_EMITIDA` | `organization_id` do contato | Linha do contato lida e travada no caminho; organização travada `FOR UPDATE`; recusa grava a organização só se o contato existe |
| `ops_contact_add` (016:1833, :1842) | `CONTATO_INCLUIDO` | Parâmetro `p_org` | Linha de `organizations` travada `FOR UPDATE` no próprio caminho; a recusa grava `NULL` se a organização não existe (016:1834) |
| `ops.contato_alterar` → `ops_contact_change_channel`, `ops_contact_remove` (016:1883, :1897) | `CONTATO_CANAL_ALTERADO`, `CONTATO_REMOVIDO` | `organization_id` da linha do contato | Contato lido e travado; organização travada `FOR UPDATE` |

- **Proteções adicionais:**
  - `ops.registrar_cadastro` é interna, sem `EXECUTE` para nenhum papel além do dono, conferido pelo bloco de invariantes da 016;
  - nenhuma função pública recebe a organização do evento como valor livre sem as validações acima.
- **Teste de consistência a acrescentar:** para cada evento com organização, ela é igual à da entidade referenciada: contato → `org_authorized_contacts.organization_id`; vínculo → prova; prova de conta → `users`.
- **Histórico:**
  - o identificador permanece gravado mesmo que a organização deixe de existir; sem FK, não há cascata nem bloqueio;
  - hoje nenhuma migration exclui organizações;
  - uma exclusão futura (retenção, D9) não apaga nem invalida o evento; a retenção dos próprios eventos é D9.

## 7. Tabelas novas dos itens 4, 5 e 10 (a inventariar com o esquema)

1. Cada FK nova é **decidida caso a caso** neste inventário, pelo modo e pelo momento em que o `KEY SHARE` é pedido. Retirar FK **não** é padrão.
2. **Contas:** a regra do §5. Fotografia de considerados (item 5) e tentativas de reenvio (item 10) inserem linhas com FK para `users` só depois de a conta envolvida estar travada no conjunto crescente.
3. **Item 4:** estado das versões, baldes, entradas e retornos. FKs só entre si, para operadores e para contatos, pedidas depois dos locks correspondentes. Nenhuma para `organizations` ou `users` num caminho que não trave antes.
4. **Item 10:** o registro de resultado só atualiza tentativas e tokens, sem inserir linha com FK nova.
5. Todo trigger novo declara o que lê e o que trava.

## 8. Ensaios (complemento, não prova)

- **Determinístico, para cada achado:**
  - a sessão A reproduz o caminho **atual** passo a passo (mesmas tabelas e modos) e para antes do lock implícito;
  - a sessão B chama a função ou o fluxo real até esperar;
  - A dá o passo seguinte;
  - `40P01` antes da correção (contraprova), espera simples depois.
- **Estresse** com `deadlock_timeout` baixo: complemento.
- **Conferência** em `pg_locks`/`pg_stat_activity` de quem espera quem, e em que modo.

## 10. Esquema detalhado das tabelas novas e seu inventário (v3, antes da implementação)

Convenções:
- "KS" = `FOR KEY SHARE` implícito da FK;
- o caminho indicado é o **único** que escreve a tabela;
- o conjunto de contas segue o §5 (organização → contas crescentes → …).

### 10.1 Item 4 — entrada, versões de chave, baldes e retorno

| Tabela ou coluna | Colunas principais | FKs (KS) | Escrita por, e ordem |
|---|---|---|---|
| `ops.ops_intake_key_state` | Linha única: `current_version`, `activated_at`, `previous_version` | — | `ops_intake_key_activate` (papel de entrada): estado `FOR UPDATE`. Semente: versão 1 na migration |
| `ops.ops_intake_entries` | `channel_type`, `mechanism` (1 desafio e-mail, 2 desafio telefone, 3 não comprovada), `origin_version`/`origin_pseudonym`, `prev_version`/`prev_pseudonym`, `designated_operator_id`, `created_at`, `expires_at` (+60 min), `used_at`, `used_request_id` (sem FK, para não formar ciclo com a solicitação) | Operador designado (KS) | `ops_intake_register`: estado das versões `FOR SHARE` → `INSERT` (KS no operador). Uso: `ops_mfa_open` (`FOR UPDATE` na entrada) |
| `ops.ops_origin_buckets` | `bucket_key` = `versão:pseudônimo` | — | `ops_mfa_open`: `INSERT … ON CONFLICT DO NOTHING` e `FOR UPDATE`, em ordem crescente |
| `ops.ops_mfa_callbacks` | Organização, contato, operador, `channel_type`, `channel_set_at` (versão do canal), `created_at`, `expires_at` (+60 min), `used_at`, `used_request_id` | `(org, contato) → contatos` (KS); operador (KS) | `ops_mfa_callback_register`: organização `FOR UPDATE` → contato `FOR SHARE` → operador `FOR SHARE` → `INSERT` |
| `ops_mfa_recovery_requests` + | `open_mode` (1 entrada, 2 retorno), `intake_entry_id`, `callback_id`, pseudônimos com versões | Entrada (KS), retorno (KS), já travados `FOR UPDATE` no caminho | `ops_mfa_open` |

- **Ordem de `ops_mfa_open`:** organização → conta alvo → (entrada: estado das versões `FOR SHARE` → baldes crescentes) → entrada ou retorno `FOR UPDATE` → operador `FOR SHARE` → `INSERT` da solicitação.
- **KS do `INSERT` da solicitação:**
  - contato solicitante: quem trava contatos `FOR UPDATE` toma a organização antes, o que dá espera simples;
  - conta alvo, entrada e retorno: já travados;
  - operador: já em `FOR SHARE`.
- **Cruzamentos:**
  - `ops_intake_register` segura o estado das versões e pede KS no operador; quem segura o operador `FOR UPDATE` (cadastro, CA) não pede o estado das versões;
  - a ativação trava só o estado das versões.
  - Sem ciclo **encontrado**.

### 10.2 Item 5 — declaração e fotografia

| Tabela ou coluna | Colunas | FKs (KS) | Escrita por, e ordem |
|---|---|---|---|
| `ops_mfa_recovery_requests` + | `other_admins_declaration` (1 `NENHUM`, 2 `TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS`), `other_admins_captured_at`, `other_admins_count` | — | `ops_mfa_verify` |
| `ops.ops_mfa_request_admins` | `(request_id, user_id)`, `captured_at`, `factor_confirmed_active`, `totp_last_step`, `active_codes`, `active_sessions`, `declared_no_usable_factor`, `declared_no_usable_codes` | Solicitação (KS, já `FOR UPDATE`); `(org, user) → users` (KS; conta já `FOR SHARE`) | `ops_mfa_verify`: organização → **contas crescentes** (alvo `FOR UPDATE`, considerados `FOR SHARE`) → solicitação → operadores → `INSERT` |

- **Composição:** o conjunto dos demais `ORG_ADMIN` é lido depois do lock da organização, as contas são travadas, e o conjunto é **lido de novo**.
  - Se apareceu conta não travada (por exemplo, papel `ORG_ADMIN` inserido, o que **não** trava a organização pela 003: só a FK para `organization_memberships`), o ato é **recusado sem escrever** (`CONTAS_EM_MUDANCA`, recusa do ato) e **não** trava a conta nova fora de ordem.
  - Mesma regra na pré-aprovação, na aprovação final, na execução e no reenvio.

### 10.3 Item 10 — tentativas, histórico e aprovação

| Tabela ou coluna | Colunas | FKs (KS) | Escrita por, e ordem |
|---|---|---|---|
| `ops_mfa_notices` (tentativa) + | `seq` (único por solicitação), `state` (0 pendente, 1 `EMITIDA`, 2 `ENTREGANDO`, 3 `ENFILEIRADA`, 4 `ACEITA`, 5 `FALHOU`, 6 `DESCONHECIDO`), `state_at`, `claim_id`, `claimed_at`, `accepted_at`, `category`. `discarded` dá lugar a `FALHOU`/`SOLICITACAO_ENCERRADA` | As existentes | Criação: pré-aprovação, execução e reenvio (contas → solicitação). Estados: `ops_notice_issue`, `ops_notice_claim`, `ops_notice_delivery_result`, `ops_notice_recover_stalled`: organização → conta alvo → solicitação → tentativa `FOR UPDATE` → tokens `FOR UPDATE` crescentes |
| `ops_mfa_recovery_requests` + | `notice_seq` (contador), `notice_seq_at_approval` | — | Incrementado sob o lock da solicitação |
| `ops_mfa_notice_tokens` | **Retirado** o índice único de token ativo por (solicitação, destinatário, finalidade), porque vários tokens válidos passam a coexistir até o aceite do novo. Índice comum no lugar | As existentes | — |
| `ops.ops_mfa_notice_results` (histórico) | Tentativa, estado informado, aplicado (sim ou não), categoria, instante | Tentativa (KS; já `FOR UPDATE`) | `ops_notice_delivery_result`, `ops_notice_claim`, recuperação |
| `ops.ops_mfa_approval_attempts` | `(request_id, notice_id)`, destinatário, estado e aceite no instante da aprovação | Tentativa (KS) | Aprovação final, depois da solicitação `FOR UPDATE`. Todo escritor de tentativa trava a solicitação antes: não há escrita concorrente na tentativa |

### 10.4 Platform (I-1, I-5)

| Caminho | Ordem nova |
|---|---|
| `confirmEmail` | Leitura do token sem lock → organização `FOR UPDATE` → conta `FOR UPDATE` → `UPDATE` do token (reconferência: mesma conta, não consumido, não revogado, não vencido) → `UPDATE users` |
| `resetMfa` | Verificação preliminar **sem lock** (`validate(…, false)`) → organização `FOR UPDATE` → **alvo `FOR UPDATE` e ator `FOR KEY SHARE`, em ordem crescente**, cada um já no modo final (sem promoção) → reconferência (sessão, step-up, permissão, elegibilidade do alvo, alvo ≠ ator) → efeitos → evento |

### 10.5 Conclusão do inventário do esquema

**Ordem global proposta:** organização → contas (crescentes) → estado das versões → baldes → entrada ou retorno → solicitação → tentativas → tokens → operadores.
- O cadastro de operadores, a CA e a ativação de versões começam **fora** dessa sequência: operador ou estado das versões primeiro, **sem** pedir nada anterior a eles nela (com I-3 corrigido).
- A autenticação trava uma conta e nada depois.
- Dentro do escopo inventariado, **nenhuma inversão encontrada**. Os ensaios determinísticos e de estresse (§8) acompanham a implementação.

## 11. Decisões da v3

### 11.1 I-5 (aprovado)

**Reset administrativo:**
1. organização;
2. ator e alvo em ordem crescente, cada um no modo final desde o início, sem promoção;
3. reconferência de sessão, step-up, permissão, elegibilidade e proibição de agir sobre si mesmo;
4. só então os efeitos e o evento.

A verificação preliminar não deixa lock de sessão (`validate(…, false)`). Nos resets cruzados, o segundo encontra a sessão do ator revogada e é recusado sem efeito no alvo.

**Pré-aprovação, execução e reenvio:** os destinatários entram no conjunto ordenado de contas, **deduplicado**, e a composição é reconferida antes das escritas. Conta necessária não travada → recusa do ato, sem aquisição fora de ordem.

**Testes:**
- contraprova do impasse anterior;
- resets cruzados nas duas ordens, com **exatamente um** concluído;
- reset concorrente com os caminhos da 016 e com o reenvio;
- recusa depois de revogação da sessão, perda da permissão ou vencimento do step-up durante a espera pelos locks.

## 12. Conclusão (da v2; a v3 acrescenta os §10 e §11)

- **I-1 a I-4:** decididos. Desenhos concretos no §4.
- **I-5:**
  - preexistente na Platform (resets cruzados) e presente na 016 atual (avisos aos demais);
  - afeta as propostas dos itens 5 e 10;
  - coordenação proposta no §4.4 e na regra do §5, **aguardando decisão**.
- **Item 5:** continua aceito, condicionado a I-1 (corrigido) e I-5 (a decidir).
- **Encerramento:** o desenho de concorrência **não** está encerrado. Faltam a decisão sobre I-5 e o inventário das tabelas novas.

## 13. Estado depois da implementação (rodada 3)

O §12 registra o estado da v2. Depois dele:
- **I-5** foi aprovado (§11.1), e o inventário das tabelas novas foi concluído (§10), sem nova inversão nem mudança de política.
- **I-1 a I-5** estão implementados na 016 e na Platform como desenhados (§4, §10.4, §11.1).
- **Testes** (execução `ops/mfa-emergencia/saidas/2026-09-28T125940Z/`, `APROVADO`):
  - contraprovas "ordem anterior" com `40P01` para I-1, I-2, I-3/I-4 e I-5, reproduzidas em SQL direto (provam a ordem, não executam o código anterior);
  - espera simples, sem impasse, com o código corrigido, nas duas ordens de chegada, conferida em `pg_stat_activity`;
  - resets cruzados com exatamente um concluído; recusa depois de revogação de sessão, perda de permissão ou vencimento de step-up durante a espera;
  - reset concorrente com execução, pré-aprovação e reenvio da 016;
  - mutação "sinais lidos antes dos locks" detectada.
- **Fora do alcance dos testes:** estresse longo de ausência de deadlock (logins, step-ups e execuções em massa). Os ensaios são determinísticos, não de carga.
- Detalhe e limites: relatório de implementação, §5f.
