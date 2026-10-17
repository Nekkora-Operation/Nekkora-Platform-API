# P2: criação de organização

**Revisão 1 (parecer de 2026-10-15, entregue em 2026-10-17):** parecer da responsável sobre a entrega do P2. Aprovou a distinção entre `401` e `403` e apontou que uma exceção da transação não prova rollback: a reserva de R11 era devolvida mesmo quando o commit podia ter acontecido. A correção e as provas estão na §8; as §1, §2, §3 e §4 foram atualizadas para o estado corrigido.

**Data:** 2026-10-14. **Autorização:** parecer da responsável de 2026-10-14, que fechou o P1 R1 e liberou "P2 — criação de organização, com entrega isolada: rota, chave própria desligada por padrão, limites, consulta de `canCreateOrganization` e autorização reconferida pela função de escrita". Depois vêm P4 → P3.

**O que este bloco entrega:** a rota `POST /api/v1/organizations`, como na seção P2 do contrato (`2026-10-13-contratos-step-up-criacao-vinculacao.md`), e o significado novo de `canCreateOrganization`. Nada de P3 ou P4 entrou em código. A migration 017 é a R5 já entregue, sem alteração (sha256 `90b8ddbc…`). A capacidade inicial de execução da organização criada continua a do bootstrap (zero), até a decisão de produto.

## 1. A rota

| Item | Implementado |
|---|---|
| Rota e corpo | `POST /api/v1/organizations` `{name, legalName?}`. Cada campo: 1 a 120 **pontos de código**, sem espaço nas pontas e sem caractere de controle. Fora disso, ou sem corpo, ou sem `name`: `400`, antes de qualquer limitador ou banco |
| Autenticação | sessão de identidade do cookie `__Host-nekkora_id`, pelo `IdentityFilter` (rota incluída em `SESSION_ROUTES`). Sem sessão: `401` pela cadeia. Sessão restrita (DC-17.13): `403`, sem reservar R11 |
| Step-up | **conferido pela função** (`identity_session_proof`, modo `step_up`, até 5 min), não pela aplicação. O front-end faz o step-up do P1 imediatamente antes |
| CSRF | `IDENTITY_SESSION`, com `Origin` igual à origem pública. Falha: `403` genérico |
| Chave | **própria**, `nekkora.criacao-organizacao.enabled`, desligada por padrão. A rota só abre com ela **e** a da entrada geral ligadas (sem entrada geral não há sessão de identidade). Qualquer uma desligada: `404` antes da cadeia, no `IdentityRoutesGate`, e de novo no controller |
| Limites | R12 (IP) primeiro. Depois **R11**: 3 criações por identidade a cada 24 h, falhando fechado (Redis fora: `503`). O teto total de 10 fica **dentro** da função, sob o lock da identidade |
| Transação | uma, `inIdentity(TokenSessao)`, READ COMMITTED, que chama **só** `identity_create_organization`. A função cria organização, conta, vínculo, ORG_ADMIN, `identity_users` e o evento 6 (`ORG_CREATED`) no mesmo commit |
| Resposta | `201 {"organizationId":"…"}`. Função devolveu nulo: `403` genérico. Exceção: se, depois do nulo, a sessão já não está ativa e no prazo (revogada ou vencida durante a espera), `401` (§2) |

### R11: reserva, não contagem

R11 é uma **reserva atômica** no Redis, chave `nk:rl:r11:<identity_id>` (nunca o e-mail):
- um único script Lua confere a janela e registra a entrada; com a janela cheia, nada é registrado e a resposta é `429` com `Retry-After` até a entrada mais antiga sair. Não há corrida entre pedidos simultâneos da mesma identidade;
- a reserva é tratada pelo **desfecho** da transação (revisão 1, §8):

| Desfecho | Reserva |
|---|---|
| recusa da função (nulo: ela não escreve nada), mesmo que o commit falhe depois | devolvida |
| exceção antes de a função criar, inclusive dentro dela: o commit nunca foi enviado (rollback) | devolvida |
| commit confirmado | mantida |
| exceção depois de a função ter criado (no commit ou depois): resultado desconhecido | **mantida**, com o registro `evento=identidade.criacao_organizacao_incerta` |

- assim um pedido sem step-up não gasta a cota do dia. Mas R11 **não** conta só criações confirmadas: em resultado desconhecido, ou se o Redis cair entre a reserva e a devolução, a reserva fica sem criação confirmada até sair da janela. O erro vai para o lado seguro.

A primitiva nova está em `nekkora-core` (`RedisLimiter.reserve` e `release`). As demais primitivas não mudaram.

### `canCreateOrganization`

Passa a ser: chave da criação ligada (e a da entrada geral), sessão não restrita (a lista já responde `403` para a restrita) e `identity_org_creation_allowed` (R5) verdadeira, chamada na mesma transação da listagem. O step-up **não** entra. É **só indicação**: a criação reconfere tudo na função de escrita, qualquer que seja o valor mostrado (o teste do teto mostra a função recusando quando a indicação diz falso, e a criação acontecendo só pela função).

## 2. A transação e as respostas

1. R12; sessão do principal (`401`); sessão restrita (`403`);
2. R11, reserva (`429`/`503`);
3. transação: a sessão resolvida pela chave tem de ser a do principal; senão `401`;
4. `identity_create_organization(token, name, legalName)`. Dentro dela, na ordem global: identidade `FOR UPDATE` → sessão `FOR UPDATE`, validadas num comando posterior aos dois locks; identidade ACTIVE e verificada `FOR UPDATE`; teto de 10; prova de step-up com o relógio medido depois dos locks; e só então as inserções e o evento 6;
5. se a função devolveu nulo, a mesma transação lê se a sessão continua ativa e no prazo (`clock_timestamp()`, com a linha já travada pela função): não → `401`; sim → `403`. Em ambos, a reserva de R11 é devolvida. A função não escreveu nada;
6. exceção antes de a função criar (por exemplo, falha ao gravar o evento, dentro dela): rollback integral, reserva devolvida, `500`;
7. exceção depois de a função ter criado, no commit ou depois: a aplicação não sabe se o banco confirmou. Reserva mantida, registro da incerteza, `500`.

**Decisão da responsável (2026-10-15):** aprovada a distinção: `401` para sessão encerrada ou vencida, inclusive durante a espera; `403` genérico para as demais recusas de autorização. A distinção é lida depois da recusa, com a linha travada, e não muda nenhum efeito.

Logs: `evento=identidade.organizacao_criada` (id da organização, IP truncado, correlação), `evento=identidade.criacao_organizacao_negada` (regra `recusa` ou `sessao`, IP truncado) e `evento=identidade.rate_limited regra=R11`. Nenhum nome, e-mail, token ou hash (conferido nos logs da classe).

## 3. Testes

`OrganizationCreationIT` (12 testes, 1 deles da revisão 1, papel real `nekkora_platform`, 017 R5, Redis real, chaves da entrada geral e da criação ligadas). Cada recusa confere a resposta **e** o estado persistido: vínculos, eventos 6, organizações e a reserva de R11 no Redis.

| Teste | O que prova |
|---|---|
| `criaComStepUpOrgAdminEventosETrocaPelaSessaoDeOrganizacao` | step-up **real** pela rota do P1, depois `201`. Organização ACTIVE com nome e razão social (acentos preservados); conta interna ACTIVE com o e-mail verificado da identidade; vínculo ACTIVE; ORG_ADMIN ativo; `identity_users` por `cadastro_web`; evento de provisionamento; um evento 6 com a organização alvo; uma reserva em R11. A lista mostra a organização com ORG_ADMIN e `canCreateOrganization: true`; a troca dá `sessao_criada` |
| `semStepUpRecusa403SemEfeitoEDevolveAReservaDeR11` | indicação verdadeira sem step-up (o step-up não entra no cálculo); a criação: `403`, nada criado, R11 em zero |
| `tetoDeDezDentroDaFuncaoSobConcorrencia` | 9 organizações preparadas pela função; indicação verdadeira. Dois pedidos **simultâneos** com step-up: exatamente um `201` e um `403` (o lock da identidade serializa e o teto é reconferido); 10 vínculos, 10 eventos 6; R11 com 1 reserva (a recusa devolveu). Indicação falsa com 10; um pedido a mais: `403` pela função |
| `r11TresCriacoesPorIdentidadeAQuartaE429` | três `201`; a quarta `429` com `Retry-After`, sem chegar à função. Outra identidade cria normalmente |
| `r11ReservaAtomicaSobConcorrencia` | seis pedidos simultâneos da mesma identidade: exatamente três `201` e três `429`; três eventos 6 |
| `corpoForaDaRegraE400EOLimiteEContadoEmPontosDeCodigo` | sem corpo, `{}`, `name` nulo ou vazio; espaço nas pontas (ASCII, U+00A0, U+2003, U+3000); controle (U+0007, U+0000, `\n` na razão social); 121 caracteres; 121 emojis; `legalName` vazio ou com espaço; surrogate isolado: todos `400`, nada criado, R11 intocado. 120 emojis (240 unidades UTF-16) e razão social de 120: `201`, gravados com 120 pontos de código |
| `sessaoRestritaRecebe403` | sessão nascida de código de recuperação: `403` na criação e na lista; nada criado; R11 intocado |
| `semSessaoEncerradaOuVencidaE401` | sem cookie; depois do logout (cookie reapresentado); sessão vencida: `401` na criação e na lista |
| `csrfDaSessaoDeIdentidadeEOrigemSaoExigidos` | sem `X-CSRF-Token`, token de outro contexto, `Origin` diferente: `403`, nada criado, R11 intocado. Controle: `201` |
| `sessaoVencidaDuranteAEsperaPelaIdentidadeE401SemEfeito` | prazo da sessão encurtado para 5 s; **só a linha da identidade** travada por outra transação, sem alteração; liberada depois do vencimento: `401`, nada criado, reserva devolvida. Controle: liberada antes, `201` |
| `falhaAoGravarOEventoDesfazTudo` | gatilho temporário recusa o evento 6: `500`; nenhuma organização, vínculo ou evento; reserva devolvida. É o **controle** da falha antes do commit |
| `confirmacaoPerdidaDepoisDoCommitMantemAReserva` (revisão 1) | commit **real** e confirmação perdida (falha injetada só em teste, §8): `500`; por outra conexão, organização, vínculo e evento 6 existem; a reserva de R11 **permanece**; o log registra a incerteza |

Fora da classe:
- `OrganizationCreationRedisDownIT` (Redis exclusivo, derrubado): `503`, nada criado;
- `IdentityOrgSessionIT`, teste novo `criacaoComAChavePropriaDesligadaE404EIndicacaoFalsa`: no contexto com a entrada geral ligada e a criação desligada, `404` mesmo com sessão, CSRF e step-up válidos e com a função de consulta dizendo que poderia; indicação falsa. Os testes de listagem já existentes continuam esperando `canCreateOrganization: false` nesse contexto;
- `IdentityRoutesGateTest` (unitário, novo): as quatro combinações das duas chaves, em POST e GET, com caminho escapado; a lista e o step-up dependem só da entrada geral;
- `SignupDisabledIT`: a rota entra na lista das que respondem `404` com a configuração padrão;
- `SignupContractTest`: rota publicada na OpenAPI com `201/400/401/403/404/429/503`, `identitySessionCookie` e `csrfHeader`.

**Um defeito achado pelos próprios testes, antes da entrega:** a primeira versão conferia as pontas com `String.strip()`, que não trata U+00A0 como espaço; `" Nome"` com espaço sem quebra passou com `201`. A conferência passou a usar `Character.isWhitespace` **ou** `Character.isSpaceChar` no primeiro e no último ponto de código, e o teste ganhou U+2003 e U+3000. A Mc4 (§3.1) devolve a versão antiga e é detectada.

**Não coberto aqui, e por quê:** o step-up **vencido** (mais de 5 min) não tem teste na rota, porque a trigger da 017 não deixa recuar `mfa_step_up_at` e esperar 5 min por teste não é razoável. A prova está na função (`identity_session_proof`), coberta pela suíte da 017; a rota só repassa o nulo, como no teste sem step-up.

### 3.1 Contraprovas (mutações)

Cada mutação foi aplicada aos fontes, com `OrganizationCreationIT`, `IdentityOrgSessionIT` e `IdentityRoutesGateTest` rodados contra ela, e depois revertida. Na revisão 1, as doze foram refeitas sobre o código corrigido (a âncora da Mc7 mudou de forma, não de efeito) e entraram a Mc12 e a Mc13. No fim, os fontes de `nekkora-platform` e `nekkora-core` conferem byte a byte com os de antes (`FONTES_IDENTICAS`). Diffs e logs estão no pacote.

| Mutação | Testes reprovados |
|---|---|
| Mc0: recusa não devolve a reserva de R11 | `semStepUpRecusa403…`, `tetoDeDez…`, `sessaoVencidaDuranteAEspera…` |
| Mc1: R11 conferido e registrado em chamadas separadas (com 50 ms entre elas) | `r11ReservaAtomicaSobConcorrencia` (**seis `201`** em vez de três), e os que conferem a devolução (o membro devolvido deixa de existir): `semStepUp…`, `tetoDeDez…`, `sessaoVencida…`, `falhaAoGravarOEvento…` |
| Mc2: sem R11 | `r11TresCriacoes…`, `r11ReservaAtomica…`, e os que conferem a reserva registrada: `criaComStepUp…`, `tetoDeDez…`, `confirmacaoPerdida…` |
| Mc3: sem validação do corpo | `corpoForaDaRegra…` |
| Mc4: pontas por `String.strip()` (a primeira versão) | `corpoForaDaRegra…` (U+00A0 passa) |
| Mc5: indicação sem a função de consulta | `tetoDeDez…` (com 10, a indicação continua verdadeira) |
| Mc6: indicação sem a chave | `IdentityOrgSessionIT`: `criacaoComAChavePropriaDesligada…` e `listaSoAsOrganizacoes…` |
| Mc7: sessão que caiu na espera responde `403` | `sessaoVencidaDuranteAEsperaPelaIdentidade…` |
| Mc8: gate sem a chave própria (só a da entrada geral) | `IdentityRoutesGateTest`. Nos testes de integração a segunda barreira, a do controller, ainda responde `404` |
| Mc9: rota fora do CSRF da sessão de identidade (cai no padrão `SESSION`) | nove testes de `OrganizationCreationIT`: toda criação legítima passa a receber `403` |
| Mc12 (revisão 1): devolução indiscriminada em exceção (o tratamento anterior) | `confirmacaoPerdidaDepoisDoCommitMantemAReserva`: reserva 0 com a organização confirmada no banco |
| Mc13 (revisão 1): exceção nunca devolve | `falhaAoGravarOEventoDesfazTudo`: reserva 1 depois de rollback integral |
| Mc10 (informativa): sem a recusa da sessão restrita no serviço | nenhum: a função recusa a sessão restrita e a reserva é devolvida. A conferência no serviço só evita reservar e abrir transação |
| Mc11 (informativa): controller sem a chave própria | nenhum: o gate responde `404` antes. É a segunda barreira, como nas demais chaves |

Doze de doze detectadas (Mc0 a Mc9, Mc12 e Mc13). Mc10 e Mc11 mostram camadas de defesa cobertas por outra camada; não demonstram que as camadas sejam independentes.

## 4. Regressão

**Revisão 1 (estado atual):**
- **Regressão Java completa** (`mvn -o test`, relatórios limpos antes), com a 017 R5: **73 classes, 445 testes**, `BUILD SUCCESS` em 17:21 min. **Sem falhas novas**: só as 5 ocorrências conhecidas do `FixtureRunnerIT` (4 falhas e 1 erro, bash 3.2). Comparada por classe com a regressão da entrega do P2, a única diferença é `OrganizationCreationIT`, de 11 para 12 testes.
- **E2E entre processos** (`bash api/e2e/run.sh`), rodado depois da regressão, sem sobreposição: `CrossProcessE2E` 6/6, `E2E OK`. A stack descartável foi removida com `docker compose -p nekkora-e2e down`, sem `-v`. O build dos jars dentro do E2E levou cerca de 10 min, mais que o habitual, sem interrupção nem erro. O E2E não exercita a criação (chave desligada por padrão).
- **Recursos:** sem interrupção. Memória livre do sistema entre 27% e 38% durante a regressão (amostras no pacote).

**Entrega do P2 (antes da revisão 1):** 73 classes, 444 testes, sem falhas novas, com as mesmas 5 do `FixtureRunnerIT`; E2E 6/6.

## 5. Arquivos alterados

| Arquivo | Mudança |
|---|---|
| `nekkora-core` `ratelimit/RedisLimiter.java` | `reserve` (script atômico) e `release`; `Reservation` |
| `limits/Limits.java` | `reserveOrganizationCreation`, `releaseOrganizationCreation` (R11) |
| `config/PlatformProperties.java`, `application.yml` | `r11-creations: 3`, `r11-window: 24h` |
| `identity/IdentityOrgRepository.java` | `createOrganization`, `creationAllowed`, `sessionActive` |
| `identity/IdentityOrganizationsService.java` | `create`; `canCreateOrganization` pela chave e pela função de consulta |
| `web/IdentityOrganizationsController.java` | rota `ORGANIZATIONS`, `CreateRequest`, `validName`, chave própria |
| `web/IdentityRoutesGate.java` | `CREATION_ROUTES`, terceira chave |
| `web/IdentityFilter.java` | rota em `SESSION_ROUTES` |
| `web/Routes.java` | `/api/v1/organizations` → `IDENTITY_SESSION` |
| `config/IdentityConfig.java` | chaves no serviço e no gate |
| `openapi/nekkora-api-v1.yaml` | caminho novo, `OrganizationCreateRequest`, `OrganizationCreated`, descrição de `canCreateOrganization` |
| testes | `OrganizationCreationIT`, `OrganizationCreationRedisDownIT`, `web/IdentityRoutesGateTest` novos; `ConfirmacaoPerdida` (só teste, revisão 1); `IdentityOrgSessionIT`, `SignupDisabledIT`, `SignupContractTest` com a rota nova |

`SecurityConfig` e `Routes.reducesAccess` **não** mudaram: a rota não é pública e cria acesso, então passa pelos limitadores.

Documentos: contrato (situação da P2 e as duas precisões), desenho §6.6 (nota), levantamento §4 (nota) e §5 (linha da rota).

## 6. Limites declarados

- **`401` para a sessão que caiu durante a espera** (§2): aprovado na revisão.
- **R11 pode reter reservas sem criação confirmada**: em resultado desconhecido do commit e quando o Redis cai antes da devolução. É o lado seguro; a reserva sai sozinha ao fim da janela de 24 h.
- **Um commit que falha com rollback conhecido no próprio commit** (por exemplo, uma restrição adiada) também cai em "desconhecido" e mantém a reserva: a aplicação não distingue esse caso de uma confirmação perdida, e a 017 não tem restrição adiada nessa transação hoje.
- **R11 por `identity_id`**, não pelo HMAC global do e-mail: a rota é autenticada e o id não revela o e-mail. A reserva devolvida na recusa permite tentativas sem step-up sem gastar cota; elas continuam limitadas por R12.
- **A capacidade inicial de execução** da organização criada é a do bootstrap (limites 1 a 3 finitos em zero; 4 e 5 ilimitados), como a 017 fixa, até a decisão de produto.
- **A organização criada ainda não usa as ações administrativas protegidas por step-up** na sessão vinculada: isso é a P4, que vem a seguir.

## 7. O que vem depois

P4 (step-up por organização em sessão vinculada) e P3 (vinculação), cada um entregue para revisão. A capacidade inicial de execução continua decisão de produto aberta. A entrada geral e a criação continuam fora do alcance do usuário final (chaves desligadas por padrão). Integração web, transporte real, semeaduras, implantação, Microsoft 365 e commit/push continuam fora. A pendência diagnóstica da pressão de memória (P1 R1) continua aberta.

## 8. Revisão 1: reserva de R11 com resultado desconhecido

**Achado da responsável.** Em `create`, qualquer `RuntimeException` de `tx.inIdentity` devolvia a reserva. Mas a exceção não prova rollback: o banco pode confirmar a criação e a conexão falhar antes de a aplicação receber a confirmação. Nesse caso a reserva voltava com a organização criada, e daria para passar das três criações da janela. O teto de 10 no banco continuava valendo.

**Correção.** O serviço marca, dentro da transação, o momento em que a função devolveu uma organização. A partir daí, uma exceção já não prova ausência de efeito:
- exceção **sem** a marca: veio antes da criação, inclusive de dentro da função; o commit nunca foi enviado, então a reserva é devolvida;
- exceção **com** a marca: veio do commit ou depois dele; o resultado é desconhecido. A reserva é mantida e o log registra `evento=identidade.criacao_organizacao_incerta regra=R11 efeito=reserva_mantida`, com IP truncado e correlação;
- a recusa da função (nulo) não escreve nada, então a reserva continua sendo devolvida mesmo que o commit falhe depois.

Não há transação distribuída. A regra só impede que uma falha de comunicação seja lida como ausência de efeito.

**Prova dirigida.** `ConfirmacaoPerdida` é uma configuração **só de teste**, importada por `OrganizationCreationIT`. Ela troca o `transactionManager` por uma subclasse do mesmo `DataSourceTransactionManager`, sobre o mesmo DataSource. Armada, a próxima transação de escrita faz o **commit real** e só então a aplicação recebe `TransactionSystemException`. Um contador confirma que a falha veio depois de um commit real. Transações somente leitura, como a resolução da sessão no filtro, não são afetadas; desarmada, a subclasse se comporta igual à original. O teste confere por outra conexão que a organização, o vínculo e o evento 6 existem e que a reserva permanece. O controle de falha antes do commit é `falhaAoGravarOEventoDesfazTudo`: rollback integral e reserva devolvida.

**Contraprovas.** A Mc12 recoloca a devolução indiscriminada: a prova dirigida reprova com reserva 0 e a organização confirmada no banco. A Mc13 nunca devolve em exceção: o controle reprova com reserva 1 depois de rollback integral. As doze mutações anteriores, refeitas sobre o código corrigido, continuam detectadas (§3.1).

**Afirmação corrigida.** R11 não conta "só criações efetivas". Ficam reservas sem criação confirmada em dois casos: resultado desconhecido e falha na devolução (Redis fora). As duas saem ao fim da janela.

