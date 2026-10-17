# Bloco 1 das rotas de identidade: cadastro, confirmação e reenvio

**Data:** 2026-10-13. **Autorização:** primeiro bloco de rotas, com cadastro, confirmação e reenvio da confirmação de e-mail. O cadastro fica **desligado por padrão**, e os testes usam transporte de e-mail simulado.

**Fora deste bloco:** login/MFA, organizações, troca de sessão, semeaduras, ambiente preservado, Microsoft 365 real e commit/push. Nenhum foi tocado. **A 017 não mudou** (R4b, hash `102b9f07…`).

> **Revisão 1 (2026-10-13), depois da leitura da responsável.** A primeira versão foi preservada em `pacote-017-bloco1-cadastro-2026-10-13`.
>
> **Aprovados:** a porta `PasswordRule` e as escolhas 1, 2, 4 e 5. "Sempre 202" vale dentro das condições de autenticação, formato HTTP e disponibilidade já documentadas.
>
> **Corrigido:**
> 1. **A titular escolhe a credencial na confirmação.** O pedido público recebe só o e-mail e não grava credencial. A senha (e o nome) entram na confirmação, numa única transação autorizada pelo token. Reproduzi na versão anterior o cenário apontado: a senha de quem fez o primeiro pedido autorizava a identidade que a titular concluiu (§5).
> 2. **Lock do token antes da validade.** Reproduzido pela contraprova: sem o lock, o `UPDATE` que esperou uma linha só travada consumiu um token já vencido (§5).
> 3. **E-mail fora do caminho da resposta,** com tratamento de falhas definido e teste com transporte simulado lento.
> 4. **Descrição corrigida:** nem o pedido nem o reenvio executam Argon2; só a confirmação.
>
> **Revisão 2 (2026-10-13): R1 aprovada para a integração local, com três precisões.**
> 1. **Desligamento da fila:** as tarefas devolvidas por `shutdownNow()` agora são descontadas de `pending()`. Há teste direcionado (§4) e contraprova (§5).
> 2. **Teste lento:** prova que a resposta **não espera o transporte**, e não que o tempo é igual em todos os caminhos (§4).
> 3. **Limites explícitos:** o papel da aplicação comprometido e as condições para habilitar o transporte real (§6).

## 1. Estrutura

| Onde | O quê |
|---|---|
| `platform.identity` | `SignupService` (só o fluxo de cadastro), `IdentityRepository` (sem estado), `SignupMailer` (envio assíncrono do cadastro) e `PasswordRule` (porta da política de senha) |
| `platform.web` | `SignupController` (sem `JdbcClient`) e `SignupGate` (chave DC-17.7) |
| `platform.config` | `IdentityConfig` (montagem separada de `PlatformConfig`, que não mudou). Liga `PasswordRule` a `PasswordPolicy::enforce` |
| acréscimos pontuais | `IdentityHmac.global`; `Limits` R12, R9 e R9b (valores de R6 e R5, sem chave nova); dois tipos em `MailSender.Kind`; CSRF em `Routes`; três entradas em `PUBLIC_POST_ROUTES`; `nekkora.cadastro.enabled=false` |

**Regras de dependência** (`IdentityDependencyRulesTest`, sobre os fontes, com autoteste por regra):

| Regra | O que proíbe |
|---|---|
| R1 | identidade → `auth`, fora de `auth.crypto` |
| R2 | `auth` (fora de `crypto`) → identidade |
| R3 | `auth.crypto` → qualquer outro pacote da Platform |
| R4 | identidade → `OrgTransactions` |
| R5 | identidade → `config` ou `web` |
| R6 | serviço de fluxo → outro serviço de fluxo |
| R7 | controller da identidade → `JdbcClient` |

## 2. Contrato HTTP

Publicado em `api/openapi/nekkora-api-v1.yaml`. `SignupContractTest` confere que o controller e o contrato coincidem.

| Rota | Corpo | Resposta | Desligado |
|---|---|---|---|
| `POST /api/v1/signup` | `{email}`; `password` ou `displayName` presentes → `400` | `202` sem corpo; `400` no formato; `429` (R12); `503` (Redis) | `404` |
| `POST /api/v1/signup/confirm` | `{token, password, displayName}` | `204`; qualquer recusa → `400 invalid_request`, sem efeito | `404` |
| `POST /api/v1/signup/confirmation/resend` | `{email}` | `202` sem corpo, inclusive com e-mail malformado | `404` |

## 3. Comportamento

**Pedido.** As etapas, em ordem:
1. R12, depois o formato do e-mail, depois a R9.
2. Estado do e-mail por `identity_signup_state`.

O desfecho depende do estado:

| Estado do e-mail | Efeito |
|---|---|
| livre | `identities` PENDING, **sem credencial**, com nome provisório igual ao próprio e-mail (a coluna é `NOT NULL`, e nada escolhido por quem pediu fica na identidade); token; evento `SIGNUP_REQUESTED` |
| PENDING | reemissão (R9b) |
| ACTIVE | aviso "já existe", sem token (R9b) |
| SUSPENDED / CLOSED | nada |

Cadastro concorrente do mesmo e-mail: o `23505` do segundo pedido é absorvido. **O pedido não executa Argon2.**

**Reenvio.** Só para identidade PENDING:
1. R9b.
2. Lock da identidade.
3. Revogação do token vivo e emissão de outro.

**O reenvio não executa Argon2.**

**Confirmação: a titular escolhe a credencial.**
1. **Antes da transação, sem nenhum lock:** R12, formato do token, nome, política de senha (`PasswordRule`) e Argon2id.
2. **Na transação**, na ordem da 017:
   1. lock da identidade;
   2. **lock do token**;
   3. só então validade e consumo, num comando novo, com o relógio lido depois de qualquer espera;
   4. `INSERT` da credencial;
   5. PENDING → ACTIVE com o nome escolhido;
   6. evento `SIGNUP_CONFIRMED`.
3. **Qualquer recusa desfaz tudo:** token, credencial e ativação.

Não cria sessão nem organização. A escrita da credencial usa o `INSERT` que a 017 já concede à Platform, sem `UPDATE` e sem mudar a 017. Uma identidade PENDING que já tenha credencial só poderia vir de dado anterior a este contrato; a confirmação a recusa (`400`).

**Envio de e-mail (`SignupMailer`).** Sai depois do commit, em duas threads próprias com fila de 1000, e a resposta não espera o transporte.

| Situação | Tratamento |
|---|---|
| exceção do transporte | registrada (`evento=cadastro.mail_falhou`), sem nova tentativa |
| fila cheia | mensagem descartada e registrada |
| desligamento | espera até 5 s pela fila. As tarefas que não começaram são descartadas, **descontadas de `pending()`** e registradas com a quantidade; as em execução são interrompidas e descontam ao terminar |
| recuperação | sempre pelo reenvio (R9b) |

O token só existe em memória até o envio. O log leva tipo e correlação, nunca e-mail ou token.

**Proteções:**
- chave desligada: 404 antes de CSRF, limitador e banco; caminho cru e decodificado; segunda barreira no controller;
- CSRF de pré-autenticação e `Origin`;
- respostas idênticas e piso de 300 ms no pedido e no reenvio;
- R9 e R9b silenciosas, R12 com 429, Redis fora com 503;
- auditoria em `identity_events`.

O R7 por IP, que já vale para essas rotas, é mais estrito que a R12; ela fica como segunda camada.

## 4. Testes

**Transporte simulado:** `PlatformTestBase.TestMailSender`, em memória e só de teste, ganhou atraso (`delayMs`) e falha (`fail`) deliberados, desligados por padrão e válidos só em `send`. Os testes existentes não os usam.

| Classe | Testes | Cobre |
|---|---|---|
| `SignupIT` (chave ligada) | 19 | ver abaixo |
| `SignupMailerTest` | 3 | não bloqueia quem chama; fila cheia descartada; falha sem nova tentativa; **desligamento com uma tarefa em execução e duas aguardando:** as duas saem da contagem, nada da fila é entregue, e um envio depois do desligamento é recusado sem ser contado |
| `SignupDisabledIT` | 1 | `404` nas três rotas, com e sem CSRF, em POST e GET, e com caminho escapado; nada criado |
| `IdentityDependencyRulesTest` | 2 | as sete regras, mais o autoteste |
| `SignupContractTest` | 1 | controller igual ao OpenAPI |

**`SignupIT` cobre:**
- **Pedido:** e-mail livre sem credencial; identidade ativa com resposta, piso e cookies iguais e credencial intocada; reemissão para PENDING; concorrência no mesmo e-mail; recusa de senha e nome no pedido; formato; CSRF e `Origin`.
- **Confirmação:**
  - define senha e nome, ativa sem sessão e vale uma só vez;
  - **o cenário das duas senhas**;
  - senha ou nome inválidos não consomem o token;
  - token inválido, vencido ou revogado;
  - identidade suspensa desfaz consumo e credencial;
  - revogação durante a espera do lock da **identidade**;
  - **vencimento durante a espera do lock do token:** outra transação só trava a linha, sem alterá-la, até o token vencer. Resultado: sem consumo, credencial, ativação ou evento.
- **E-mail:**
  - **transporte lento (1,5 s):** prova que **a resposta não espera o transporte**. Nos cinco caminhos (livre, PENDING, ACTIVE, SUSPENDED e desconhecido), a resposta chegou entre 300 e 1200 ms, abaixo da duração de um envio, com envios ainda em andamento depois das respostas, e as mensagens chegaram depois. **Não prova igualdade absoluta de tempo** entre os caminhos: banco, limitador e agendamento ainda variam por caminho, dentro do piso;
  - **transporte com falha:** a resposta não muda, nada é repetido, e o reenvio recupera.
- **Limites:** reenvio, R9b, R9 e R12.

## 5. Reprodução e contraprovas

**Reprodução do cenário das duas senhas na versão ANTERIOR.** Usei uma cópia de rascunho da árvore com os fontes do pacote do bloco 1, fora do repositório, e o teste `ReproducaoDuasSenhasV1IT`:
1. outra pessoa pede o cadastro com a senha dela;
2. a titular pede com a dela;
3. a titular confirma.

Resultado: `senha_da_outra_pessoa_autoriza=true` e `senha_da_titular_autoriza=false`.

**Contraprovas desta revisão.** Cada uma foi aplicada, testada e revertida, e os fontes foram conferidos idênticos depois.

| Mutação | Reprovou |
|---|---|
| Mr1: confirmação **sem o lock do token** | o teste do vencimento, com `expected 400 but was 204`. **O `UPDATE` que esperou a linha só travada consumiu o token vencido**: a condição de validade tinha sido avaliada antes da espera. É exatamente o caso apontado na revisão |
| Mr2: envio **síncrono** | o teste do transporte lento: o caso "livre" esperou o envio |
| Mr3: pedido **aceita** senha | os testes do pedido e das duas senhas |
| Mr4: confirmação **sem gravar** a credencial | os testes da confirmação e das duas senhas |
| Mr5 (revisão 2): desligamento **sem descontar** as tarefas devolvidas | o teste do desligamento, na contagem depois do `close()` |

As 9 mutações da primeira versão continuam valendo para o código que não mudou; a Mu9 é coberta pelo teste da revogação durante a espera.

**Regressão Java completa (revisão 2):** **sem falhas novas**. São 60 classes e 370 testes, iguais à revisão 1 exceto `SignupMailerTest`, que passou de 2 para 3/3. **Na revisão 1:** 60 classes e 369 testes. As classes são iguais às da primeira versão do bloco, exceto `SignupIT`, que passou de 14 para 19 testes, e `SignupMailerTest`, nova, com 2/2. Continuam as **5 falhas conhecidas** de `FixtureRunnerIT` com o bash 3.2 (4 falhas e 1 erro).

**Logs** de `SignupIT`, `SignupDisabledIT` e `SignupMailerTest`, inclusive das threads de envio: nenhum e-mail, nenhuma cadeia com formato de token e nenhum hash de 64 hexadecimais. Os eventos registrados são `cadastro.concorrente`, `cadastro.mail_falhou`, `cadastro.mail_descartado` e `csrf.negado`, só com tipo e correlação.

## 6. Limites e pendências

- **E-mail real:** fora; a porta local registra só a intenção.
- **Garantia na aplicação (aceita neste bloco):** a transação reúne consumo do token, credencial, ativação e evento. **Ela não protege contra o papel da aplicação comprometido:** quem age como `nekkora_platform` pode inserir uma credencial numa identidade PENDING sem token, porque a 017 concede `INSERT` em `identity_credentials` sem essa condição. A responsável não pediu revisão da 017 agora.
- **Fila em memória (aceita para a etapa local):** mensagens se perdem com a fila cheia, falha do transporte ou desligamento. Não há nova tentativa automática; a recuperação é o reenvio.
- **Antes de habilitar o transporte real,** é preciso definir disponibilidade, tempos máximos de envio e observação das falhas (hoje só log).
- **Índice da caixa de homologação** (`indice_caixa.py`): não conhece os dois tipos novos e falha fechado; é pendência da etapa que ligar o cadastro num ensaio.
- **Redis fora** nestas rotas: `503` por construção (`failClosed`), não exercitado por IT próprio.
