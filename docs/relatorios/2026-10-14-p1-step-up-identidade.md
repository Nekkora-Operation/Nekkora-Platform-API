# P1: step-up da sessão de identidade

**Data:** 2026-10-14. **Autorização:** parecer da responsável de 2026-10-14, que fechou o bloco 3 R2 e a R5 da 017 e liberou "P1 — step-up da identidade, conforme o contrato revisado, entregando esse bloco isoladamente para revisão". Depois vêm P2 → P4 → P3.

**Revisão 1 (2026-10-14):** parecer da responsável sobre a entrega do P1, que apontou a sessão podendo vencer durante as esperas sem ser reconferida. A correção e as provas estão na §8; as §1, §2, §3 e §4 foram atualizadas para o estado corrigido.

**O que este bloco entrega:** a rota `POST /api/v1/auth/step-up`, exatamente como na seção P1 do contrato (`2026-10-13-contratos-step-up-criacao-vinculacao.md`). Nada de P2, P3 ou P4 entrou em código. A migration 017 é a R5 já entregue, sem alteração (sha256 `90b8ddbc…`).

## 1. A rota

| Item | Implementado |
|---|---|
| Rota e corpo | `POST /api/v1/auth/step-up` `{code}`, só TOTP. Corpo sem `code`, inclusive `{recoveryCode}`: `400`. Nenhum código de recuperação é consumido |
| Autenticação | sessão de identidade do cookie `__Host-nekkora_id`, pelo `IdentityFilter` (rota incluída em `SESSION_ROUTES`). Sem sessão: `401` pela cadeia. Sessão restrita (DC-17.13): `403`, sem conferir o código |
| CSRF | `IDENTITY_SESSION` (o mesmo da troca), com `Origin` igual à origem pública. Falha: `403` genérico |
| Chave | `nekkora.entrada-geral.enabled`, no `IdentityRoutesGate` e no controller. Desligada: `404` antes da cadeia |
| Limites | R12 (IP) antes de tudo; R8 e R8m pela identidade global (`IdentityHmac.global` do e-mail), antes de conferir o código. Código errado e replay contam em R8m, o mesmo contador da verificação do login. Redis fora: `503` |
| Resposta | `204`. Código errado, replay, sem fator confirmado ou passo vencido nas esperas: `403` genérico. Sessão que caiu (revogada, vencida, identidade suspensa) durante o pedido, inclusive durante as esperas depois da validação: `401`, sem efeito e sem contar |
| Evento | `identity_events` tipo 13 (`STEP_UP`), sem organização alvo, na mesma transação |

Serviço novo: `IdentityStepUpService`, no pacote `platform.identity`. Ele importa só `auth.crypto` de `platform.auth`, e as regras de dependência (`IdentityDependencyRulesTest`) continuam passando. O levantamento de modularidade previa o step-up dentro de `IdentityCredentialService`. Ele virou serviço próprio porque é pré-condição de outros fluxos (P2, P3), e o levantamento registra isso (nota de 2026-10-14 na §4 e linha nova na tabela da §5).

## 2. A transação

Uma só, `inIdentity(TokenSessao)`, READ COMMITTED, na ordem global da 017 (identidade → sessão de identidade):

1. a sessão do contexto tem de ser a do principal, e a identidade a dele;
2. identidade `FOR UPDATE`;
3. sessão `FOR UPDATE`, **reconferida sob o lock**: não revogada, no prazo, com MFA; identidade ACTIVE com e-mail verificado. Se falhar: `401`. Restrita: `403`. A aquisição do lock (`SELECT 1 … FOR UPDATE`) e a avaliação (`expires_at > clock_timestamp()` e o resto) são **dois comandos**: num único `SELECT … FOR UPDATE`, a lista de saída é calculada antes da espera pelo lock e só é recalculada se a linha mudou, então o prazo não seria medido depois da espera (comprovado pela Mt2, §8);
4. fator confirmado e habilitado, lido sob o lock da identidade (a troca de fator da 017 trava a identidade antes);
5. TOTP conferido com o relógio lido depois das esperas pela identidade e pela sessão;
6. anti-replay (pode esperar o lock da linha do fator): `UPDATE identity_mfa_factors SET last_used_step = passo WHERE factor_id = ? AND confirmed_at IS NOT NULL AND disabled_at IS NULL AND (last_used_step IS NULL OR last_used_step < passo)`, que precisa afetar 1 linha. Senão, `403`, contado em R8m;
7. `UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp()` só na sessão da requisição;
8. evento 13. As chaves estrangeiras dele pedem a identidade, que já está travada por esta transação (desenho §13.5, requisito 7);
9. **sessão reconferida** pelo relógio do banco, depois de todas as esperas e antes do commit: revogada ou vencida, uma exceção desfaz a transação inteira (passo, marcador e evento) e a resposta é `401`, sem contar em R8m;
10. **janela do passo reconferida** antes do commit. Fora dela, uma exceção desfaz a transação inteira (passo, marcador e evento) e a resposta é `403`, sem contar em R8m.

As triggers da 017 continuam valendo por baixo: passo que não avança, step-up que recua, step-up no futuro e step-up em sessão revogada são recusados.

## 3. Testes

`IdentityStepUpIT` (15 testes, 2 deles da revisão 1, papel real `nekkora_platform`, 017 R5, Redis real). Cada recusa confere a resposta **e** o estado persistido: marcador da sessão, passo do fator e eventos 13.

| Teste | O que prova |
|---|---|
| `stepUpElevaSoASessaoDaRequisicaoComEventoEAntiReplay` | `204`; marcador só na sessão da requisição (a outra sessão da mesma identidade não muda); `identity_session_proof(…, 'step_up')` verdadeira; um evento 13, sem organização; o passo avança. O mesmo código: `403` em outra sessão, `403` na mesma, e `401` na verificação de um login novo (mesmo fator, mesmo anti-replay) |
| `stepUpHabilitaAFuncaoDeCriacaoQueAntesRecusava` | `identity_create_organization` com o token da sessão: recusa antes do step-up, cria depois. Mostra que o P1 alimenta a prova que o P2 vai usar |
| `codigoErradoRecusaSemGravarNada` | código errado e formato inválido: `403`, nada gravado |
| `corpoSemCodigoE400ECodigoDeRecuperacaoNaoEAlternativa` | sem corpo, `{}` e `{recoveryCode}`: `400`; nenhum código de recuperação consumido |
| `sessaoRestritaRecebe403SemConferirOCodigo` | sessão nascida de código de recuperação: `403`, passo intocado, sem evento |
| `semSessaoOuComSessaoEncerradaE401` | sem cookie e depois do logout (cookie reapresentado): `401` |
| `csrfDaSessaoDeIdentidadeEOrigemSaoExigidos` | sem `X-CSRF-Token`, com token de outro contexto, com `Origin` diferente: `403`, nada gravado. Controle: com os dois corretos, `204` |
| `codigoErradoContaEmR8mPelaIdentidadeGlobal` | 12 códigos errados, cada um de um IP diferente (fora de R8 por identidade + IP). Passado o limiar, `429` no step-up e também na verificação do login da mesma identidade, ambos de IPs novos |
| `logoutDuranteAEsperaPelaIdentidadeERecusado` | sessão revogada enquanto o step-up espera a identidade: `401`, nada gravado |
| `suspensaoDuranteAEsperaPelaIdentidadeERecusada` | identidade suspensa durante a mesma espera: `401`, nada gravado |
| `stepUpTravaAIdentidadeAntesDaSessaoESemCicloComOLogout` | com a **sessão** travada por outra transação, o step-up espera segurando a **identidade** (um `FOR UPDATE NOWAIT` nela falha com `55P03`). O logout real (identidade → sessão) entra na fila atrás dele. Liberada a sessão: step-up `204`, depois logout `204`, sem `40P01`; revogação depois do marcador |
| `passoVencidoDepoisDaValidacaoDesfazTudo` | o fator é travado por outra transação sem alteração. O step-up valida o passo e espera no anti-replay, e o passo vence nessa espera. Resultado: `403` e rollback integral, sem marcador, sem avanço do passo e sem evento |
| `sessaoVencidaDuranteAEsperaPelaSessaoE401SemEfeito` (revisão 1) | prazo da sessão encurtado para 5 s; **só a linha da sessão** travada por outra transação, sem alteração; liberada depois que o relógio do banco passou do prazo, com o TOTP ainda na janela: `401`, sem marcador, sem avanço do passo, sem evento. Controle: a mesma espera liberada antes do vencimento, `204` |
| `sessaoVencidaDuranteOAntiReplayDesfazTudoCom401` (revisão 1) | prazo encurtado para 5 s; **só a linha do fator** travada; o step-up passa pela sessão no prazo, valida o TOTP e espera no anti-replay; a sessão vence nessa espera, com o TOTP ainda na janela: `401` e rollback integral (passo, marcador, evento). Controle: liberada antes do vencimento, `204` |
| `falhaAoGravarOEventoDesfazOStepUp` | gatilho temporário recusa o evento 13: `500`, sem marcador e sem avanço do passo |

Fora da classe:
- `IdentityStepUpRedisDownIT` (Redis exclusivo, derrubado): `503`, código não conferido, nada gravado;
- `SignupDisabledIT`: a rota entra na lista das que respondem `404` com a chave desligada, com e sem CSRF, em POST e GET;
- `SignupContractTest`: a rota está publicada na OpenAPI com `204/400/401/403/404/429/503`, `identitySessionCookie` e `csrfHeader`.

Logs da classe, conferidos: só `evento=identidade.step_up` e `evento=identidade.step_up_negado`, com a identidade em HMAC, o IP truncado e a regra (`codigo`, `sessao`, `sessao_vencida`, `passo_vencido`). Nenhum e-mail, código, token ou hash.

### Contraprovas (mutações)

Cada mutação foi aplicada ao `IdentityStepUpService` (e, nas Mt2 e Mt3, ao `IdentityAuthRepository`), com `IdentityStepUpIT` rodado contra ela, e depois revertida. Na revisão 1, as oito foram refeitas sobre o código corrigido (15 testes), e entraram as Mt1 a Mt3. No fim, os fontes conferem byte a byte com os de antes (`FONTES_IDENTICAS`). Diffs e logs estão no pacote.

| Mutação | Testes reprovados |
|---|---|
| Ms0: sem reconferir sessão e identidade sob o lock | `logoutDuranteAEspera…`, `suspensaoDuranteAEspera…` |
| Ms1: sessão travada antes da identidade | `stepUpTravaAIdentidadeAntesDaSessao…` (o `NOWAIT` na identidade deixa de falhar), `logoutDuranteAEspera…` (`500` em vez de `401`: a revogação do teste espera a sessão que o step-up já travou, enquanto o step-up espera a identidade) |
| Ms2: sem anti-replay | `stepUpElevaSoASessao…` (o replay passa), `passoVencidoDepoisDaValidacao…` e `sessaoVencidaDuranteOAntiReplay…` (sem o `UPDATE` do fator, não há espera depois da validação) |
| Ms3: sem a janela final | `passoVencidoDepoisDaValidacaoDesfazTudo` |
| Ms4: evento em transação separada | `falhaAoGravarOEventoDesfazOStepUp` (marcador e passo ficam gravados) |
| Ms5: sem recusa da sessão restrita (as duas conferências) | `sessaoRestritaRecebe403SemConferirOCodigo` |
| Ms6: código errado fora de R8m | `codigoErradoContaEmR8mPelaIdentidadeGlobal` |
| Ms7: sem evento | `stepUpElevaSoASessao…`, `falhaAoGravarOEvento…`, `stepUpTravaAIdentidade…` e os controles dos dois testes da revisão 1 |
| Mt1 (revisão 1): sem a reconferência final da sessão | `sessaoVencidaDuranteOAntiReplayDesfazTudoCom401` (`204` em vez de `401`). O cenário da espera pela sessão continua `401`, pela medição posterior ao lock |
| Mt2 (revisão 1): prazo medido no mesmo `SELECT … FOR UPDATE` (forma anterior) e sem a reconferência final | os dois testes da revisão 1 (`204` em vez de `401`). Comprova que, no mesmo comando, o `clock_timestamp()` foi calculado antes da espera pelo lock |
| Mt3 (revisão 1, informativa): prazo no mesmo `SELECT … FOR UPDATE`, reconferência final mantida | nenhum: mesmo com a leitura inicial antiga, a reconferência final cobre os dois cenários e devolve `401` com rollback. Isso não demonstra que as duas verificações sejam independentes |

Oito de oito detectadas, mais Mt1 e Mt2. A Mt3 não é uma mutação a detectar: mostra que a defesa final cobre os dois cenários mesmo com a leitura inicial antiga. No fim, os fontes conferem byte a byte com os de antes (`FONTES_IDENTICAS`).

## 4. Regressão

**Revisão 1 (estado atual):**
- **Regressão Java completa** (`mvn -o test`, relatórios limpos antes), com a 017 R5: **70 classes, 430 testes**, `BUILD SUCCESS` em 17:10 min. **Sem falhas novas**: só as 5 ocorrências conhecidas do `FixtureRunnerIT` (4 falhas e 1 erro, bash 3.2). Comparada por classe com a regressão da entrega do P1, a única diferença é `IdentityStepUpIT`, de 13 para 15 testes.
- **E2E entre processos** (`bash api/e2e/run.sh`), rodado depois da regressão e sem sobreposição: `CrossProcessE2E` 6/6, `E2E OK`. Depois, a stack descartável foi removida com `docker compose -p nekkora-e2e down`, sem `-v`. O E2E não exercita o step-up; ele confere que o resto continua funcionando com a mudança.
- **Execução incompleta antes desta.** Uma primeira rodada da regressão da revisão 1 foi encerrada pelo Claude Code por falta de memória no sistema, enquanto a sessão estava ociosa. Ela não é aprovação nem falha do produto. O log foi escrito por cerca de 2 h (a da entrega do P1 levou 16:25 min) e parou ao iniciar `IdentityStepUpIT`, com 57 classes concluídas. Além das 5 conhecidas, registrou falhas em `RedisUnavailableIT`, `ProductReadMatrixIT`, `SessionsAndAdministrationIT` e `SignupIT`: `login passo 1: 403`, `503`, e tempos de classe entre 578 e 1111 s. Na rodada completa acima, essas quatro classes passaram sem mudança de código. Não está demonstrado qual processo consumia a memória. Antes de repetir: nenhum processo Maven ou Surefire ativo, nenhum contêiner listado (o Ryuk do Testcontainers já tinha removido os da rodada) e nada foi removido por mim. Na repetição, a memória livre do sistema ficou entre 27% e 32% e o swap perto de 12 GiB de 13 GiB, sem nova interrupção. Logs da rodada interrompida e amostras de memória estão no pacote.

**Entrega do P1 (antes da revisão 1):** 70 classes, 428 testes, sem falhas novas, com as mesmas 5 do `FixtureRunnerIT`; E2E 6/6.

## 5. Arquivos alterados

Contra os fontes da revisão 2 do bloco 3 (a base foi reconstruída do pacote r2 e conferida: fora dos arquivos abaixo, o restante é idêntico; a OpenAPI de base confere byte a byte com a do pacote do bloco 3).

| Arquivo | Mudança |
|---|---|
| `identity/IdentityStepUpService.java` | novo |
| `identity/IdentityAuthRepository.java` | `EV_STEP_UP = 13`; `LockedSession`; `identityActive`, `lockSession`, `consumeStep`, `recordStepUp` |
| `web/IdentityAuthController.java` | rota `STEP_UP`, corpo `StepUpRequest(code)` |
| `web/IdentityFilter.java` | `STEP_UP` em `SESSION_ROUTES` |
| `web/IdentityRoutesGate.java` | `STEP_UP` em `ENTRY_ROUTES` |
| `web/Routes.java` | `/api/v1/auth/step-up` → `IDENTITY_SESSION` |
| `config/IdentityConfig.java` | bean do serviço |
| `openapi/nekkora-api-v1.yaml` | caminho `/api/v1/auth/step-up` |
| testes | `IdentityStepUpIT` e `IdentityStepUpRedisDownIT` novos; `SignupDisabledIT` e `SignupContractTest` com a rota nova |

`SecurityConfig` **não** mudou: a rota não é pública, e o Spring Security exige a autenticação que o `IdentityFilter` dá. Nem `Routes.reducesAccess` (o step-up eleva acesso, então passa pelos limitadores).

Documentos:
- contratos: situação da P1 e uma correção na P4. O caminho real da rota existente é `/api/v1/o/{org}/auth/mfa/step-up`; o texto omitia o `/mfa`;
- desenho: nota na §6.3;
- levantamento: §4 e §5 (classificação da rota).

## 6. Limites declarados

- **O sucesso não zera R8m.** É o mesmo comportamento do step-up por organização (`AuthService.stepUp`). O contador só zera na entrada completa (login).
- **Passo que vence enquanto espera a identidade ou a sessão** conta como código errado, como na verificação do login: o TOTP só é conferido depois desses locks. Só o vencimento **depois** da validação (espera no fator ou no evento) cai na reconferência final, que responde `403` sem contar.
- **A sessão restrita é recusada pelo principal**, antes de qualquer consulta, e de novo sob o lock. A marca `recovery_used_at` é imutável depois do nascimento da sessão (trigger da 017), então não há como uma sessão ficar restrita no meio do pedido. A segunda conferência é defesa.
- **Sem fator confirmado** responde `403` sem contar em R8m. Hoje uma sessão de identidade não existe sem fator confirmado; o caso só aconteceria por alteração direta no banco.
- **P4 continua pendente:** o step-up por organização em sessão **vinculada** ainda segue o caminho do fator local, a lacuna já declarada no bloco 3 (§6). Este bloco não muda essa rota.

## 7. O que vem depois

P2 (criação de organização, com a função de consulta da R5 em `canCreateOrganization`), depois P4 e P3, cada um entregue para revisão. A capacidade inicial de execução continua decisão de produto aberta. A entrada geral continua fora do alcance do usuário final. Integração web, transporte real, semeaduras, implantação, Microsoft 365 e commit/push continuam fora.

## 8. Revisão 1: sessão vencida durante as esperas

**Achado da responsável.** A sessão era conferida só em `lockSession`. Depois disso, o anti-replay pode esperar o lock do fator. `recordStepUp` exige só `revoked_at IS NULL`, e a verificação final reconferia só a janela do TOTP. Assim, uma sessão podia vencer durante a espera, com o TOTP ainda válido, e o caminho gravava passo, marcador e evento e respondia `204`. A trigger da 017 não barra isso. O contrato pede `401` sem efeitos.

**Correção:**
- `lockSession` separa a aquisição do lock (`SELECT 1 … FOR UPDATE`) da avaliação (prazo, revogação, MFA, restrição), feita num segundo comando já com a linha travada. No mesmo comando, a lista de saída do `SELECT … FOR UPDATE` é calculada antes da espera pelo lock, e só é recalculada se a linha mudou. Por isso, encurtar o prazo sem alterar a linha passava despercebido. A Mt2 comprova isso.
- `sessionStillValid` reconfere revogação e prazo pelo relógio do banco, depois de todas as esperas, logo após o evento e antes da janela do passo. Se a sessão caiu, uma exceção desfaz a transação inteira (avanço do fator, marcador, evento), a resposta é `401` e nada conta em R8m. O log registra a regra `sessao_vencida`.
- `recordStepUp` não mudou; a guarda dele continua a da trigger. O prazo é conferido em dois pontos: na leitura depois do lock da sessão e na reconferência final. A reconferência final é a que cobre o vencimento durante as esperas posteriores (anti-replay, marcador, evento).

**Provas dirigidas** (`IdentityStepUpIT`, com o prazo da sessão encurtado para 5 s pelo superusuário; a 017 só permite encurtar). Nas duas, o teste confere que o TOTP continua na janela no fim:

| Cenário | Resultado | Controle (liberada antes do vencimento) |
|---|---|---|
| só a linha da sessão travada até ela vencer | `401`, sem marcador, passo intocado, sem evento | `204`, evento gravado |
| só o fator travado; a sessão vence durante o anti-replay | `401`, rollback integral | `204`, marcador e evento gravados |

**Contraprovas:** Mt1 (sem a reconferência final) reprova o segundo cenário com `204`. Mt2 (prazo de volta ao mesmo `SELECT … FOR UPDATE` e sem a reconferência final) reprova os dois. Mt3, informativa, mantém a reconferência final com o prazo no mesmo `SELECT`, e os dois cenários dão `401`: a defesa final cobre esses cenários mesmo com a leitura inicial antiga, o que não demonstra independência entre as duas verificações. Detalhes na §3.

O que não mudou: o serviço próprio, o sucesso sem zerar R8m, a distinção entre passo vencido antes da validação (conta como código errado) e depois dela (`403` sem contar), e a ordem dos locks.
