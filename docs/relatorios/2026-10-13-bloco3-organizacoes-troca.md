# Bloco 3 das rotas de identidade: organizações da identidade e troca por sessão de organização

**Data:** 2026-10-13. **Autorização:** a responsável aprovou o bloco 2 e liberou organizações, troca para sessão de organização e vinculação, mantendo os contratos aprovados: sessão restrita recusada, elegibilidade reconferida no banco e vinculação com prova dos dois lados.

**O que este bloco entrega:** as duas rotas que já tinham contrato completo no desenho:
- `GET /api/v1/me/organizations` (§6.4);
- `POST /api/v1/sessions/organization` (§6.5 e DC-17.6), pela porta da §4.3 do levantamento de modularidade;
- a revalidação da DC-17.4: a sessão de organização derivada cai quando a sessão de identidade cai.

**O que não entrega, e por quê.** A criação de organização (§6.6) e a vinculação (DC-17.11) exigem **step-up da identidade** dentro de 5 minutos, e esse step-up ainda não tem contrato HTTP. A vinculação também não tem contrato HTTP próprio. Pela regra do levantamento (§5), a rota é classificada antes de entrar em código. A §6 traz as propostas desses contratos, para decisão, e uma lacuna encontrada no step-up de organização para conta vinculada.

> **Revisão 2 (2026-10-14), depois da leitura da responsável.** A revisão 1 foi preservada em `pacote-017-bloco3-organizacoes-troca-r1-2026-10-13`. O teto e o evento no mesmo commit foram aceitos. Faltava fechar um cenário de locks, apontado pela leitura do código.
>
> 1. **Ciclo pelo lock implícito das chaves estrangeiras.** A transação de emissão travava a organização antes de qualquer linha da identidade. O `INSERT` do evento (`identity_events.identity_id`) e o da sessão (`user_sessions.identity_session_id`) pedem `FOR KEY SHARE` na identidade e na sessão de identidade **depois** disso. A vinculação trava identidade e sessão e depois espera a organização `FOR UPDATE`. Resultado: ciclo. A ordem global da 017 é identidade → sessão de identidade → organização → conta.
>    - **Reprodução na revisão 1, com testes dirigidos:** a conta fica travada, a troca chega a ela e espera, e só então a vinculação começa (a chamada real `identity_link_account`, como `nekkora_platform`). Liberada a conta, o banco abortou a vinculação com `40P01` nos dois cenários: com **outra sessão** da mesma identidade (ciclo pela FK do evento, o cenário do parecer) e com a **mesma sessão** (ciclo pela FK da sessão, já no `INSERT`).
>    - **Correção:** a segunda transação começa resolvendo a candidata de novo pela `identity_org_session_target`, que trava identidade e sessão de identidade `FOR UPDATE`. Só depois a porta trava organização `FOR SHARE` → conta `FOR UPDATE` → excedentes → `INSERT` (sob o savepoint) → evento. Continuam uma transação só e o savepoint. A releitura também dá a candidata, o marcador de MFA e o prazo já sob o lock.
>    - **Resultado:** a vinculação espera a identidade, a troca emite e a vinculação termina com `false` (desafio inexistente), sem `40P01`.
> 2. **Mesmo defeito fora da troca, achado na auditoria da ordem:** o **logout da identidade** revogava a sessão e depois gravava o evento (sessão → identidade). Com a troca (identidade → sessão), isso formava ciclo.
>    - **Reprodução na revisão 1:** um gatilho temporário de teste segura o logout entre a revogação e o evento, e a troca começa nesse intervalo. A troca foi abortada com `40P01` e respondeu `500`.
>    - **Correção:** o logout trava a identidade antes de revogar. É uma linha, num bloco já aprovado; o comportamento HTTP não muda.
> 3. **Documentos corrigidos:**
>    - desenho §13.5: o requisito 1 ("organização antes de qualquer outra coisa") estava errado. Foi corrigido, com o registro, e entrou o requisito 7 (locks das chaves estrangeiras);
>    - levantamento §4.3: item 2 do contrato da porta, agora pré-condição de quem chama;
>    - javadocs de `OrgSessionIssuer`, `LinkedSessionIssuer`, `OrgSessionExchange` e `IdentityTransactions.inIdentityAndOrganization`.
> 4. **Provas mantidas:** teto com nove sessões, suspensão durante a espera, rollback quando o evento falha e troca com a organização travada antes das sessões. O teste "sessão de identidade revogada durante a espera" **mudou de forma**. A revogação crua, como superusuário, durante a espera agora esperaria a emissão, que espera a organização travada pelo próprio teste. O teste novo usa o **logout real**: ele espera a emissão, revoga depois, e a sessão emitida cai na requisição seguinte (DC-17.4).
> 5. **Contraprova que deixou de valer, declarada:** Mp1 (sem o lock da conta antes do limite) **não reprova mais nenhum teste**. Duas trocas da mesma identidade agora se serializam na identidade, antes de chegar à conta, e uma conta vinculada só recebe sessão vinculada pela troca da própria identidade. O lock da conta continua no código e no requisito 2, como defesa; a garantia do teto passa a vir do lock da identidade. Não há, hoje, caminho real que exercite só o lock da conta.
> 6. **Parecer sobre as decisões** e as duas condições explícitas estão no documento de contratos. A R5 da 017 sai em pacote próprio.
>
> **Revisão 1 (2026-10-13), depois da leitura da responsável.** A primeira versão foi preservada em `pacote-017-bloco3-organizacoes-troca-2026-10-13`. A listagem, a revalidação e as decisões D1, D3, D4, D6 a D9 foram aprovadas; D2 foi aprovada provisoriamente; D5 não foi ratificada.
>
> 1. **Teto de sessões sob concorrência.** A emissão travava a organização `FOR SHARE`, mas a conta só no `INSERT`, pela trigger, **depois** de contar as sessões. Duas trocas seguram juntas o lock compartilhado da organização.
>    - **Reprodução na primeira versão:** com nove sessões, a conta travada por outra transação até as duas trocas esperarem por ela e depois liberada, o resultado foi **11 sessões ativas**.
>    - **Correção:** organização `FOR SHARE` → **conta `FOR UPDATE`** → conferência e revogação das excedentes → `INSERT`, na mesma transação. O lock da trigger continua como defesa.
>    - **Resultado:** 10 ativas, porque a segunda troca recalcula e revoga a mais antiga.
>    - O requisito 2 do desenho §13.5 e o contrato da porta no levantamento (§4.3) foram corrigidos, com o registro da correção.
> 2. **Emissão e evento no mesmo commit.** A emissão rodava numa transação de organização, e o evento numa transação posterior. Sobravam dois resultados parciais: sessão emitida sem evento, e erro na resposta com a sessão já criada e excedentes já revogadas.
>    - **Reprodução na primeira versão:** falha injetada só no `INSERT` do evento de emissão. A resposta foi `500`, mas a sessão nova existia (11 linhas) e uma excedente tinha sido revogada.
>    - **Correção:** a emissão passou a rodar numa única transação READ COMMITTED com **os dois contextos** (`IdentityTransactions.inIdentityAndOrganization`), e o evento é gravado nela.
>    - **Recusa da trigger:** é desfeita até um savepoint, para que o evento de recusa entre na mesma transação.
>    - **Resultado:** falhou o evento, nada foi emitido nem revogado. A emissão continua do lado da organização (`LinkedSessionIssuer`), alcançada pela mesma interface; os pacotes Java seguem separados, as garantias transacionais não.
> 3. **Precisão do teste com o Redis fora:** passou a afirmar "sem emissão nem evento", porque o `IdentityFilter` ainda consulta a sessão antes da recusa.
> 4. **Contratos P1 a P4** fechados num documento próprio, com os ajustes do parecer: `2026-10-13-contratos-step-up-criacao-vinculacao.md`. Ele pede uma única mudança de banco, a R5 da 017, e por isso nenhum desses contratos entrou em código.

**Continuam fora:** integração web, conclusão da recuperação de MFA (T1), transporte real de e-mail, semeaduras, implantação, Microsoft 365, ambiente preservado e commit/push. **Este bloco não muda a 017** (R4b, hash `102b9f07…`). A R5 é entregue em pacote separado.

## 1. Rotas

| Rota | Autenticação | CSRF | Limites | Sucesso | Recusa |
|---|---|---|---|---|---|
| `GET /api/v1/me/organizations` | sessão de identidade (`__Host-nekkora_id`) | — (leitura) | R12 | `200 {organizations:[{organizationId, name, roles}], canCreateOrganization}` | `401` sem sessão; `403` sessão restrita |
| `POST /api/v1/sessions/organization` `{organizationId}` | sessão de identidade | `IDENTITY_SESSION` (novo) | R10, R12 | `200 {status:"sessao_criada", csrfToken}` + `__Host-nekkora_session` (Lax) | `200 {status:"sem_acesso"}` para qualquer causa; `400` UUID ausente ou não canônico; `401`; `403` CSRF ou sessão restrita |

As duas ficam sob a chave da entrada geral (`nekkora.entrada-geral.enabled`, desligada por padrão): desligada, `404` antes da cadeia de segurança. Publicadas no OpenAPI, com os esquemas `IdentityOrganizationList`, `OrganizationSessionRequest` e `OrganizationSessionResult` e a resposta `RestrictedIdentitySession`.

## 2. Como a troca funciona

| Passo | Transação | O que acontece |
|---|---|---|
| 1. Resolução inicial | identidade (`TokenSessao`), escrita | `identity_org_session_target` indica a **candidata** (organização, conta). A sessão de identidade fornece o marcador de MFA herdado e o prazo máximo. A candidata não autoriza nada |
| 2. Emissão e evento (revisões 1 e 2) | **uma** transação READ COMMITTED com os contextos da organização e da identidade (`inIdentityAndOrganization`) | 0) **identidade e sessão de identidade `FOR UPDATE`**, pela `identity_org_session_target` de novo, que reconfere a candidata e dá o marcador de MFA e o prazo (revisão 2); 1) organização `FOR SHARE`; 2) **conta `FOR UPDATE`**; 3) revogação das sessões excedentes; 4) `INSERT` com `identity_session_id`, sob savepoint. A trigger da 017 trava a conta de novo e reconfere vínculo, sessão de identidade (ativa e não restrita), identidade e as cinco condições de elegibilidade; 5) evento `ORG_SESSION_ISSUED` (7) com a organização, ou `ORG_SESSION_DENIED` (8) **sem** o alvo pedido |

Na primeira resolução, uma candidata vazia também grava o evento 8 na mesma transação dela.

**Os cinco requisitos da porta (desenho §13.5) e onde estão:**
1. Ordem global da 017 (revisão 2): identidade → sessão de identidade (`OrgSessionExchange`, primeiro comando da transação) → organização `FOR SHARE` (`LinkedSessionIssuer.issue`, primeiro comando da porta).
2. Conta travada **depois da organização e antes da política de limite** (revisão 1; a versão anterior deixava esse lock só com a trigger, e isso permitia ultrapassar o teto).
3. Variante de `create` que grava `identity_session_id`: `SessionRepository.createLinked`. Mesma política de limite por conta; a revogação dos excedentes foi extraída para `revokeExcess`, sem mudar o `create` do login por organização.
4. Recusa da trigger (`23514`) vira `sem_acesso`. Qualquer outro erro sobe como falha, sem virar recusa.
5. `READ COMMITTED`: é o isolamento de `IdentityTransactions`, e a porta o confere junto com o contexto da organização. Outro isolamento ou outro contexto: erro de programação, não recusa.
6. Evento no mesmo commit da emissão ou da recusa (revisão 1).
7. Locks implícitos das chaves estrangeiras contam na ordem (revisão 2): quando o evento e a sessão vinculada são gravados, a identidade e a sessão de identidade já estão travadas.

**Estrutura.** O lado da organização (`session.LinkedSessionIssuer`, que usa `SessionRepository` e, desde a revisão 1, roda na transação de quem chama) não conhece a identidade. A identidade só o alcança pela interface `identity.OrgSessionIssuer`, ligada em `IdentityConfig`. `IdentityDependencyRulesTest` ganhou duas regras, cada uma com seu caso sintético:
- **R8:** `platform.identity` não referencia `session`, `account`, `mfa`, `admin`, `access` nem `emergency`;
- **R9:** esses pacotes não referenciam `platform.identity`.

A R4 continua sem nenhuma exceção: nenhum arquivo da identidade usa `OrgTransactions`.

**Revalidação da DC-17.4, sem mudar a 017.** Com a alternativa A, a Platform não lê `identity_sessions` no contexto de organização. A 017, porém, já concede a ela `EXECUTE` em `identity_org_session_allowed`, que confere o contexto e então o ramo 2 inteiro. `SessionRepository.validate` passou a exigir, só quando `identity_session_id` está preenchido:

```sql
CASE WHEN s.identity_session_id IS NULL THEN true
     ELSE public.identity_org_session_allowed(s.organization_id, s.user_id, s.identity_session_id) END
```

A sessão do login por organização segue exatamente como antes: o `CASE` garante que a auxiliar nem é chamada para ela. O logout da identidade, a revogação por troca de fator ou senha, o vencimento e a suspensão da identidade derrubam a sessão de organização **na requisição seguinte**, sem escrita entre organizações.

## 3. Decisões tomadas neste bloco, para conferência

| # | Decisão | Motivo |
|---|---|---|
| D1 | A listagem devolve `roles` (lista), não `role` como no exemplo da §6.4 | `identity_organizations` devolve os códigos ativos do vínculo, e uma pessoa pode ter mais de um papel. Um campo único perderia informação. Se a responsável preferir `role`, a regra para escolher um papel precisa ser definida |
| D2 | `canCreateOrganization` é sempre `false` neste bloco | a criação ainda não tem rota (§6). Mesmo com a rota, a Platform não lê `identity_users`, então o campo não poderá refletir o teto de 10; quem garante o teto é a função |
| D3 | Listagem e troca usam a chave da entrada geral | fazem parte da mesma jornada: sem entrada geral não há sessão de identidade nova. A criação de organização deve ter chave própria (P2) |
| D4 | O prazo da sessão de organização derivada não passa do prazo da sessão de identidade | a revalidação já a derrubaria; assim o cookie não anuncia um prazo que a sessão não terá |
| D5 | Recusa registrada sem o alvo pedido. ~~Emissão registrada em transação própria, depois da emissão~~ | o alvo pedido pode nem existir (a FK recusaria) e não deve ficar como sinal. **Não ratificada na segunda parte:** deixava resultado parcial. Na revisão 1, emissão e evento são gravados na mesma transação (topo) |
| D6 | O `IdentityFilter` autentica o Spring Security **só** nas duas rotas novas, por comparação exata da URI | uma rota nova sem organização continua fechada até ser classificada, mesmo com a sessão de identidade |
| D7 | Sessão restrita: `403` antes de qualquer consulta, nas duas rotas | DC-17.13. O banco também recusa (zero linhas e trigger), então a ordem só evita trabalho e o evento de recusa |
| D8 | R10: 30 por hora, janela deslizante, chave pelo hash do token da sessão de identidade (`nekkora.limits.r10-exchanges` e `r10-window`) | desenho §7. O token nunca vai ao Redis. A listagem não consome R10 |
| D9 | `organizationId` precisa ser UUID canônico | `UUID.fromString` aceita formas como `1-1-1-1-1`. Malformado ou ausente: `400`, que não depende da organização e não serve de sonda |

## 4. Testes

**`IdentityOrgSessionIT` (15 testes desde a revisão 2, papel real `nekkora_platform`, 017 R4b, Redis real).** A organização da identidade é criada na preparação pela própria função da 017 (`identity_create_organization`, depois de gravar o step-up na sessão), porque a rota de criação ainda não existe. A troca e a listagem passam pelo caminho real.

| Teste | O que prova |
|---|---|
| `listaSoAsOrganizacoesVinculadasEElegiveis` | lista vazia para identidade recém-confirmada; depois, só as organizações vinculadas e ativas, com `roles`. Ficam fora: a suspensa, a conta antiga **não vinculada** com o mesmo e-mail, e a organização de outra identidade |
| `trocaEmiteSessaoDeOrganizacaoVinculadaQueServeAsRotasDaOrganizacao` | cookie `__Host-nekkora_session` (Lax, Secure, HttpOnly, token só no cookie); `identity_session_id` gravado; MFA herdado; prazo igual ao da sessão de identidade; evento 7; `GET /o/{org}/me` responde `200`; piso de tempo também no sucesso |
| `semAcessoEUniformeParaQualquerCausa` | organização inexistente, de outra identidade, de conta antiga com o mesmo e-mail e suspensa: corpo **idêntico**, sem cookie, sem sessão, piso de tempo nas quatro, evento 8 sem alvo |
| `logoutDaIdentidadeDerrubaASessaoDeOrganizacaoDerivadaSemEscritaNaOrganizacao` | depois do logout de identidade, a sessão de organização dá `401` na requisição seguinte, e a linha dela continua sem `revoked_at`: quem recusa é a revalidação |
| `sessaoDeOrganizacaoDoLoginPorOrganizacaoContinuaComoAntes` | controle do ramo 1: login por organização, sem sessão de identidade, segue válido |
| `sessaoRestritaARecuperacaoERecusadaNasDuasRotas` | sessão nascida de código de recuperação: `403` na listagem e na troca, sem sessão emitida |
| `semSessaoDeIdentidadeCsrfErradoECorpoInvalido` | sem sessão: `401`; o cookie de **organização** sozinho não autentica a rota da identidade; CSRF errado ou ausente: `403`; UUID malformado, não canônico ou ausente: `400` |
| `organizacaoSuspensaDuranteAEsperaDaEmissaoERecusada` | outra transação trava a organização; a emissão espera (observada em `pg_stat_activity`); a organização é suspensa e confirmada; a troca responde `sem_acesso`, sem sessão. A candidata da resolução inicial não autorizou |
| `logoutDuranteAEsperaDaEmissaoEsperaAEmissaoEDerrubaASessaoEmitida` (revisão 2; substitui `sessaoDeIdentidadeRevogadaDuranteAEsperaDaEmissaoERecusada`) | a organização travada, a emissão esperando por ela; o **logout real** é disparado e espera a emissão (duas esperas observadas). Liberada a organização: emissão (evento 7, nenhum 8), depois o logout (`204`), e a sessão emitida dá `401` na requisição seguinte, sem escrita na organização |
| `trocaEVinculacaoComOutraSessaoDaMesmaIdentidadeNaoFormamCiclo` (revisão 2) | conta travada; a troca espera a conta; **depois** a `identity_link_account` real, com outra sessão da mesma identidade, começa e espera; liberada a conta, a troca emite e a vinculação devolve `false`, sem `40P01`. Na revisão 1: `40P01` |
| `trocaEVinculacaoComAMesmaSessaoNaoFormamCiclo` (revisão 2) | o mesmo com a mesma sessão (ciclo pela FK de `user_sessions.identity_session_id`). Na revisão 1: `40P01` |
| `logoutConcorrenteComATrocaNaoFormaCiclo` (revisão 2) | gatilho temporário segura o logout entre a revogação e o evento; a troca começa nesse intervalo. Logout `204`, troca `sem_acesso` (a sessão já revogada), nada emitido. Na revisão 1: a troca abortada com `40P01` e `500` |
| `duasTrocasConcorrentesComNoveSessoesRespeitamOTeto` (revisão 1) | nove sessões; a conta fica travada por outra transação até as **duas** trocas esperarem (observado em `pg_stat_activity`; desde a revisão 2, uma espera a conta e a outra a identidade); liberada, as duas emitem e restam **10** ativas. Na primeira versão: 11 |
| `falhaAoGravarOEventoDeEmissaoDesfazAEmissao` (revisão 1) | dez sessões; falha injetada só no evento de emissão desta identidade (gatilho temporário de teste, removido no fim). Resposta `500`, nenhuma sessão nova e **o mesmo conjunto de sessões ativas**, sem revogação pelo limite. Na primeira versão: a sessão existia e uma excedente tinha sido revogada |
| `trocaTravaAOrganizacaoAntesDasSessoesDaConta` | conta no limite de 10 sessões. T1 faz o que a suspensão faz: trava a organização e depois revoga as sessões da conta. Com a ordem certa, a troca espera só pela organização, T1 revoga as 10 sem esperar (menos de 500 ms) e, depois, a troca emite |

**`IdentityOrgSessionLimitsIT`** (Redis exclusivo, R10 reduzido a 2): a terceira troca da mesma sessão dá `429` com `Retry-After`; outra sessão de identidade tem cota própria; a listagem não consome R10.

**`IdentityOrgSessionRedisDownIT`** (Redis derrubado): listagem e troca dão `503`, **sem emissão nem evento**. Precisão da revisão 1: a versão anterior dizia "sem consulta ao banco", e isso não está demonstrado, porque o `IdentityFilter` resolve a sessão de identidade antes da recusa.

**`IdentityDependencyRulesTest`:** R8 e R9 novas, com casos sintéticos que reprovam.

### Contraprovas por mutação

**Revisão 2.** Mesmo procedimento; no fim, os SHA-256 de todos os fontes Java conferem com os de antes (`FONTES_IDENTICAS`):

| Mutação | Testes que reprovaram (15 rodados) |
|---|---|
| Mq0: segunda transação sem a releitura que trava identidade e sessão | `trocaEVinculacaoComOutraSessao…`, `trocaEVinculacaoComAMesmaSessao…` e `logoutDuranteAEsperaDaEmissao…` |
| Mq1: logout sem travar a identidade antes de revogar | `logoutConcorrenteComATrocaNaoFormaCiclo` |
| Mp0: emissão sem o `FOR SHARE` prévio da organização | `trocaTravaAOrganizacaoAntesDasSessoesDaConta` |
| Mp1: emissão sem o lock da conta antes do limite | **nenhum**: mascarada pelo lock da identidade (topo, item 5) |
| Mp2: recusa da trigger sem voltar ao savepoint | `organizacaoSuspensaDuranteAEsperaDaEmissaoERecusada` |
| Mp3: evento de volta a uma transação separada | `falhaAoGravarOEventoDeEmissaoDesfazAEmissao` |

**Revisão 1.** Mesmo procedimento, com a fonte corrigida (`diff -r` sem diferença no fim):

| Mutação | Testes que reprovaram |
|---|---|
| Mp0: emissão sem o `FOR SHARE` prévio da organização (a Mo2, repetida para manter a prova contra a suspensão) | `trocaTravaAOrganizacaoAntesDasSessoesDaConta` |
| Mp1: emissão sem o lock da conta antes do limite | `duasTrocasConcorrentesComNoveSessoesRespeitamOTeto` |
| Mp2: recusa da trigger sem voltar ao savepoint | as duas esperas com recusa: a transação abortada não grava o evento de recusa, e a troca falha em vez de responder `sem_acesso` |
| Mp3: evento de volta a uma transação separada | `falhaAoGravarOEventoDeEmissaoDesfazAEmissao` (sessão criada apesar da falha) e `sessaoDeIdentidadeRevogadaDuranteAEspera…` (com a sessão de identidade revogada, a chave não resolve e a recusa fica sem evento: o primeiro caso do parecer, observado) |

**Primeira versão (preservada):**

Cada mutação foi aplicada à fonte, o teste rodou e a fonte foi restaurada. No fim, `diff -r` contra a cópia anterior não mostrou diferença.

| Mutação | Teste que reprovou (e só ele) |
|---|---|
| Mo1: `validate` sem a revalidação da sessão de identidade | `logoutDaIdentidadeDerrubaASessaoDeOrganizacaoDerivadaSemEscritaNaOrganizacao` |
| Mo2: emissão sem o `FOR SHARE` prévio da organização | `trocaTravaAOrganizacaoAntesDasSessoesDaConta`: T1 esperou 946 ms pela troca, que já segurava a sessão excedente. É o impasse, desfeito pelo PostgreSQL ao abortar uma das duas |
| Mo3: troca sem a recusa da sessão restrita | `sessaoRestritaARecuperacaoERecusadaNasDuasRotas`: o banco ainda recusa, mas responde `sem_acesso` em vez de `403` |
| Mo4: listagem sem a recusa da sessão restrita | o mesmo teste: `200` com lista vazia em vez de `403` |
| Mo5: prazo sem o limite da sessão de identidade | `trocaEmiteSessaoDeOrganizacaoVinculadaQueServeAsRotasDaOrganizacao` |
| Mo6: troca sem o piso de tempo | `semAcessoEUniformeParaQualquerCausa` e `trocaEmite…` (os dois conferem o piso) |
| Mo7: CSRF da sessão de identidade ignorado | `semSessaoDeIdentidadeCsrfErradoECorpoInvalido` |
| Mo8: troca sem R10 | `IdentityOrgSessionLimitsIT.r10LimitaATrocaPorSessaoDeIdentidade` |

Com a fonte correta, as três classes passam: 10/10, 1/1 e 1/1. O `SignupContractTest` ganhou a conferência das duas rotas novas contra o OpenAPI (método, códigos e esquemas de segurança) e passa com 3/3.

## 5. Regressão

**Revisão 2:** suíte completa, com os relatórios limpos antes, sobre a 017 R4b (a R5 entrou só depois, no pacote próprio). **68 classes, 414 testes**, só as 5 falhas conhecidas do `FixtureRunnerIT`. Comparada por classe com a revisão 1, a única diferença é `IdentityOrgSessionIT` passando de 12 para 15 testes. As classes do logout da identidade (`IdentityLoginIT`, `IdentityLogoutLimitsIT`, `IdentityLogoutRedisDownIT`) passam como antes com o lock novo. Logs das classes de troca e logout: nenhum e-mail, token ou Argon2; os valores de 64 hexadecimais são IDs de contêiner do Testcontainers.

**Revisão 1:** suíte completa com os relatórios limpos antes. **68 classes, 411 testes**, só as 5 falhas conhecidas do `FixtureRunnerIT`. Contra a primeira versão do bloco, a única diferença por classe é `IdentityOrgSessionIT` passando de 10 para 12 testes. Logs das três classes novas: nenhum e-mail, token, hash ou Argon2 (fora os IDs de contêiner do Testcontainers).

**Primeira versão (preservada):**

Suíte completa (`mvn -B -o test`), com os relatórios do surefire limpos antes:

| | Bloco 2, revisão 2 | Este bloco |
|---|---|---|
| Classes | 65 | 68 (+`IdentityOrgSessionIT`, `IdentityOrgSessionLimitsIT`, `IdentityOrgSessionRedisDownIT`) |
| Testes | 396 | 409 (+12 nas classes novas, +1 no `SignupContractTest`) |
| Falhas e erros | as 5 conhecidas do `FixtureRunnerIT` (bash 3.2) | **as mesmas 5, e nenhuma outra** |

Fora as classes novas e o teste a mais, a comparação por classe é idêntica.

**Achado da primeira execução, registrado.** Na primeira rodada, seis classes já existentes falharam com `FATAL: remaining connection slots are reserved for roles with the SUPERUSER attribute`: `AuthenticationFlowIT`, `MyPermissionsRouteIT`, `ProductReadRoutesIT`, `WorkspaceAccessIT`, `ReducingAccessRoutesIT` e `IdentityLogoutLimitsIT`. A causa não é o código do bloco:
- cada classe com propriedades próprias ganha um contexto Spring;
- o cache de contextos mantém o pool de cada um aberto;
- o PostgreSQL de teste tem o limite padrão de 100 conexões, e a suíte já estava perto dele. As três classes novas o ultrapassaram.

A correção foi `@DirtiesContext` **só nas três classes novas**, que fecham o próprio contexto ao terminar. Nenhum teste existente foi alterado. O log e a tabela por classe dessa primeira execução estão no pacote. Fica como ponto de atenção para os próximos blocos: toda classe com contexto próprio consome um pool. A saída duradoura é limitar o pool nos testes ou o tamanho do cache de contextos, decisão que não tomei aqui.

**Logs das três classes novas:** nenhum e-mail, nenhum token de 43 caracteres, nenhum hash de 64 hexadecimais (as duas ocorrências são IDs de contêiner do Testcontainers) e nenhum hash Argon2.

## 6. Contratos que faltam: propostas para decisão

> **Revisão 1:** esta seção é a primeira versão das propostas. A versão fechada, com os ajustes do parecer, está em `2026-10-13-contratos-step-up-criacao-vinculacao.md`.

### P1 — Step-up da identidade

Necessário para a criação de organização (DC-17.5) e para a vinculação (DC-17.11). Também é o caminho A da DC-17.13 (troca de aparelho).

| Item | Proposta |
|---|---|
| Rota | `POST /api/v1/auth/step-up` `{code}`, só TOTP, sem código de recuperação (como o step-up por organização) |
| Autenticação | sessão de identidade **não restrita**. Restrita: `403`. A DC-17.13 lista o que a sessão restrita pode fazer, e o step-up não está na lista |
| CSRF / limites | `IDENTITY_SESSION`; R8m (código errado, pela identidade global) e R12 |
| Transação | identidade travada → sessão travada → fator confirmado lido sob o lock → TOTP conferido → anti-replay (`last_used_step` só avança, como a trigger da 017 já exige) → `mfa_step_up_at = clock_timestamp()` → **janela do passo reconferida antes do commit**, a lição do bloco 2. A Platform já tem os dois `UPDATE` por coluna; a 017 não muda |
| Resposta | `204`. Código errado, replay ou sem fator: `403`, como o step-up por organização (`AuthService.stepUp`), com a tentativa contada em R8m. Sessão que caiu: `401` |
| Evento | a 017 não tem tipo para step-up (1 a 12). Proposta: só log, sem evento, até haver decisão sobre um tipo novo, que exigiria revisar a 017 |

### P2 — Criação de organização

A §6.6 já define rota, corpo e resposta. Faltam três pontos:
- **Chave própria**, `nekkora.criacao-organizacao.enabled`, desligada por padrão. É a "fábrica de tenants" da DC-17.5 e merece controle separado, como o cadastro.
- **Recusa:** a função devolve nulo sem dizer a causa (sem step-up, teto atingido, identidade inelegível). A proposta é `403` genérico, com o front-end fazendo o step-up imediatamente antes da criação, como já acontece nas ações administrativas por organização. O vocabulário de erro do contrato 8.7 §5.2 não tem um código "step-up necessário".
- **R11** (3 por dia) no Redis, falhando fechado; o teto total de 10 continua na função.

A capacidade de execução zero (§13, item 5a) continua sendo decisão de produto pendente.

### P3 — Vinculação

DC-17.11 em duas etapas:

| Etapa | Proposta |
|---|---|
| 1. Prova da conta antiga | `POST /api/v1/links/start` `{organizationId, password, code \| recoveryCode}`, sessão de identidade não restrita. A conta é procurada **pelo e-mail da identidade** naquela organização; a pessoa nunca informa outro e-mail. Senha (Argon2) e segundo fator **da conta**. Se tudo conferir, cria `identity_link_challenges` com os dois contextos (`inIdentityAndOrganization`, já previsto no `IdentityTransactions`). A trigger da 017 trava a conta e reconfere o e-mail e a ausência de vínculo. O token vai num cookie novo, `__Host-nekkora_link` (Strict, 5 min). Resposta uniforme: `204`, ou `401` para qualquer falha, sem distinguir conta inexistente, senha errada, código errado, conta já vinculada ou inelegível |
| 2. Prova da identidade e consumo | `POST /api/v1/links/confirm`, sessão de identidade com step-up de até 5 min (P1) e o cookie da etapa 1. Chama `identity_link_account`, que trava, reconfere e executa o fechamento da DC-17.12 inteiro. `204` ou `403` genérico |

**Requisitos que precisam entrar junto, antes do primeiro vínculo:**
- **DC-17.9, camada 1:** a elegibilidade do `AccountRepository` passa a excluir a conta vinculada, pela auxiliar `identity_account_is_linked`, que a Platform já executa;
- **DC-17.12:** pedido de recuperação de senha por organização para conta vinculada. A trigger recusa o token (`23514`), e a resposta tem de continuar `202` idêntica;
- a verificação da senha e do fator da conta é código de organização. Pela R8, ela entra por uma **segunda porta** implementada do lado da organização, como a `LinkedSessionIssuer`;
- limitador próprio, por identidade e por conta alvo (DC-17.11), e aviso ao endereço.

### P4 — Lacuna encontrada: step-up por organização em conta vinculada

As ações administrativas que exigem step-up (por exemplo, o reset do MFA de um membro) leem `user_sessions.mfa_step_up_at`. Esse campo só é gravado por `POST /o/{org}/auth/step-up`, que confere o TOTP **do fator local da conta**. A conta vinculada não tem fator local: a criada pela DC-17.5 nunca teve, e a vinculada pela DC-17.12 tem o fator desabilitado. **Um ORG_ADMIN que entra pela identidade não consegue fazer step-up na organização, e as ações que o exigem ficam inacessíveis para ele.**

Alternativas:

| Alternativa | Como | Custo |
|---|---|---|
| **A (recomendada)** | Em sessão de organização vinculada, `POST /o/{org}/auth/step-up` confere o TOTP **do fator da identidade**, por uma porta do lado da identidade, e grava o step-up nas duas sessões | código Java e uma porta; a 017 não muda |
| B | as ações administrativas aceitam o step-up da sessão de identidade de origem | a Platform não lê `identity_sessions` no contexto de organização: exigiria uma auxiliar nova, ou seja, revisar a 017 |

É decisão da responsável. Sem ela, organizações criadas pelo cadastro ficam sem as ações administrativas protegidas por step-up.

## 7. Limites declarados

- **Recusa sem evento num único caso** (revisão 1): se a sessão de identidade deixar de resolver entre a resolução inicial e o **início** da transação de emissão, a emissão nem começa e a recusa não é registrada, porque não há contexto de identidade para gravá-la. Desde a revisão 2, uma revogação que chegue **depois** desse início espera a emissão. Nada foi emitido. A emissão e o evento nunca ficam separados.
- **Desde a revisão 2, a identidade é travada na transação de emissão** (`FOR UPDATE`, pela função da 017), primeiro, na ordem global. Consequências: trocas da mesma identidade se serializam, como já acontecia na resolução inicial; um logout ou uma revogação **esperam** a emissão em curso e derrubam a sessão emitida na requisição seguinte; e o lock da conta antes do limite ficou como defesa, sem caminho real que o exercite sozinho (Mp1).
- **A revisão 1 dizia** "a identidade não é travada na transação de emissão"; era essa a origem do ciclo.
- **Custo da revalidação:** uma chamada de função a mais por requisição, **só** em sessão de organização vinculada. Não foi medido, assim como o custo do `IdentityFilter` (bloco 2).
- **Piso de tempo:** reduz a diferença entre as causas, mas não a elimina (desenho §12).
- **A autenticação pelo `IdentityFilter` restrita às duas rotas** não é exercitada por uma rota que a contornaria, porque hoje não existe outra rota sem organização que exija autenticação. A garantia vem da comparação exata no código.
- A integração web, a recuperação de MFA (T1) e o transporte real continuam pendentes. **A entrada geral e a troca não estão liberadas ao usuário final.**
