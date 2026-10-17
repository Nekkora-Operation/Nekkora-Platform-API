# Cadastro web e entrada geral: desenho do contrato e da compatibilidade (Missão 1)

**ESTADO: DESENHO FINAL, R3 IMPLEMENTADA LOCALMENTE (2026-10-11).** As rodadas R0, R1 e R2 continuam preservadas, cada uma com o seu pacote. A R2 foi aprovada pela responsável no escopo da migration e da suíte. A R3 traz o login com consumo, credencial vigente, fator e sessão numa só operação, a troca de senha, e a parte D da suíte (base populada **com a 016** → 017). O relatório é `2026-10-11-identidade-017-validacao-r3.md`, os ajustes estão na **§13.4** e o contrato da integração Java está em `2026-10-11-identidade-017-contrato-integracao-java.md`. **Fora desta etapa:** Java, implantação, ambiente preservado, Microsoft 365, commit e push.

A responsável aprovou a direção das duas missões e condicionou o início da migration ao fechamento das definições abaixo, todas incorporadas nesta revisão:

| Condição | Onde ficou |
|---|---|
| Identidade global sem vincular nem unir contas antigas automaticamente por e-mail | DC-17.2, já era a proposta |
| **Definir como convivem o MFA antigo e o novo**, incluindo recuperação e reset administrativo | **§3.3.1, nova** |
| **Quem pode redefinir o MFA global** — administrador de uma organização não pode ganhar poder sobre o acesso da pessoa às outras | **§3.3.1 e DC-17.9, novas** |
| Busca global limitada à autenticação, sem expor se uma identidade existe nem suas organizações | DC-17.1, condição acrescentada |
| Criação de organização exige e-mail verificado | DC-17.5, condição explicitada |
| UUID permanece como identificador nesta etapa; endereço legível fica para depois | DC-17.8, ratificada |
| As outras cinco decisões são de arquitetura, mesmo sem alternativa apresentada | §3, nota de abertura |
| **Vinculação exige comprovação de controle da identidade e da conta antiga** | **DC-17.11, nova** |
| **Bloqueio da entrada antiga: destino de sessões, desafios e recuperação** | **DC-17.12, nova** |
| **Recuperação do MFA exige autenticação recente explícita** | **DC-17.13, nova** |
| `/o/{org}/login` preservada, encaminhando para a autenticação global | DC-17.9 e DC-17.10, ajustadas |
| **Como a identidade vinculada abre sessão de organização sem o fator antigo** | **§4.4, nova: contrato de trigger e invariantes** |
| **Impedir que cadastro de fator, recuperação ou desafio recriem a entrada antiga, inclusive sob concorrência** | **§4.5, nova: contrato de fechamento** |
| Desafio de vinculação de uso único, ligado ao par exato, consumido atomicamente | DC-17.11, precisada |
| Sessão de recuperação com acesso restrito; momento das revogações | DC-17.13, precisada |
| Critério explícito de retirada do formulário antigo | DC-17.10, precisada |
| Cenários de banco destes contratos | §10.1, nova |
| **Lock da conta no caminho obrigatório do banco, não só no `AuthService`**; papel da aplicação sem escrita direta que o contorne; corrida testada com o papel real | **§4.5 camada 3, reescrita; §10.1 grupo C, refeito** |
| **Sessão de organização recusa, no banco, sessão de identidade restrita à recuperação**, mesmo com fator confirmado | **§4.4 ramo 2 e propriedade 4; DC-17.13; §10.1 grupo G, novo** |
| **`xmin` não prova criação**: a criação de organização não pode aceitar `user_id` arbitrário | **DC-17.5 e §5: `identity_create_organization` cria e vincula, sem receber `user_id`; §10.1 grupo I** |
| **Confirmação de fator irreversível, campos protegidos imutáveis, troca exclusiva da função autorizada** | **§3.3.1, I-1 reescrita e "Imutabilidade do fator"; §10.1 grupo H** |
| **Auxiliares de leitura conferem o contexto da organização**; UUID de outra organização falha fechado | **§4.5 camada 3, peça 1; §5; §10.1 grupo J** |
| Consistência: §9 contra DC-17.9; contagem de funções da §5; "nenhuma rota muda" distinguindo contas vinculadas | §1, DC-17.1, §5, §6, §9 |
| Resíduos: G-7 falha por privilégio; regra da sessão restrita só nas funções que recebem sessão | §10.1 G-7; §5 |
| **Provas de concorrência por identidade, autorização das funções e privilégios efetivos** | **§10.1, grupos K, L e M, novos** |

**Data:** 2026-10-11.

**Objetivo da missão:** permitir criar uma conta pela web e autenticar **sem informar previamente uma organização**, preservando isolamento, autorização, auditoria e proteção contra abuso.

**Base conferida nesta árvore:**
- `db/migrations/002_identity.sql` (identidade, vínculos, tokens, sessões);
- `db/migrations/003_access_control.sql` (papéis, ORG_ADMIN, auditoria de provisionamento);
- `db/migrations/012_security_rls.sql` (RLS por organização, §13);
- `db/migrations/013_portal_externo.sql` (padrão de função `SECURITY DEFINER` com dono próprio);
- `db/migrations/014_mfa_sessoes.sql` (fator TOTP, desafios, endurecimento de `user_sessions`);
- `api/nekkora-platform/.../auth/AuthService.java`, `account/AccountRepository.java`, `web/Routes.java`, `web/SessionFilter.java`, `web/CsrfFilter.java`, `web/Cookies.java`, `limits/Limits.java`;
- `api/openapi/nekkora-api-v1.yaml` (contrato definitivo, DC-14);
- `docs/relatorios/2026-09-25-preparacao-autorizacao-api-nekkora.md` (§3.1 e §8.1: seletor de organização, sem lookup global).

---

## 1. Resumo da proposta

A entrada geral não pode ser obtida ajustando o login atual: ela exige uma **camada de identidade acima de `users`**, porque hoje uma conta pertence a exatamente uma organização e a RLS só devolve linhas sob o contexto de uma organização.

A proposta é:

1. criar `identities` (pessoa), com e-mail único global, senha e MFA **na identidade**;
2. ligar identidade a contas por `identity_users`, **sem nenhuma ligação automática** das contas que já existem;
3. emitir uma **sessão de identidade** (sem organização) após senha + MFA, e trocá-la por uma **sessão de organização** — exatamente a `user_sessions` de hoje — quando há vínculo;
4. resolver tudo que atravessa organizações em funções `SECURITY DEFINER` com dono próprio, no padrão já aprovado da 013, com visibilidade limitada **à identidade** em vez de limitada a uma organização;
5. manter todas as rotas `/api/v1/o/{org}/…` como estão hoje **para as contas não vinculadas**.

O resultado é que, para toda conta **não vinculada** a identidade — que é toda conta existente hoje —, nenhuma rota existente muda de comportamento; nenhuma conta atual é alterada pela migration; e o `nekkora_platform` continua sem SELECT direto entre organizações. Para conta **vinculada** (a criada pela DC-17.5 e, na missão posterior, a vinculada pela DC-17.11), as rotas de credencial local mudam de propósito: login por organização, pedido de recuperação de senha, verificação de e-mail local e cadastro de fator local deixam de servir a ela (DC-17.9, DC-17.12, §4.5). As demais rotas `/o/{org}/…` funcionam igual para os dois tipos, depois que a sessão de organização existe.

---

## 2. O que o modelo atual impede

Sete achados, todos verificados no código desta árvore. Cada um é um bloqueio real, não um inconveniente.

| # | Achado | Onde | Consequência para a missão |
|---|---|---|---|
| A-1 | `users.organization_id` é `NOT NULL` e **imutável** por trigger | `002_identity.sql`, `prevent_identity_reassignment()` | Não existe conta sem organização. Cadastrar antes de haver organização é impossível. |
| A-2 | Unicidade de e-mail é **por organização** (`UNIQUE (organization_id, email_normalized)`) | `002_identity.sql` | O mesmo e-mail já pode existir em organizações distintas. Um login geral por e-mail é ambíguo sobre `users`. |
| A-3 | `organization_memberships` tem `UNIQUE (user_id)` | `002_identity.sql` | Uma conta tem no máximo um vínculo interno. Uma pessoa em duas organizações exige duas contas. |
| A-4 | `user_sessions` tem FK composta `(organization_id, user_id)` | `002_identity.sql` | Toda sessão nasce dentro de uma organização. Não há sessão "autenticada e sem organização". |
| A-5 | RLS filtra 39 tabelas por `nekkora.organization_id`; sem contexto, zero linhas | `012_security_rls.sql`, §13 | Buscar conta por e-mail sem organização devolve vazio para o papel `nekkora_platform`. |
| A-6 | Um fator TOTP ativo por `user_id`, com AAD `organization_id \|\| user_id \|\| key_id` | `014_mfa_sessoes.sql`; `SecretCipher` | Uma pessoa em três organizações cadastraria três TOTP. Incompatível com "MFA antes de escolher a organização". |
| A-7 | Trigger de INSERT em `user_sessions` exige fator confirmado **daquele `user_id`** | `014_mfa_sessoes.sql`, `enforce_user_session_mfa_on_insert()` | Conta recém-criada por cadastro não teria fator próprio e a sessão seria recusada pelo banco. **Agrava-se com a DC-17.12**, que desabilita o fator da conta vinculada: as duas regras juntas trancariam a conta vinculada fora. Contrato da solução na **§4.4**. |

Há ainda uma decisão explícita a reverter: a §3.1 do relatório de preparação registra "**não faz lookup global por e-mail**" e a §8.1 registra que uma função `SECURITY DEFINER` entre tenants "exigiria mudança de segurança e migration própria; fica fora desta proposta, **salvo se o formato de entrada não puder carregar o contexto**". O login geral é exatamente essa exceção: a entrada é só e-mail e senha. A reversão é pedida em **DC-17.1** adiante e é deliberada, não um desvio.

---

## 3. Decisões propostas

Cada decisão traz a recomendação, a alternativa considerada e o motivo da escolha.

**Nota de 2026-10-11, pedida pela responsável:** a marca **[precisa de aprovação]** sinalizava apenas onde havia **bifurcação**. Ela não classificava o resto como detalhe. **Todas** as decisões desta seção são de arquitetura e todas precisam de ratificação, inclusive as cinco sem alternativa apresentada (DC-17.4 a DC-17.8). Onde não apresentei alternativa foi porque não encontrei segunda opção defensável, não porque a escolha seja menor.

### DC-17.1 — Resolução por e-mail sem organização **[aprovada em 2026-10-11, com condições]**

**Recomendado:** permitir a resolução global de login **somente** sobre as tabelas novas de identidade, por função `SECURITY DEFINER` de dono próprio (`nekkora_identity_owner`). As tabelas de identidade não têm `organization_id` e portanto não entram na RLS tenant da 012. O `nekkora_platform` **não** ganha SELECT entre organizações sobre `users`.

**Condição da responsável (2026-10-11), incorporada:** a busca global existe **só para autenticar**. Nenhuma rota, autenticada ou não, revela se uma identidade existe, nem quais organizações ela tem, a quem não seja a própria pessoa. Concretamente:

| Rota | Garantia |
|---|---|
| `POST /api/v1/signup` | `202` idêntico para e-mail livre, pendente e já existente, com piso de tempo de resposta (§6.1) |
| `POST /api/v1/auth/login` | `401` genérico para identidade inexistente, senha errada e identidade não confirmada, com verificação contra hash fictício |
| `POST /api/v1/signup/confirmation/resend` | `202` idêntico, sempre |
| `GET /api/v1/me/organizations` | exige sessão de identidade; devolve **só** as organizações da própria identidade |
| `POST /api/v1/sessions/organization` | `sem_acesso` idêntico em todas as causas, com piso de tempo (DC-17.6) |
| Rotas existentes `/o/{org}/…` | nenhuma passa a expor identidade. Para conta vinculada, as de credencial local recusam com a **mesma** resposta genérica que já dão a credencial inválida, sem revelar o vínculo (DC-17.9) |

`identity_resolve_login` recebe e-mail normalizado e devolve dado de autenticação. Ela **não** tem variante que liste identidades, procure por prefixo ou conte resultados.

**Alternativa:** seletor legível (slug da organização) no lugar do e-mail. Rejeitada: não atende "autenticar sem informar previamente uma organização", que é o objetivo da missão.

### DC-17.2 — Camada de identidade, sem união automática **[aprovada em 2026-10-11, com condições]**

**Recomendado:** `identities` (e-mail único global) + `identity_users` (1:N para `users`). Contas que já existem **não** recebem linha em `identities` nem em `identity_users`. Um cadastro com e-mail que coincide com contas atuais cria uma identidade **nova e sem ligações**. A ligação é sempre um ato explícito e auditado, e fica para missão posterior (aceite de convite já autenticado, ou reivindicação com prova da senha daquela organização).

**Consequência aceita:** durante a transição, a mesma pessoa pode ter a identidade nova e contas antigas que ela não alcança pela entrada geral. As contas antigas continuam alcançáveis pelo login por organização, que não muda. A matriz de convivência está na §9.

**Alternativa:** ligar automaticamente por e-mail igual. Rejeitada pelo enunciado da missão e porque um e-mail igual não prova ser a mesma pessoa, nem que quem cadastra controla a conta antiga.

### DC-17.3 — MFA na identidade **[aprovada em 2026-10-11, com condições]**

**Recomendado:** fator TOTP, códigos de recuperação e desafios **na identidade** (`identity_mfa_factors`, `identity_mfa_recovery_codes`, `identity_auth_challenges`), com AAD do segredo `identity_id || key_id` (sem organização). O `user_mfa_factors` por conta continua existindo, intacto, para as contas atuais.

**Consequência:** `enforce_user_session_mfa_on_insert()` muda (achado A-7). A formulação "fator da conta **ou** fator da identidade" que estava aqui era solta demais e foi substituída pelo contrato da **§4.4**: dois ramos mutuamente exclusivos, vínculo conferido no banco, sessão de identidade não restrita.

**Alternativa:** espelhar o segredo da identidade em cada organização. Rejeitada: duplica segredo, multiplica o raio de um comprometimento e contraria o AAD por organização da 014.

**Aprovada para o fluxo novo em 2026-10-11, condicionada a definir a convivência com o MFA antigo.** Essa definição é a §3.3.1.

#### 3.3.1 Convivência do MFA antigo e do novo

Dois lugares de fator, independentes, sem trigger nem caminho de código entre eles:

| Repositório | Escopo | Quem autentica com ele | **Quem pode redefinir** |
|---|---|---|---|
| `user_mfa_factors` (014) | conta, dentro de **uma** organização | login por organização, em conta **não vinculada** a identidade | a própria pessoa, por código de recuperação; e ORG_ADMIN daquela organização, com `identity.mfa.reset` e step-up recente |
| `identity_mfa_factors` (017) | identidade, **atravessa** organizações | entrada geral | **somente a própria pessoa**, por código de recuperação de identidade. **Nenhum papel de organização.** |

**Três invariantes.** Elas são a resposta à pergunta da responsável sobre quem redefine o MFA global.

**I-1 — nenhum papel de organização alcança o fator da identidade.** Garantido por privilégio, não por código. **Revisto em 2026-10-11, apontado pela responsável:** a versão anterior concedia `UPDATE (confirmed_at)` ao `nekkora_platform`, e os dois índices únicos só limitam **quantos** fatores há em cada estado. Eles não tornam a confirmação irreversível nem comprovam que a transição foi autorizada: a aplicação poderia anular o `confirmed_at` do fator antigo, liberar o lugar no índice e confirmar outro. A concessão passa a ser só a de leitura e do passo consumido:

```
GRANT SELECT                       ON identity_mfa_factors        TO nekkora_platform;  -- verificar TOTP
GRANT UPDATE (last_step)           ON identity_mfa_factors        TO nekkora_platform;  -- consumir passo (monotono, ver abaixo)
GRANT SELECT                       ON identity_mfa_recovery_codes TO nekkora_platform;
GRANT UPDATE (used_at, used_in_identity_session_id)
                                   ON identity_mfa_recovery_codes TO nekkora_platform;  -- consumir codigo
-- INSERT, DELETE, TRUNCATE e UPDATE de qualquer outra coluna NAO sao concedidos em nenhuma das duas
```

Toda escrita de estado do fator passa por três funções `SECURITY DEFINER` do dono `nekkora_identity_owner` (§5), e **só** por elas:

| Função | Transição | Autorização conferida no banco |
|---|---|---|
| `identity_mfa_enroll_pending` | cria fator **pendente**; descarta (desabilita) o pendente anterior, se houver | desafio de identidade ativo (cadastro) ou sessão de identidade autorizada pela DC-17.13 (troca) |
| `identity_mfa_confirm_first` | pendente → confirmado, e grava os códigos | desafio de identidade ativo **e** a identidade **nunca** teve fator confirmado |
| `identity_mfa_replace` | T1 inteiro: desabilita o antigo, confirma o novo, invalida os códigos antigos, grava os novos, preenche `recovery_cleared_at` | step-up ou marca de recuperação dentro da janela, lidos da linha da sessão (DC-17.13) |

**"Nunca teve fator confirmado" é condição estável**, e não um retrato que se possa refazer: como nenhuma linha perde `confirmed_at` e nenhuma linha é apagada (abaixo), uma identidade que confirmou um fator uma vez nunca mais satisfaz `identity_mfa_confirm_first`. Toda confirmação seguinte é troca, e troca só passa por `identity_mfa_replace`. As funções **não** verificam o código TOTP — isso continua na aplicação, como na 014; elas verificam a **autorização** e a **transição**, que é o que a aplicação não pode afirmar.

**Imutabilidade do fator.** Uma trigger `BEFORE UPDATE OR DELETE` em `identity_mfa_factors` vale para **qualquer papel, inclusive o dono das funções**, para que um erro numa função também falhe fechado:

| Campo | Regra |
|---|---|
| `factor_id`, `identity_id`, segredo cifrado, `key_id`, nonce, algoritmo, dígitos, período, `created_at` | **imutáveis** |
| `confirmed_at` | só nulo → valor, **uma vez**; nunca valor → nulo, nunca valor → outro valor. Nulo → valor exige `current_user = 'nekkora_identity_owner'` |
| `disabled_at` | só nulo → valor, **uma vez**; nunca volta a nulo. Exige `current_user = 'nekkora_identity_owner'` |
| `last_step` | só aumenta |
| `DELETE` | recusado sempre |

E em `BEFORE INSERT`: a linha nasce com `confirmed_at` e `disabled_at` nulos, e só com `current_user = 'nekkora_identity_owner'`. `current_user` dentro de `SECURITY DEFINER` é o dono, e o `nekkora_platform` não é membro desse papel, então não pode assumi-lo (invariante da §4.3).

Em `identity_mfa_recovery_codes`, a mesma forma: `code_hash`, `factor_id`, `identity_id` e `created_at` imutáveis; `used_at` e `used_in_identity_session_id` só nulo → valor, uma vez, e juntos; `invalidated_at` só nulo → valor e só pelo dono; nenhum `INSERT` fora das funções; `DELETE` recusado.

Desabilitar um fator confirmado, portanto, só acontece dentro de `identity_mfa_replace(…)` (chamada `identity_mfa_reset` em revisões anteriores), que **exige prova de autenticação recente** nos termos da **DC-17.13**. Sessão aberta, sozinha, não basta. O caminho administrativo de organização não tem privilégio para escrever essas colunas: a tentativa falha no banco, não numa checagem de aplicação. Privilégio por coluna é o idioma que a 012 e a 013 já usam.

**I-2 — redefinir um fator nunca afeta o outro.** São linhas em tabelas distintas, sem trigger entre elas. O reset administrativo da 014 continua agindo **só** em `user_mfa_factors`, com o escopo de organização que ele já tem.

**I-3 — conta vinculada a identidade entra só pela entrada geral** (DC-17.9). Sem isso, I-1 e I-2 não bastariam: um ORG_ADMIN poderia redefinir o fator *da conta* de uma pessoa vinculada e, com isso, influenciar uma porta de entrada dela. Fechando a porta por organização nas contas vinculadas, resta **uma** porta por pessoa, cujo fator nenhum administrador de organização alcança.

**Recuperação do MFA de identidade.** Códigos de recuperação próprios, emitidos no cadastro do fator, de uso único, com o mesmo comportamento que `verify()` já tem hoje: usar um código revoga todas as sessões de identidade, revoga as sessões de organização derivadas, invalida desafios pendentes, grava evento e dispara o aviso `NOTICE_RECOVERY_CODE_USED`.

**Lacuna declarada, de propósito.** Perder a senha **e** os códigos de recuperação não tem saída nesta missão. Não existe reset administrativo do MFA de identidade, porque dar esse poder a um administrador de organização é exatamente o que a responsável proibiu. Se a saída for necessária, ela é **missão própria**, no formato da 016: autorização provada no banco, fora de qualquer organização, com aviso ao titular e janela de contestação. Fica registrada aqui como pendência conhecida, não como esquecimento.

**Transição das contas atuais.** Enquanto uma conta não é vinculada, nada muda para ela. No ato de vincular (missão posterior), decidir se o fator da conta é retirado em favor do fator da identidade. Esta missão não vincula ninguém e portanto não precisa dessa decisão.

### DC-17.4 — Duas sessões, cookies distintos

**Recomendado:**
- `__Host-nekkora_id` — **sessão de identidade**: pessoa autenticada com MFA, sem organização. Vale só nas rotas sem organização. `SameSite=Lax`.
- `__Host-nekkora_session` — **sessão de organização**: a `user_sessions` de hoje, com forma e política inalteradas, criada pela troca da §6.5.

`user_sessions` ganha `identity_session_id` (nulo nas sessões do login por organização). A revogação é **imediata e sem escrita entre organizações**: o `SessionFilter` passa a exigir, quando a coluna está preenchida, que a sessão de identidade correspondente esteja ativa. `identity_sessions` não tem `organization_id`, logo o `nekkora_platform` a lê sem contexto de organização.

### DC-17.5 — Criação de organização pelo próprio cadastro

**Recomendado:** `POST /api/v1/organizations`, com sessão de identidade e MFA recente (step-up da identidade dentro de 5 min, mesma janela do `session.step-up` atual).

**Condição da responsável (2026-10-11), explicitada:** a rota exige **e-mail verificado**. Três guardas, nesta ordem:

1. `identities.status = 2` (ACTIVE) **e** `identities.email_verified_at IS NOT NULL`, conferido na transação;
2. a sessão de identidade só nasce após MFA, e o MFA só é cadastrável por identidade já confirmada — então um e-mail não verificado não chega a ter sessão;
3. a `users` criada herda `email_verified_at` da identidade, nunca um valor novo, para não haver conta interna com e-mail não verificado.

A guarda 1 é redundante com a 2 **de propósito**: a condição é verificada no ponto de uso, não presumida do caminho percorrido.

**Toda a criação acontece dentro de `identity_create_organization(p_session_token, p_name, p_legal_name)`**, função `SECURITY DEFINER` de `nekkora_identity_owner`. Numa transação, ela: cria `organizations` ACTIVE com UUID gerado por ela; cria `users` (interna, ACTIVE, `email_verified_at` herdado da identidade, **sem** linha em `user_credentials`); cria `organization_memberships` ACTIVE; grava `identity_users` com o `user_id` que **ela mesma** acabou de obter do `INSERT … RETURNING`; atribui ORG_ADMIN; e registra `identity_provisioning_events` com `channel='cadastro_web'`.

**Correção de 2026-10-11, apontada pela responsável.** A revisão anterior separava a criação (aplicação) da ligação (`identity_attach_created_account`, recebendo `user_id`) e usava `xmin` da linha como prova de que a conta fora criada na transação. **Isso não prova criação**: um `UPDATE` numa conta existente também grava uma versão nova com o `xmin` da transação corrente, e a aplicação, que tem `UPDATE` em `users`, poderia "rejuvenescer" uma conta antiga e anexá-la sem a prova dupla da DC-17.11. A função passa a **não receber `user_id` nem `organization_id`** de fora: só pode ligar a conta que ela própria inseriu numa organização que ela própria inseriu. Não há prova a fabricar, porque não há o que apontar. `identity_attach_created_account` deixa de existir.

O dono ganha, para isso, `INSERT` naquelas tabelas com política de RLS `WITH CHECK (organization_id = current_setting('nekkora.fn_organization_id'))`, a GUC interna definida pela própria função para a organização que ela acabou de gerar, no padrão da 012 e da 013. Ele **não** ganha `UPDATE` em `users` fora da política estreita da vinculação (§4.5, camada 3). A conta criada nasce vinculada, então nasce sob a DC-17.9: sem credencial local e sem fator local.

A autoria fica registrada na própria identidade que criou a organização. A 003 já prevê `assigned_by_membership_id` nulo somente no bootstrap, e `identity_provisioning_events` já é append-only — o fluxo novo usa o mecanismo que existe, sem afrouxá-lo.

**Limite proposto:** no máximo 3 organizações por identidade por dia e 10 no total, por limitador (R11, §7). O teto de 10 também é conferido **dentro** de `identity_create_organization`, contando as linhas de `identity_users` da identidade com `link_channel = 'cadastro_web'`, sob o lock da identidade (`identity_provisioning_events` não tem coluna de identidade; §13, item 6). Sem limite, a rota é uma fábrica de tenants.

### DC-17.6 — Resposta única do destino `/o/{org}/login`

**Recomendado:** a troca por sessão de organização devolve **duas respostas possíveis e indistinguíveis em causa**:
- `200 {"status":"sessao_criada","csrfToken":"…"}` + `Set-Cookie: __Host-nekkora_session`;
- `200 {"status":"sem_acesso"}` para **todos** estes casos: organização inexistente, suspensa ou encerrada; identidade sem vínculo; vínculo não ACTIVE; conta não elegível.

Com piso de tempo de resposta (mesmo mecanismo de `requestRecovery`, `recovery-response-floor`) e limitador por sessão de identidade (R10, §7), para que a rota não sirva de sonda de existência de organização. O front-end encaminha para `/organizacoes` em `sem_acesso` — que é exatamente o que a missão pede.

**Alternativa:** `404` para organização inexistente e `403` para falta de vínculo. Rejeitada: distingue causas e permite enumerar organizações.

### DC-17.7 — Cadastro desligado por padrão

**Recomendado:** `nekkora.cadastro.enabled` (padrão `false`), no mesmo padrão de `nekkora.emergency-notices.enabled`. Com a chave desligada, as rotas de cadastro respondem `404`. A ativação depende da Missão 2 validada, como a própria missão determina. A Missão 1 avança inteira com transporte de homologação.

### DC-17.8 — `{org}` continua UUID

**Recomendado:** manter a decisão 8.1 (seletor é o `organization_id` na rota). `/o/{org}/login` é rota de front-end; o `{org}` que chega ao backend permanece UUID. Slug legível é missão própria, com resolução inequívoca e sua própria proteção contra enumeração.

**Ratificada pela responsável em 2026-10-11:** o UUID permanece como identificador nesta etapa e o endereço legível fica para depois.

### DC-17.9 — Conta vinculada a identidade entra só pela entrada geral **[decisão nova, 2026-10-11]**

Decisão acrescentada para atender a exigência da responsável de que um administrador de organização não ganhe poder sobre o acesso da pessoa às outras organizações.

**Recomendado:** quando uma `users` tem linha em `identity_users`, o **endpoint de credencial por organização** (`POST /api/v1/o/{org}/auth/login`) não autentica aquela conta, e a entrada daquela pessoa passa a ser exclusivamente a entrada geral mais a troca da §6.5.

**Ajuste pedido pela responsável em 2026-10-11:** a **rota de front-end `/o/{org}/login` é preservada**. Ela deixa de ser um formulário de senha por organização e passa a **encaminhar para a autenticação global, mantendo a organização como destino** da troca da §6.5. O que DC-17.9 fecha é o endpoint de credencial do backend para contas vinculadas, não o endereço que a pessoa digita. A convivência entre a rota preservada e as contas antigas ainda não vinculadas é a **DC-17.10**.

**Por que é necessário.** Sem ela, uma pessoa vinculada teria **duas** portas com **dois** fatores redefiníveis por autoridades diferentes. O ORG_ADMIN da organização A poderia redefinir o fator da conta dela em A e, com isso, mexer numa porta de entrada da pessoa. Com a decisão, sobra uma porta por pessoa vinculada, cujo fator só ela redefine (§3.3.1, I-1).

**Efeito na compatibilidade:** nenhum, hoje. Esta missão **não vincula nenhuma conta existente** (DC-17.2), logo nenhuma conta atual passa a recusar o login por organização. A decisão vale a partir do primeiro vínculo, que é a missão posterior de ligação — e é lá que ela precisa estar decidida de antemão, não depois.

**Implementação:** uma condição a mais na elegibilidade lida por `AccountRepository.byEmail`, sem rota nova e sem mensagem nova. A resposta é a mesma de qualquer credencial inválida, para não revelar que a conta foi vinculada.

### DC-17.10 — Convivência das duas portas em `/o/{org}/login` **[decisão nova, 2026-10-11]**

Decorrência do ajuste da DC-17.9. Durante a transição existem dois tipos de conta e **duas portas**, e a rota precisa servir as duas sem revelar qual é a de cada pessoa.

**O problema, declarado:** descobrir qual porta usar exigiria consultar o e-mail antes da senha, e qualquer resposta que dependa da conta revela se ela existe e se está vinculada. Isso contraria a condição da DC-17.1. Um sinal do backend dizendo "continue na entrada geral" é, por si, revelação de existência.

**Recomendado:** a tela de `/o/{org}/login` oferece as duas portas **sem sondar nada**, com a entrada geral como caminho principal e o formulário por organização disponível como alternativa rotulada para contas ainda não migradas. A pessoa escolhe; o backend nunca é consultado antes de uma tentativa completa de credencial. Nenhuma resposta depende da existência ou do estado de vínculo da conta.

**Critério de retirada, precisado em 2026-10-11 a pedido da responsável.** "Retirar quando a vinculação terminar" tem de significar uma destas duas coisas, e nenhuma outra:

| Forma aceita | Como se verifica |
|---|---|
| **Retirada geral, após a migração das contas** | `SELECT count(*) FROM users u WHERE u.account_type = 1 AND NOT EXISTS (SELECT 1 FROM identity_users iu WHERE iu.user_id = u.user_id)` devolve **zero**. Nesse ponto o formulário sai para todos, numa publicação, sem condição por pessoa |
| **Decisão tomada depois de autenticar** | a pessoa já autenticada pode ver caminhos diferentes, porque aí a identidade dela é conhecida e nada é revelado a quem não se autenticou |

**Expressamente proibido:** mostrar ou esconder o formulário conforme o e-mail digitado. Isso reintroduziria exatamente a sondagem que a DC-17.1 proíbe, com a diferença de ficar escondida numa decisão de interface em vez de numa resposta de API. A tela antes da autenticação é **a mesma para todo mundo**.

**Alternativas rejeitadas:**

| Alternativa | Por que não |
|---|---|
| Backend responde qual porta usar | revela existência e estado de vínculo da conta. Contraria DC-17.1 |
| Tentar a entrada geral e, no `401`, cair para a porta por organização | duas verificações de Argon2 por tentativa, diferença de tempo entre os casos e um `401` que não distingue senha errada de porta errada |
| Fechar a porta por organização de imediato | tira o acesso de toda conta atual antes de existir caminho de vinculação |

---

### DC-17.11 — Vinculação: prova de controle dos dois lados **[decisão nova, 2026-10-11]**

Condição da responsável: coincidência de e-mail **não basta**. A definição vem agora porque a forma da migration depende dela.

**Recomendado:** vincular exige, **na mesma operação e dentro de uma janela de 5 minutos**, prova dos dois lados:

| Lado | Prova exigida |
|---|---|
| Identidade | sessão de identidade válida **e** step-up do fator da identidade dentro de 5 min |
| Conta antiga | **senha** da conta **e** segundo fator dela: TOTP da conta ou código de recuperação da conta |

**Três guardas somadas, não alternativas:**

1. o e-mail normalizado da conta tem de ser **igual** ao da identidade. Necessário, para que ninguém anexe conta alheia; **não suficiente**, e é exatamente o ponto da responsável;
2. as duas provas acima, ambas, na mesma operação;
3. a conta tem de estar elegível pela definição vigente, e a organização ativa.

**Quem pode vincular:** **somente a própria pessoa.** Nenhum papel de organização vincula em nome de outra, pelo mesmo motivo que proíbe o reset administrativo do MFA de identidade: vincular decide qual fator passa a governar o acesso da pessoa, e isso não pode ser ato de uma autoridade de organização.

**Registro:** `identity_users.link_channel`, evento `IDENTITY_LINKED` em `identity_events`, e aviso ao endereço, que nesse caso é o mesmo dos dois lados. Limitador próprio, por identidade e por conta alvo.

**Correção de 2026-10-11, apontada pela responsável:** eu disse que sem `identity_link_challenges` a operação "não é atômica". **Isso está errado.** A atomicidade vem da transação, das restrições e do controle de concorrência, não da existência da tabela. Se as duas provas chegassem numa única requisição, uma transação só bastaria.

**A tabela se justifica por outros três motivos, esses sim reais:**

1. **carregar estado entre dois passos.** O fluxo é em duas etapas, porque pedir sessão de identidade com step-up, senha da conta antiga e segundo fator dela numa única tela é pior e mais frágil. O desafio é o que liga as duas etapas;
2. **tornar a prova de uso único**, pelo padrão já declarado na 002: um só `UPDATE … WHERE consumed_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp() RETURNING …`, com a concorrência resolvida pelo MVCC e não por ler-depois-escrever;
3. **amarrar a prova ao par exato**, que é a condição que a responsável pôs.

**Forma da tabela, conforme esse parecer:**

| Coluna | Papel |
|---|---|
| `identity_id` e `user_id` | o **par exato**. O consumo confere os dois; um desafio emitido para um par não serve para outro |
| `token_hash` com `UNIQUE` | uso único, sem valor bruto persistido |
| `account_credential_hash` | credencial da conta validada na etapa 1, reconferida sob o lock na etapa 2, como `auth_challenges` já faz |
| `expires_at` | janela de 5 minutos, com `CHECK (expires_at > created_at)` |
| `consumed_at`, `revoked_at`, `attempts` | consumo único, revogação e contador de tentativas, no formato de `auth_challenges` |

**Efeito na migration `017`:** entra `identity_link_challenges` na §4.1. O motivo é o fluxo de dois passos e o uso único amarrado ao par, não a atomicidade.

---

### DC-17.12 — Bloqueio da entrada antiga: o que acontece no ato de vincular **[decisão nova, 2026-10-11]**

Condição da responsável: bloquear só a tela de login **não basta**. Definir o destino de sessões, desafios e caminhos de recuperação anteriores.

**Recomendado:** o ato de vincular executa, **na mesma transação** em que grava `identity_users`:

| Estado anterior | Destino |
|---|---|
| `user_sessions` ativas da conta | **todas revogadas**, inclusive a que estiver em uso |
| `auth_challenges` pendentes da conta | **todos invalidados** |
| `password_reset_tokens` pendentes da conta | **todos revogados** |
| `email_verification_tokens` pendentes da conta | **todos revogados** |
| `user_mfa_factors` confirmado da conta | **desabilitado** (`disabled_at`) |
| `user_mfa_recovery_codes` da conta | **todos invalidados** |
| `user_credentials` da conta | linha **mantida**, inerte. Preserva `password_changed_at` e as FKs de auditoria; não autentica mais nada, porque a porta está fechada e o fator desabilitado |

**Por que desabilitar o fator da conta, e não apenas fechar a porta.** Fechar a porta é regra de aplicação; desabilitar o fator remove o objeto que um ORG_ADMIN poderia redefinir. Com o fator desabilitado, o reset administrativo da 014 não tem alvo naquela conta.

**Mas desabilitar não basta, e a responsável está certa nisso.** Fator desabilitado ainda poderia ser reabilitado, e cadastro de fator novo, emissão de desafio, recuperação ou troca de senha poderiam recriar a entrada. O fechamento completo é o **§4.5**, com as três camadas e os quatro intercalamentos. E a sessão de organização da conta vinculada passa a nascer pelo **§4.4**, que é o contrato que resolve o conflito com a trigger da 014. Esta decisão **depende** dos dois.

**Recuperação de senha depois do vínculo:** `POST /api/v1/o/{org}/auth/password-reset/request` deixa de emitir token para conta vinculada. Aqui não há custo de enumeração, porque essa rota já responde `202` idêntico em todos os casos. A recuperação daquela pessoa passa a ser a da identidade.

**Reversão:** desvincular não está nesta missão e não deve ser improvisada. Reabilitar um fator desabilitado e reabrir a porta por organização reintroduz a porta dupla. Se for necessário, é decisão própria.

---

### DC-17.13 — Recuperação e substituição do fator de identidade: autenticação recente **[decisão nova, 2026-10-11]**

Condição da responsável: "sessão da própria pessoa" precisa de requisitos explícitos; **uma sessão aberta, sozinha, não deve permitir substituir o fator**.

**Recomendado:** substituir um fator de identidade confirmado exige um destes dois caminhos, e **nenhum outro**:

| Caminho | Situação | Exigência |
|---|---|---|
| **A — troca de aparelho** | a pessoa **ainda tem** o fator | sessão de identidade válida **e** `mfa_step_up_at` dentro de 5 min, com TOTP do fator atual |
| **B — perda do fator** | a pessoa **não tem** o fator | entrada geral com senha **e** código de recuperação de identidade; a sessão nasce marcada com `recovery_used_at`, e a substituição é permitida **só** dentro de 15 min a partir dessa marca |

**O que fica proibido, explicitamente:** sessão de identidade válida sem step-up recente e sem marca de recuperação **não** substitui fator.

**Correção de 2026-10-11, apontada pela responsável.** Eu escrevi que "uma sessão de até 12 horas, roubada, não serve para tomar o segundo fator". **A afirmação é forte demais.** A regra elimina o caso em que a sessão **sozinha** basta. Ela **não** elimina o risco de roubo de sessão: uma sessão furtada dentro da janela de 5 minutos de step-up, ou furtada de quem consiga produzir um step-up em seguida, continua sendo risco. O que o desenho oferece é redução, com a janela curta, a revogação das demais sessões na troca e o aviso ao titular. Mesmo limite declarado que a §3.4 do relatório de preparação declara para o tempo de resposta: controles complementares, não garantia.

**Precisão pedida pela responsável: a sessão nascida de recuperação é RESTRITA.**

Senha mais código de recuperação permite **cadastrar e confirmar o fator novo, e nada mais**. O acesso normal às organizações só volta depois disso.

| Operação | Sessão com `recovery_used_at`, antes de confirmar o fator novo |
|---|---|
| `POST /api/v1/auth/mfa/enroll/start` | **permitida** |
| `POST /api/v1/auth/mfa/enroll/confirm` | **permitida**. É o que levanta a restrição |
| `POST /api/v1/auth/logout` | permitida |
| `GET /api/v1/me/organizations` | **recusada**, `403` genérico |
| `POST /api/v1/sessions/organization` | **recusada**, `403` genérico |
| `POST /api/v1/organizations` | **recusada**, `403` genérico |

A restrição é lida da linha da sessão, no banco, e não é estado guardado na aplicação. Precisão de 2026-10-11: "fator novo ainda não confirmado" não pode ser inferido do estado dos fatores, porque entre T0 e T1 o fator **antigo** continua confirmado e habilitado. Por isso a linha carrega as duas marcas:

```
restrita  :=  recovery_used_at IS NOT NULL AND recovery_cleared_at IS NULL
```

| Coluna de `identity_sessions` | Quem escreve | Proteção |
|---|---|---|
| `recovery_used_at` | só na criação da sessão de recuperação | imutável depois do `INSERT` (trigger, no padrão da não reativação da 014); `nekkora_platform` sem `UPDATE` na coluna |
| `recovery_cleared_at` | **só** `identity_mfa_replace(…)`, em T1 | `nekkora_platform` sem `UPDATE` na coluna; `CHECK (recovery_cleared_at IS NULL OR recovery_cleared_at >= recovery_used_at)` |

**A recusa existe no banco, em três pontos**, e não depende das rotas: a trigger de `user_sessions` (§4.4, ramo 2) recusa a criação de sessão de organização com sessão restrita; `identity_organizations` e `identity_org_session_target` devolvem zero linhas para ela; e `identity_create_organization` recusa (§5). Um step-up feito numa sessão restrita **não** levanta a restrição: só T1 levanta.

**Nascimento da sessão restrita, também amarrado no banco.** Consumir um código de recuperação de identidade exige preencher `identity_mfa_recovery_codes.used_in_identity_session_id`, e uma trigger de restrição adiada (`DEFERRABLE INITIALLY DEFERRED`) recusa o commit se a sessão apontada não tiver `recovery_used_at` preenchido. Sem isso, a aplicação poderia consumir o código e abrir uma sessão comum.

**Momento de cada revogação, que era a segunda pergunta:**

| Instante | O que acontece | O que **não** acontece |
|---|---|---|
| **T0 — código de recuperação consumido no login** | revoga todas as **outras** sessões de identidade; as sessões de organização derivadas delas caem na requisição seguinte (DC-17.4); invalida desafios de identidade pendentes; grava evento; dispara o aviso | os códigos de recuperação **restantes continuam válidos**, e o fator antigo continua cadastrado |
| **T1 — fator novo confirmado** | desabilita o fator antigo; invalida **todos** os códigos antigos restantes; emite o conjunto novo; levanta a restrição desta sessão | — |

**Por que os códigos restantes sobrevivem até T1.** Se fossem invalidados em T0 e a pessoa abandonasse o fluxo entre T0 e T1, ela ficaria sem fator e sem código, ou seja, trancada fora, e a lacuna declarada na §3.3.1 passaria a ser alcançável por desistência. Mantendo-os até T1, a tentativa é repetível e a porta antiga fecha exatamente quando a nova abre. O preço é que, entre T0 e T1, o conjunto antigo ainda vale: é uma troca deliberada, e está registrada aqui como tal.

**Em ambos os caminhos:** o fator novo nasce pendente e só o `confirm` o ativa; o fator antigo é desabilitado em T1 e não antes; o evento é gravado e o aviso é disparado.

**Efeito no modelo:** `identity_sessions` ganha `recovery_used_at` e `recovery_cleared_at`, e a função de substituição — antes `identity_mfa_reset`, agora **`identity_mfa_replace`** (§5) — deixa de aceitar só um token de sessão: ela lê, na própria linha da sessão, se há step-up dentro da janela **ou** marca de recuperação dentro da janela. A prova fica no banco, não num parâmetro que a aplicação afirma.

**Inconsistência encontrada ao escrever este ajuste, e corrigida.** A §4.1 dava a `identity_mfa_factors` um `UNIQUE (identity_id) WHERE disabled_at IS NULL`, copiado da 014. Com ele, o fator novo **não poderia nascer pendente** enquanto o antigo segue habilitado até T1 — os caminhos A e B desta decisão seriam impossíveis. Os índices passam a ser dois: no máximo um fator **confirmado** habilitado e no máximo um **pendente** habilitado por identidade.

**Os índices não bastam, e a revisão anterior errou ao apoiar a exclusividade da troca neles.** Eles contam estados; não impedem que uma confirmação seja desfeita e refeita. A exclusividade passa a vir de três coisas somadas, descritas em §3.3.1: o `nekkora_platform` não tem privilégio de escrita em `confirmed_at`, `disabled_at` nem `INSERT`; a trigger de imutabilidade torna as duas transições irreversíveis para qualquer papel; e a confirmação do primeiro fator exige que a identidade nunca tenha tido fator confirmado. A troca, em T1, só passa por `identity_mfa_replace`, que numa transação desabilita o antigo, confirma o novo, invalida os códigos antigos, grava os novos e preenche `recovery_cleared_at`. O cadastro do **primeiro** fator passa por `identity_mfa_confirm_first`, não mais por escrita direta.

---

## 4. Modelo de dados proposto (migration `017`)

**Dependência:** `001`–`015`. **Independente da `016`**, que não substitui nenhuma das funções alteradas aqui (conferido). Atômica, uma transação, no padrão das anteriores.

### 4.1 Tabelas novas (nenhuma com `organization_id`)

| Tabela | Conteúdo | Observações |
|---|---|---|
| `identities` | `identity_id`, `email` (imutável), `email_normalized` gerada, `display_name`, `email_verified_at`, `status` (1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=CLOSED), timestamps | `UNIQUE (email_normalized)` **global**. Mesmos CHECKs de `users.email`. |
| `identity_credentials` | `identity_id` PK/FK, `password_hash`, `password_changed_at` | Mesma forma de `user_credentials`; hash completo, nunca senha. |
| `identity_users` | `(identity_id, user_id)` PK, `linked_at`, `link_channel` | `UNIQUE (user_id)`: uma conta pertence a no máximo uma identidade. **`nekkora_platform` não tem `SELECT`, `INSERT`, `UPDATE`, `DELETE` nem `TRUNCATE`**: escrita só por `identity_link_account` e `identity_create_organization`, leitura só pelas funções da §5 (§4.5, camada 3). |
| `identity_mfa_factors` | espelho de `user_mfa_factors` por identidade | **Dois** índices únicos parciais: `(identity_id) WHERE disabled_at IS NULL AND confirmed_at IS NOT NULL` e `(identity_id) WHERE disabled_at IS NULL AND confirmed_at IS NULL` (DC-17.13, correção). AAD do segredo sem organização (DC-17.3). |
| `identity_mfa_recovery_codes` | espelho de `user_mfa_recovery_codes` | Hash do código; uso único; `used_in_identity_session_id`, obrigatório quando `used_at` é preenchido, com conferência adiada de que a sessão nasceu restrita (DC-17.13). Emitidos só pelas funções de fator; imutabilidade em §3.3.1. |
| `identity_auth_challenges` | espelho de `auth_challenges` por identidade | Hash do token, credencial validada, hash candidato de re-hash, CSRF do desafio, contador de tentativas. |
| `identity_link_challenges` | desafio de vinculação (DC-17.11) | Liga as duas etapas e torna a prova de uso único, amarrada ao par exato: `identity_id`, `user_id` alvo, hash do token, credencial da conta validada, janela de 5 min, contador de tentativas. (A atomicidade vem da transação, não da tabela: correção registrada em DC-17.11.) |
| `identity_sessions` | `identity_session_id`, `identity_id`, `session_token_hash` único, `created_at`, `expires_at`, `last_seen_at`, `revoked_at`, `mfa_verified_at` NOT NULL, `mfa_step_up_at`, **`recovery_used_at`**, **`recovery_cleared_at`** (DC-17.13), `csrf_token` | Sessão de identidade só existe após MFA, por constraint. Triggers de não reativação e de prazo não extensível, no padrão da 014. |
| `identity_email_tokens` | `purpose` (1=CONFIRMACAO_CADASTRO; 2 reservado para recuperação de senha), `identity_id`, `target_email`, `token_hash` único, `created_at`, `expires_at`, `consumed_at`, `revoked_at` | Mesmos CHECKs de `email_verification_tokens`. Nunca o valor bruto. |
| `identity_events` | append-only: `identity_id`, `event_type`, `target_organization_id` opcional (não `organization_id`, para não virar tabela de tenant), `correlation_id`, `created_at` | Triggers `prevent_update`/`prevent_delete`/`prevent_truncate` da 003. Sem e-mail, token ou hash. |

### 4.2 Alterações em tabelas existentes

```
ALTER TABLE user_sessions ADD COLUMN identity_session_id UUID REFERENCES identity_sessions (identity_session_id);
CREATE OR REPLACE FUNCTION enforce_user_session_mfa_on_insert()   -- contrato integral na secao 4.4; toma o lock da conta (4.5, camada 3)
CREATE OR REPLACE FUNCTION prevent_user_session_reactivation()    -- identity_session_id passa a ser imutavel

-- triggers de recusa da secao 4.5, camada 2 (conta vinculada nao recria entrada local);
-- cada uma toma o lock da conta ANTES de conferir o vinculo (4.5, camada 3)
CREATE TRIGGER … BEFORE INSERT OR UPDATE ON auth_challenges
CREATE TRIGGER … BEFORE INSERT OR UPDATE ON user_mfa_factors
CREATE TRIGGER … BEFORE INSERT OR UPDATE ON user_mfa_recovery_codes
CREATE TRIGGER … BEFORE INSERT OR UPDATE ON password_reset_tokens
CREATE TRIGGER … BEFORE INSERT OR UPDATE ON email_verification_tokens
CREATE TRIGGER … BEFORE INSERT OR UPDATE ON user_credentials    -- INSERT tambem: a conta criada pela DC-17.5 nasce sem credencial local
```

A substituição de `enforce_user_session_mfa_on_insert()` é o ponto onde o conflito do achado A-7 se resolve, e o contrato dela está por inteiro na **§4.4** — não é "aceita um ou outro", é uma condição de dois ramos mutuamente exclusivos com o vínculo conferido no banco.

`users` ganha uma política de RLS para `nekkora_identity_owner` em `SELECT`/`UPDATE (updated_at)`, limitada à conta alvo da vinculação pela GUC que a própria função define (§4.5, camada 3). Nada mais em tabela existente. **Nenhuma linha** de `users`, `organization_memberships`, `user_credentials` ou `user_mfa_factors` é alterada pela migration: as triggers novas só recusam escrita futura de conta vinculada, e nenhuma conta está vinculada no momento da aplicação.

### 4.3 Invariantes ao final da migration

No padrão da 012 §15:

| # | Invariante conferida pela própria migration |
|---|---|
| 1 | a contagem de tabelas com `organization_id` **não muda**: 43 (39 da 012 mais 4 da 014; a revisão anterior dizia 39, §13 item 1), e nenhuma tabela nova tem a coluna |
| 2 | toda função `SECURITY DEFINER` nova tem `search_path` fixo e nenhum `EXECUTE` para `PUBLIC` |
| 3 | as funções de identidade executáveis pelo `nekkora_platform` são exatamente as oito públicas e as duas auxiliares da §5, e nenhuma outra |
| 4 | `nekkora_platform` **não** tem `UPDATE (disabled_at)` em `identity_mfa_factors` (§3.3.1, I-1) |
| 5 | as seis triggers de recusa da §4.5 e a trigger de `user_sessions` existem, estão habilitadas (`tgenabled = 'O'`) e **não** pertencem a nenhum papel de aplicação |
| 6 | E-1, E-2 e E-3 da §4.4 devolvem zero linhas |
| 7 | `nekkora_platform` não tem nenhum privilégio em `identity_users`, nem `UPDATE` em `recovery_used_at` ou `recovery_cleared_at`; em `identity_mfa_factors` tem exatamente `SELECT` e `UPDATE (last_step)`; em `identity_mfa_recovery_codes`, exatamente `SELECT` e `UPDATE (used_at, used_in_identity_session_id)` |
| 8 | nenhuma das tabelas das triggers da §4.5, nem `users`, `user_sessions` ou `identity_users`, tem como dono um papel com `LOGIN`; e `nekkora_platform` segue `NOSUPERUSER NOREPLICATION NOBYPASSRLS` |
| 9 | `nekkora_platform` **não** é membro de `nekkora_identity_owner` (`pg_has_role(…, 'MEMBER')` falso), e as triggers de imutabilidade de `identity_mfa_factors` e `identity_mfa_recovery_codes` existem e estão habilitadas |

As nove são verificadas num bloco `DO $$ … $$` ao final da migration, que levanta exceção e desfaz tudo se qualquer uma falhar, como a 012, a 013 e a 014 fazem.

### 4.4 Contrato da sessão de organização quando a conta é vinculada

**A incompatibilidade, nomeada.** O achado A-7 registra que a trigger da 014 exige fator confirmado e ativo **daquela conta** para criar sessão. A DC-17.12 desabilita esse fator ao vincular. As duas coisas juntas tornariam a conta vinculada incapaz de abrir sessão. A DC-17.3 mencionava a mudança da trigger numa linha, o que **não é contrato**. Este é.

**Regra, em duas alternativas mutuamente exclusivas.** `enforce_user_session_mfa_on_insert()` passa a exigir, além do que já exige hoje (marcador de MFA, CSRF, sem step-up, sem revogação):

```sql
-- ramo 1 - conta NAO vinculada: a regra da 014, intacta
(NOT EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = NEW.user_id)
 AND NEW.identity_session_id IS NULL
 AND EXISTS (SELECT 1 FROM public.user_mfa_factors f
              WHERE f.organization_id = NEW.organization_id AND f.user_id = NEW.user_id
                AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL))

-- ramo 2 - conta vinculada: a sessao de identidade E a prova, e ela tem de ser DESTA identidade
OR
(NEW.identity_session_id IS NOT NULL
 AND EXISTS (SELECT 1 FROM public.identity_users iu
               JOIN public.identity_sessions s ON s.identity_id = iu.identity_id
              WHERE iu.user_id = NEW.user_id
                AND s.identity_session_id = NEW.identity_session_id
                AND s.revoked_at IS NULL
                AND s.expires_at > clock_timestamp()
                AND s.mfa_verified_at IS NOT NULL
                AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL))  -- nao restrita
 AND EXISTS (SELECT 1 FROM public.identity_mfa_factors g
               JOIN public.identity_users iu2 ON iu2.identity_id = g.identity_id
              WHERE iu2.user_id = NEW.user_id
                AND g.confirmed_at IS NOT NULL AND g.disabled_at IS NULL))
```

Antes de avaliar os dois ramos, a função toma o lock da conta (`SELECT 1 FROM public.users WHERE user_id = NEW.user_id FOR UPDATE`, recusando se a linha não for encontrada), pelo mesmo motivo das triggers da §4.5: sem ele, uma sessão pela porta antiga poderia avaliar o ramo 1 antes de uma vinculação concorrente confirmar e sobreviver a ela. As leituras de `identity_users` são feitas por função `SECURITY DEFINER` auxiliar, porque o `nekkora_platform` não tem privilégio nessa tabela (§4.5, camada 3).

**Quatro propriedades que essa forma garante e que um `OR` solto não garantiria:**

1. **Bicondicional de estado.** `identity_session_id IS NULL` se e somente se a conta não é vinculada. Os dois ramos não se sobrepõem, então não existe caminho em que a ausência de fator da conta seja suprida sem uma sessão de identidade, nem caminho em que uma conta vinculada nasça sessão sem ela.
2. **A sessão de identidade tem de ser da identidade ligada àquela conta.** Um `OR` que apenas exigisse "existe fator de identidade confirmado" permitiria criar sessão para a conta de outra pessoa com a sessão de identidade de quem chamou. O `JOIN` por `identity_users` fecha isso **no banco**, não na aplicação.
3. **A proteção das contas não vinculadas fica literalmente a mesma.** O ramo 1 é a condição da 014 palavra por palavra, mais a exigência de `identity_session_id` nulo. Nenhuma conta atual passa a aceitar sessão com menos prova do que aceita hoje.
4. **Sessão de identidade restrita à recuperação não gera sessão de organização**, ainda que o fator antigo da identidade continue confirmado e habilitado entre T0 e T1 — que é exatamente quando a segunda condição do ramo 2 seria satisfeita. A recusa vem da marca na linha da sessão (DC-17.13), não do estado dos fatores.

**Invariantes de estado, verificados na migration e na suíte:**

| # | Invariante |
|---|---|
| E-1 | nenhuma `user_sessions` **ativa** com `identity_session_id` nulo cujo `user_id` esteja em `identity_users` |
| E-2 | nenhuma `user_sessions` com `identity_session_id` de sessão de identidade **não ligada** àquela conta |
| E-3 | nenhum `user_mfa_factors` **habilitado** para `user_id` em `identity_users` |

E-1 vale porque a DC-17.12 revoga todas as sessões da conta no mesmo ato da vinculação. E-3 vale porque a DC-17.12 desabilita o fator e a §4.5 impede recriá-lo.

---

### 4.5 Contrato de fechamento da entrada local

**O ponto da responsável, aceito:** fator desabilitado não basta. O reset administrativo fica sem alvo, mas cadastro de fator, recuperação, emissão de desafio ou troca de senha poderiam recriar a entrada. A regra tem de ser explícita e valer também sob concorrência com a vinculação.

**Regra:** conta vinculada **não autentica por credenciais locais nem cadastra fator local**. Três camadas, e as três são necessárias.

**Camada 1 — elegibilidade.** A expressão de elegibilidade lida por `AccountRepository` passa a exigir `NOT EXISTS (SELECT 1 FROM identity_users iu WHERE iu.user_id = u.user_id)`. Isso faz o login por organização recusar com o `401` genérico de sempre, e faz a reconferência sob o lock (`recheck`) invalidar um desafio cuja conta foi vinculada no meio do caminho.

**Camada 2 — triggers de recusa.** Elegibilidade é regra de aplicação; estas são fato no banco. `BEFORE INSERT OR UPDATE`, recusando com `ERRCODE = '23514'` quando `NEW.user_id` está em `identity_users`. Um `UPDATE` que leva a linha a estado **terminal** (consumida, invalidada, revogada, desabilitada) não reabre entrada e não é conferido: é o caminho do próprio fechamento da DC-17.12 e do reset operacional da 016 (§13, item 4).

| Tabela | O que fica impedido |
|---|---|
| `auth_challenges` | emitir desafio de login por organização |
| `user_mfa_factors` | cadastrar fator local novo **e** reabilitar um desabilitado (`disabled_at` → nulo) |
| `user_mfa_recovery_codes` | emitir códigos locais novos |
| `password_reset_tokens` | emitir token de recuperação local |
| `email_verification_tokens` | emitir token de verificação local |
| `user_credentials` | criar credencial local **e** trocar `password_hash` |

A trigger de `user_mfa_factors` cobre o caso que a responsável levantou de forma direta: não há cadastro de fator local depois do vínculo, e não há reabilitação do antigo.

**Camada 3 — ordenação por lock, no caminho obrigatório do banco.** As triggers recusam, mas sozinhas não ordenam: em `READ COMMITTED`, duas transações concorrentes podem não ver uma à outra.

**Correção de 2026-10-11, apontada pela responsável.** A versão anterior apoiava a ordenação no `AccountRepository.lock` e na regra declarada do `AuthService`. Isso é regra de **aplicação**: um caminho novo que esquecesse o lock, ou uma escrita direta com o papel `nekkora_platform`, escaparia dela, e a garantia pretendida é do **banco**. O lock passa a ser tomado pelo próprio banco, em quatro peças:

| Peça | Contrato |
|---|---|
| **1. Lock dentro da trigger** | Cada uma das seis triggers da camada 2 e a trigger de `user_sessions` (§4.4) executam, **antes** de conferir o vínculo, `SELECT organization_id INTO v_org FROM public.users WHERE user_id = NEW.user_id FOR UPDATE`, sob a RLS do contexto corrente. Se a linha não for encontrada (por exemplo, conta de outra organização), recusam com `23514`: falha fechada. A conferência do vínculo é então feita pela auxiliar com `(v_org, NEW.user_id)`, que confere o contexto de novo do lado do dono (§5). Quando a aplicação já tomou o lock, a segunda aquisição na mesma transação não custa nada; quando não tomou, a trigger toma. A função da trigger é `VOLATILE`, então a conferência do vínculo, feita depois do lock, usa instantâneo novo e enxerga a vinculação que acabou de confirmar. |
| **2. Vinculação só por função** | O único caminho que grava `identity_users` para conta existente é `identity_link_account(…)`, `SECURITY DEFINER` de `nekkora_identity_owner`. Ela toma o lock da organização e depois `FOR UPDATE` na conta, na ordem do inventário de locks (achado I-1); confere o vínculo sob o lock; consome o desafio de vinculação; grava `identity_users`; executa **dentro dela** todo o fechamento da DC-17.12; e faz um `UPDATE` real na linha de `users` (`updated_at`). |
| **3. Sem contorno pelo papel da aplicação** | `nekkora_platform` não tem nenhum privilégio em `identity_users` (§4.1), então não vincula nem desvincula por escrita direta. Não é dono de nenhuma das tabelas envolvidas, então não executa `ALTER TABLE … DISABLE TRIGGER`; é `NOSUPERUSER`, então não altera `session_replication_role` para desligar triggers; e nenhuma função auxiliar que ele executa escreve qualquer coisa. As triggers valem para qualquer papel, inclusive o de aplicação chamando SQL sem passar pelo `AuthService`. |
| **4. Isolamento acima de `READ COMMITTED`** | Uma transação em `REPEATABLE READ` com instantâneo anterior à vinculação não veria `identity_users` mesmo depois do lock. O `UPDATE` real da peça 2 fecha isso: ao tentar o `FOR UPDATE` numa linha atualizada por transação concorrente já confirmada, o PostgreSQL recusa com `40001`. Falha fechada. A própria `identity_link_account` exige `READ COMMITTED` e recusa se chamada em outro nível, porque em `REPEATABLE READ` ela não veria desafios confirmados depois do seu instantâneo e não os invalidaria. |

**Verificação ao commit, como cinto adicional.** Uma trigger de restrição adiada em `identity_users` (`DEFERRABLE INITIALLY DEFERRED`, `AFTER INSERT`) confere, no commit, E-1 e E-3 para a conta recém-vinculada e a ausência de desafio ativo, token pendente e código local válido. Se o fechamento ficar incompleto por qualquer motivo, a vinculação inteira é desfeita.

Consequência: vinculação e entrada local **serializam** no mesmo registro de `users` independentemente do código que as chama. Quem chega depois vê o estado confirmado do outro, ou é recusado por serialização. Um eventual `40P01` (impasse) também aborta uma das duas, e abortar é falha fechada.

**Os quatro intercalamentos possíveis, e o desfecho de cada um:**

| Intercalamento | Desfecho |
|---|---|
| vinculação obtém o lock primeiro | a entrada local espera **dentro da trigger**, mesmo sem ter chamado `AccountRepository.lock`; ao obter o lock, a trigger vê o vínculo e recusa |
| entrada local obtém o lock primeiro e cria desafio | a vinculação espera; ao obter o lock, invalida aquele desafio no mesmo ato (DC-17.12). Nenhum desafio ativo sobrevive |
| sessão de organização nasce pela porta antiga, antes da vinculação | a vinculação a revoga no mesmo ato. E-1 continua valendo |
| duas vinculações da mesma conta | `UNIQUE (user_id)` em `identity_users` deixa só uma vencer; a outra falha por restrição, não por leitura |

**Nenhum intercalamento deixa entrada local viva numa conta vinculada.** Essa é a afirmação que a §10.1 testa, e não um raciocínio a confiar.

---

## 5. Isolamento: funções `SECURITY DEFINER` com escopo de identidade

O ponto de segurança da proposta. A 013 isola limitando a função a **uma organização** (`nekkora.fn_organization_id`). Aqui a leitura precisa atravessar organizações, então a limitação é outra: **a identidade**.

Dono novo `nekkora_identity_owner` (NOLOGIN, sem privilégio de login, no padrão de `nekkora_portal_owner`), com política própria em `users`, `organizations` e `organization_memberships`:

```sql
CREATE POLICY identity_function_context ON public.users
  AS PERMISSIVE FOR SELECT TO nekkora_identity_owner
  USING (EXISTS (SELECT 1 FROM public.identity_users iu
                  WHERE iu.user_id = users.user_id
                    AND iu.identity_id = NULLIF(pg_catalog.current_setting('nekkora.fn_identity_id', true), '')::uuid));
```

Sem a GUC, zero linhas — falha fechada, como na 012. Com a GUC, **só as contas daquela identidade** ficam visíveis. Nenhum caminho dá visão de todas as organizações.

Oito funções públicas, `EXECUTE` só para `nekkora_platform`, e nenhuma outra (§4.3, invariante 3). Entre as que **recebem sessão de identidade** — `identity_organizations`, `identity_org_session_target`, `identity_link_account`, `identity_create_organization`, `identity_mfa_enroll_pending` e `identity_mfa_replace` —, todas devolvem **zero linhas ou zero efeito** para sessão restrita à recuperação, exceto `identity_mfa_enroll_pending` e `identity_mfa_replace`, que são exatamente o que a sessão restrita pode fazer (DC-17.13). `identity_resolve_login` recebe e-mail e `identity_mfa_confirm_first` recebe desafio, não sessão; a regra da sessão restrita não se aplica a elas:

| Função | Entrada | Saída | Lê |
|---|---|---|---|
| `identity_resolve_login(p_email_normalized)` | e-mail normalizado | `identity_id`, `status`, `email_verified_at`, `password_hash`, `tem_fator_confirmado` | só tabelas de identidade — **nenhuma leitura entre organizações** |
| `identity_organizations(p_session_token)` | token da sessão de identidade | `(organization_id, name, papel_codigo)` das organizações com vínculo ACTIVE e conta elegível | sob `fn_identity_id` |
| `identity_org_session_target(p_session_token, p_organization_id)` | token + organização | `(organization_id, user_id)` ou **zero linhas** | sob `fn_identity_id` |
| `identity_mfa_replace(p_session_token, p_new_factor_id, p_new_code_hashes)` | token da sessão de identidade; a **prova** é lida na linha da sessão: `mfa_step_up_at` ou `recovery_used_at` dentro da janela (DC-17.13) | sucesso ou zero efeito | único caminho que escreve `disabled_at` em `identity_mfa_factors` (§3.3.1, I-1) e `recovery_cleared_at` em `identity_sessions`; executa T1 inteiro |
| `identity_link_account(p_session_token, p_link_token, …)` | sessão de identidade com step-up; token do desafio de vinculação | sucesso ou zero efeito | único caminho que vincula conta existente; lock, consumo do desafio e fechamento da DC-17.12 na mesma transação (§4.5, camada 3) |
| `identity_create_organization(p_session_token, p_name, p_legal_name)` | sessão de identidade com step-up, **não restrita**; **nenhum `user_id` nem `organization_id` de entrada** | `organization_id` criado, ou zero efeito | DC-17.5: cria organização, conta, vínculo interno, ORG_ADMIN, evento de provisionamento e `identity_users`, ligando só a conta que ela mesma inseriu |
| `identity_mfa_enroll_pending(p_challenge_or_session_token, p_secret…)` | desafio de identidade ativo, ou sessão autorizada pela DC-17.13 | `factor_id` pendente | único caminho que insere fator |
| `identity_mfa_confirm_first(p_challenge_token, p_factor_id, p_code_hashes)` | desafio de identidade ativo; identidade **sem** fator confirmado, nunca | sucesso ou zero efeito | primeiro fator; grava os códigos |

**Auxiliares de leitura usadas pelas triggers**, `SECURITY DEFINER`, sem escrita, `EXECUTE` só para `nekkora_platform` porque as triggers rodam com o papel de quem escreve:

| Auxiliar | Conferência de contexto, antes de responder |
|---|---|
| `identity_account_is_linked(p_organization_id, p_user_id)` | `current_setting('nekkora.organization_id')` existe e é igual a `p_organization_id`, e a conta `p_user_id` pertence a essa organização |
| `identity_org_session_allowed(p_organization_id, p_user_id, p_identity_session_id)` | a mesma, e então os ramos da §4.4 |

**Correção de 2026-10-11, apontada pela responsável:** a revisão anterior dizia que bastava ao chamador "já conhecer o UUID". Conhecer um UUID não é autorização. As auxiliares seguem o padrão de `record_usage_ingestion` da 012: contexto ausente ou divergente levanta `42501`; conta fora da organização do contexto também. **Não existe resposta "não vinculada" para conta de outra organização**: a chamada falha, e a trigger que a chamou falha junto. O limite dessa conferência é o mesmo da RLS da 012 — o contexto é definido pelo papel da aplicação ao abrir a transação —, e as auxiliares não o tornam mais fraco do que a leitura direta de `users` sob RLS já é.

`identity_organizations` e `identity_org_session_target` devolvem **zero linhas** para sessão restrita à recuperação (DC-17.13).

A função de substituição existe por causa de I-1: `nekkora_platform` **não** recebe `UPDATE (disabled_at)` em `identity_mfa_factors`, então desabilitar um fator confirmado só é possível por dentro dela. A prova de autenticação recente é lida **no banco**, na linha da sessão, e não aceita como parâmetro: a aplicação não pode afirmar um step-up que não ocorreu. Nenhum papel de organização a executa com autoridade de organização.

`identity_org_session_target` **não cria sessão**. Ela só resolve o alvo. A sessão de organização é criada pela aplicação no caminho já provado: `OrgTransactions.inOrganization(org, READ_WRITE)` com `SessionRepository.create`. Assim a escrita de sessão continua sob a RLS tenant e sob o trigger da 014, sem privilégio novo de escrita para o dono da função.

---

## 6. Contrato HTTP

Nove operações novas, todas fora de `/api/v1/o/{org}/`. **Nenhuma rota existente muda para conta não vinculada.** Para conta vinculada, as rotas de credencial local deixam de servir, com a resposta genérica de sempre (§1, DC-17.9). Erro sempre `{"error":"<codigo>"}` (contrato 8.7 §5.2). Escritas exigem `Origin` igual à origem pública e `X-CSRF-Token` do contexto.

### 6.1 Cadastro

| Rota | Contexto CSRF | Resposta |
|---|---|---|
| `POST /api/v1/signup` | pré-autenticação | `202` sempre, corpo vazio |
| `POST /api/v1/signup/confirmation/resend` | pré-autenticação | `202` sempre, corpo vazio |
| `POST /api/v1/signup/confirm` | pré-autenticação | `204`; token inválido/expirado/consumido → `400 {"error":"invalid_request"}` |

`POST /api/v1/signup` recebe `{email, password, displayName}`. A resposta é **sempre** `202`, sem revelar se o e-mail já tem identidade:
- e-mail livre → cria `identities` PENDING + `identity_credentials`, emite token e envia a confirmação;
- e-mail com identidade ACTIVE → **não** cria nada, **não** emite token, e envia um aviso "já existe uma conta com este e-mail" (sem token e sem link de cadastro);
- e-mail com identidade PENDING → trata como reenvio, sujeito ao limitador R9b.

A senha passa pela `PasswordPolicy` atual e pelo Argon2id com os parâmetros em vigor. Piso de tempo de resposta igual ao de `requestRecovery`, para que o caminho "já existe" não se distinga pelo tempo.

A confirmação **não** cria sessão e **não** cria organização: deixa a identidade ACTIVE com `email_verified_at` preenchido. Em seguida a pessoa faz a entrada geral, cadastra o TOTP e então cria a organização. Isso reaproveita o fluxo de cadastro de MFA que já existe, em vez de abrir um segundo caminho para criar sessão.

### 6.2 Política do token de confirmação

| Item | Valor | Mecanismo |
|---|---|---|
| Formato | 32 bytes aleatórios, base64url de 43 caracteres | `Tokens.newToken()`, já em uso |
| Persistência | **só** `sha256` hex em `identity_email_tokens.token_hash` | igual a `email_verification_tokens` |
| Validade | **24 h** | `nekkora.session.email-verification`, valor já configurado |
| Consumo | **único**, por um `UPDATE … WHERE consumed_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp() RETURNING …` | padrão declarado na 002: concorrência resolvida pelo MVCC, não por ler-depois-escrever |
| Reemissão | o reenvio **revoga** os pendentes daquela identidade e emite um novo | igual ao que `requestRecovery` já faz com `password_reset_tokens` |
| Simultâneos | no máximo um válido por identidade, como efeito da revogação acima | — |
| Reenvio | R9b: 3 por identidade/hora e 10 por IP/hora, nos mesmos valores de `r5-*` | Redis |
| Transporte | chamado **depois do commit**, fora da transação; o valor bruto só existe em memória até ali | contrato §6.1, já observado por `AuthService` |
| Log | nunca token, hash, e-mail ou link | `MailSender` local já registra só a intenção |

### 6.3 Entrada geral

| Rota | Contexto CSRF | Resposta |
|---|---|---|
| `POST /api/v1/auth/login` | pré-autenticação | `200 {"status":"mfa_required"\|"mfa_enrollment_required","csrfToken":"…"}` + cookie `__Host-nekkora_id_mfa` |
| `POST /api/v1/auth/mfa/enroll/start` | desafio de identidade | `200 {"otpauthUri":"…"}` |
| `POST /api/v1/auth/mfa/enroll/confirm` | desafio de identidade | `200 {"recoveryCodes":[…],"csrfToken":"…"}` + cookie `__Host-nekkora_id` |
| `POST /api/v1/auth/mfa/verify` | desafio de identidade | `200 {"csrfToken":"…"}` + cookie `__Host-nekkora_id` |
| `POST /api/v1/auth/logout` | logout de identidade | `204` |

Os cinco espelham, passo a passo, o que `AuthService` já faz por organização, com as mesmas regras que atravessam todos os métodos: busca em transação **somente leitura**; Argon2 **fora** de transação e sem conexão; escrita só depois, em transação nova; falha de negócio dentro da transação é **resultado** e não exceção; lock da identidade antes de desafio, fator, códigos e sessões; resposta genérica; log sem e-mail, senha, código, token, hash nem "conta inexistente".

O `logout` da identidade revoga a sessão de identidade. As sessões de organização derivadas dela caem na requisição seguinte, pela revalidação de DC-17.4 — sem escrita entre organizações.

**Step-up da sessão de identidade (P1, acrescentado em 2026-10-14).** `POST /api/v1/auth/step-up` `{code}`, com o CSRF da sessão de identidade, devolve `204`. Só aceita o TOTP do fator da identidade, em sessão não restrita. Grava `mfa_step_up_at` só na sessão da requisição e o evento 13 na mesma transação, depois de travar identidade → sessão e reconferir as duas. Contrato completo no documento de contratos de 2026-10-13, seção P1.

### 6.4 Organizações disponíveis

`GET /api/v1/me/organizations`, com sessão de identidade.

```json
{ "organizations": [ { "organizationId": "…", "name": "…", "role": "ORG_ADMIN" } ],
  "canCreateOrganization": true }
```

Campos em allowlist, como nas demais rotas de leitura. Lista **só** organizações com vínculo interno ACTIVE, conta elegível e organização ativa — a mesma definição de elegibilidade de `AccountRepository.Account.eligible()`. Lista vazia é resposta legítima e é o caso de quem acabou de confirmar o e-mail.

### 6.5 Troca por sessão de organização

`POST /api/v1/sessions/organization`, corpo `{organizationId}`, com sessão de identidade. Resposta conforme DC-17.6.

O caminho de sucesso: `identity_org_session_target` resolve `(organization_id, user_id)`; a aplicação abre `inOrganization(org, READ_WRITE)`, trava a conta, cria a `user_sessions` com `identity_session_id` preenchido e `mfa_verified_at` herdado do MFA da identidade; emite o cookie de organização. A partir daí **todas** as rotas `/api/v1/o/{org}/…` funcionam sem nenhuma alteração.

O destino `/o/{org}/login` é, então, dois chamados: entrada geral e, logo após o MFA, esta troca com o `{org}` da URL. Em `sem_acesso`, o front-end vai para `/organizacoes`. Não há rota nova para o destino: ele é composição de duas que existem no contrato.

### 6.6 Criação de organização

`POST /api/v1/organizations`, corpo `{name, legalName?}`, com sessão de identidade e step-up de MFA da identidade dentro de 5 min. A rota chama `identity_create_organization` e nada mais escreve. Resposta `201 {"organizationId":"…"}` e, em seguida, a troca da §6.5 dá a sessão de organização. Conteúdo da transação em DC-17.5.

**Nota de 2026-10-14 (P2):** implementada conforme o contrato P2 (`2026-10-13-contratos-step-up-criacao-vinculacao.md`), com chave própria `nekkora.criacao-organizacao.enabled`, desligada por padrão. Relatório: `2026-10-14-p2-criacao-organizacao.md`.

### 6.7 Classificação de rotas e CSRF

`Routes` passa a reconhecer as rotas sem organização explicitamente, porque hoje o `default` do `csrfContext` as classificaria como `SESSION`:

| Contexto novo | Estado comparado | Cookie |
|---|---|---|
| `PRE_AUTHENTICATION` (reuso) | Redis, pelo `__Host-nekkora_pre` | existente |
| `IDENTITY_CHALLENGE` | `identity_auth_challenges.csrf_token` | `__Host-nekkora_id_mfa`, `SameSite=Strict` |
| `IDENTITY_SESSION` | `identity_sessions.csrf_token` | `__Host-nekkora_id`, `SameSite=Lax` |
| `IDENTITY_LOGOUT` | igual a `LOGOUT`: sem sessão válida é no-op | `__Host-nekkora_id` |

`IdentityFilter` novo, irmão do `SessionFilter`, resolve a sessão de identidade **somente** nas rotas sem organização e reconstrói o principal do banco a cada requisição, sem nada em `HttpSession`. O `SessionFilter` continua lendo só `__Host-nekkora_session` e só em `/api/v1/o/{org}/…`. Os dois cookies nunca são aceitos na mesma rota.

---

## 7. Proteção contra abuso

Todos os limitadores atuais são **por organização** (`Limits`: chaves `nk:rl:r1:<org>:…`, R6 por organização). A entrada geral não tem organização, então precisa de regras próprias. `IdentityHmac` ganha uma variante global (`"global|" + e-mail normalizado`), mantendo a regra de que o e-mail em claro nunca vai ao Redis nem ao log.

| Regra | Alvo | Proposta inicial | Falha |
|---|---|---|---|
| R8 | senha errada na entrada geral, por identidade global + IP | mesmos valores de R1/R2 (5 falhas/15 min; atraso progressivo a partir da 6ª em 1 h) | fecha |
| R8m | código de MFA errado na entrada geral | mesmos valores de R4 (10/h) | fecha |
| R9 | `POST /api/v1/signup` por IP | 10/h, igual a `r5-per-ip` | fecha |
| R9b | reenvio de confirmação | 3 por identidade/h e 10 por IP/h, iguais a `r5-*` | fecha |
| R10 | troca por sessão de organização, por sessão de identidade | 30/h, com piso de tempo de resposta | fecha |
| R11 | criação de organização, por identidade | 3/dia e 10 no total | fecha |
| R12 | balde global das rotas sem organização, por IP | capacidade e taxa de R6 (300, 5/s) | fecha |

Redis fora do ar devolve `503` nas chamadas que falham fechado, como hoje. O `logout` da identidade fica **fora** do limitador, pela mesma razão que `logout` e `revoke-all` já ficam: reduz acesso.

---

## 8. Auditoria

`mfa_events` exige `organization_id` e não serve para eventos sem organização. `identity_events`, append-only, registra: `SIGNUP_REQUESTED`, `SIGNUP_CONFIRMED`, `MFA_ENROLLED`, `MFA_VERIFIED`, `RECOVERY_CODE_USED`, `ORG_CREATED`, `ORG_SESSION_ISSUED`, `ORG_SESSION_DENIED`, `IDENTITY_LINKED`, `LOGOUT`. Cada linha leva `correlation_id` e, quando houver, `organization_id`. Nunca e-mail, token ou hash.

A criação de organização continua auditada **também** por `identity_provisioning_events`, que já é a prova de autoria do primeiro ORG_ADMIN e já é append-only. O fluxo novo não cria um segundo mecanismo de prova; usa o que a 003 definiu.

---

## 9. Compatibilidade com as contas atuais

A matriz é a parte da missão que a responsável pediu primeiro. Nenhuma linha existente é alterada pela migration.

| Situação da pessoa | `/api/v1/o/{org}/auth/login` (hoje) | `/api/v1/auth/login` (novo) | `GET /me/organizations` |
|---|---|---|---|
| Conta atual, sem identidade | funciona, inalterado | `401` genérico | não se aplica |
| Identidade nova, sem organização | não se aplica | funciona | lista vazia |
| Identidade nova, com organização que ela criou | **`401` genérico**: a conta criada nasce vinculada e sem credencial local (DC-17.5, DC-17.9) | funciona; a organização é alcançada pela troca da §6.5 | lista a organização |
| Conta antiga vinculada pela missão posterior (DC-17.11) | **`401` genérico** (DC-17.9) | funciona | lista a organização |
| Identidade nova + conta antiga de mesmo e-mail, não ligadas | funciona na conta antiga | funciona na identidade | **não** lista a organização da conta antiga |
| Mesmo e-mail em duas organizações distintas (A-2) | funciona em cada uma | funciona na identidade | lista só as organizações ligadas |

Pontos que a convivência preserva:

- **Isolamento:** o `nekkora_platform` continua sem SELECT entre organizações sobre `users`. A única visão que atravessa organizações é a do dono das funções da §5, limitada por `fn_identity_id` à própria identidade.
- **Autorização:** o acesso dentro de uma organização continua decidido pela matriz da 003, pela conta e pelo vínculo. A sessão de identidade **não concede nada** dentro de organização nenhuma: ela só permite listar vínculos, criar organização e pedir a troca.
- **Revogação:** suspender a conta, revogar o vínculo ou suspender a organização continua bloqueando a requisição seguinte, porque a `user_sessions` continua revalidada a cada requisição. A revogação da sessão de identidade derruba as sessões derivadas na requisição seguinte.
- **Organizações distintas com usuários do mesmo domínio:** já suportado e preservado. `organization_domains` tem `UNIQUE (organization_id, domain_normalized)`, escopo por organização e não global; o cadastro **não** usa domínio de e-mail para escolher organização, e `organization_domains` não participa de nenhum caminho novo.
- **MFA das contas atuais:** `user_mfa_factors` fica intacto e o ramo 1 da §4.4 é a regra da 014 palavra por palavra. O que muda é para conta vinculada: a sessão de organização nasce pelo ramo 2, e as triggers da §4.5 impedem fator local.

---

## 10. Testes

| Suíte | Onde | Cobre |
|---|---|---|
| Banco `017` | `db/tests/identidade-017/run.sh`, container descartável | instalação limpa e atualização de base populada; políticas RLS novas; `EXECUTE` das funções; invariantes da §4.3; falha fechada sem `fn_identity_id` |
| HTTP cadastro | `SignupRouteIT` | `202` idêntico para e-mail livre, PENDING e ACTIVE; token de uso único; expirado; reenvio revoga o anterior; limitadores |
| HTTP entrada geral | `GeneralLoginIT` | senha errada, identidade inexistente e identidade não confirmada com a **mesma** resposta; cadastro e verificação de TOTP; CSRF por contexto |
| Troca de organização | `OrgSessionExchangeIT` | `sem_acesso` idêntico nos quatro casos de DC-17.6; sucesso cria `user_sessions` com `identity_session_id`; cookie de identidade recusado em `/o/{org}/…` |
| Criação de organização | `OrganizationCreationIT` | ORG_ADMIN efetivo, `identity_provisioning_events` gravado, step-up exigido, R11 |
| **Mesmo domínio em organizações distintas** | `MesmoDominioOrganizacoesDistintasIT` | duas organizações com usuários `@acme.com`; nenhuma vê a outra; `/me/organizations` lista só as ligadas; a troca para a organização não ligada devolve `sem_acesso` |
| Convivência | `CompatibilidadeContasAtuaisIT` | cada linha da matriz da §9, incluindo que conta antiga sem identidade não entra pela rota nova |
| Revogação | `IdentitySessionRevocationIT` | revogar a sessão de identidade derruba as sessões de organização na requisição seguinte |
| **Reset de MFA, fronteira** | `MfaResetFronteiraIT` | **I-1**: o caminho administrativo de organização não consegue desabilitar fator de identidade, e a tentativa falha por **privilégio no banco**, não por checagem de aplicação. **I-2**: reset do fator da conta não altera o fator da identidade, e vice-versa. **I-3**: conta vinculada recusa o login por organização com o `401` genérico |
| Recuperação de identidade | `IdentityRecoveryCodeIT` | código de uso único; usar um revoga sessões de identidade e derivadas, invalida desafios e dispara o aviso |
| **Autenticação recente** | `FatorIdentidadeAutenticacaoRecenteIT` | **DC-17.13**: sessão válida **sem** step-up e **sem** marca de recuperação **não** substitui fator; step-up expirado não substitui; marca de recuperação expirada não substitui; os caminhos A e B funcionam dentro das janelas |
| **Vinculação** | `VinculacaoProvaDuplaIT` | **DC-17.11**: e-mail igual sem as provas não vincula; senha sem segundo fator não vincula; step-up de identidade expirado não vincula; nenhum papel de organização vincula em nome de outra. **DC-17.12**: o ato revoga sessões, invalida desafios, revoga tokens de recuperação e verificação, desabilita o fator da conta e invalida os códigos dela |
| **Duas portas** | `DuasPortasLoginIT` | **DC-17.10**: nenhuma resposta antes de uma tentativa completa de credencial depende da existência ou do estado de vínculo da conta |
| Regressão | suítes atuais sem alteração | `AuthenticationFlowIT`, `SessionsAndAdministrationIT`, `EmailVerificationRouteIT`, `ProductReadRoutesIT` passam sem mudança |

O OpenAPI `api/openapi/nekkora-api-v1.yaml` recebe as nove operações e sobe para `1.1.0`, conferido contra os controladores e os testes HTTP como manda a D-INV-06.

### 10.1 Cenários de banco dos contratos da §4.4 e §4.5

Suíte `db/tests/identidade-017/run.sh`, container descartável, no formato das suítes existentes. Estes cenários são **de banco**, exercidos por SQL direto com os papéis reais: eles provam que a regra vale mesmo que a aplicação erre, o que é o ponto da exigência da responsável de ter contrato e teste, não exceção no código.

**Grupo A — nascimento da sessão de organização (§4.4)**

| # | Estado | `INSERT` em `user_sessions` |
|---|---|---|
| A-1 | conta **não** vinculada, fator da conta confirmado e ativo, `identity_session_id` nulo | **aceito** |
| A-2 | conta **não** vinculada, **sem** fator da conta | **recusado** (regra da 014, intacta) |
| A-3 | conta **não** vinculada, `identity_session_id` preenchido | **recusado** (exclusividade dos ramos) |
| A-4 | conta vinculada, sessão da identidade **ligada**, ativa, com MFA; fator da identidade confirmado | **aceito** |
| A-5 | conta vinculada, `identity_session_id` nulo | **recusado** |
| A-6 | conta vinculada, `identity_session_id` de sessão de **outra** identidade | **recusado** (isolamento; é o caso que um `OR` solto deixaria passar) |
| A-7 | conta vinculada, sessão de identidade **revogada** | **recusado** |
| A-8 | conta vinculada, sessão de identidade **expirada** | **recusado** |
| A-9 | conta vinculada, fator da identidade pendente ou desabilitado | **recusado** |
| A-10 | conta vinculada, fator da conta ainda habilitado | estado proibido por E-3; o cenário prova que a vinculação não o deixa acontecer |

**Grupo B — fechamento da entrada local (§4.5, camada 2)**

| # | Escrita tentada para conta **vinculada** | Esperado |
|---|---|---|
| B-1 | `INSERT` em `auth_challenges` | recusado, `23514` |
| B-2 | `INSERT` em `user_mfa_factors` | recusado |
| B-3 | `UPDATE` reabilitando fator (`disabled_at` → nulo) | recusado |
| B-4 | `INSERT` em `user_mfa_recovery_codes` | recusado |
| B-5 | `INSERT` em `password_reset_tokens` | recusado |
| B-6 | `INSERT` em `email_verification_tokens` | recusado |
| B-7 | `UPDATE` de `password_hash` em `user_credentials` | recusado |
| B-8 | as sete acima, para conta **não** vinculada | **todas aceitas**: nenhuma conta atual perde caminho |

**Grupo C — concorrência com a vinculação (§4.5, camada 3)**

Duas sessões de banco, com ponto de sincronismo explícito, no formato que as suítes de concorrência da 016 já usam. **A transação concorrente (T2) roda com o papel real `nekkora_platform`, com o contexto de organização definido como a aplicação define, e emite o SQL direto, sem passar pelo `AuthService` e sem chamar `AccountRepository.lock`.** É isso que prova que o lock vem do banco: se o cenário só passasse com o lock explícito da aplicação, ele estaria testando a aplicação. A vinculação (T1) é feita por `identity_link_account`, também chamada como `nekkora_platform`.

| # | Intercalamento | Esperado |
|---|---|---|
| C-1 | T1 vincula e retém o lock; T2 faz `INSERT` cru em `auth_challenges` | T2 **espera** dentro da trigger; T1 confirma; T2 é recusada com `23514` |
| C-2 | T2 insere desafio e confirma; T1 vincula | T1 invalida aquele desafio no mesmo ato; **nenhum** desafio ativo sobrevive |
| C-3 | T2 cria `user_sessions` pela porta antiga e confirma; T1 vincula | T1 revoga a sessão; **E-1** continua valendo |
| C-4 | T1 vincula e retém o lock; T2 faz `INSERT` cru em `user_sessions` pela porta antiga | T2 espera na trigger da §4.4; ao obter o lock, o ramo 1 falha e o ramo 2 também; recusada |
| C-5 | T2 abre o `INSERT` em `auth_challenges` **antes** de T1 e segura a transação aberta; T1 vincula | T1 espera o lock de T2; T2 confirma; T1 obtém o lock e invalida o desafio; nenhum sobrevive |
| C-6 | T1 e T2 vinculam a mesma conta | uma vence; a outra falha, sob o lock ou por `UNIQUE (user_id)`, nunca por leitura |
| C-7 | duas transações consomem o mesmo desafio de vinculação em paralelo | **um só** consumo tem efeito (DC-17.11, item 2) |
| C-8 | T1 vincula; T2 faz `UPDATE` cru de `password_hash` | serializam no lock; a de T2 é recusada |
| C-9 | T2 em `REPEATABLE READ` tira instantâneo; T1 vincula e confirma; T2 faz `INSERT` em `user_mfa_factors` | T2 recusada com `40001` (ou `23514`); nunca aceita |
| C-10 | `identity_link_account` chamada em `REPEATABLE READ` | recusada, sem efeito |
| C-11 | Cada uma das sete escritas da camada 2 repetida no padrão de C-1, sem lock da aplicação | as sete esperam e são recusadas |

**O que o grupo C precisa demonstrar como conclusão:** em **nenhum** intercalamento sobrevive entrada local viva numa conta vinculada, seja desafio ativo, sessão ativa pela porta antiga, fator habilitado ou token de recuperação pendente.

**Grupo C′ — o papel da aplicação não contorna o caminho** (como `nekkora_platform`)

| # | Tentativa | Esperado |
|---|---|---|
| C′-1 | `INSERT`, `UPDATE`, `DELETE`, `SELECT` e `TRUNCATE` em `identity_users` | `42501`, permissão negada |
| C′-2 | `ALTER TABLE auth_challenges DISABLE TRIGGER …` (e nas demais tabelas da camada 2 e em `user_sessions`) | recusado: não é dono |
| C′-3 | `SET session_replication_role = replica` | recusado |
| C′-4 | `UPDATE user_mfa_factors SET disabled_at = NULL` direto em conta vinculada | recusado pela trigger |
| C′-5 | chamar diretamente as funções auxiliares de leitura para escrever qualquer coisa | não há caminho: elas não escrevem |

**Grupo D — prova de autenticação recente e sessão restrita (DC-17.13)**

| # | Estado da linha de `identity_sessions` | `identity_mfa_replace` |
|---|---|---|
| D-1 | `recovery_used_at` dentro de 15 min | **permitido** |
| D-2 | `recovery_used_at` fora de 15 min | recusado |
| D-3 | sem step-up e sem `recovery_used_at` | recusado |
| D-4 | `mfa_step_up_at` dentro de 5 min | **permitido** |
| D-5 | `mfa_step_up_at` fora de 5 min | recusado |
| D-6 | sessão revogada, com step-up válido | recusado |
| D-7 | em T0: demais sessões de identidade revogadas, derivadas caem, desafios invalidados, **códigos antigos restantes preservados** | conforme a tabela de T0 |
| D-8 | em T1: fator antigo desabilitado, **todos** os códigos antigos invalidados, conjunto novo emitido | conforme a tabela de T1 |

A restrição de **rota** da sessão de recuperação é de camada HTTP e fica em `SessaoRecuperacaoRestritaIT`: as três rotas recusadas com `403` genérico antes de confirmar o fator novo, e liberadas depois. A recusa **no banco** é o grupo G.

**Grupo E — invariantes de estado (§4.4)**

| # | Consulta de invariante | Esperado |
|---|---|---|
| E-1 | `user_sessions` ativa com `identity_session_id` nulo cujo `user_id` está em `identity_users` | **zero linhas** |
| E-2 | `user_sessions` com `identity_session_id` de identidade não ligada àquela conta | **zero linhas** |
| E-3 | `user_mfa_factors` habilitado para `user_id` em `identity_users` | **zero linhas** |

As três rodam no fim de **cada** cenário dos grupos A a D e G a J, não só uma vez ao final da suíte. Invariante que só é conferida no fim não localiza qual cenário a quebrou.

**Grupo G — sessão restrita à recuperação, recusada no banco (DC-17.13; §4.4, propriedade 4)**

Todos como `nekkora_platform`, por SQL direto.

| # | Estado ou tentativa | Esperado |
|---|---|---|
| G-1 | conta vinculada; sessão de identidade ligada, ativa, com MFA, `recovery_used_at` preenchido e `recovery_cleared_at` nulo; **fator antigo da identidade confirmado e habilitado**; `INSERT` em `user_sessions` | **recusado** |
| G-2 | o mesmo, depois de `identity_mfa_replace` concluir T1 | **aceito** |
| G-3 | sessão restrita com `mfa_step_up_at` recente | **recusado**: step-up não levanta a restrição |
| G-4 | `identity_organizations` e `identity_org_session_target` com sessão restrita | zero linhas |
| G-5 | `identity_create_organization` com sessão restrita | recusado, sem efeito; nenhuma linha criada |
| G-6 | `UPDATE identity_sessions SET recovery_cleared_at = …` ou `SET recovery_used_at = NULL` | `42501` |
| G-7 | `UPDATE identity_mfa_factors SET confirmed_at = …` no fator novo enquanto o antigo está habilitado | `42501`: o `nekkora_platform` não tem privilégio na coluna (§3.3.1); a substituição só passa por `identity_mfa_replace` |
| G-8 | consumir código de recuperação apontando para sessão **sem** `recovery_used_at` | recusado no commit pela restrição adiada |

**Grupo H — fator de identidade: confirmação irreversível e troca exclusiva (§3.3.1)**

Todos como `nekkora_platform`, por SQL direto, salvo onde indicado. Cada cenário também confere que o estado anterior ficou intacto.

| # | Tentativa | Esperado |
|---|---|---|
| H-1 | **primeiro fator, caminho legítimo:** `identity_mfa_enroll_pending` com desafio ativo, depois `identity_mfa_confirm_first` com os hashes | **aceito**; um confirmado, N códigos |
| H-2 | `INSERT` direto em `identity_mfa_factors`, pendente ou já confirmado | `42501` |
| H-3 | `UPDATE … SET confirmed_at = …` direto no fator pendente do primeiro cadastro | `42501`: o primeiro fator também só passa pela função |
| H-4 | `UPDATE … SET confirmed_at = NULL` no fator confirmado | `42501` |
| H-5 | o mesmo de H-4 executado **como `nekkora_identity_owner`** (conexão de teste com o papel) | recusado pela trigger: confirmação não volta a pendente nem para o dono |
| H-6 | como dono: `confirmed_at` valor → outro valor; `disabled_at` valor → nulo | recusado pela trigger |
| H-7 | como dono: `UPDATE` de `identity_id`, segredo cifrado, `key_id`, nonce ou `created_at` | recusado pela trigger, um caso por coluna |
| H-8 | `UPDATE … SET last_step` para valor **menor** | recusado pela trigger |
| H-9 | `DELETE` do fator, como aplicação e como dono | recusado |
| H-10 | identidade com fator confirmado; `identity_mfa_confirm_first` sobre um pendente novo | recusado: não é o primeiro |
| H-11 | identidade cujo único fator confirmado foi desabilitado por `identity_mfa_replace` e substituído; `identity_mfa_confirm_first` sobre outro pendente | recusado: a condição "nunca teve" é estável |
| H-12 | sequência da responsável: anular `confirmed_at` do antigo, depois confirmar um novo | o primeiro passo falha (H-4); o segundo falha por privilégio e pelo índice |
| H-13 | `identity_mfa_replace` sem step-up e sem marca de recuperação | recusado, sem efeito (já em D-3) |
| H-14 | `INSERT` direto em `identity_mfa_recovery_codes` para fator já confirmado | `42501` |
| H-15 | `UPDATE … SET used_at = NULL` num código usado; `invalidated_at` → nulo; `code_hash` alterado | recusado |
| H-16 | `nekkora_platform` tenta `SET ROLE nekkora_identity_owner` | recusado: não é membro |

**Grupo I — criação de organização não anexa conta existente (DC-17.5)**

| # | Tentativa | Esperado |
|---|---|---|
| I-1 | **cenário pedido pela responsável:** na mesma transação, `UPDATE users SET display_name = display_name` numa conta antiga de mesmo e-mail da identidade (o `xmin` passa a ser o da transação), e então tentar anexá-la sem a prova dupla | não há função que aceite o `user_id`; a única que grava vínculo para conta existente é `identity_link_account`, que recusa sem as duas provas. Ao final, `identity_users` não contém a conta antiga |
| I-2 | `identity_create_organization` legítima | cria exatamente uma organização, uma conta, um vínculo interno, um ORG_ADMIN, um evento e uma linha em `identity_users`, cujo `user_id` é o da conta criada e de nenhuma outra |
| I-3 | a conta criada em I-2 recebe `INSERT` em `user_credentials`, `user_mfa_factors` ou `auth_challenges` | recusado pelas triggers da §4.5 |
| I-4 | `identity_create_organization` sem step-up, com identidade não ACTIVE ou sem `email_verified_at` | recusado, sem nenhuma linha criada |
| I-5 | 11ª criação pela mesma identidade | recusada dentro da função, independentemente do Redis |
| I-6 | a antiga assinatura `identity_attach_created_account` | não existe no catálogo |

**Grupo J — auxiliares de leitura falham fechado fora do contexto (§5)**

| # | Chamada como `nekkora_platform` | Esperado |
|---|---|---|
| J-1 | `identity_account_is_linked` sem `nekkora.organization_id` definido | `42501` |
| J-2 | contexto da organização A, `p_organization_id` = B | `42501` |
| J-3 | contexto e parâmetro = A, `p_user_id` de conta da organização B (vinculada **e** não vinculada) | `42501` nos dois casos: nenhuma resposta distingue as duas |
| J-4 | `identity_org_session_allowed` com os mesmos três casos | `42501` |
| J-5 | contexto A, `INSERT` em `auth_challenges` para `user_id` de B | recusado já no lock (linha invisível sob RLS), `23514` |
| J-6 | contexto e parâmetro = A, conta de A | resposta correta, vinculada ou não |

**Grupo K — concorrência pela mesma identidade**

Toda função que recebe sessão ou desafio de identidade toma primeiro `SELECT 1 FROM identities WHERE identity_id = … FOR UPDATE`, e só depois trava a linha da sessão. Ordem global: **identidade → sessão de identidade → organização → conta**. Nenhum caminho toma lock de identidade depois de outro, então não há ciclo entre as funções (§13, item 3: a ordem anterior tinha um).

| # | Intercalamento | Esperado |
|---|---|---|
| K-1 | duas `identity_mfa_confirm_first` em paralelo, sobre dois pendentes da mesma identidade | uma confirma; a outra, ao obter o lock, vê o fator confirmado e é recusada |
| K-2 | duas `identity_mfa_replace` em paralelo pela mesma identidade | serializam; a segunda vê o antigo já desabilitado e o novo já confirmado, e recusa sem efeito |
| K-3 | `identity_mfa_confirm_first` e `identity_mfa_replace` em paralelo | serializam; no fim há exatamente um fator confirmado habilitado |
| K-4 | duas `identity_create_organization` em paralelo no teto (9 existentes) | uma cria a 10ª; a outra é recusada sob o lock |
| K-5 | `identity_link_account` e `identity_create_organization` em paralelo pela mesma identidade | serializam sem impasse (mesma ordem de lock) |
| K-6 | dois consumos do mesmo código de recuperação | um só tem efeito |

**Grupo L — autorização de cada função**

Cada função pública chamada como `nekkora_platform` **sem** a prova que exige, uma linha por exigência. Esperado em todas: zero linhas ou zero efeito, e o estado das tabelas de identidade idêntico antes e depois (comparado por `md5` das linhas).

| Função | Provas retiradas uma a uma |
|---|---|
| `identity_resolve_login` | e-mail inexistente, prefixo, maiúsculas, curinga e nulo: zero linhas; não escreve nada. (A função devolve o `status` de propósito; quem iguala as respostas de PENDING, SUSPENDED e senha errada é a rota, com o `401` genérico.) |
| `identity_organizations`, `identity_org_session_target` | sessão inexistente, revogada, expirada, restrita; organização sem vínculo; vínculo não ACTIVE; organização suspensa |
| `identity_link_account` | sem step-up; step-up vencido; desafio de outro par; desafio consumido, revogado ou vencido; e-mail diferente; organização suspensa |
| `identity_create_organization` | sem step-up; sessão restrita; identidade não ACTIVE; e-mail não verificado; teto atingido |
| `identity_mfa_enroll_pending` | desafio inexistente ou consumido; sessão sem step-up nem marca de recuperação |
| `identity_mfa_confirm_first` | desafio de outra identidade; fator de outra identidade; fator já confirmado; identidade que já teve fator confirmado |
| `identity_mfa_replace` | grupo D, mais fator novo de outra identidade e fator novo já confirmado |

**Grupo M — privilégios efetivos**

Matriz lida do catálogo (`has_table_privilege`, `has_column_privilege`, `has_function_privilege`, `pg_has_role`) para `nekkora_platform`, `nekkora_identity_owner` e `PUBLIC`, comparada com uma matriz esperada **completa** e versionada na suíte: qualquer privilégio a mais ou a menos falha. Complementa as invariantes 3, 4, 7, 8 e 9 da §4.3, que a migration confere, com a lista integral de tabelas, colunas e funções novas e das existentes que a 017 toca.

**Grupo F — instalação limpa e atualização de base populada**

No formato da suíte da 016: a `017` aplicada sobre `001`–`015` limpa, e aplicada sobre uma base com contas, sessões, fatores e desafios **pré-existentes**, provando que nenhuma conta atual é alterada e que nenhuma sessão atual é revogada pela migration em si (ao contrário da 014, que revogou todas de propósito). Acrescido na implementação: `001`–`017` **com** a 016, para a compatibilidade das duas candidatas.

**Mutações.** A suíte precisa reprovar quando o contrato é quebrado. Em M1, a trigger de fechamento perde o `FOR UPDATE`. Em M2, a auxiliar da §4.4 perde a recusa da sessão restrita. As duas são detectadas e depois restauradas, e o catálogo volta idêntico ao da instalação.

---

## 11. Ordem de trabalho

1. ~~Aprovar a direção~~: **concluído em 2026-10-11**, com as condições do cabeçalho.
2. ~~Ratificar esta revisão~~: **liberada pela responsável em 2026-10-11** para desenho final, migration, testes e resultados locais — instalação limpa e atualização de base populada incluídas —, com a condição de trazer também as provas de concorrência por identidade (grupo K), de autorização de cada função (grupo L) e de privilégios efetivos (grupo M). Continuam **fora** desta etapa: Java, aplicação no ambiente preservado, Microsoft 365, commit e push. Histórico da ratificação: a responsável indicou em 2026-10-11 que, resolvidos o lock no caminho obrigatório do banco e a recusa da sessão restrita no banco, a implementação local e a suíte de banco podem avançar, **sem aplicar no ambiente preservado**. Os dois pontos estão na §4.4, na §4.5 camada 3, na DC-17.13 e nos grupos C, C′ e G. Na conferência da §5, a responsável **não ratificou** e apontou dois contornos e dois ajustes, todos incorporados nesta revisão: `xmin` não prova criação (DC-17.5, `identity_create_organization`, grupo I); índices não tornam a troca exclusiva (§3.3.1, imutabilidade e três funções de fator, grupo H); auxiliares conferem o contexto da organização (§5, grupo J); e consistência da §1, DC-17.1, §5, §6 e §9. Os três resíduos documentais seguintes (G-7, alcance da regra de sessão restrita na §5, registro desta liberação) foram corrigidos junto. Nenhuma rodada no ambiente foi pedida nem feita. Ela incorpora ainda: §3.3.1 (convivência e reset do MFA), **§4.4** (contrato da sessão de organização para conta vinculada, que resolve o conflito do achado A-7 com a DC-17.12), **§4.5** (fechamento da entrada local, com as três camadas e os quatro intercalamentos), **§10.1** (cenários de banco dos dois contratos), DC-17.9 a DC-17.13 e as condições em DC-17.1, DC-17.5 e DC-17.8.
3. ~~Migration `017` + suíte de banco~~: **implementadas localmente em 2026-10-11**, suíte verde (relatório `2026-10-11-identidade-017-validacao.md`). **Aprovar a suíte e os ajustes da §13 antes de qualquer código Java**, como foi feito com a 013 e a 014.
4. ~~Funções `SECURITY DEFINER` e papel `nekkora_identity_owner`~~: entraram na própria `017`, com os testes negativos de contexto (grupo J) e de privilégio de I-1 (grupos H e M).
5. Camada Java: `IdentityRepository`, `IdentityAuthService`, `IdentityFilter`, contextos novos de CSRF, limitadores R8–R12.
6. Rotas de cadastro, entrada geral, `/me/organizations`, criação de organização e troca de sessão.
7. OpenAPI `1.1.0` e testes HTTP.
8. Ligar `nekkora.cadastro.enabled` **somente** depois da Missão 2 validada (DC-17.7).

**Telas ficam para missão posterior**, como a missão determina. Esta proposta não presume nenhuma decisão de interface além dos destinos `/o/{org}/login` e `/organizacoes` já citados pela responsável.

---

## 12. Riscos e limites declarados

- **A reversão de §3.1 é real.** A proposta a contém ao máximo — resolução global só sobre tabelas de identidade, visibilidade entre organizações só por função de dono próprio limitada à identidade — mas é uma ampliação de superfície e precisa ser aprovada com esse nome.
- **Duas senhas pela mesma pessoa.** Enquanto a ligação explícita não existir, quem tem conta antiga e identidade nova pode ter duas senhas distintas. É consequência direta de "não unir automaticamente" e termina na missão de ligação.
- **Cadastro público é superfície nova de abuso.** Os limitadores da §7 são configuração **inicial**, no mesmo espírito da 8.6, e precisam de calibração com tráfego real. Sem CAPTCHA ou prova de trabalho, R9 e R12 são a única barreira contra criação em massa de identidades; se isso não bastar, a decisão de adicionar um desafio é própria.
- **Enumeração por tempo.** Piso de resposta e hash fictício reduzem, não eliminam. O mesmo limite já está declarado na §3.4 do relatório de preparação.
- **Roubo de sessão continua sendo risco.** A DC-17.13 elimina o caso em que a sessão sozinha basta para substituir o segundo fator. Ela **não** elimina o roubo: sessão furtada dentro da janela de step-up, ou de quem consiga produzir um step-up em seguida, ainda é risco. A redução vem da janela curta, da revogação das demais sessões na troca e do aviso ao titular. Uma afirmação forte demais sobre isso foi corrigida na DC-17.13.
- **Entre T0 e T1 da recuperação, o conjunto antigo de códigos ainda vale** (DC-17.13). Troca deliberada: invalidá-los em T0 trancaria fora quem desistisse no meio do fluxo.
- **Perder senha e códigos de recuperação não tem saída nesta missão** (§3.3.1). É consequência direta de proibir reset administrativo do MFA de identidade, que é o que impede um administrador de organização de alcançar o acesso da pessoa às outras. A saída, se necessária, é missão própria no formato da 016. Fica declarada, não escondida.
- **DC-17.9 a DC-17.12 precisam valer antes do primeiro vínculo.** Nenhuma tem efeito hoje, porque esta missão não vincula ninguém. Mas a missão de vinculação não pode começar sem elas, sob pena de abrir a porta dupla que a §3.3.1 fecha. A `017` já carrega `identity_link_challenges` por isso: o desafio de vinculação precisa existir no banco antes do fluxo que o usa.
- **A tela de duas portas da DC-17.10 é temporária e precisa de prazo.** Sem prazo, ela se torna permanente e a ambiguidade entre as duas entradas passa a ser o estado normal do produto. O prazo é a conclusão da missão de vinculação.
- **Desvincular não está definido, de propósito.** Reabilitar um fator desabilitado e reabrir a porta por organização reintroduz exatamente a porta dupla que a DC-17.12 fecha. Se a reversão for necessária, é decisão própria (DC-17.12).
- **O lock dentro das triggers serializa, por conta, as escritas locais que hoje já serializam pela aplicação.** Não há perda de concorrência nos caminhos existentes, que já tomam o mesmo lock antes. Um caminho que hoje não o tomasse passa a esperar, o que é o efeito pretendido.
- **As auxiliares de leitura confiam no contexto de organização que a aplicação define**, como toda a RLS da 012. Elas não respondem sobre conta fora desse contexto, mas também não são mais fortes que ele: se o papel da aplicação definir o contexto da organização B, ele vê de B o que a RLS já mostraria. Não acrescentam superfície; não removem a que a 012 já declarou.
- **As funções de fator não verificam o TOTP.** Elas provam autorização e transição; a correção do código continua na aplicação, como na 014. Uma aplicação comprometida pode confirmar um fator cujo código não conferiu, mas só dentro de uma transição que o banco autorizou: primeiro fator de identidade que nunca teve um, ou troca com step-up ou recuperação dentro da janela.
- **`016` continua candidata.** A `017` não depende dela, mas a ordem de aplicação em base persistente precisa ser decidida junto com o gate da 016. A instalação conjunta 001–017 passou com as invariantes das duas.
- **A criação de organização por cadastro é um segundo caminho de criação** (§13, item 5). A 012 declara `provision_organization` como único caminho. A `017` acrescenta `identity_create_organization`, que insere a organização diretamente e reaproveita `provision_resource_limit` e `provision_first_org_admin` da 012.
- **Escrita em `user_sessions` sem contexto de organização falha agora, inclusive para superusuário** (§13, item 7). Fixtures e manutenção que inseriam sessão como superusuário sem `nekkora.organization_id` passam a ser recusadas. Isso pode afetar bases de teste Java, que estão fora desta etapa.

---

## 13. Ajustes surgidos na implementação (2026-10-11)

A implementação da `017` e da suíte expôs pontos que o desenho não tinha. Alguns são correções minhas e outros são decisões novas. Todos estão no código e nos testes, e os que são decisão estão marcados para conferência.

| # | Ponto | Tipo | O que foi feito |
|---|---|---|---|
| 1 | A contagem de tabelas de tenant é **43**, não 39 | correção do desenho | A 014 acrescentou 4. A invariante 1 confere 43 e que nenhuma tabela nova tem `organization_id`. |
| 2 | `identity_events.organization_id` tornaria a tabela "de tenant" pela convenção da 012 | correção do desenho | Renomeada para `target_organization_id`. |
| 3 | **Impasse** entre duas trocas de fator concorrentes da mesma pessoa, cada uma com uma sessão diferente | **defeito encontrado e corrigido** | A ordem era sessão → identidade, e `identity_mfa_replace` revoga as **demais** sessões depois de travar a identidade. Passou a ser identidade → sessão de identidade → organização → conta. K-2 e K-3 exercitam exatamente esse caso. |
| 4 | As triggers da camada 2 recusariam também o próprio fechamento da DC-17.12, e o reset da 016 em conta vinculada | **aceito pela responsável com condição; R1 estrita** | Na R0, a exceção deixava mudar qualquer coluna junto com a transição terminal. **Falha confirmada.** Na R1, a exceção libera **só** as colunas terminais que estavam nulas e a limpeza prevista (`credential_hash` e `pending_password_hash` de `auth_challenges`, só para nulo). Qualquer outra coluna alterada junto é **recusada** (`23514`), em conta vinculada ou não. Ver §13.1. |
| 5 | Criação de organização sem afrouxar o bootstrap da 012 | **decisão, para conferência** | A `identity_create_organization` insere a organização, a conta e o vínculo interno, e chama `provision_resource_limit` e `provision_first_org_admin` com `EXECUTE` novo para o dono. A trigger de bootstrap da 012 fica intacta: o `current_user` dentro dela continua sendo `nekkora_provisioning_owner`. |
| 5a | **Limites da organização criada por cadastro** | **decisão de produto, para conferência** | São os mesmos do bootstrap da organização interna: recursos 1–3 finitos em zero e 4–5 sem limiar. Ou seja, a organização nasce sem capacidade de execução até a operação provisionar. Isso é provisório. |
| 6 | O teto de 10 organizações contava `identity_provisioning_events`, que não tem identidade | correção do desenho | Passou a contar `identity_users` com `link_channel = 'cadastro_web'`, sob o lock da identidade. K-4 prova a concorrência no teto. |
| 7 | A trigger de `user_sessions` agora exige conta visível no contexto | consequência declarada | Superusuário sem `nekkora.organization_id` deixa de conseguir criar sessão (`23514`). Falha fechada, e pode afetar fixtures Java. |
| 8 | **Marca de step-up ou de recuperação no futuro** estenderia a janela da DC-17.13 para sempre | **defeito encontrado e corrigido** | A Platform grava `mfa_step_up_at` e `recovery_used_at`. O banco agora recusa valor futuro; valor retroativo só encurta a janela e continua aceito (D-9). |
| 9 | **Privilégios da Platform nas tabelas de identidade** não estavam listados no desenho | **NÃO ratificado pela responsável; ver §13.2** | A Platform tem `SELECT` nas tabelas de identidade, que não têm dado de organização; isso inclui `identity_credentials`, como já tem em `user_credentials`. A escrita é por coluna, só onde o fluxo da §6 exige. Não há nada em `identity_users`. No fator e nos códigos, só o passo TOTP e o consumo. A matriz completa está em `m_esperado.txt` (grupo M). `identity_resolve_login` continua sendo a consulta de login, mas não acrescenta isolamento a essa leitura. |
| 10 | `e-mail` da identidade imutável | decisão | Sem isso, trocar o e-mail permitiria fabricar a igualdade da DC-17.11, guarda 1. Troca de e-mail fica para missão própria. |
| 11 | O primeiro fator, pelo desafio de cadastro, só é permitido a identidade que nunca confirmou fator; o pendente anterior é descartado (desabilitado) pela função | precisão | Fecha o atalho de criar pendente pelo desafio depois de já ter fator. |
| 12 | `elevacao` | consequência declarada | A `017` exige SUPERUSER na janela. O `db/bootstrap/aplicar_como_migrador.sh` local passou a incluí-la no padrão. `ops/homologacao/implantacao/elevacao.txt` **não** foi alterado: isso é da etapa de ambiente. |

### 13.1 R1: correções depois da revisão do pacote

| # | Falha (R0) | Correção (R1) | Prova |
|---|---|---|---|
| R1-1 | `user_credentials.user_id` podia mudar sem passar pela conferência: dava para mover uma credencial para uma conta vinculada sem credencial | Trigger `prevent_user_credential_reassignment`: `user_id` e `created_at` imutáveis, para **toda** conta | N-8 e N-9 |
| R1-2 | `password_reset_tokens` e `email_verification_tokens` não tinham imutabilidade desde a 002; a exceção terminal deixava trocar dono, hash ou prazo junto com o consumo ou a revogação | Trigger `prevent_local_token_change`: só `consumed_at` e `revoked_at` mudam, de nulo para preenchido, uma vez. Dono, hash, prazo, e-mail alvo e criação são imutáveis, e um token terminal não muda mais. Vale para toda conta | N-1 a N-7 |
| R1-3 | A exceção terminal da camada 2 liberava a linha inteira | A exceção passou a ser estrita, como no item 4 acima. Campo protegido junto é **recusado**, e não encaminhado à conferência do vínculo | N-10 a N-13 |
| R1-4 | **Desafio validado antes da espera pelo lock** em `identity_mfa_enroll_pending` e `identity_mfa_confirm_first`, sem reconferência depois | Função interna `identity_challenge_resolve`. A leitura sem lock só descobre a identidade. Depois do lock da identidade, o desafio é **reconferido** com `clock_timestamp()` e a linha dele fica **travada** até o fim da transação, então uma invalidação concorrente da aplicação espera. `identity_mfa_confirm_first` **consome** o desafio na mesma transação | R-1 a R-4, com sincronismo explícito e espera de lock observada. A mutação M3 aplica o corpo da R0 e R-2 reprova |

**Controles positivos (R1).** Continuam aceitas:
- todas as escritas no formato do Java atual (N-P): revogar e consumir token, consumir e invalidar desafio com limpeza dos hashes, incrementar tentativas, trocar o segredo de um fator pendente, confirmar fator, consumir passo e código, trocar senha;
- a vinculação de uma conta com estado vivo (N-V, além da fixture e do grupo C);
- com a 016 instalada, o bloco de efeitos do reset operacional. Ele é extraído do arquivo da 016 e executado com o papel `nekkora_ops_mfa_owner`.

**Ressalva de autenticidade, registrada a pedido da responsável.** A Platform escreve `mfa_step_up_at` e `recovery_used_at`, e as funções de fator aceitam que o TOTP foi validado pela aplicação. O banco **limita a janela**: recusa marca no futuro, mede a janela com o próprio relógio e exige a ordem dos locks. Mas ele **não comprova** que o TOTP ou o código de recuperação foram de fato validados. Uma aplicação comprometida grava "agora" e a marca vale. Recusar data futura impede só a extensão indefinida da janela, não a fabricação da prova.

**Regra da 012.** A 012 continua sem alteração. A mudança da regra "único caminho de criação de organização" está registrada no cabeçalho da `017`, na §12 deste desenho e no item 5 acima.

### 13.2 Leitura das tabelas de identidade: justificativa por tabela e alternativa mínima

**Situação na R1, que não foi alterada à espera de decisão.** O `nekkora_platform` tem `SELECT` de tabela inteira em oito tabelas de identidade (a matriz completa está em `m_esperado.txt`). Isso é **leitura global**: sem nenhum contexto, o papel do banco lista todas as identidades, os e-mails, os hashes de senha, os segredos cifrados de TOTP, os desafios e as sessões. A responsável tem razão: isso **não** equivale a `user_credentials`, que fica sob a RLS por organização.

**`identity_resolve_login` não impede enumeração pelo papel do banco.** Com o `SELECT` atual, o papel lista tudo sem precisar dela. Mesmo sem o `SELECT`, o papel ainda pode testar e-mails um a um pela função, na velocidade do banco. A função limita a **forma** da resposta, não o alcance de quem tem `EXECUTE`.

| Tabela | O que a aplicação precisa ler (fluxos da §6) | Quando a identidade é conhecida | Acesso mínimo necessário |
|---|---|---|---|
| `identities` | cadastro: o e-mail existe e em que estado; confirmação: a identidade do token; telas autenticadas: a própria | no cadastro, **não**; depois do login, sim | função de cadastro por e-mail exato, que devolve só a ação a tomar; o resto, só a própria linha |
| `identity_credentials` | `password_hash` no login; troca de senha | login: não (vem de `identity_resolve_login`); troca: sim | **nenhum `SELECT` direto**: o hash já vem da função de login; a troca, só na própria linha |
| `identity_mfa_factors` | segredo cifrado para verificar o TOTP; estado do fator | sim: depois de desafio ou sessão | só a própria linha |
| `identity_mfa_recovery_codes` | localizar o código pelo hash para consumir | sim | só a própria linha |
| `identity_auth_challenges` | localizar pelo hash do token do cookie | **não**: o token é o que identifica | função por hash exato do token, ou contexto definido a partir dela |
| `identity_sessions` | localizar pelo hash do token, a cada requisição (`IdentityFilter`) | **não** | função por hash exato do token (padrão de `portal_resolve_session`, 013) |
| `identity_link_challenges` | etapa 1 grava; contador de tentativas | sim | só a própria linha; o consumo já é da função |
| `identity_email_tokens` | confirmação de cadastro pelo hash do token | **não** | função por hash exato |
| `identity_events` | — | — | só `INSERT`, como hoje |

**Alternativa mínima recomendada (A), no modelo da 012:**
- RLS nas tabelas de identidade com a política `identity_id = NULLIF(current_setting('nekkora.identity_id', true), '')::uuid` para o `nekkora_platform`.
- As quatro leituras de antes da identificação (e-mail no cadastro e no login; hash de token de desafio, sessão e e-mail) passam por funções `SECURITY DEFINER` que devolvem só a linha do valor exato.
- Sem contexto, o papel não vê nada; com contexto, só aquela identidade.
- A força é a mesma da RLS por organização, porque o contexto é definido pela aplicação. Ela não protege contra uma aplicação comprometida, mas tira a listagem global e o acesso por descuido.
- `identity_credentials` fica sem nenhum `SELECT`.

**Alternativa (B), mais estrita.** Nenhum privilégio de tabela; tudo por função, como o papel do portal na 013. Mais funções e mais superfície de contrato. Fica registrada, mas não é a recomendada.

**Decisão da responsável (2026-10-11): alternativa A**, mantendo explícito que o contexto de identidade é definido pela aplicação. Implementada na R2 (§13.3).

### 13.3 R2: validade da prova depois de esperas e alternativa A

**Intervalo de validade (achado da responsável na R1).** Em `identity_mfa_confirm_first`, a hora e a prova eram conferidas antes da espera pelo lock do fator, e o consumo não reconferia o prazo. A revisão das outras funções encontrou o mesmo padrão em mais quatro lugares:
- no cadastro de pendente, esperando o lock do pendente anterior;
- na troca de fator, esperando o fator novo, os códigos vivos e as demais sessões;
- na vinculação, esperando as linhas do fechamento;
- na criação de organização.

A regra comum às cinco funções: **todos** os locks primeiro, inclusive os das linhas que serão alteradas; **depois** a hora, com `clock_timestamp()`; a prova reconferida com ela antes de qualquer efeito. Depois da hora medida, nenhuma escrita espera lock. O consumo condicionado como primeira escrita existe **só** onde há desafio a consumir (texto corrigido a pedido da responsável):

| Função | Prova | O que acontece depois dos locks e da hora medida |
|---|---|---|
| `identity_mfa_confirm_first` | desafio de cadastro | **a primeira escrita é o consumo do desafio**, condicionado a `expires_at > v_t` e com `ROW_COUNT = 1`. Sem esse consumo, nenhum efeito |
| `identity_link_account` | desafio de vinculação, e sessão com step-up | a sessão é reconferida; **a primeira escrita é o consumo do desafio**, condicionado a `expires_at > v_t` e à credencial vigente, com `ROW_COUNT = 1`. Sem esse consumo, nenhum efeito |
| `identity_mfa_enroll_pending` | desafio de cadastro **ou** sessão | **sem consumo**: a validade do desafio, ou a autorização da sessão pela DC-17.13, é reconferida com `v_t` **antes** dos efeitos. Se falhar, nenhum efeito |
| `identity_mfa_replace` | sessão (DC-17.13) | **sem consumo**: a autorização é reconferida com `v_t` antes dos efeitos |
| `identity_create_organization` | sessão com step-up | **sem consumo**: a autorização é reconferida antes dos efeitos |

**Alternativa A (escolhida pela responsável).**

| Condição da responsável | Como ficou |
|---|---|
| Sem contexto, nenhuma leitura direta; com contexto, só as linhas daquela identidade, inclusive nas tabelas filhas | RLS `ENABLE + FORCE` nas dez tabelas de identidade. Política `identity_request_context` para o `nekkora_platform` em nove delas (`identity_id = nekkora.identity_id`), e nenhuma em `identity_users`, que segue sem privilégio algum |
| `identity_credentials` sem `SELECT` direto | sem `SELECT` e sem `UPDATE`. Só `INSERT (identity_id, password_hash)`, e na própria identidade. O hash sai só por `identity_resolve_login`. Troca de senha e re-hash de identidade ficam para uma função própria, em missão posterior |
| Funções anteriores à autenticação: chave exata, só os campos necessários, sem listagem, prefixo ou contagem | `identity_signup_state(e-mail)` → `(identity_id, status)`; `identity_challenge_lookup(token)` → `(challenge_id, identity_id)`; `identity_session_lookup(token)` → `(identity_session_id, identity_id)`; `identity_email_token_lookup(token)` → `(token_id, identity_id)`. As de token só devolvem linha viva e no prazo; o formato de cada uma é conferido no catálogo (P-7) |
| A escrita também respeita o isolamento | a mesma política é `FOR ALL` com `WITH CHECK`. Inserir para outra identidade é recusado (`42501`), e `UPDATE` em linha de outra identidade afeta zero linhas (P-1, P-3) |
| Testes de contexto ausente, identidade diferente e reuso da conexão | grupo P: P-1, P-2/P-3 e P-4 (troca A → B → A na mesma conexão; `SET LOCAL` não vaza depois do `COMMIT`) |
| Matriz com privilégios efetivos, inclusive herdados | o grupo M usa `has_*_privilege`, que inclui herança e `PUBLIC`. Agora também confere RLS e o texto de cada política nas 22 tabelas |

**O dono das funções** tem a política `identity_function_scope`, sem filtro, nas dez tabelas. Ele é `NOLOGIN` e sem membros, e só é alcançável por dentro das funções `SECURITY DEFINER`. A fronteira dele é o código dessas funções, que recebem chave exata ou prova já validada. A conferência adiada do código de recuperação passou a ser do dono, porque roda no commit, quando o contexto pode já ser outro.

**Ressalvas, mantidas a pedido da responsável:**
- **A RLS limita o acesso ao contexto, mas não prova que a aplicação autenticou aquela pessoa.** O contexto é definido pela aplicação, como na RLS por organização da 012.
- **As funções de consulta exata não impedem sondagens repetidas** por um papel de banco comprometido, que pode testar e-mails ou tokens um a um. Elas limitam a forma da resposta, não o número de perguntas.
- **A autenticidade do step-up** continua dependendo da aplicação (§13.1).

**Capacidade de execução.** O valor zero da suíte segue **provisório**, não é decisão de produto. A recomendação da responsável, ainda pendente de decisão, é cadastro imediato, execução dependente de ativação explícita e essa condição visível na web.

### 13.4 R3: senha da identidade, sessão só por função e parte D

| Ponto | Contrato, conforme a revisão da responsável |
|---|---|
| **Conclusão do login** | `identity_login_complete` aceita **só** desafio de finalidade 1. Confere **sempre** a credencial vigente; se ela mudou, devolve `SENHA_OBSOLETA`, invalida o desafio e não cria sessão, mesmo com o MFA correto. Não há tratamento de equivalência para re-hash concorrente: hash diferente não prova senha igual, e o banco não verifica Argon2. Consumo, re-hash, uso do fator ou do código e **criação da sessão** acontecem no mesmo comando, e qualquer erro desfaz tudo |
| **Confirmação inicial** | `identity_mfa_confirm_first` (finalidade 2) segue o mesmo contrato de credencial, re-hash e sessão, e passa a devolver `(status, identity_session_id)` |
| **Troca de senha** | `identity_password_change`: step-up de até 5 minutos e comparar e trocar sobre o hash esperado. No mesmo comando, invalida **todos os desafios de autenticação pendentes**, com limpeza dos hashes, e revoga as demais sessões. Nunca devolve o hash |
| **Locks** | nas três funções, credencial, código, sessões e desafios que serão alterados são travados **antes** da hora medida (V-5 e V-6 exercitam a espera) |
| **Privilégios** | a sessão de identidade **só nasce por função**. A Platform perdeu `INSERT` em `identity_sessions`, `UPDATE (consumed_at)` nos desafios e `UPDATE` nos códigos de recuperação. Ficam com ela o step-up, `last_seen_at`, a revogação, as tentativas e a invalidação |
| **Ordem 016 → 017** | provada na parte D: base populada com a 016 e massa da 016 pelos caminhos reais; a 017 não altera dados de `public` nem de `ops`; os atos da 016 seguem depois dela, inclusive a execução do reset através das triggers da 017 |

**Limite declarado:** o banco limita janelas e transições, mas não verifica Argon2 nem TOTP. A autenticidade dessas provas continua dependendo da aplicação (§13.1 e §13.3).


### 13.5 R4: elegibilidade garantida no banco na emissão da sessão de organização

**Decisão da responsável (2026-10-13):** a elegibilidade da conta, da organização e do vínculo interno fica **garantida no banco** no momento da emissão. A resolução inicial (`identity_org_session_target`) continua só indicando uma candidata.

**Lacuna da R3.** A trigger de nascimento da sessão reconferia o vínculo com a identidade, a sessão de identidade e a identidade. As cinco condições a seguir **só** eram lidas na resolução inicial:
- `users.account_type = 1`;
- `users.status = 2`;
- `email_verified_at` presente;
- `organizations.status = 1`;
- `organization_memberships.status = 2`.

**O que mudou na 017.** Só duas funções:

| Peça | Mudança |
|---|---|
| `enforce_user_session_mfa_on_insert` | no ramo 2 (`identity_session_id` preenchido), trava a **organização `FOR SHARE` antes da conta** (`FOR UPDATE`, como já fazia) |
| `identity_org_session_allowed` | o ramo 2 exige também as cinco condições acima, lidas **depois** dos locks da trigger. Cada comando lê o estado confirmado mais recente, em `READ COMMITTED` |

**Ordenação entre emissão e desativação.** Segue a ordem global organização → conta (inventário de locks, §2 e §5; 017: identidade → sessão → organização → conta):
- **Quem desativa uma conta ou um vínculo trava a organização `FOR UPDATE`.** Pela disciplina da 003 e por `AdminService.suspend`, isso acontece antes da escrita. Mesmo fora dessa disciplina, acontece ao fim do comando, pela trigger de presença de `ORG_ADMIN` da 003.
- **Quem desativa a organização escreve a própria linha.** Isso conflita com `FOR SHARE`.
- **Os dois lados se serializam na organização:**
  - desativação confirmada antes: a emissão espera, reconfere e recusa;
  - emissão confirmada antes: a desativação espera e encontra a sessão já criada, que `suspend` revoga.
- **A linha do vínculo não é travada pela emissão.** Quem desativa o vínculo segura essa linha enquanto espera a organização, e travá-la formaria ciclo.
- **Desativação fora da disciplina** (`UPDATE users` sem o lock prévio da organização): pela análise, o ciclo possível termina em `40P01` e **falha fechado**. Esse caso **não foi exercitado** na suíte. A disciplina da 003 continua sendo a regra.

**Ramo 1 (conta não vinculada): inalterado.** Não ganha condição nova nem lock novo. Com a conta suspensa, o vínculo inativo ou a organização inativa, o `INSERT` continua aceito pelo banco, como antes. A elegibilidade dessas contas continua sendo conferida pela aplicação (Q-5, Q-C7).

**Testes (grupo Q da suíte da 017, todos com o papel real `nekkora_platform`):**

| Cenário | Situação | Resultado |
|---|---|---|
| Q-0 | controle positivo | aceito |
| Q-1 a Q-4 | conta suspensa, e-mail não verificado, vínculo inativo e organização inativa (O4, dedicada), cada um **depois** da resolução inicial | recusados com o 23514 uniforme. Depois da reativação, aceitos (controle positivo de cada causa) |
| Q-C1 a Q-C3 | desativação **confirmada durante a espera** da emissão | espera de T2 observada em `pg_stat_activity`, recusa e estado final conferido |
| Q-C4 a Q-C6 | emissão confirmada antes; desativação esperando | a desativação espera e encontra a sessão: revogada por `suspend` e pela desativação do vínculo; existente quando a organização é desativada |
| Q-C7 / Q-C8 | organização travada `FOR UPDATE` por outra transação | o ramo 1 não espera; o ramo 2 espera (interrompido por `lock_timeout`) |
| M8 | mutação: trigger sem o lock da organização | **detectada**: a desativação do vínculo não espera, e a sessão emitida fica ativa com o vínculo inativo |
| M9 | mutação: auxiliar sem a reconferência | **detectada**: conta suspensa depois da resolução recebe sessão |

**R4b: isolamento (revisão da responsável, 2026-10-13).** Em REPEATABLE READ e SERIALIZABLE, o lock **não** atualiza o snapshot. O cenário:
1. T1 fixa o snapshot com o vínculo ativo.
2. T2 trava a organização, desativa o vínculo e confirma.
3. T1 emite.

A organização foi só travada, não alterada, e a conta não mudou. Por isso o lock de T1 não provoca `40001`, e a reconferência lê o vínculo antigo. **Reproduzido na R4:** vínculo inativo e sessão emitida ativa.

**Correção:** no ramo 2, a trigger **exige READ COMMITTED** antes dos locks. A recusa usa `25N01`, o mesmo código de `identity_require_read_committed`. O ramo 1 continua aceito em qualquer isolamento.

| Cenário | Situação | Resultado |
|---|---|---|
| Q-RR1 | o cenário acima, em REPEATABLE READ | recusa `25N01`; sem sessão |
| Q-RR2 / Q-RR3 | SERIALIZABLE, e REPEATABLE READ sem mudança concorrente | recusa `25N01` |
| Q-RC1 | o mesmo cenário em READ COMMITTED | a reconferência vê o vínculo desativado e recusa (`23514`) |
| Q-RC2 | READ COMMITTED, conta elegível | aceito |
| Q-R1 | ramo 1 em REPEATABLE READ e em SERIALIZABLE | aceito, como antes |
| M10 | mutação: trigger sem a exigência | **detectada**: REPEATABLE READ emite com o snapshot antigo |

**Requisitos para a porta (etapa das rotas):**
1. **Ordem global da 017 na transação da troca: identidade → sessão de identidade → organização `FOR SHARE` → conta.** A identidade e a sessão de identidade são travadas (`FOR UPDATE`, pela própria `identity_org_session_target`, que resolve a candidata de novo) **antes** de qualquer linha da organização. Só então vem a organização `FOR SHARE`, antes das sessões da conta: `SessionRepository.create` revoga as sessões excedentes (`UPDATE user_sessions`) **antes** do `INSERT`, e sem o lock prévio da organização a ordem seria sessões → organização, o inverso de `suspend` (organização → conta → sessões).
   > **Correção de 2026-10-14 (bloco 3, revisão 2), apontada pela responsável.** A redação anterior dizia "organização `FOR SHARE` antes de qualquer outra coisa". **Estava errada:** contrariava a ordem global da 017 e ignorava os locks implícitos das chaves estrangeiras. O `INSERT` da sessão (`user_sessions.identity_session_id`) e o do evento (`identity_events.identity_id`) pedem `FOR KEY SHARE` na sessão de identidade e na identidade. Pedidos **depois** da organização, formam ciclo com a vinculação, que trava identidade e sessão e depois espera a organização `FOR UPDATE`. Ciclo reproduzido na revisão 1 (`40P01`), com outra sessão da mesma identidade e com a mesma sessão.
2. **Conta `FOR UPDATE` depois da organização e ANTES da política de limite.** A ordem completa é organização → conta → conferência e revogação das sessões excedentes → `INSERT`, na mesma transação. O lock da trigger continua como defesa, mas não substitui o lock anterior à política de limite.
   > **Correção de 2026-10-13 (bloco 3, revisão 1), apontada pela responsável.** A redação anterior dizia "o lock da conta fica com a trigger, ou é tomado depois da organização". **Estava incompleta.** Duas emissões da mesma conta seguram juntas o lock compartilhado da organização. Se o lock da conta vier só da trigger, as duas contam as sessões antes de qualquer uma travar a conta. Com nove sessões e teto de dez, a segunda cria a décima primeira sem recalcular o limite. A corrida foi reproduzida na primeira versão do bloco 3: 11 sessões ativas.
3. `SessionRepository.create` não grava `identity_session_id`. A porta precisa de uma variante que grave.
4. A recusa da trigger (`23514`) vira o `sem_acesso` uniforme da DC-17.6.
5. A transação da troca roda em **READ COMMITTED**. Em outro isolamento, a emissão vinculada é recusada pelo banco (`25N01`, R4b).
6. **Emissão e evento no mesmo commit** (acrescentado em 2026-10-13, bloco 3, revisão 1). A sessão emitida e o registro dela, ou o registro da recusa, são confirmados juntos, ou nada é confirmado. A transação da emissão tem os dois contextos, o da organização e o da identidade.
7. **Locks implícitos das chaves estrangeiras contam na ordem** (acrescentado em 2026-10-14, bloco 3, revisão 2). Toda escrita que referencia `identities` ou `identity_sessions` (eventos, sessões vinculadas, desafios) pede `FOR KEY SHARE` nessas linhas. Quem já segura uma linha posterior na ordem tem de ter travado a identidade e a sessão de identidade **antes**. Isso vale também fora da troca: o logout da identidade revogava a sessão e depois gravava o evento (sessão → identidade). Corrigido para travar a identidade antes da revogação. Ciclo reproduzido com a troca (`40P01`).

### 13.6 R5: eventos de step-up e consulta de criação

**Decisão da responsável (2026-10-14),** no parecer sobre os contratos de step-up, criação e vinculação (`2026-10-13-contratos-step-up-criacao-vinculacao.md`). A R5 é a alteração mínima de banco que esses contratos pedem:

| Peça | Mudança |
|---|---|
| `identity_events.event_type` | `CHECK (event_type BETWEEN 1 AND 14)`: 13 = `STEP_UP` (P1), 14 = `ORG_STEP_UP` (P4) |
| `identity_org_creation_allowed(p_session_token)` | função nova, `STABLE`, `SECURITY DEFINER`, sem lock, `EXECUTE` só para a Platform. Verdadeiro se a sessão está ativa, no prazo, com MFA e **não restrita**, a identidade ACTIVE e verificada e há menos de 10 vínculos `cadastro_web` |

**`canCreateOrganization` é só indicação para a interface.** A criação continua reconferindo sessão, step-up, identidade e teto **dentro** de `identity_create_organization`, sob o lock da identidade, qualquer que seja o valor mostrado. A função entra na matriz de privilégios (grupo M) e na invariante 3, com testes de sessão expirada, revogada e restrita (grupo T) e duas mutações (M11, M12).

Nada mais muda: nenhum privilégio de tabela, trigger ou função existente. A capacidade inicial de execução continua decisão de produto pendente.
