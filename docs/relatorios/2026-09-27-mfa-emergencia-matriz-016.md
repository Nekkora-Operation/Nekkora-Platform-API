# Recuperação emergencial de MFA — matriz da 016 e inventário de locks

**Data:** 2026-09-27.
**Base:** contrato aprovado `docs/relatorios/2026-09-27-contrato-recuperacao-emergencial-mfa.md` (commit `ca4033b`, SHA-256 `d22f3b6c…4353`).
**Estado:**
- desenho registrado **antes** de consolidar o SQL da `db/migrations/016_recuperacao_emergencial_mfa.sql`;
- atualizado depois da implementação com os nomes finais e com o que os testes mostraram (§5.3);
- candidato para revisão, sem habilitação em produção.

A migration confere esta matriz no próprio bloco final (§19 da 016), e a suíte `db/tests/mfa-emergencia-016` confere de novo na instalação limpa e na base atualizada.

> **Revisão 4 (rodada 3, 2026-09-28):** os §1–§6 abaixo descrevem a revisão 3. As mudanças da revisão 4 estão no §7, e o inventário de locks completo e vigente está em `2026-09-28-mfa-emergencia-inventario-locks.md` (v3, §10). Onde os dois divergem, valem o §7 e o inventário.

## 1. Papéis

| Papel | Tipo | Atributos | Membros | Uso |
|---|---|---|---|---|
| `nekkora_ops_mfa_owner` | NOLOGIN | sem SUPERUSER, CREATEDB, CREATEROLE, REPLICATION ou BYPASSRLS | nenhum | Dono das funções operacionais (`public.ops_*` e internas `ops.*`). Nunca conecta |
| `nekkora_ops_mfa` | NOLOGIN (grupo) | idem | papéis `op_*`, sem ADMIN OPTION | Recebe `EXECUTE` só nas 20 funções de operador |
| `op_<apelido>` | LOGIN, **um por pessoa** | idem, sem senha | — | Operador. Criado pelo DBA **fora** da migration. Só `hostssl … cert map=ops clientname=CN`. Membro **só** de `nekkora_ops_mfa`, conferido no cadastro (`ops.papel_operador_invalido`) |
| `nekkora_ops_ca` | LOGIN | idem | nenhum | Serviço da CA: resolve sujeito do IdP → papel e registra ou revoga certificados |
| `nekkora_platform` | LOGIN (existente) | inalterado | — | Emite avisos, consome tokens e emite provas de conta. **Nenhuma** função de operador. Perde o INSERT em `mfa_events.ops_request_id` |
| demais papéis de serviço | existentes | inalterados | — | **Nenhum** privilégio novo |

**Elevação:** a 016 exige superusuário, como a 011–013, por dois motivos: `CREATE ROLE` e `CREATE EXTENSION sslinfo` (extensão não confiável). A inclusão da 016 na elevação autorizada da homologação (`elevacao.txt`) **não foi feita** e depende de decisão.

## 2. Schema `ops` e tabelas

- O schema `ops` pertence ao migrador. Só `nekkora_ops_mfa_owner` tem USAGE; nenhum papel de LOGIN tem USAGE.
- A extensão `sslinfo` fica em `ops`, com EXECUTE só para o dono operacional.
- As 14 tabelas pertencem ao migrador. O dono operacional tem SELECT, INSERT e UPDATE nelas (só SELECT e INSERT nas três de auditoria), **sem** DELETE, TRUNCATE, REFERENCES ou TRIGGER.
- Todas as tabelas têm triggers de imutabilidade e de bloqueio de DELETE e TRUNCATE.

| Tabela | Conteúdo | Protege |
|---|---|---|
| `ops_operators` | Operador, `db_role` único, (`idp_issuer`, `idp_subject`) único, estado (desativação final) | Identidade operacional |
| `ops_operator_permissions` | Habilitação por ato (1 VERIFICAR … 6 CADASTRAR), com histórico e retirada; `granted_by ≠ operator_id` | Habilitação no instante do ato |
| `ops_operator_certificates` | Emissor, serial, `not_before`, `not_after` (≤ 15 min), `auth_time` e método do IdP (CHECK: `phr+mfa` ou `phrh+mfa`), revogação | Autorização por ato |
| `ops_account_link_proofs` → `ops_operator_platform_accounts` | Prova de controle de conta (hash; emitida pela Platform na sessão do titular) → vínculo | Vínculo impeditivo |
| `ops_contact_link_proofs` → `ops_operator_contact_links` | Prova emitida por **outro** operador (código pelo canal do contato; hash) → vínculo | Vínculo impeditivo |
| `org_authorized_contacts`, `org_authorized_contact_events` | Contatos autorizados, `channel_set_at`, remoção; eventos com as confirmações (nunca o valor do canal) | D2 |
| `ops_mfa_recovery_requests` | Estados, prazos (`t_total = abertura + 72 h`), participantes, fator aprovado; CHECKs de distinção e de prazo; exclusividade por alvo em VERIFICADA–APROVADA | Máquina de estados |
| `ops_mfa_notices`, `ops_mfa_notice_tokens` | Avisos por destinatário; tokens por finalidade (só SHA-256), uso único, validade | Contrato 6.1 |
| `ops_mfa_recovery_events`, `ops_registry_events` | Auditoria append-only com operador, `session_user`, emissor e serial lidos pelo servidor, origem (`CONFIRMADO_PELO_BANCO`, `INFORMADO_PELA_CLI`) | Auditoria |

**`mfa_events`:**
- colunas novas `ops_request_id` e `reason_code`;
- FK composta com a solicitação, `UNIQUE (ops_request_id)`;
- CHECKs: `(event_type = 4) = (ops_request_id IS NOT NULL)`, tipo 4 sem ator, `reason_code` só no tipo 3, e `event_type <> 3 OR reason_code IS NOT NULL` **NOT VALID**.

## 3. Funções

Padrão de todas as funções públicas:
- `SECURITY DEFINER`, dono `nekkora_ops_mfa_owner`;
- `SET search_path = pg_catalog, pg_temp` e objetos qualificados;
- as que leem tabelas de tenant têm `SET nekkora.fn_organization_id = ''`, que zera o contexto na entrada e o restaura na saída;
- **(rodada 2)** a **primeira instrução** é `ops.exigir_read_committed()`: logo depois do `BEGIN` nas plpgsql, e primeiro comando nas SQL. Qualquer isolamento diferente de `read committed` dá erro `25N01` e rollback, porque em `REPEATABLE READ`/`SERIALIZABLE` o snapshot anterior aos locks esconderia revogações de certificado e de habilitação. O bloco de invariantes reprova a migration se alguma função pública não seguir a regra.

**Ato de solicitação** (chamador `nekkora_ops_mfa`, retorno `(r_resultado OK|RECUSA, r_motivo, r_solicitacao, r_estado)`):

| Função | Habilitação | Efeito |
|---|---|---|
| `ops_mfa_open(org, alvo, contato_solicitante)` | VERIFICAR | `SOLICITADA`. Limite de 3 por origem em 24 h; ≥ 2 contatos elegíveis; alvo do cenário C |
| `ops_mfa_verify(solicitação, retorno_ok, segundo_contato, segundo_ok)` | VERIFICAR | `VERIFICADA`. Exclusividade por alvo; não verificadas substituídas; limites D6c |
| `ops_mfa_preapprove(solicitação)` | APROVAR, ≠ verificador | `PRE_APROVADA`; fixa o fator aprovado; cria os avisos |
| `ops_mfa_final_approve(solicitação)` | APROVAR, ≠ verificador | `APROVADA` se `confirmação + 24 h ≤ agora < T_total` |
| `ops_mfa_execute(solicitação)` | EXECUTAR, ≠ aprovadores | Reset (§7 do contrato) ou recusa |
| `ops_mfa_cancel(solicitação, motivo)` | CANCELAR | `CANCELADA` |
| `ops_mfa_resend_notice(solicitação)` | VERIFICAR | Novo aviso ao titular, sem `CONFIRMAR` depois da confirmação |
| `ops_mfa_status(solicitação)` | CONSULTAR | Estado, com a trava da solicitação; `lock_timeout` fixo de 10 s; `TRAVA_NAO_OBTIDA` sem a trava |
| `ops_mfa_record_failure(solicitação, ato, sqlstate, etapa)` | operador ativo com certificado vigente | Evento `INFORMADO_PELA_CLI` |

**Cadastro** (chamador `nekkora_ops_mfa`, habilitação CADASTRAR, salvo nota):
- `ops_operator_create`, `ops_operator_grant`, `ops_operator_revoke` e `ops_operator_disable`: sem auto-habilitação nem autodesativação. A retirada e a desativação também revogam os certificados vigentes;
- `ops_contact_add`, `ops_contact_change_channel` e `ops_contact_remove`: confirmações da D2;
- `ops_contact_link_code_issue`: prova de contato;
- `ops_link_remove`: por outro operador;
- `ops_link_platform_account` e `ops_link_contact`: vínculo do **próprio** operador, que só precisa estar ativo com certificado vigente.

**CA** (chamador `nekkora_ops_ca`):
- `ops_ca_resolve_operator`;
- `ops_register_certificate`: validade ≤ 15 min, `auth_time` ≤ 5 min, serial único e método **`phr+mfa` ou `phrh+mfa`** (rodada 2: `acr` resistente a phishing mais `mfa` afirmado, conferidos pelo verificador da CA; um `amr` isolado como `hwk` não vale);
- `ops_revoke_certificate`.

**Platform** (chamador `nekkora_platform`):
- `ops_account_proof_issue`: exige o contexto de requisição da organização;
- `ops_notice_pending` e `ops_notice_issue`;
- `ops_notice_confirm` e `ops_notice_contest`: exigem que o contexto de requisição seja igual à organização da rota.

**Sem EXECUTE para ninguém:**
- `ops_bootstrap_operator`: só superusuário e só com o cadastro vazio;
- as funções internas `ops.*`: `exigir_read_committed`, `autorizar`, `participante_invalido`, `alvo_inelegivel`, `vencida`, `registrar`, `encerrar` e demais.

**Autoria:**
- o operador vem de `session_user` → `ops_operators.db_role`, e o CN do certificado (`sslinfo`) tem de ser igual a `session_user`;
- **nenhum parâmetro de autoria, emissor, serial ou instante**; o teste `IdentidadeIT` confere a lista exata de entradas das 20 funções.

**Autorização por ato** (`ops.autorizar`), depois dos locks e com `v_agora := clock_timestamp()` capturado depois deles:
1. certificado presente;
2. CN igual a `session_user`;
3. registro da CA com o mesmo emissor e serial, do mesmo operador, não revogado, com `not_before ≤ v_agora < not_after`;
4. operador ativo;
5. habilitação ativa para o ato.

**Recusa do ato × recusa da solicitação:**
- **Recusa do ato:** falha do próprio ator, de parâmetro ou de estado. É registrada e a solicitação **não** muda: outro operador habilitado pode agir.
- **Recusa da solicitação:** alvo inelegível, participante inválido, fator divergente, evidência negativa ou limite. A solicitação vai para `RECUSADA`, ou `EXPIRADA` se for por prazo.

## 4. Privilégios do dono operacional nas tabelas de tenant (RLS forçada)

| Tabela | Privilégio (por coluna) | Política nova `ops_function_context` |
|---|---|---|
| `organizations`, `users` | SELECT (ids, status, tipo); UPDATE (`updated_at`) **só** porque `SELECT … FOR UPDATE` exige UPDATE em alguma coluna; nenhuma função atualiza | `organization_id = fn_organization_id` |
| `organization_memberships`, `organization_role_assignments` | SELECT (ids, status) | idem |
| `roles` (global, sem RLS) | SELECT (`role_id`, `code`) | — |
| `user_mfa_factors`, `user_mfa_recovery_codes`, `auth_challenges`, `user_sessions` | SELECT das colunas lidas; UPDATE só das colunas do reset | idem |
| `mfa_events` | SELECT (ids, tipo, `ops_request_id`); INSERT (org, usuário, tipo, correlação, `ops_request_id`) | idem |

Sem acesso a `user_credentials`, sem INSERT ou UPDATE em papéis e vínculos, sem UPDATE em `mfa_events`. Isso é conferido no §19 da 016.

## 5. Inventário de locks

### 5.1 Explícitos, em ordem única

| Caminho | Ordem |
|---|---|
| Atos sobre a solicitação (verificar, pré-aprovar, aprovar, executar, cancelar, reenviar, consultar) | `organizations` FOR UPDATE → `users` do alvo FOR UPDATE → solicitação FOR UPDATE → `ops_operators` do ator e dos participantes, crescente, FOR SHARE (`ops.travar_operadores`) |
| Verificar: outras solicitações do mesmo alvo | Depois da própria, crescente, FOR UPDATE. Todo caminho que as trava já segura a conta do alvo antes |
| Abertura | `organizations` → `users` do alvo → ator FOR SHARE |
| Confirmação e contestação (Platform) | Token sem lock → `organizations` → `users` do alvo → solicitação → token FOR UPDATE |
| Emissão de aviso (Platform) | Aviso sem lock → `organizations` → `users` do alvo → solicitação → aviso FOR UPDATE |
| Registro de falha | Solicitação FOR KEY SHARE → operador FOR SHARE. Não conflita com FOR SHARE; só espera um FOR UPDATE da solicitação |
| Cadastro (habilitação, retirada, desativação, remoção de vínculo) | `ops_operators` envolvidos, crescente (alvo FOR UPDATE, ator FOR SHARE). **Nenhum** lock de organização, conta ou solicitação |
| Vínculo próprio (conta ou contato) | Próprio operador FOR UPDATE → prova FOR UPDATE → vínculo, com FK para a **prova** (§5.2) |
| Contatos (inclusão, alteração, remoção, prova de contato) | `organizations` FOR UPDATE → contatos (crescente) → ator FOR SHARE |
| CA: registro e revogação | Operador FOR UPDATE → certificado |
| Reset administrativo (Platform, existente) | `users` do alvo FOR UPDATE |
| Login (Platform, existente) | `users` FOR UPDATE → desafio |

### 5.2 Implícitos (FK, triggers, índices), conferidos

| Origem | Lock | Por que não forma ciclo |
|---|---|---|
| INSERT em `mfa_events` tipo 4 | FOR KEY SHARE em `users` e na solicitação | Ambos já travados FOR UPDATE pela própria execução |
| INSERT de aviso para os demais administradores | FOR KEY SHARE nas linhas `users` deles | Login e reset de outro administrador travam só a conta dele e não esperam organização, solicitação ou operador |
| INSERT de evento, habilitação ou certificado | FOR KEY SHARE no operador | Compatível com FOR SHARE; só espera um FOR UPDATE do mesmo operador (espera simples) |
| **Vínculo de conta** | FK para `ops_account_link_proofs`, **não** para `users` | Uma FK direta para `users` pediria KEY SHARE na conta alvo enquanto o operador está FOR UPDATE, e a execução trava a conta antes do operador. Isso formaria ciclo, evitado pelo desenho |
| UPDATE de fator, códigos, desafios e sessões | Row locks + triggers da 014 | Os triggers não consultam outras tabelas, exceto o INSERT de sessão, que só lê fatores |
| Triggers das tabelas `ops` | Nenhuma leitura de outras tabelas | — |
| Índices únicos parciais (exclusividade por alvo, habilitação ativa, token ativo) | Espera por inserção concorrente de mesma chave | Serializada antes pelos locks de organização e conta |
| Trigger `enforce_org_admin_presence` (003) | FOR UPDATE na organização | Nenhuma função da 016 altera `users`, vínculos ou atribuições |

### 5.3 O que os testes mostraram

1. **O registro de certificado espera a execução.** `ops_register_certificate` trava o operador FOR UPDATE. Enquanto uma execução segura a linha desse operador em FOR SHARE, **emitir credencial** para ele espera o commit da execução.
   - É coerente com a disciplina do contrato: a emissão altera a autorização daquele operador.
   - A espera é curta; nenhum ciclo foi observado.
   - Visto em `ConcorrenciaIT.vinculoImpeditivoNovoConcorrenteComAExecucao`, onde a sessão bloqueada era a da CA.
2. **Quem trava primeiro vence**, verificado nos dois sentidos com a transação da execução mantida aberta depois da função retornar (`ConcorrenciaIT`):
   - retirada de habilitação;
   - vínculo impeditivo novo;
   - cancelamento;
   - contestação;
   - login do titular;
   - reset administrativo;
   - disputa pelo limite da organização.

## 6. Fixtures temporais (só na base de teste)

- Os prazos do contrato (72 h, 24 h, 60 min, 15 min, 30 dias, 7 dias) estão fixos no código. Não foram encurtados e não há relógio configurável pelo chamador.
- Os testes deslocam para trás os instantes gravados, simulando a passagem do tempo:
  - solicitação: abertura, `T_total`, marcos e encerramento;
  - avisos e tokens: emissão, validade e uso;
  - registro de certificado;
  - `channel_set_at` e `created_at` dos contatos.
- O deslocamento é feito como superusuário, com `session_replication_role = replica` (sem triggers), **só** no container descartável (`OpsBanco.recuar*`, `vencerCertificado`, `envelhecerContato`).
- Os CHECKs de prazo continuam valendo, porque o deslocamento é uniforme.
- **Exceção declarada:** `recuarAbertura` desloca só a abertura, `T_total` e a verificação, que a CHECK amarra à abertura + 24 h. A pré-aprovação, os avisos e os tokens ficam no instante real ("aberta há 50 h, pré-aprovada agora").
  - Serve só ao teste da confirmação tardia (`AvisosIT`).
  - As CHECKs continuam satisfeitas: `t_total = opened_at + 72 h`, verificação dentro de 24 h da abertura, e pré-aprovação depois da verificação e antes de `T_total`.

## 7. Revisão 4 (rodada 3)

**Papéis:** novo `nekkora_ops_intake` (LOGIN, serviço de registro de entrada), com `EXECUTE` só em `ops_intake_register` e `ops_intake_key_activate`. Sem acesso a tabelas nem a funções de operador. `nekkora_ops_mfa` passa a ter 21 funções de operador.

**Tabelas novas em `ops`** (21 no total; mesmas regras de dono, privilégios, imutabilidade e bloqueio de DELETE/TRUNCATE):

| Tabela | Conteúdo |
|---|---|
| `ops_intake_key_state` | Linha única: versão vigente, anterior e instante de ativação (transição de 25 h) |
| `ops_intake_entries` | Entrada de uso único (60 min), mecanismo de comprovação, pseudônimos HMAC (vigente e, na transição, anterior), operador designado |
| `ops_origin_buckets` | Um balde por `versão:pseudônimo`, só para serializar a contagem da cota |
| `ops_mfa_callbacks` | Retorno pelo canal registrado: operador, contato, tipo e versão do canal, uso único, 60 min |
| `ops_mfa_request_admins` | Fotografia dos considerados na verificação (fator ativo, passo TOTP, códigos, sessões) |
| `ops_mfa_notice_results` | Histórico de cada resultado informado de uma tentativa (APLICADO, REPETIDO, ATRASADO, CONFLITANTE, TRANSICAO_INVALIDA, RECUPERACAO) |
| `ops_mfa_approval_attempts` | Tentativas vigentes consideradas na aprovação final |

**Funções públicas novas** (37 no total): `ops_mfa_callback_register` (operador, VERIFICAR); `ops_notice_claim`, `ops_notice_attempt_state`, `ops_notice_delivery_result`, `ops_notice_stalled`, `ops_notice_recover` (Platform); `ops_intake_register`, `ops_intake_key_activate` (entrada). Assinaturas alteradas: `ops_mfa_open(org, alvo, contato, entrada, retorno)`, `ops_mfa_verify(…, declaracao_demais)`, `ops_mfa_resend_notice(solicitacao, destinatario)`; `ops_notice_confirm/contest` devolvem a categoria interna.

**Ordem global de locks:** organização → contas (alvo `FOR UPDATE`, considerados e destinatários `FOR SHARE`, juntos e crescentes, deduplicados) → estado das versões → baldes → entrada ou retorno → solicitação → tentativas → tokens → operadores. Cadastro de operadores, CA e ativação de versão começam fora dessa sequência; a autenticação trava uma conta e nada depois. Detalhe por caminho: inventário v3, §10.

**FK retirada:** `ops_registry_events.organization_id → organizations` (I-3/I-4, decisão pontual). A coluna e o valor continuam gravados; a derivação e a validação da organização por função estão no inventário, §6.

**Platform:** `confirmEmail` e `resetMfa` seguem as ordens de I-1 e I-5 (inventário §10.4).

**Janelas:** 720 h (carência e organização) e 168 h (alvo), independentes do `TimeZone` da sessão.
