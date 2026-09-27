# Recuperação emergencial de MFA — proposta de contrato

**Data:** 2026-09-27 — **revisão 3 consolidada**, com os seis ajustes da segunda revisão e as precisões da terceira (confirmação do aviso, D9 como pendência real, fórmula dos prazos, locks e reconciliação). **Direção aprovada** (papel individual por operador, certificado e autoria por `session_user`), condicionada a estes ajustes. **Ainda sem migration nem implementação.**
**Base:** `origin/main` em `72057e7`, branch `feat/mfa-emergencia`. Não incorpora o provider de e-mail pendente.
**Contrato vigente:** `2026-09-26-decisoes-8-6-8-7-8-10.md`, §3.4. Esta proposta detalha o *break-glass* previsto ali e substitui a execução "pela identidade de migration" por uma identidade operacional dedicada.
**Dependências apenas propostas, não disponíveis:** CA operacional, IdP corporativo para operadores, cadastro operacional e cadastro de contatos autorizados.

**O que mudou na revisão 3:**
1. **Credencial e autorização:**
   - "sem senha de banco; credencial **temporária** por certificado";
   - validade e revogação da **autorização operacional** conferidas **em cada ato**, inclusive depois da espera por locks, pelo emissor e serial que o **servidor** lê da própria conexão;
   - conexão aberta além da validade é tratada;
   - a emissão exige MFA **recente**.
2. **Resultado desconhecido** depois da perda da resposta: a CLI consulta o estado ao reconectar e nunca presume rollback. O registro separado distingue "informado pela CLI" de "confirmado pelo banco".
3. **Concorrência com habilitações:**
   - trava compartilhada com os caminhos que alteram operadores, habilitações, vínculos e certificados, em ordem determinística;
   - reconferência dos vínculos impeditivos de **todos** os participantes.
4. **Prazo total de 72 h** desde a abertura, sem prorrogação. Execução antes do **menor** entre o prazo total e a aprovação final + 60 min. A espera de 24 h cabe nessa janela. "Notificação confirmada" definida.
5. **Vínculos estruturais compostos:** solicitação ↔ organização ↔ conta alvo ↔ fator aprovado ↔ evento.
6. **Pessoas e limites:**
   - regra administrativa de uma identidade operacional por pessoa;
   - correção da regra de distinção: o verificador **pode** ser o executor, e o aprovador é distinto de ambos;
   - limites que não permitem bloquear a vítima com pedidos não verificados.
7. **D2 e D6 aprovadas como política da v1**, com as precisões: organização sem dois contatos elegíveis não tem acesso ao procedimento, e os limites executados são conferidos atomicamente. **D9 continua pendente** como gate de produção (§12).
8. **Confirmação do aviso** com contrato próprio (§6.1). A mudança de "titular e administradores" para "só o titular" é explícita.
9. **Prazos por fórmula** (§6); **locks** descritos pela ordem explícita, com as dependências de FK e triggers conferidas na implementação (§7); **reconciliação** que espera a mesma trava da solicitação (§7).
10. **Conferência final:**
    - validade própria do token de contestação, até `T_total` e só enquanto a solicitação não for terminal;
    - a primeira confirmação válida fixa o início da espera;
    - critérios de recusa consolidados no §5, sem referência circular.

**Princípios:**
- A recuperação de MFA **não troca senha, não concede acesso direto e não amplia papéis, vínculos nem concessões**.
- Ela invalida o que existia e obriga um novo cadastro de MFA antes de qualquer acesso normal.
- Perda de senha é um cenário separado.

## 1. O que já existe (lido no código e nas migrations da `main`)

| Elemento | Onde | Comportamento |
|---|---|---|
| Permissão `identity.mfa.reset` | 014 §1 | Só `ORG_ADMIN` |
| Reset administrativo | `AdminService.resetMfa` | Sessão válida, conta interna com a permissão, **alvo ≠ ator**, *step-up* **TOTP** ≤ 5 min na mesma sessão, lock da **conta**. Na mesma transação: desafios, fator e códigos invalidados, **todas** as sessões revogadas e `mfa_events` `RESET_BY_ADMIN`. Aviso por e-mail depois do commit. **Sem motivo** |
| Escopo por organização | RLS da 012 + lock da conta | Um alvo de outra organização resulta em `TARGET_NOT_FOUND` |
| Fator, códigos, desafios | 014 §2–§4 | Fator com `disabled_at` final; códigos com hash e uso único; desafios de 5 min e 5 tentativas |
| Sessão exige MFA | 014 §6 | Trigger: sessão só com o marcador e um fator **confirmado e ativo** |
| Recadastro | `AuthService` | Sem fator confirmado, o login devolve `mfa_enrollment_required`. **Não há troca de fator** para quem tem um confirmado |
| Auditoria de MFA | 014 §5 | Append-only; `4 RESET_BY_OPERATIONS` reservado e sem uso; `actor_user_id` só aceita usuário da mesma organização |
| Precedente operacional | 003 e 012 | `provision_first_org_admin`: papel LOGIN → função `SECURITY DEFINER` de proprietário NOLOGIN |
| Introspecção TLS | PostgreSQL 16 | `pg_stat_ssl` (`client_dn`, `client_serial`, `issuer_dn`) e a extensão `sslinfo` expõem emissor e serial da conexão. **Não expõem a validade do certificado.** A autenticação por certificado valida a conexão **no estabelecimento**, e uma conexão aberta não é encerrada quando o certificado vence |
| E-mail | `main` | Só registra a intenção. Entrega real pendente (missão pausada) |

## 2. Cenários

| # | Cenário | Caminho |
|---|---|---|
| A | Perdeu o dispositivo, **tem códigos** | Entra com um código. A troca de dispositivo fica **fora desta missão** (D7). Para trocar o fator: B, ou C se for o único administrador |
| B | Há outro `ORG_ADMIN` disponível | **Reset administrativo existente**, com **motivo codificado obrigatório** (D5) |
| C | **Único `ORG_ADMIN`** (ou todos) sem fator utilizável e sem códigos | **Emergência operacional** para conta `ORG_ADMIN` **existente, ativa, com vínculo ativo e fator confirmado e ativo**. Não cria papel nem vínculo |
| D | Organização sem administrador disponível | Sucessão, procedimento separado (D8) |
| E | Perdeu também a senha | Separado. A recuperação de MFA nunca troca senha. Perda de senha e MFA, sozinha, não justifica sucessão (D8) |

Uma conta sem fator confirmado e ativo não precisa de emergência: o login leva ao cadastro. Não existe reset de "nenhum fator".

## 3. Autenticação e autorização operacional

### 3.1 Identidade da conexão

- **Um papel de LOGIN por operador** (`op_<apelido>`): NOLOGIN enquanto não habilitado, sem membership em outro papel de operador nem em papel privilegiado. Recebe `EXECUTE` só nas funções operacionais, pelo grupo NOLOGIN `nekkora_ops_mfa`.
- **Sem senha de banco; credencial temporária por certificado.** `pg_hba`: `hostssl … cert map=ops clientname=CN`, só a partir da rede de administração, sem pooler compartilhado.
- **Autoria por `session_user`.** Dentro da função `SECURITY DEFINER`, `current_user` é o proprietário e `session_user` é o operador autenticado. `SET ROLE` muda só `current_user`, e `SET SESSION AUTHORIZATION` exige superusuário. **Nenhum parâmetro de autoria.**
- **A credencial é reutilizável durante a validade:** o certificado e a chave valem para qualquer conexão dentro dela. Por isso, a validade da conexão **não** é tratada como autorização do ato (§3.3).

### 3.2 Emissão (CA operacional, dependência proposta)

- O operador se autentica no IdP corporativo com MFA resistente a phishing. **A emissão exige autenticação recente:** `auth_time` do IdP ≤ 5 min e método resistente a phishing. Uma sessão antiga do IdP ou um token de renovação **não** emitem certificado.
- A CA emite **só** o CN do próprio operador (sujeito do IdP → CN, 1:1), com validade curta (proposta: 15 min), e a chave privada é gerada no dispositivo do operador.
- **A CA registra cada emissão no banco**, por um papel próprio da CA (`nekkora_ops_ca`, só `EXECUTE` em `ops_register_certificate`). O registro guarda operador, emissor, serial, `not_before` e `not_after`, e **nunca** vem da CLI.
- **Revogação:** `ops_revoke_certificate` (pela CA ou pela segurança) marca `revoked_at`. Ela também é chamada ao desabilitar o operador ou retirar habilitação.

### 3.3 Conferência em cada ato

Toda função operacional (verificar, pré-aprovar, aprovar em definitivo, executar, cancelar, consultar estado), **depois dos locks e com o instante do banco `v_agora` capturado depois deles**, confere:
1. **Emissor e serial lidos pelo servidor** da própria conexão, em `pg_stat_ssl` para `pid = pg_backend_pid()` ou pela `sslinfo`; a escolha e a visibilidade para o proprietário são conferidas na implementação.
2. Existe um registro de emissão com o mesmo emissor e serial, do **mesmo operador** que `session_user`, **não revogado**, com `not_before ≤ v_agora < not_after`.
3. Operador **ativo** e **habilitado para aquele ato** em `v_agora`.

Consequência: uma conexão mantida aberta depois do `not_after` continua conectada, mas **nenhum ato é aceito** nela. Um certificado revogado deixa de valer **no próximo ato**, mesmo com a sessão TLS viva.

**Fronteira de confiança:** um comprometimento da CA, ou do papel que registra emissões, permite personificação. Mitigações:
- administração da CA separada dos operadores;
- auditoria de emissão correlacionada pelo serial em cada ato;
- validade curta;
- revisão periódica.

Endurecimento futuro, **não nesta versão**: assinatura por ato com chave de hardware, verificada no banco.

## 4. Pessoas distintas

| Pessoa | Identificador | Fonte |
|---|---|---|
| Operador | `ops_operators.operator_id`, com `db_role` único e sujeito do IdP único | IdP + certificado registrado + `session_user` |
| Titular | `users.user_id` da **conta** alvo (a mesma pessoa pode ter contas em organizações diferentes; a recuperação atua só na conta alvo) | Cadastro da Platform |
| Solicitante | `org_authorized_contacts.contact_id`, ou o titular confirmado pelo canal registrado | Cadastro de contatos (D2) |

**Regra de distinção dos atos:**
- o **verificador pode ser o executor**;
- o **aprovador** (pré-aprovação e aprovação final) é **distinto** do verificador e do executor;
- nenhum operador pode ser titular nem solicitante (dentro dos limites abaixo).

**Uma identidade operacional por pessoa (regra administrativa do cadastro):**
- Sujeitos distintos no IdP só representam pessoas distintas se ninguém tiver duas identidades operacionais.
- O cadastro de operador é feito pela equipe de segurança a partir do registro funcional da pessoa, com um **único** sujeito do IdP e um **único** papel por pessoa, termo de responsabilidade e revisão periódica.
- Duplicidade é proibida e tratada como incidente.
- Essa regra é **administrativa**: o banco não consegue prová-la.

**Vínculos impeditivos:**
- `ops_operator_platform_accounts` guarda as contas da Platform **comprovadamente controladas** pelo operador (login do operador na conta durante o cadastro), e o mesmo mecanismo vale para contatos autorizados.
- Em **cada ato e na execução**, a função reconfere os vínculos de **todos** os participantes (verificador, aprovador e executor): nenhum pode ter conta vinculada na organização alvo ou na conta alvo, nem ser, por vínculo comprovado, o solicitante.

**Risco residual preservado:**
- um operador com conta **não vinculada** na organização alvo, ou que seja secretamente o titular ou um contato, **não é detectado** por comparação de cadastros;
- a mitigação é organizacional: duas pessoas, retorno pelo canal registrado, notificação e espera, termo de conflito de interesse e auditoria;
- sem CPF;
- **nenhum CHECK garante a regra inteira.** Os CHECKs cobrem só a distinção entre operadores dentro da solicitação.

## 5. Comprovação de identidade do titular (D2)

**Nunca bastam isoladamente:** e-mail, domínio, UUID, dados de perfil, urgência, pedido de canal não registrado ou documento enviado por e-mail.

**Evidências exigidas:**
1. **Retorno pelo canal previamente registrado:** o verificador **inicia** o contato pelo canal do contato autorizado registrado. A pessoa confirma o pedido e um código de uso único gerado pelo verificador, que não é guardado.
2. **Confirmação independente** por um **segundo** contato autorizado, pelo canal registrado dele.

**Vídeo com documento não é alternativa suficiente** nesta versão.

**Critérios de recusa** (lista única; a D2 remete a ela):
- evidência ausente ou divergente;
- retorno pelo canal registrado sem sucesso;
- canal registrado alterado há menos de 30 dias;
- pressão por urgência ou pedido para pular etapas;
- solicitante que não é o titular nem contato autorizado registrado;
- organização sem dois contatos elegíveis (sem acesso ao procedimento, D2);
- conta, vínculo ou organização inelegível (§2, cenário C);
- solicitação verificada já aberta para o mesmo alvo, ou limite de recuperações executadas atingido (D6).

## 6. Estados, prazos, espera e notificação

**Estados (só avançam):**
- `SOLICITADA` → `VERIFICADA` → `PRE_APROVADA` → *notificação confirmada* → espera de 24 h → `APROVADA` → `EXECUTADA`;
- ou `RECUSADA`, `CANCELADA` ou `EXPIRADA`.

**Prazos (D4, D11), como regra:**
- `T_total = abertura + 72 h`, sem prorrogação automática.
- Aprovação final aceita só se `confirmação + 24 h ≤ v_agora < T_total`.
- Execução aceita só se `v_agora < min(T_total, aprovação_final + 60 min)`.
- **Consequências da fórmula:**
  - a janela de execução é `min(T_total, aprovação_final + 60 min) − aprovação_final`;
  - para **reservar uma hora completa** de execução, a confirmação precisa ocorrer até `abertura + 47 h` (= 72 h − 24 h − 60 min), e a aprovação final assim que a espera terminar;
  - uma confirmação **posterior** continua válida enquanto `confirmação + 24 h < T_total`, mas deixa uma janela de execução menor que 60 min (no limite, nenhuma);
  - fora disso, a solicitação expira, e um novo pedido começa do zero.
- Todos os prazos são comparados com `v_agora` (§3.3), nunca com o horário da ferramenta.

**Notificação** (decisão D3 mantida: **sem produção antes da notificação validada**):
- Ao pré-aprovar, o sistema envia o aviso ao **titular** e aos **demais `ORG_ADMIN`** pela entrega real de e-mail, quando validada. **Aceite SMTP não conta como confirmação.**
- **Mudança explícita em relação à revisão 2:** a revisão 2 previa aviso e atenção do titular **e** dos demais administradores. Esta revisão exige **a confirmação só do titular** para iniciar a espera.
  - Os demais `ORG_ADMIN` continuam **notificados** e podem **contestar**, mas a confirmação deles não é exigida.
  - Motivo: no cenário C eles podem não existir, e um administrador ausente não deve bloquear sozinho uma recuperação legítima.
  - A proteção que eles representam passa a ser a **contestação** durante a espera, não a confirmação.
- Sem confirmação do titular a tempo (fórmula acima), a solicitação expira.

### 6.1 Contrato da confirmação e da contestação do aviso

O titular está **sem MFA**, então a confirmação **não** depende de sessão autenticada.

| Item | Regra |
|---|---|
| **Credencial** | Token próprio por **destinatário**, no formato de token da v1 (32 bytes aleatórios em base64url, contrato 8.3), só o **SHA-256** guardado. Vinculado a `(solicitação, organização, destinatário)` e à **finalidade**: `CONFIRMAR` só para o titular; `CONTESTAR` para qualquer notificado. **Uso único** em cada finalidade |
| **Validade por finalidade** | **`CONFIRMAR`:** até `min(T_total, emissão + 24 h)`. **`CONTESTAR`:** até `T_total`, aceito **somente enquanto a solicitação não estiver em estado terminal** (`EXECUTADA`, `RECUSADA`, `CANCELADA`, `EXPIRADA`). Assim, a contestação cobre toda a espera, mesmo quando a confirmação acontece horas depois da emissão |
| **Fixação da confirmação** | A **primeira** confirmação válida fixa `confirmação` e o início da espera. Reenvio, novo POST ou nova confirmação **não** substituem esse instante nem reiniciam as 24 h. Depois de confirmada, **não há nova emissão** de token `CONFIRMAR` para a solicitação. Antes da confirmação, um reenvio gera token `CONFIRMAR` novo e invalida o anterior do titular. Um reenvio de `CONTESTAR` gera token novo e invalida o anterior do mesmo destinatário, sem mudar a validade (`T_total`) |
| **Página** | Link com o token no **fragmento**, página estática como as páginas de conta. **Abrir o link não confirma nem contesta.** Só o **POST explícito**, com CSRF de pré-autenticação e Origin, registra o ato |
| **Efeito do POST de confirmação** | Registra `confirmação = v_agora`, **pelo sistema**. **Não** cria sessão, **não** aprova, **não** executa o reset, **não** muda senha nem fator |
| **Efeito do POST de contestação** | Cancela a solicitação (se ainda não executada), com o motivo "contestada por destinatário notificado". Sem sessão e sem outro efeito |
| **Quem registra** | Só o sistema, a partir do POST com token válido. Operadores **não** registram confirmação nem contestação em nome de ninguém |
| **Significado** | A confirmação comprova **controle do canal destinatário** (a caixa de e-mail do titular) **no momento**. **Não** é comprovação independente de identidade; a identidade continua vindo das evidências de D2 (§5) |
| **Resposta pública** | Genérica e idêntica para token inválido, vencido, usado ou de outra finalidade (sem revelar o estado da solicitação) |
| **Concorrência** | Confirmação, contestação e execução travam a solicitação na mesma ordem (§7). Uma contestação confirmada antes da execução impede o reset, e uma confirmação depois do prazo não conta |
| **Auditoria** | Evento operacional por confirmação, contestação ou tentativa inválida, com destinatário (id), finalidade e resultado; **nunca** o token nem o link |
| **Dependência** | Entrega real de e-mail validada e páginas de confirmação. **Bloqueia produção** (D3) |

## 7. Execução

**`ops_mfa_execute(request_id)`**, sem parâmetro de autoria.

1. **Locks, em ordem determinística:**
   1. organização (disciplina da 003);
   2. conta alvo (o mesmo lock do reset administrativo e do login);
   3. solicitação (`FOR UPDATE`);
   4. **linhas de `ops_operators` de todos os participantes**, em ordem crescente de `operator_id` (`FOR SHARE`).
2. `v_agora` capturado.
3. **Reconferências** (qualquer falha é **recusa prevista**):
   - autorização operacional do executor (§3.3);
   - executor ≠ aprovador;
   - **verificador, aprovador e executor ativos e habilitados** em `v_agora`, com os vínculos impeditivos de **todos** reconferidos (§4);
   - solicitação `APROVADA`, **não cancelada, não contestada**, não executada, dentro dos prazos do §6;
   - organização ativa; conta ativa; vínculo interno ativo; alvo **ainda `ORG_ADMIN` efetivo**;
   - fator **confirmado e ativo** e **exatamente** o aprovado;
   - **limites de recuperações executadas** (D6c) contados **sob os locks**: por organização sob o lock da organização (passo 1.1), por alvo sob o lock da conta (1.2). Duas solicitações de alvos diferentes disputando a última recuperação permitida da organização se serializam no lock da organização, e a segunda vê a primeira contada e recusa.
4. **Efeitos**, os mesmos do reset administrativo:
   - desafios, fator e códigos invalidados;
   - todas as sessões revogadas;
   - `mfa_events` `RESET_BY_OPERATIONS` com vínculo composto (§8);
   - solicitação `EXECUTADA`, por `UPDATE … RETURNING`;
   - evento operacional.

   Senha, papéis, vínculos, concessões, status e e-mail não mudam.
5. **Depois:** o próximo login, com a **senha atual**, cai em `mfa_enrollment_required`.

**Disciplina de concorrência com as alterações de operadores** (proposta de regra):
- Todo caminho que **desabilita** operador, **altera habilitações**, **cria ou remove** vínculo impeditivo ou **revoga** certificado trava a linha do operador em `ops_operators` com `FOR UPDATE` como **primeira** instrução, e não pede nenhum dos locks **explícitos** do passo 1 (organização, conta, solicitação).
- **Locks implícitos** (checagens de FK, triggers, índices únicos nas tabelas alteradas) também existem nesses caminhos e na execução. A implementação precisa **inventariar e testar** essas dependências, para confirmar que não criam ordem conflitante com a do passo 1. Não se afirma ausência de ciclo sem esse inventário.

Qual operação vence:
- **a alteração confirma primeiro:** a execução, ao obter o `FOR SHARE`, lê o estado novo e **recusa**;
- **a execução obtém o `FOR SHARE` primeiro:** a alteração **espera** o commit da execução; o ato foi válido no seu instante, e a alteração vale para os atos seguintes.

Os demais atos (verificar, pré-aprovar, aprovar, cancelar) seguem a mesma ordem, sem os locks de organização e conta quando não os afetam.

**Recusa prevista ≠ falha técnica ≠ resultado desconhecido:**
- **Recusa prevista:** nenhum efeito de reset. Registra o resultado (`RECUSADA_NA_EXECUCAO` com motivo codificado, ou `EXPIRADA`) e **confirma** essa transação. Retorna o resultado.
- **Falha técnica informada pelo banco** (erro retornado **antes** do commit): rollback integral. A CLI grava `ops_mfa_record_failure(request_id, categoria)` **em outra transação**, marcado como **informado pela CLI**, com SQLSTATE e etapa, sem mensagem livre.
- **Resultado desconhecido** (perda de conexão ou de resposta **depois** de enviar o commit, ou sem resposta nenhuma): **não se presume rollback**, porque o commit pode ter ocorrido.
  - A CLI **reconecta** (nova autenticação, se preciso) e chama `ops_mfa_status(request_id)`, que **aguarda a mesma trava da solicitação** (`FOR UPDATE`) usada pela execução, com tempo limite. Uma execução ainda em andamento, mesmo que a conexão original tenha caído, segura essa trava até o commit ou rollback. Ler `APROVADA` **sem** a trava não prova que ela terminou.
  - Trava obtida e `EXECUTADA`: apresenta o resultado **confirmado pelo banco**, sem registrar falha.
  - Trava obtida, ainda `APROVADA` e nenhum evento tipo 4 da solicitação: a execução anterior **terminou sem efeito** (rollback). Apresenta "não executada, confirmado pelo banco agora", e a solicitação segue válida dentro dos prazos.
  - **Trava não obtida no tempo limite, ou consulta impossível:** mantém **"desconhecido"**, grava no log local da CLI e alerta. Não registra "reset falhou" nem repete efeitos.
- **Registro separado indisponível:** a CLI sai com erro, com log local e alerta. **Não se promete auditoria persistida no banco sob indisponibilidade.**

## 8. Vínculos estruturais e auditoria

**Mudanças aditivas propostas (migration nova, não agora):**
- **Solicitação:**
  - `ops_mfa_recovery_requests (request_id PK, organization_id, target_user_id, approved_factor_id, …)`;
  - `UNIQUE (request_id, organization_id, target_user_id)`;
  - **FK composta** `(organization_id, target_user_id) → users (organization_id, user_id)`;
  - **FK composta** `(organization_id, target_user_id, approved_factor_id) → user_mfa_factors (organization_id, user_id, factor_id)`, apoiada no `UNIQUE (organization_id, user_id, factor_id)` que a 014 **já tem** (sem índice novo). Assim, o fator aprovado pertence **estruturalmente** à mesma conta e organização.
- **Evento de reset:**
  - `mfa_events.ops_request_id UUID` com **FK composta** `(ops_request_id, organization_id, user_id) → ops_mfa_recovery_requests (request_id, organization_id, target_user_id)`;
  - **`UNIQUE (ops_request_id)`**;
  - `CHECK ((event_type = 4) = (ops_request_id IS NOT NULL))`.

  Um evento só pode apontar para uma solicitação **da mesma organização e do mesmo alvo**, e cada solicitação gera no máximo um reset. Como não há eventos do tipo 4, o CHECK vale para todo o histórico. `correlation_id` não é o vínculo.
- **Motivo administrativo (D5):** `mfa_events.reason_code` com `CHECK (event_type <> 3 OR reason_code IS NOT NULL) NOT VALID`, que vale para inserções novas e preserva o histórico.

**Tabelas operacionais** (fora do tenant; só os proprietários operacionais escrevem):

| Tabela | Conteúdo |
|---|---|
| `ops_operators` | Operador, `db_role` único, sujeito do IdP único, estado, habilitações por ato |
| `ops_operator_certificates` | Emissor, serial, operador, `not_before`, `not_after`, `revoked_at`, registrados **pela CA** |
| `ops_operator_platform_accounts` | Contas da Platform comprovadamente controladas pelo operador |
| `org_authorized_contacts` | Contatos autorizados, canal registrado, datas de alteração (D2) |
| `ops_mfa_recovery_requests` | Estado, prazos, solicitante, operadores de cada ato, fator aprovado |
| `ops_mfa_recovery_events` (append-only) | Cada ato, recusa ou falha: ato, operador, **emissor e serial lidos pelo servidor**, motivo codificado, métodos e resultados da verificação, decisão, categoria da falha, **origem do registro** (`CONFIRMADO_PELO_BANCO` / `INFORMADO_PELA_CLI`) |

**Nunca guardar:** documento, imagem, gravação, código de uso único, segredo TOTP, códigos de recuperação, tokens ou senhas.

**RLS:** `mfa_events`, `user_mfa_factors`, `user_mfa_recovery_codes`, `auth_challenges`, `user_sessions`, `users` e os vínculos estão sob RLS forçada, e as políticas atuais cobrem `nekkora_platform` e os proprietários da 012. O proprietário operacional precisará de **política própria** no padrão `tenant_function_context`, com o contexto interno definido pela função a partir da solicitação. Isso fica para a migration e a revisão dela.

## 9. Notificação

Conforme o §6:
- aviso ao pré-aprovar, com confirmação do titular que inicia a espera;
- aviso ao executar;
- aviso aos demais administradores no reset administrativo de outro `ORG_ADMIN`.

**D3:** sem produção antes da notificação validada, e sem aviso manual como exceção.

## 10. Plano de testes negativos (para a implementação)

| Teste | Esperado |
|---|---|
| **Personificação (obrigatório):** com a credencial de X, agir como Y (sem parâmetro de autoria; `SET ROLE`; `SET SESSION AUTHORIZATION`; certificado de X com usuário `op_y`) | Tudo recusado. Os atos de X são registrados como X |
| **Conexão aberta atravessando o vencimento** do certificado | O ato depois do `not_after` é recusado, com a conexão ainda aberta |
| **Certificado revogado com a sessão TLS viva** | O próximo ato é recusado |
| **Espera por locks atravessando a validade do certificado ou da aprovação** | Recusa pelo `v_agora` capturado depois dos locks |
| **Emissor ou serial "informados"** (a CLI tenta passar valores) | Nenhum parâmetro aceito; só a leitura do servidor vale |
| **Emissão pela CA sem MFA recente** (sessão antiga do IdP, token de renovação) | Emissão negada (teste da CA) |
| **Autoaprovação** (aprovador = verificador; aprovador = executor; participante com vínculo impeditivo; contato vinculado a operador) | Recusa prevista registrada, sem reset. **Verificador = executor é aceito** |
| **Retirada de habilitação** ou desabilitação do aprovador ou verificador, ou vínculo impeditivo novo, **concorrendo** com a execução | Vence quem trava primeiro, pela disciplina do §7. Nunca um reset com participante inválido no instante do ato |
| **Cancelamento ou contestação concorrente** | Nunca reset depois de cancelamento ou contestação confirmados |
| **Organização cruzada** | Recusa. Nada muda nas duas organizações |
| **Evento válido isoladamente, associado à solicitação de outro alvo** (ou de outra organização) | Violação da FK composta. Nada gravado |
| **Fator aprovado de outra conta** | Violação da FK composta na solicitação |
| **Prazos:** execução depois do prazo total; aprovação final antes das 24 h; janela de 60 min ultrapassando o prazo total | Recusa. O menor prazo vale |
| **Notificação:** aceite SMTP sem confirmação do titular; confirmação por GET; confirmação registrada por operador | A espera não começa |
| **Confirmação do aviso:** GET do link; POST com token de outra finalidade, vencido, usado ou de outro destinatário; operador tentando confirmar; confirmação depois do limite; contestação depois da execução | Só o POST válido do titular confirma, sem criar sessão nem aprovar. Resposta pública idêntica nas recusas. Contestação válida cancela antes da execução |
| **Confirmação tardia e contestação dentro da espera:** aviso emitido às 10h, confirmado às 18h; contestação às 12h do dia seguinte (depois de `emissão + 24 h` e antes do fim da espera e da execução) | A contestação é aceita e **cancela** a solicitação. A aprovação final e a execução depois disso são recusadas |
| **Confirmação repetida ou reenviada:** segundo POST de confirmação; reenvio depois de confirmada; nova confirmação com token reemitido antes da primeira | O instante da primeira confirmação válida permanece; a espera não reinicia; nenhum `CONFIRMAR` é emitido depois da confirmação |
| **Limite executado da organização disputado** (duas solicitações de alvos diferentes, em paralelo, pela última recuperação permitida) | Só uma executa; a outra recusa, serializada no lock da organização |
| **Reconciliação com execução ainda em andamento** (a conexão cai e a execução segura a trava da solicitação) | `ops_mfa_status` espera a trava. Se não obtiver no tempo limite, o resultado fica **desconhecido**, nunca "não executada" |
| **Resposta perdida depois do commit** (obrigatório) | A CLI reconecta e mostra **EXECUTADA**, confirmado pelo banco. Nenhum registro de "falha", nenhum efeito repetido |
| **Sem consulta possível depois da perda** | Estado **desconhecido**, com log local e alerta. Nada registrado como falha no banco |
| **Rollback por falha técnica antes do commit** | Nada muda; registro separado **informado pela CLI** |
| **Execução concorrente** (N execuções; com login do titular; com reset administrativo) | Exatamente uma execução, ou recusa por fator divergente |
| **Sessões e fatores antigos** | Todos recusados depois do reset. Nenhuma sessão sem novo cadastro |
| **Sem ampliação** | Papéis, vínculos, concessões, status, senha e e-mail idênticos |
| **Limites** (D6): muitos pedidos não verificados para a vítima; pedido verificado legítimo depois disso | Os não verificados não bloqueiam o verificado. Exclusividade só a partir de `VERIFICADA` |
| **Reset administrativo** (regressão e motivo obrigatório) | Conforme o §8 |
| **Auditoria** | Todos os atos registrados, sem segredos. Varredura com controle positivo |

## 11. Riscos residuais declarados

- Comprometimento da CA ou do papel que registra emissões (§3.3).
- Operador com conta não vinculada na organização alvo, ou pessoa com duas identidades operacionais apesar da regra administrativa (§4).
- Indisponibilidade simultânea do banco e do log local impede o registro da falha (§7). Resta o alerta.
- O titular sem acesso ao próprio e-mail não confirma a notificação, e a solicitação expira (§6). O caminho é outro procedimento (D8).

## 12. Decisões

| # | Decisão | Estado |
|---|---|---|
| D1 | Autoridade operacional | **Direção aprovada:** papel individual, certificado e `session_user`. **Condicionada** ao §3 revisto (autorização por ato, registro de emissão, MFA recente, revogação) |
| D2 | Evidências e contatos autorizados | **Aprovado como política da v1:** **(a)** duas evidências: retorno pelo canal registrado + confirmação independente de um **segundo** contato autorizado; vídeo **não** suficiente. **(b)** A lista pertence ao processo de segurança da Nekkora, operada pelo atendimento. **(c)** Inclusão na contratação ou por solicitação do signatário, confirmada pelo canal registrado dele. **(d)** Alteração ou remoção confirmada pelo canal **anterior** e por outro contato; um canal alterado há menos de 30 dias não serve. **(e)** Critérios de recusa: a lista única do §5. **(f) Organização sem dois contatos elegíveis não tem acesso a este procedimento**, sem exceção improvisada. Isso é informado **no onboarding**, junto com o pedido de cadastro dos contatos |
| D3 | Notificação | **Decidido:** sem produção antes da notificação validada. Espera iniciada só pela confirmação do titular registrada pelo sistema (§6) |
| D4 | Prazos | **Aprovado e ajustado:** 72 h **totais** desde a abertura, sem prorrogação; aprovação final + 60 min; execução até o menor dos dois; uso único |
| D5 | Motivo administrativo | **Aprovado:** codificado, obrigatório, aditivo (`NOT VALID`) |
| D6 | Limites | **Aprovado como política da v1:** **(a)** pedidos não verificados não bloqueiam ninguém e expiram em 24 h; a proteção contra abuso é por origem, nunca pela cota do titular ou da organização. **(b)** A exclusividade por alvo começa em `VERIFICADA`, e os pedidos não verificados do mesmo alvo são encerrados como substituídos. **(c)** No máximo 1 recuperação executada por alvo a cada 7 dias e 3 por organização a cada 30 dias; recusados e não verificados não contam. **Conferência atômica** sob os locks da execução (§7), inclusive na disputa entre alvos pela última recuperação da organização |
| D7 | Troca de dispositivo | Fora desta missão. O *step-up* atual exige TOTP, ou seja, o dispositivo perdido |
| D8 | Sucessão | Procedimento separado |
| D9 | Retenção | **Pendente (gate de produção; não impede testes com dados sintéticos).** **Prazo proposto, não aprovado:** vigência contratual + cinco anos. A definição depende da análise, pelo jurídico e pelo encarregado de dados, de **aplicabilidade, finalidade, necessidade e marco inicial**. O art. 27 do CDC trata da prescrição de certas pretensões de reparação, contada do conhecimento do dano e de sua autoria; **não** estabelece obrigação geral de guardar auditoria por esse prazo e não fundamenta sozinho o prazo. Finalidades propostas para essa análise: responsabilização dos operadores, investigação de fraude e defesa em litígio. Dados mínimos: ids, códigos, datas, emissor e serial. Os ids continuam sendo dados pessoais; a auditoria não é anônima |
| D10 | Ferramenta | **CLI operacional**, sem senha de banco; credencial temporária por certificado. Depende de D1 |
| D11 | Espera | **Adotado:** 24 h depois da notificação confirmada, com aprovação final depois e dentro do prazo total. Sem atalho por urgência |

## 13. Dependências (propostas, não disponíveis)

- Entrega real e validada de e-mail, com a página de confirmação do aviso (D3, §6.1). **Bloqueia produção.**
- Definição da retenção pelo jurídico e pelo encarregado de dados (D9). **Bloqueia produção**; não impede testes com dados sintéticos.
- IdP corporativo para operadores, com MFA resistente a phishing e `auth_time` confiável.
- CA operacional com política sujeito→CN, validade curta, registro de emissão no banco e revogação.
- Cadastro operacional (uma identidade por pessoa) e cadastro de contatos autorizados (D2).
- Migration aditiva: papéis, tabelas, funções, colunas, política RLS do proprietário operacional e `pg_hba` operacional (a unicidade usada pela FK do fator já existe na 014). Migrations 001–015 inalteradas. **Nada disso é feito nesta etapa.**
