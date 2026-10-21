# P4 e P3: contas vinculadas administráveis

**Revisão 1 (parecer de 2026-10-19):** a chave da vinculação foi aprovada. O reset administrativo de MFA de conta vinculada passa a ser recusado (§3, §5), e a orientação para resultado desconhecido da etapa 2 foi corrigida (§7). A pressão de memória fica como hipótese para o timeout isolado do Redis (§8.5). Os testes e as contraprovas dirigidos aos ajustes estão em §8.2, §8.3 e §8.6.

**Data:** 2026-10-18. **Missão:** PLATFORM-PR-001, entrega B. **Base:** `main` em `4a528b3` (017 R5, P1 R1, P2 R1). **Contrato:** `2026-10-13-contratos-step-up-criacao-vinculacao.md`, seções P3 e P4, com o parecer de 2026-10-14. **Migration:** 017 R5 **sem alteração** (sha256 `90b8ddbc…`); nenhuma migration nova.

## 1. Problema e resultado observável

**Antes:** uma pessoa que entra pela identidade global e chega a uma organização pela troca (sessão de organização *vinculada*) não conseguia fazer nenhuma ação protegida por step-up. A rota de step-up da organização conferia só o fator **local**, que a conta vinculada não tem. A organização criada pelo próprio cadastro (P2) ficava com um ORG_ADMIN que não podia, por exemplo, redefinir o MFA de outra pessoa. E não havia como trazer uma conta antiga para a identidade.

**Agora:**
- **P4:** na sessão de organização vinculada, o step-up usa o fator **da identidade** de origem e eleva só aquela sessão de organização. A ação protegida, recusada antes, passa a ser aceita (provado pela API e no E2E entre processos).
- **P3:** a pessoa vincula a sua conta antiga à identidade em duas etapas, provando os dois lados. No mesmo ato, a entrada local da conta é fechada: sessões, desafios, tokens, fator e códigos locais deixam de valer. A partir daí, ela entra pela entrada geral e pela troca. Contas não vinculadas seguem exatamente como antes.

**Defeito encontrado e corrigido no caminho (já presente na `main`):** com uma conta vinculada, o login por organização e o pedido de recuperação de senha respondiam **500**, em vez do 401 genérico e do 202 idêntico. A trigger da 017 recusava a escrita (23514) e nada tratava a recusa. Para a conta criada pelo cadastro (P2), isso já acontecia na `main` com a chave da criação ligada. Além do erro, o 500 revelava a existência e o vínculo da conta. Reprodução no código da `main`, antes da correção: `LinkedAccountLocalEntryIT`, 2 testes, `expected: 202 but was: 500` e `expected: 401 but was: 500` (§8.4).

## 2. Escopo

**Incluído:** P4 (rota existente), P3 (duas rotas novas), os bloqueios da entrada local de conta vinculada que o contrato manda entrar junto, R13, OpenAPI, testes de integração pela API, E2E entre processos com as duas jornadas e a proposta de CI (no PR de consolidação).

**Fora, e não simulado:** telas web (a jornada foi validada pela API, §7 descreve o comportamento esperado da web), recuperação completa de MFA da identidade, entrega real de e-mail (o aviso de vínculo usa a porta de e-mail existente: o transporte local só registra a intenção), Microsoft 365, implantação, ambiente preservado, capacidade inicial de execução (decisão de produto pendente) e desvinculação (o contrato não a prevê).

## 3. Decisões de implementação (sem mudança de contrato)

| Decisão | Motivo |
|---|---|
| **Chave própria `nekkora.vinculacao.enabled`** (`NEKKORA_VINCULACAO`), desligada por padrão; só abre com a entrada geral ligada; desligada: `404` antes da cadeia | o contrato não nomeia chave para P3; segue o padrão do P2 (rota nova sensível, desligável sem desligar a entrada geral). **Os bloqueios da entrada local não dependem dela**: valem para toda conta vinculada, inclusive as do cadastro |
| P4 **sem chave nova** | é a rota existente. Sessão vinculada só nasce pela troca, que já depende da entrada geral |
| Portas `LinkedOrgSessionPort` (P4) e `LegacyAccountPort` (P3) | contrato P4, "Estrutura": a identidade não importa código de organização (regras R1–R9 de `IdentityDependencyRulesTest`). Os lados da organização (`mfa.LinkedSessionStepUp`, `auth.LegacyAccountProof`) não conhecem a identidade. A ligação, como a da `LinkedSessionIssuer`, fica em `IdentityConfig` |
| `ValidSession.identitySessionId` | a rota de step-up precisa saber se a sessão é vinculada e qual é a sessão de origem. O valor vem da mesma leitura que valida a sessão |
| R13 = 5 falhas em 15 min, por identidade global e por conta alvo (HMAC organização + e-mail) | valores do contrato, em `nekkora.limits.r13-*`. Qualquer falha da etapa 1 conta, porque a resposta é uma só |
| Aviso do vínculo: `MailSender.Kind.NOTICE_IDENTITY_LINKED`, depois do commit | contrato P3, etapa 2, "Depois". O uso de código local na etapa 1 manda o aviso que o login já manda (`NOTICE_RECOVERY_CODE_USED`) |
| Troca de senha local em sessão vinculada: `403` antes do Argon2 | contrato P3, "o que entra junto": a senha local não autentica nem muda depois do vínculo; `403` genérico nas rotas autenticadas |
| Recusa 23514 tratada no login (`401` genérico), na recuperação (`202` idêntico, mesmo piso) e na troca de senha (`403`) | a elegibilidade lida antes do lock não basta: o vínculo pode acontecer entre a leitura e o lock. O banco recusa, e a aplicação responde como o contrato manda (§8.4, intercalamento 1) |

**Constatações registradas, sem mudança:**
- **Reset administrativo de MFA de conta vinculada (revisão 1, decisão da responsável):** recusado com `403` genérico pelo **serviço** (`AdminService.resetMfa`). O vínculo é conferido sob o lock da conta (organização `FOR UPDATE` → contas), antes de qualquer efeito: a recusa não revoga sessões, não grava evento e não manda aviso. As triggers da 017 **não** garantem essa recusa e não foram alteradas. As escritas do reset são transições terminais (desabilitar, invalidar, revogar), que a 017 permite de propósito porque o fechamento da DC-17.12 e a operação da 016 precisam delas. Contas não vinculadas seguem no caminho de sempre. Reset e vinculação concorrentes serializam na organização, que as duas travam `FOR UPDATE` antes da conta.
- **Reenvio de verificação de e-mail local:** não há rota na Platform que emita `email_verification_tokens`. A trigger da 017 cobre uma futura.

## 4. P4: step-up por organização em sessão vinculada

`POST /api/v1/o/{org}/auth/mfa/step-up` `{code}`. Sessão do login por organização: caminho anterior, intacto (fator local, R1/R4). Sessão vinculada: `LinkedOrgStepUpService`.

| Item | Implementado |
|---|---|
| CSRF | o da sessão de organização, pelo `CsrfFilter` (contexto `SESSION`, inalterado). O CSRF global não é aceito no lugar dele (provado) |
| Cookie de identidade | exigência **adicional**, só aqui. Tem de resolver **exatamente** a sessão de identidade de origem (`user_sessions.identity_session_id`). Ausente, de outra identidade ou de outra sessão da mesma identidade: `403`, sem conferir o código e sem contar falha. Conferido antes da transação e **de novo dentro dela** (defesa dupla, §8.3, M2) |
| Transação | uma, `inIdentityAndOrganization(TokenSessao do cookie, org)`, READ COMMITTED: identidade `FOR UPDATE` → sessão de identidade de origem `FOR UPDATE`, reconferida (ativa, no prazo, não restrita; identidade ACTIVE) → organização `FOR SHARE` → conta `FOR UPDATE` → sessão de organização `FOR UPDATE`, revalidada por `SessionRepository.validate` (prazo, inatividade, conta, organização, vínculo interno e a auxiliar da DC-17.4) e conferida contra a de origem → fator da identidade lido sob o lock → TOTP → anti-replay no fator da identidade → `mfa_step_up_at` **só** na sessão de organização → `mfa_events` 6 → `identity_events` 14 com a organização → as duas sessões e a janela do passo reconferidas pelo relógio **depois de todas as esperas**; falhou: rollback integral |
| Locks implícitos | `UPDATE user_sessions` (linha já travada); `INSERT mfa_events` pede `KEY SHARE` em `organizations` e `users`, já travadas em modo mais forte; `INSERT identity_events` pede `KEY SHARE` em `identities` e `organizations`, já travadas; `UPDATE identity_mfa_factors` trava o fator, que só é escrito por quem já tem a identidade. Nenhum lock fora da ordem global |
| Respostas | `204`; código errado, replay, sem fator, cookie divergente: `403`; passo vencido nas esperas: `403`, sem contar; sessão que caiu (qualquer das duas): `401`, sem efeito |
| Limites | R8m pela identidade global (o mesmo contador do MFA da entrada geral e do P1), em vez de R1/R4. R7 continua no filtro |

## 5. P3: vinculação da conta antiga

### Etapa 1 — `POST /api/v1/links/start`

`{organizationId, password, code}` ou `{organizationId, password, recoveryCode}`.

| Item | Implementado |
|---|---|
| Autenticação | sessão de identidade não restrita (`IdentityFilter`), CSRF `IDENTITY_SESSION`. Restrita: `403` |
| Corpo | exatamente um dos dois códigos; senha presente; `organizationId` UUID canônico. Fora disso: `400`, antes de limitador e banco |
| Conta alvo | **pelo e-mail normalizado da identidade**, na organização pedida, só conta interna. A pessoa nunca informa outra conta, outro e-mail nem outra pessoa. Por isso **nenhum administrador vincula em nome de outra pessoa**: não existe parâmetro para isso |
| Limites | R12 (IP); R13 antes da prova, falhando fechado |
| Leitura e Argon2 | transação somente leitura com os dois contextos; Argon2 fora da transação, contra o hash ou contra o fictício (mesmo custo) quando não há conta ou credencial |
| Transação de prova | `inIdentityAndOrganization`: identidade e sessão `FOR UPDATE` e reconferidas → pela porta: organização `FOR SHARE` → conta `FOR UPDATE` → reconferência sob o lock: conta interna **elegível** (ACTIVE, verificada, organização e vínculo ativos, **não vinculada**), mesmo e-mail, **hash vigente igual ao conferido** → fator local confirmado e habilitado (`FOR UPDATE`) → TOTP com `consumeStep` (passo só avança) **ou** código local consumido (`used_at`) com `mfa_events` 2 → revogação de todo desafio de vinculação vivo da identidade → `INSERT identity_link_challenges` (par exato, hash conferido, 5 min). A trigger da 017 trava a conta de novo e confere e-mail e ausência de vínculo → sessão de identidade e janela do passo reconferidas antes do commit |
| Consumo | o passo ou o código local são consumidos já aqui, como no login (contrato). Falha em qualquer ponto depois do consumo: rollback integral, o código volta a valer |
| Resposta | `204` + cookie `__Host-nekkora_link` (Secure, HttpOnly, Path=/, SameSite=Strict, Max-Age=300). **Qualquer** falha da prova: o mesmo `401 {"error":"unauthenticated"}`, contado em R13. A causa só vai ao log sanitizado |

### Etapa 2 — `POST /api/v1/links/confirm`

`{organizationId}`, com a sessão de identidade (step-up de até 5 min, do P1) e o cookie do desafio.

| Item | Implementado |
|---|---|
| Transação | uma, READ COMMITTED, que chama **só** `identity_link_account` (017). A função reconfere tudo sob os seus locks, consome o desafio só se ele for da identidade da sessão, estiver no prazo e a credencial for a da etapa 1, e faz o fechamento inteiro da DC-17.12 e o evento 9 |
| Resposta | `204` vinculada; `false`: `403` genérico. Nos dois casos o cookie do desafio expira |
| Depois | aviso ao endereço, depois do commit. As sessões de organização da conta já foram revogadas; a pessoa entra pela troca |

### Bloqueios da entrada local (valem para toda conta vinculada)

| Caminho | Comportamento |
|---|---|
| Login por organização | elegibilidade de `AccountRepository` exige conta não vinculada (`identity_account_is_linked`): `401` genérico. Vínculo entre a leitura e o lock: a trigger recusa o desafio e a resposta é o mesmo `401` |
| Recuperação de senha por organização | `202` idêntico, mesmo piso, sem token e sem e-mail |
| Reconferência sob o lock (MFA do login) | o desafio de uma conta vinculada no meio do caminho é invalidado |
| Troca de senha local | `403` (sessão vinculada); se o vínculo acontecer no meio, a trigger recusa e a resposta é `403` |
| Reset administrativo de MFA | `403` genérico, conferido pelo serviço sob o lock da conta, sem efeito (sessões, evento, aviso). Se a vinculação concorrente confirmar primeiro, o reset vê o vínculo depois dos locks e recusa; se o reset confirmar primeiro, a vinculação vem depois dele |
| A rota `/o/{org}/login` da web | preservada como entrada com destino (DC-17.9 e DC-17.10). O que fecha é o endpoint de credencial local da conta vinculada |

## 6. Contrato publicado

`api/openapi/nekkora-api-v1.yaml`: P4 descrito na rota existente, com a alternativa de segurança que inclui o cookie de identidade; `/api/v1/links/start` e `/api/v1/links/confirm`; esquema de segurança `linkChallengeCookie`; efeitos sobre o login, a recuperação e a troca de senha locais; chaves. `SignupContractTest` passa a conferir as rotas da vinculação contra o OpenAPI.

## 7. Experiência: estados que o frontend precisa apresentar

As telas não fazem parte desta missão. Os estados abaixo saem das respostas reais da API. Nenhum deles distingue conta inexistente, senha errada, conta inelegível ou já vinculada.

### Step-up na organização (P4)

| Resposta | Estado a apresentar | Repetir automaticamente? |
|---|---|---|
| `204` | confirmado; refazer a ação protegida em até 5 min | — |
| `403` | "código recusado". Pode ser código errado, já usado ou vencido, **ou** este navegador não ter mais a sessão global que abriu esta organização. Se repetir, mandar autenticar de novo pela entrada geral | não |
| `401` | sessão expirada ou encerrada: autenticar de novo pela entrada geral e voltar à organização pela troca | não |
| `429` | limite atingido: esperar o `Retry-After` (o mesmo limite vale para o step-up global e para o login da identidade) | não |
| `503` | indisponível: tentar de novo depois, pela pessoa | não |
| erro de rede, tempo esgotado ou `500` | **resultado desconhecido**: o código pode ter sido consumido. Pedir o **próximo** código antes de tentar de novo | não |

### Vinculação (P3)

| Etapa | Resposta | Estado a apresentar | Repetir automaticamente? |
|---|---|---|---|
| 1 | `204` | conta antiga confirmada; seguir para confirmar a identidade (step-up) e a etapa 2 em até 5 min | — |
| 1 | `401` | "não foi possível confirmar essa conta" (mensagem única). Se `GET /api/v1/me/organizations` também der `401`, a causa é a sessão global: autenticar de novo | não |
| 1 | `403` | sessão em modo de recuperação: primeiro cadastrar o fator novo | não |
| 1 | `400` | erro do formulário (escolher **um** entre código do app e código de recuperação) | não |
| 1 | `429` | limite atingido: esperar o `Retry-After` | não |
| 1 | `404` | recurso desligado: não oferecer a vinculação | não |
| 1 | erro de rede, tempo esgotado, `500`, `503` | **resultado desconhecido**: o código local pode ter sido consumido. Recomeçar a etapa 1 com o próximo código (a etapa 1 nova revoga a anterior) | não |
| 2 | `204` | conta vinculada. Ela aparece em `GET /api/v1/me/organizations` e se entra pela troca. A sessão antiga daquela organização foi encerrada | — |
| 2 | `403` | não confirmada (sem step-up recente, desafio vencido ou substituído, senha da conta trocada no intervalo). Recomeçar pela etapa 1. O frontend deve fazer o step-up **imediatamente antes** da etapa 2 | não |
| 2 | `401` | sessão expirada: autenticar de novo e recomeçar | não |
| 2 | erro de rede, tempo esgotado, `500` | **resultado desconhecido**. **Não repetir automaticamente.** A API não tem consulta conclusiva do vínculo, e esta missão não cria uma. `GET /api/v1/me/organizations` **não** decide: a organização aparecer indica o vínculo, mas a ausência não prova rollback, porque a listagem omite vínculos com conta, vínculo interno ou organização inelegível ou suspensa. A tela mostra o resultado como desconhecido e deixa a pessoa decidir se recomeça pela etapa 1. Se o vínculo tiver acontecido, a etapa 1 nova responde o `401` único (conta já vinculada) e nada muda | **nunca** |

**Dependências que a interface não deve simular:** o aviso por e-mail do vínculo e do uso de código local **não é entregue** (transporte real fora desta missão), então a tela não deve afirmar "enviamos um e-mail". A recuperação completa do MFA da identidade não existe. O formulário de senha por organização em `/o/{org}/login` continua para contas ainda não vinculadas; para a vinculada, ele dá a mesma recusa de qualquer credencial inválida, e a tela oferece as duas portas sem sondar nada (DC-17.10).

## 8. Provas

### 8.1 Matriz de riscos → provas

| Risco | Prova (classe#teste) |
|---|---|
| Elevar a sessão errada | `LinkedOrgStepUpIT#elevaSoASessaoDeOrganizacaoSolicitanteEHabilitaAAcaoProtegida`: duas sessões de organização da mesma sessão de identidade, uma de outra sessão da mesma pessoa, uma de outra identidade; só a solicitante muda, nenhuma sessão de identidade muda, e a ação protegida passa só nela |
| Cookies ou CSRF trocados | `#cookieDeIdentidadeDivergenteECsrfTrocadoSaoRecusadosSemEfeito`: cookie ausente, de outra sessão da mesma identidade, de outra identidade, CSRF global no lugar do da organização, sem CSRF; passo intocado, nada gravado |
| Prova vence enquanto espera | `#passoVencidoDuranteOAntiReplayDesfazTudo`, `#sessaoDeOrganizacaoVencidaDuranteOAntiReplayDesfazTudo` (com controle liberado antes do vencimento), `#logoutDaIdentidadeDuranteAEsperaERecusado`, `#sessaoDeOrganizacaoRevogadaDuranteAEsperaERecusada`; P3: `AccountLinkIT#desafioVencidoERecusado` |
| Suspensão/revogação concorrente | `#organizacaoSuspensaDuranteAEsperaERecusadaComAIdentidadeJaTravada` (espera real em `pg_stat_activity`; identidade e sessão já travadas, `NOWAIT` falha), `#stepUpsConcorrentesDaMesmaPessoaSerializamNaIdentidade` |
| Replay | P4: `#codigoAceitoNaoServeDeNovoEmNenhumLugar` (outra sessão de organização, a mesma, step-up global, login; e o inverso). P3: `#codigoLocalUsadoNumLadoNaoServeNoOutro`, `#jornadaComCodigoDeRecuperacaoConsomeOCodigoNaEtapa1`, `#desafioDeUsoUnicoSoOMaisRecenteESoParaAPropriaIdentidade` |
| Troca de fator/senha | `#senhaTrocadaDuranteAEsperaDaEtapa1InvalidaAProva`, `#fatorLocalDesabilitadoDuranteAEsperaDaEtapa1InvalidaAProva`, `#senhaLocalTrocadaEntreAsEtapasInvalidaAProva` |
| Reset administrativo de conta vinculada | `#resetAdministrativoDeContaVinculadaERecusadoSemEfeito` (403, sessão da pessoa intacta, sem evento nem aviso; controle não vinculado 204), `#resetQueEsperaAVinculacaoERecusado` e `#vinculacaoQueEsperaOResetConcluiDepoisDele` (as duas ordens, com espera real) |
| Vinculação concorrente | `#confirmacoesConcorrentesVinculamUmaVez` (204 + 403, um vínculo, um evento); outra identidade com o desafio alheio: `#desafioDeUsoUnico…`; outra identidade com a senha e o TOTP certos da conta alheia: `#falhasDaProvaRespondemOMesmo401SemEfeito` |
| Porta antiga sobrevive | `#jornadaCompletaComTotpFechaAPortaLocalEAbreAEntradaGeral` (sessão, desafio, token, fator e códigos locais vivos antes; nenhum depois; login local, desafio pendente e troca de senha recusados), `#loginLocalQueEsperaAVinculacaoERecusadoComO401Generico` (intercalamento 1 da §4.5), `LinkedAccountLocalEntryIT` |
| Auditoria parcial | `LinkedOrgStepUpIT#falhaEmQualquerEventoDesfazOStepUp` (falha injetada em `mfa_events` e em `identity_events`: `500`, nada gravado, depois controle `204`). No P3, os eventos são da função da 017 (já provada na suíte de banco) e o `mfa_events` 2 é escrito na mesma transação do consumo |
| Falha de infraestrutura | `LinkedAccountsRedisDownIT` (Redis fora: `503` nas duas etapas e no P4, sem desafio, vínculo, step-up ou passo); cota esgotada: `#codigoErradoRecusaSemEfeitoEContaEmR8m` (R8m, compartilhado com o step-up global), `#r13LimitaAsFalhasPorIdentidadeEContaAlvo` |
| Regressão de legado | `LinkedOrgStepUpIT#contaNaoVinculadaContinuaNoCaminhoLocal`; controles de conta comum em `LinkedAccountLocalEntryIT`; regressão completa (§8.5) |
| Vazamento | `AccountLinkIT#logsSemSegredos` (processo e PostgreSQL: senhas, TOTPs, códigos de recuperação, tokens de sessão e de desafio e o hash dele, CSRF, segredos dos fatores, `otpauth://`, e-mail); `LinkedAccountsE2E#nenhumSegredoNosLogsDosContainers` |

### 8.2 Testes novos

| Classe | Testes | O que cobre |
|---|---|---|
| `LinkedOrgStepUpIT` | 12 | P4 |
| `AccountLinkIT` | 18 | P3, as duas etapas, concorrência, reset administrativo (revisão 1) e vazamento |
| `LinkedAccountLocalEntryIT` | 3 | bloqueios da entrada local; chave desligada |
| `LinkedAccountsRedisDownIT` | 1 | P3 e P4 com Redis fora |
| `IdentityRoutesGateTest` | +1 | chave da vinculação |
| `SignupContractTest` | +1 | rotas da vinculação no OpenAPI |
| `LinkedAccountsE2E` | 6 | E2E entre processos (§8.6) |

### 8.3 Contraprovas

Cada linha remove **uma** defesa central e roda só o teste que a prova (o mesmo fonte, restaurado depois de cada uma). Três sobreviveram na primeira rodada; as três são camadas sobrepostas de propósito, e removendo as duas camadas juntas o teste reprova (M2b, M12b, M13b). Diffs e logs ficam fora do repositório; o resumo da execução está no PR.

| Mutação | Teste | Resultado |
|---|---|---|
| M1 step-up gravado em todas as sessões da conta | `elevaSo…` | reprovou |
| M2 sem a igualdade da sessão de origem **antes** da transação | `cookieDeIdentidadeDivergente…` | **passou: defesa sobreposta** (a mesma igualdade é reconferida dentro da transação) |
| M2b sem a igualdade da sessão de origem antes **e** dentro da transação | `cookieDeIdentidadeDivergente…` | reprovou |
| M3 sem a reconferência final das sessões (P4) | `sessaoDeOrganizacaoVencida…` | reprovou |
| M4 sem a reconferência da janela do passo (P4) | `passoVencido…` | reprovou |
| M5 organização sem lock (P4) | `organizacaoSuspensa…` | reprovou |
| M6 anti-replay ignorado (P4) | `codigoAceitoNaoServeDeNovo…` | reprovou |
| M7 credencial não reconferida sob o lock (P3) | `senhaTrocadaDuranteAEspera…` | reprovou |
| M8 anti-replay local ignorado (P3) | `codigoLocalUsado…` | reprovou |
| M9 sem R13 | `r13Limita…` | reprovou |
| M10 desafio anterior não revogado (P3) | `desafioDeUsoUnico…` | reprovou |
| M11 login sem tratar a recusa da trigger | `loginLocalQueEspera…` | reprovou (`500`) |
| M12 elegibilidade sem o vínculo | `LinkedAccountLocalEntryIT` | **passou: defesa sobreposta** (camada 2 da §4.5: a trigger recusa e a aplicação responde 401/202) |
| M12b elegibilidade sem o vínculo **e** recuperação sem tratar a trigger | `LinkedAccountLocalEntryIT` | reprovou (2 de 3) |
| M13 sem o bloqueio da troca de senha em sessão vinculada | `jornadaCompleta…` | **passou: defesa sobreposta** (a trigger recusa a troca do hash e a resposta é `403`; o bloqueio só evita o Argon2) |
| M13b sem o bloqueio **e** sem tratar a trigger na troca de senha | `jornadaCompleta…` | reprovou (`500`) |
| M14 (revisão 1) reset sem recusar conta vinculada | `resetAdministrativo…`, `resetQueEspera…` | reprovou (2 de 2) |
| M15 (revisão 1) vínculo conferido antes dos locks | `resetQueEsperaAVinculacao…` | reprovou: lido antes da espera, o vínculo ainda não existia e o reset passou |

### 8.4 Reprodução do defeito na `main`

`LinkedAccountLocalEntryIT` (2 testes da época) rodado no código de `4a528b3`, em checkout limpo, antes da correção: os dois reprovaram, com `500` no lugar de `202` (recuperação de conta do cadastro) e no lugar de `401` (login de conta vinculada). Depois da correção, o mesmo cenário passa.

### 8.5 Regressão completa e E2E sobre o commit candidato

Clone limpo do commit `6c85880` (o último com código). Java 21.0.5, Maven 3.9.16 (wrapper), Docker 27.4.0, macOS arm64 com 8 GB. `FixtureRunnerIT` com Bash 5.3.20 (`-Dnekkora.bash`). Contagens lidas do log, não do `BUILD SUCCESS`.

| Módulo | Testes | Falhas | Erros | Pulados |
|---|---|---|---|---|
| nekkora-core | 18 | 0 | 0 | 0 |
| nekkora-ops | 155 | 0 | 0 | 0 |
| nekkora-platform | 293 (260 + 33 novos) | 0 | 0 | 0 |
| nekkora-portal | 12 | 0 | 0 | 0 |
| **total (77 classes)** | **478** | **0** | **0** | **0** |

Memória livre do sistema durante a rodada: mínimo de 26%, com swap perto de 12 GB (amostra a cada 30 s).

**E2E entre processos** (`api/e2e/run.sh`, projeto descartável `nekkora-e2e-pr001`, removido depois só ele): `CrossProcessE2E` 6/6 e `LinkedAccountsE2E` 6/6.

**Primeira rodada, preservada e investigada (commit `0aa7a36`):** 1 falha e 42 erros no `nekkora-platform`. Causa: as três classes novas com contexto próprio (`@DynamicPropertySource`) ficavam em cache com o pool aberto e esgotavam as conexões do PostgreSQL de teste (`FATAL: remaining connection slots are reserved for roles with the SUPERUSER attribute`). As classes seguintes não subiam o contexto. Correção em `6c85880`: `@DirtiesContext`, como as demais classes com contexto próprio. Na mesma rodada, `MfaConcurrencyIT` teve 1 falha anterior às classes novas (`login passo 1: 503`, `limitador.indisponivel`: timeout de 500 ms do Redis). É a mesma assinatura das falhas sob pressão de memória registradas no P1 R1, com mais contextos abertos naquela rodada. Não se repetiu depois da correção (5/5). Classificação: **intermitente, não reproduzida**. A pressão de memória é **hipótese**, não causa demonstrada: a assinatura e as amostras de memória são compatíveis com ela, mas nenhum teste a isolou. Também não há relação demonstrada com o código novo.

**`FixtureRunnerIT` e o Bash:** no mesmo commit da `main` (`4a528b3`), com o Bash 3.2 do macOS: 4 falhas e 1 erro (as cinco ocorrências conhecidas); com o Bash 5.3: 5/5.

### 8.6 Revisão 1: provas dirigidas e regressão sobre `9f98f71`

**Dirigidas:** `AccountLinkIT#resetAdministrativoDeContaVinculadaERecusadoSemEfeito`, `#resetQueEsperaAVinculacaoERecusado` e `#vinculacaoQueEsperaOResetConcluiDepoisDele`: 3/3. Contraprovas M14 e M15 (§8.3): as duas reprovaram. A M15 confirma que o vínculo precisa ser lido **depois** dos locks.

**Regressão completa** em clone limpo de `9f98f71` (código de `6ac25d8` mais o merge da consolidação; mesmo ambiente da §8.5; `./mvnw` executado direto, já com o bit de execução):

| Módulo | Testes | Falhas | Erros | Pulados |
|---|---|---|---|---|
| nekkora-core | 18 | 0 | 0 | 0 |
| nekkora-ops | 155 | 0 | 0 | 0 |
| nekkora-platform | 296 | **1** | 0 | 0 |
| nekkora-portal | 12 | 0 | 0 | 0 |

**E2E** (projeto descartável `nekkora-e2e-pr001b`, removido depois): `CrossProcessE2E` 6/6 e `LinkedAccountsE2E` 6/6.

**A falha, preservada e investigada:** `MfaConcurrencyIT#mfaCompletionVersusAdminResetNeverLeavesAnActiveSessionOrFactor`, rodada 3 de 10. O reset administrativo, em corrida com a conclusão do MFA da mesma conta **não vinculada**, respondeu `500` com SQLSTATE 23514. Esse teste passou nas regressões completas de `4a528b3` e de `6c85880`. Investigação:
- repetição isolada do teste: **0 falhas em 80 rodadas** no candidato e **0 em 80** na `main`;
- análise: a conclusão do MFA e o reset serializam no lock da conta. A única mudança no caminho do reset é uma leitura (`identity_account_is_linked`) depois dos locks, e essa função recusa com 42501, não 23514;
- a mensagem do banco se perdeu com o container compartilhado: a aplicação registra só o SQLSTATE, por desenho.

**Classificação:** intermitente, **causa não identificada**, relação com a mudança da revisão 1 **não demonstrada nem descartada**. Não foi tratada como resolvida por repetição. Para a próxima ocorrência, `MfaConcurrencyIT` passa a imprimir as linhas de erro do log do PostgreSQL de teste quando o ato administrativo não conclui (só saída de teste; o log do PostgreSQL já é conferido sem segredos em `LimitsAndLogsIT`). É a principal pendência de verificação deste PR. O check de CI do PR é uma execução independente.

**Situação em 2026-10-20 (missão API-FECHAMENTO-001):** o mecanismo foi encontrado e corrigido. As revogações de sessão de organização gravavam `clock_timestamp()` sem `greatest(..., created_at)`; com um recuo do relógio da VM do Docker Desktop, a sessão recém-criada pela conclusão do MFA violava `user_sessions_check2` (`revoked_at >= created_at`) e o reset inteiro voltava com `500`. O defeito já existia na `main` e não depende da leitura do vínculo da revisão 1. Para **esta** ocorrência, o mecanismo é a hipótese mais consistente entre as examinadas; a causa continua **não confirmada** (a mensagem do banco se perdeu). Situação: defeito de revogação corrigido; atribuição da ocorrência original inconclusiva. Investigação, provas e limites: `2026-10-20-fechamento-23514-mfa-reset.md`. O registro acima fica como estava.

## 9. Como reproduzir

```
cd api
./mvnw -B test -pl nekkora-platform -am -Dtest='LinkedOrgStepUpIT,AccountLinkIT,LinkedAccountLocalEntryIT,LinkedAccountsRedisDownIT'
./mvnw -B test -Dnekkora.bash=<bash 4+>        # regressao completa (no macOS, o Bash 3.2 do sistema reprova o FixtureRunnerIT)
e2e/run.sh                                       # stack isolada; roda CrossProcessE2E e LinkedAccountsE2E
```

## 10. Riscos residuais, compatibilidade e implantação

- **Compatibilidade:** contas não vinculadas seguem como antes. A conta vinculada passa a receber `401`/`202`/`403` onde antes recebia `500`. Nenhuma migration, nenhuma mudança de privilégio no banco.
- **Para ligar a vinculação:** `NEKKORA_ENTRADA_GERAL=true` e `NEKKORA_VINCULACAO=true`. Antes disso em produção: o transporte real de e-mail (aviso de vínculo) e a recuperação de MFA da identidade (quem perde o fator da identidade perde o acesso às organizações vinculadas, porque a porta local está fechada).
- **Sem desvinculação:** o vínculo é definitivo nesta missão (DC-17.12). Desfazer exige decisão própria.
- **Mensagem única do 401 da etapa 1:** pela regra de não sondar contas, a interface não sabe dizer à pessoa qual prova falhou.
