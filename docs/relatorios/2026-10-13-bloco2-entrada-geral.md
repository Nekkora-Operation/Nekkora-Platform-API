# Bloco 2 das rotas de identidade: entrada geral (login, desafio e MFA)

**Data:** 2026-10-13. **Autorização:** bloco de login, desafio e MFA da identidade confirmada, usando as funções da 017.

**Fora deste bloco:**
- organizações, troca para sessão de organização e vinculação das contas antigas, que são os próximos blocos;
- substituição do fator (T1 da DC-17.13) e troca de senha, que ficam no bloco de credenciais;
- integração web, transporte real de e-mail, semeaduras, implantação, Microsoft 365, ambiente preservado e commit/push.

Nenhum desses foi tocado. **A 017 não mudou** (R4b, hash `102b9f07…`).

> **Revisão 1 (2026-10-13), depois da leitura da responsável.** A primeira versão foi preservada em `pacote-017-bloco2-entrada-geral-2026-10-13`.
>
> 1. **TOTP vinculado ao fator e ao instante da conclusão.** A conferência saiu de uma transação anterior e passou para dentro da transação de conclusão:
>    - a identidade é travada; `identity_mfa_replace` trava a identidade antes, então a troca de fator fica fora do intervalo;
>    - o fator é lido sob esse lock;
>    - o TOTP é conferido depois da espera pela identidade. *Precisão da revisão 2: isso não cobria as esperas posteriores, dentro da função da 017; ver a revisão 2 abaixo;*
>    - a função da 017 conclui na mesma transação;
>    - por fim, a transação confere que o passo foi gravado **no mesmo fator** conferido; senão, é desfeita.
>
>    O mesmo vale para o primeiro fator, que já passava o id do fator.
> 2. **Reconferência antes do desafio.** Depois do lock da identidade e antes do `INSERT`: mesma identidade, ACTIVE, e-mail verificado e **hash vigente igual ao conferido pelo Argon2**, lidos pela função da 017.
> 3. **Chaves separadas.**
>    - `nekkora.cadastro.enabled`: pedido, confirmação e reenvio;
>    - `nekkora.entrada-geral.enabled`: login, primeiro fator e verificação;
>    - as duas desligadas por padrão;
>    - **o logout de identidade não tem chave.**
>
>    Um desligamento completo da identidade, se vier, terá nome e efeito próprios.
> 4. **Logout de identidade provado** com a cota R7 esgotada e com o Redis indisponível, preservando CSRF e revogação.
>
> **As quatro provas concorrentes reproduzem o defeito na versão anterior.** Usei uma cópia de rascunho com o serviço e o repositório anteriores; nos quatro cenários, a resposta foi 200 onde deveria ser 401 (§5).

> **Revisão 2 (2026-10-13): validade do TOTP depois das esperas da função.** A revisão 1 validava o TOTP depois da espera pela identidade, mas a função da 017 ainda trava desafio, credencial e fator **depois** dessa validação, e só confere o anti-replay, não a janela do passo. A frase "depois de qualquer espera" estava forte demais.
> - **Reprodução na versão da revisão 1.** Outra transação trava só a linha do desafio; a requisição valida um TOTP válido e não usado e espera o desafio dentro da função; o passo vence com o desafio ainda válido. Resultado: **200** na verificação e **200** no primeiro cadastro do fator.
> - **Correção.** Depois que a função retorna, ou seja, depois das esperas dela, e antes do commit, a mesma transação reconfere a janela do passo (agora-1 … agora+1, a tolerância de `Totp.verify`). Fora dela, uma exceção desfaz a transação inteira: sessão, consumo do desafio e avanço do fator; no primeiro cadastro, também a confirmação do fator e os códigos. Isso vale nos dois caminhos.
> - **Depois do commit** não há espera por lock no caminho TOTP. A conferência adiada da 017 só atua quando há código de recuperação.
> - **Mz5:** a formulação passou a ser "não exercitada independentemente nos cenários atuais" (§4 e §5).

## 1. Rotas e contrato

Publicadas em `api/openapi/nekkora-api-v1.yaml`, com a tag "Entrada geral (identidade)" e dois esquemas de segurança novos, `identityChallengeCookie` e `identitySessionCookie`. `SignupContractTest` confere que o controller e o contrato coincidem.

| Rota | Contexto CSRF | Sucesso | Recusa |
|---|---|---|---|
| `POST /api/v1/auth/login` `{email, password}` | pré-autenticação | `200 {status, csrfToken}` + `__Host-nekkora_id_mfa` (Strict, 5 min). `status`: `mfa_required` ou `mfa_enrollment_required` | `401` único; `429` (R8/R12) |
| `POST /api/v1/auth/mfa/enroll/start` | desafio de identidade | `200 {otpauthUri}` | `401` |
| `POST /api/v1/auth/mfa/enroll/confirm` `{code}` | desafio de identidade | `200 {recoveryCodes, csrfToken}` + `__Host-nekkora_id` (Lax, 12 h) | `401` |
| `POST /api/v1/auth/mfa/verify` `{code}` ou `{recoveryCode}` | desafio de identidade | `200 {csrfToken}` + `__Host-nekkora_id` | `400` se não vier exatamente um; `401` |
| `POST /api/v1/auth/logout` | logout de identidade | `204`, com o cookie expirado. Sem sessão válida: no-op | `403` se houver sessão e o CSRF não conferir |

Todas ficam fora de `/api/v1/o/{org}`. Login e MFA respondem `404` com a chave da entrada geral desligada; o logout **não tem chave** (§3).

## 2. Estrutura

| Onde | O quê |
|---|---|
| `platform.identity` | `IdentityLoginService` (só este fluxo), `IdentityAuthRepository` (sem estado: desafio, fator, sessão e chamadas às funções da 017) e `IdentitySessionResolver` (resolve sessão e desafio dos cookies **a cada requisição**, para os filtros) |
| `platform.web` | `IdentityAuthController`; `IdentityFilter`, irmão do `SessionFilter`, só nas rotas sem organização; contextos `IDENTITY_CHALLENGE` e `IDENTITY_LOGOUT` em `Routes` e `CsrfFilter`; cookies em `Cookies` |
| `platform.auth.crypto` | `SecretCipher.encryptForIdentity` e `decryptForIdentity`: AAD `identity\|<identity_id>\|<key_id>`, sem organização (DC-17.3). A via de organização ficou byte a byte igual |
| `platform.limits` | R8 (senha, valores de R1/R2) e R8m (código, valores de R4), pela identidade **global** e sem organização |
| `platform.config` | beans na `IdentityConfig`; `IdentityFilter` e rotas públicas na `SecurityConfig` |

**As regras de dependência continuam verdes:**
- a identidade só usa `auth.crypto` de `platform.auth`;
- os dois serviços de fluxo (`SignupService` e `IdentityLoginService`) não se referenciam;
- os controllers e filtros não usam `JdbcClient`.

## 3. Decisões para revisão

1. **Chaves (revisada).** A primeira versão usava a chave do cadastro para tudo, e a responsável não ratificou isso. Agora:
   - `nekkora.cadastro.enabled` desliga só o cadastro;
   - `nekkora.entrada-geral.enabled` desliga login, primeiro fator e verificação;
   - o logout não tem chave.

   O filtro `IdentityRoutesGate` aplica as duas chaves, e os controllers recusam com 404 como segunda barreira.
2. **Sessão restrita sem saída neste bloco.** O login por código de recuperação cria a sessão restrita (T0 da DC-17.13, no banco). O fator novo e a liberação (T1, `identity_mfa_replace`) pertencem ao bloco de credenciais. Até lá, a sessão restrita só serve para logout; nenhuma rota deste bloco usa a sessão de identidade para outra coisa. A resposta segue o contrato (`{csrfToken}`) e **não informa** a restrição. Se a web precisar saber, é um campo a decidir no bloco de credenciais.
3. **Aviso de código de recuperação síncrono,** depois do commit e com a falha registrada. **Aceito pela responsável só nesta etapa local:** antes do transporte real, é preciso tratar o tempo máximo e as falhas. Uma espera longa pode impedir a entrega do cookie depois de o código já ter sido consumido.
4. **Código errado e passo repetido contam como tentativa.** O passo TOTP já usado, recusado pelo anti-replay do banco (`FATOR_RECUSADO`), e o código de recuperação inexistente também contam. Cinco tentativas invalidam o desafio (teto do `CHECK` da 017), com a R8m.
5. **Prazo da sessão pelo relógio do banco,** lido na mesma transação da função (`now() + 12 h`), para caber no `CHECK` e na validação de 12 h da 017 sem depender do relógio da aplicação.

## 4. Garantias e limites

**O banco garante,** pelas funções da 017:
- consumo único do desafio;
- credencial vigente (senha trocada depois do passo 1 não gera sessão);
- anti-replay do passo TOTP;
- primeiro fator só para quem nunca confirmou;
- código de recuperação de uso único, com a sessão restrita, a revogação das demais sessões e a invalidação dos demais desafios;
- nascimento da sessão só por função;
- re-hash por comparar e trocar.

**A aplicação confere** o TOTP (com o segredo decifrado no AAD da identidade), o Argon2 (com hash fictício para identidade inexistente), o formato dos códigos e os limites. **Limite declarado (desenho, §13.1):** o banco não verifica Argon2 nem TOTP.

**Também a aplicação, sob o lock da identidade:**
- **Passo 1:** reconfere identidade, estado, verificação do e-mail e hash vigente antes de emitir o desafio.
- **Conclusão:** lê o fator, confere o TOTP e chama a função na mesma transação. O fator não pode ser trocado no intervalo (a troca trava a identidade antes).
- **Verificação final (revisão 2):** depois que a função retorna e antes do commit, o passo precisa estar dentro da janela no instante medido **depois das esperas da função**, e precisa estar gravado no fator conferido. Senão, rollback integral.

**Limite da conferência do fator:** ela **não foi exercitada independentemente nos cenários atuais**, porque o lock da identidade já exclui a troca pelos caminhos que seguem a ordem de locks (a mutação Mz5 sobrevive, §5). O vínculo ao fator é garantido pela ordem de locks e pela aplicação. A função da 017 continua sem receber o id do fator; levar o vínculo para dentro dela exigiria revisar a 017.

**Cookies:** os dois cookies nunca valem na mesma rota. O `IdentityFilter` não roda em `/api/v1/o/…`, e o `SessionFilter` não lê o cookie de identidade (testado: a sessão de identidade recebe `401` numa rota de organização).

## 5. Testes

| Classe | Testes | Cobre |
|---|---|---|
| `IdentityLoginIT` (as duas chaves ligadas; identidade criada pelo cadastro real; TOTP calculado do segredo da URI) | 20 | ver abaixo |
| `IdentityKeysIT` (**cadastro desligado, entrada ligada**) | 1 | as três rotas de cadastro em `404`; identidade existente entra (login, primeiro fator e verificação) e sai (logout revoga) |
| `IdentityLogoutLimitsIT` (Redis exclusivo, R7 com recomposição mínima) | 1 | com a cota R7 do IP esgotada (controle imediatamente antes: `429`), o logout com CSRF errado dá `403` sem revogar; com o CSRF certo, `204` e a sessão revogada |
| `IdentityLogoutRedisDownIT` (Redis exclusivo, derrubado) | 1 | controle: login com `503`; logout com CSRF errado dá `403`; com o certo, `204` e a sessão revogada |
| `SecretCipherIdentityTest` | 1 | ida e volta; outra identidade não decifra; o ciphertext de identidade não vale como de organização e vice-versa; a via de organização está inalterada |
| `SignupDisabledIT` (tudo desligado) | 2 | `404` nas três rotas de cadastro e nas quatro de login e MFA; **o logout continua disponível**: sem sessão, no-op; com sessão, CSRF exigido e revogação |
| `SignupContractTest` | 2 | cadastro e entrada geral iguais ao OpenAPI |
| `IdentityDependencyRulesTest` | 2 | as sete regras, agora com dois serviços de fluxo |

**`IdentityLoginIT` cobre:**
- **Passo 1:** login sem fator abre desafio de cadastro (atributos do cookie conferidos, nenhuma sessão); resposta única para identidade inexistente, senha errada, identidade não confirmada e **identidade suspensa com a senha certa**, sem desafio criado; R8 (`429` na sexta tentativa, mesmo com a senha certa).
- **Primeiro fator:**
  - cadastro completo: CSRF exigido, fator pendente pela função, código errado registrado, sessão, 10 códigos, evento, só o hash da sessão no banco;
  - desafio de verificação não serve para cadastrar fator.
- **Verificação:**
  - TOTP com evento, e o passo repetido recusado;
  - código de recuperação: sessão restrita, demais sessões revogadas, 9 códigos sobrevivendo até T1, aviso, uso único;
  - exatamente um código;
  - cinco códigos errados invalidam o desafio;
  - senha trocada depois do passo 1;
  - re-hash de credencial com parâmetros antigos;
  - verificações concorrentes com o mesmo código criam uma só sessão.
- **Logout e cookies:** CSRF exigido com sessão, revogação, evento e no-op sem sessão; o cookie de identidade não vale em rota de organização.
- **Concorrência (revisão 1).** Nos quatro testes, o superusuário segura a identidade, a espera da requisição é observada no banco e a mudança é confirmada durante a espera:
  - **troca do fator A por B entre a validação e a conclusão,** com B cifrado como a troca legítima o cifraria: 401, nenhuma sessão, passo de B intocado;
  - **passo TOTP vencido durante a espera,** escolhido depois do último passo usado, para que só o vencimento possa recusar: 401, com o desafio ainda válido e não consumido;
  - **suspensão** entre a primeira leitura e a emissão do desafio: resposta genérica, nenhum desafio;
  - **troca de senha** no mesmo intervalo: idem.
- **Espera posterior à validação (revisão 2).** Outra transação trava só a linha do desafio, sem alterá-la; a requisição valida um TOTP válido e não usado e fica esperando **dentro da função da 017**; o passo vence, e o desafio continua válido:
  - **verificação:** 401, nenhuma sessão, desafio não consumido e ainda válido, `last_used_step` do fator inalterado;
  - **primeiro cadastro do fator:** 401, nenhuma sessão, desafio não consumido e ainda válido, fator ainda pendente e sem passo, nenhum código de recuperação gravado.

**Contraprovas.** Cada mutação foi aplicada, testada e revertida, e os fontes foram conferidos idênticos depois.

| Mutação | Reprovou |
|---|---|
| Mx1: cadastro do fator sem conferir o TOTP | teste do primeiro fator |
| Mx2: código errado sem registrar tentativa | 3 testes |
| Mx3: CSRF do desafio ignorado | teste do primeiro fator |
| Mx4: CSRF do logout ignorado | teste do logout |
| Mx5: propósito do desafio ignorado | teste do desafio de verificação |
| Mx6: login sem conferir a elegibilidade | **sobreviveu na primeira rodada**: no contrato novo do cadastro, a identidade pendente não tem credencial, então a função de login não a devolve. Acrescentei a identidade **suspensa com senha**, e a mutação passou a reprovar |
| Mx7: sem R8 | teste da R8 |
| Mx8: AAD da identidade igual ao formato de organização | `SecretCipherIdentityTest` |

**Revisão 1, reprodução na versão anterior.** Cópia de rascunho com `IdentityLoginService` e `IdentityAuthRepository` da primeira versão e os testes novos:

| Cenário | Versão anterior | Versão atual |
|---|---|---|
| troca de fator durante a espera | `200`: concluiu sobre B | `401` |
| passo vencido durante a espera | `200` | `401` |
| suspensão antes da emissão | `200`: desafio emitido | `401` |
| troca de senha antes da emissão | `200`: desafio emitido | `401` |

**Duas correções no próprio teste, antes de chegar a esse resultado:**
- o envio da espera longa estourava o prazo fixo de 30 s do `BrowserClient`; passou a usar prazo de 120 s, com os mesmos cookies e cabeçalhos;
- o passo escolhido podia já ter sido usado no cadastro do fator, e então o anti-replay recusava pelo motivo errado. Com essa falha, o teste passava também na versão anterior. Agora o passo é posterior ao último usado.

**Contraprovas da revisão 1.** Cada mutação foi aplicada, testada e revertida, e os fontes foram conferidos idênticos depois.

| Mutação | Reprovou |
|---|---|
| Mz1: login sem a reconferência | os testes da suspensão e da troca de senha |
| Mz2: logout de volta sob chave | o teste do logout com tudo desligado |
| Mz3: entrada geral presa à chave do cadastro | `IdentityKeysIT` |
| Mz4: logout de identidade fora de `reducesAccess` | `IdentityLogoutLimitsIT` |
| Mz5: sem a conferência do passo no fator conferido | **sobrevive**: defesa não exercitada independentemente nos cenários atuais, porque o lock da identidade já exclui a troca pelos caminhos que seguem a ordem de locks |

**Revisão 2: reprodução e contraprovas.**

| Execução | Verificação | Primeiro cadastro |
|---|---|---|
| código da revisão 1, sem a verificação final | `200`: passo vencido aceito | `200`: passo vencido aceito |
| código da revisão 2 | `401`, sem efeito | `401`, sem efeito |
| Mw1: revisão 2 **sem** a verificação final da verificação | **reprova** | passa (o caminho dele tem a verificação) |
| Mw2: revisão 2 **sem** a verificação final do primeiro cadastro | passa | **reprova** |

Em todas as execuções, os fontes foram restaurados e conferidos idênticos.

**Regressão Java completa (revisão 2):** **sem falhas novas**. São 65 classes e 396 testes, iguais à revisão 1, exceto `IdentityLoginIT`, que passou de 18 para 20. Continuam as **5 falhas conhecidas** de `FixtureRunnerIT` com o bash 3.2 (4 falhas e 1 erro). Logs de `IdentityLoginIT`: nenhum e-mail, token, hash de 64 hexadecimais, URI otpauth ou hash Argon2 (contagens: emails=0 tokens=0 hashes=0 otpauth=0 argon=0).

**Na revisão 1:** **Regressão Java completa (revisão 1):** **sem falhas novas**. São 65 classes e 394 testes: iguais à primeira versão do bloco, mais `IdentityKeysIT`, `IdentityLogoutLimitsIT` e `IdentityLogoutRedisDownIT` (1/1 cada), com `IdentityLoginIT` passando de 14 para 18 e `SignupDisabledIT` de 1 para 2. Continuam as **5 falhas conhecidas** de `FixtureRunnerIT` com o bash 3.2 (4 falhas e 1 erro).

**Logs** das classes de identidade: nenhum e-mail, nenhum token, nenhuma URI otpauth e nenhum hash Argon2. As duas únicas cadeias de 64 hexadecimais são identificadores de container do Testcontainers ao subir o Redis exclusivo, não dado da aplicação.

**Na primeira versão:** **Regressão Java completa:** **sem falhas novas**. São 62 classes e 386 testes: iguais às do bloco 1 (revisão 2), mais `IdentityLoginIT` (14/14) e `SecretCipherIdentityTest` (1/1), e `SignupContractTest` passou de 1 para 2. Continuam as **5 falhas conhecidas** de `FixtureRunnerIT` com o bash 3.2 (4 falhas e 1 erro).

**Logs de `IdentityLoginIT`:** nenhum e-mail, nenhuma cadeia com formato de token, nenhum hash de 64 hexadecimais, nenhuma URI otpauth, nenhum hash Argon2 e nenhum código TOTP. Os eventos registrados são `identidade.desafio_emitido`, `identidade.autenticada`, `identidade.negada` e `identidade.rate_limited`, com o HMAC da identidade global e o IP truncado, além de `csrf.negado`.

## 6. Pendências e lacunas declaradas

- **Logout de identidade com R7 esgotado e com Redis fora:** provados na revisão 1 (§5).
- **Redis fora nas demais rotas de identidade:** o login com `503` está provado (controle de `IdentityLogoutRedisDownIT`); as rotas de MFA seguem `503` por construção.
- **Custo:** o `IdentityFilter` abre uma transação de leitura por requisição sem organização que traga o cookie de identidade. Não medido.
- **Sessão restrita:** sem saída até o bloco de credenciais (§3, item 2).
- **Índice da caixa de homologação:** continua sem os tipos novos de e-mail (pendência do bloco 1).
