# Recuperação de senha da identidade por e-mail (missão 002, PR 4)

**Depende de:** PR #11 (019) e, por ele, #10. **Migration nova:** `020_identidade_recuperacao_senha.sql`. 016–019 não são editadas.

**Escopo conferido.** A 017 reservou `identity_email_tokens.purpose = 2` e deixou a recuperação de senha da identidade "para decisão posterior" (contrato de integração Java, §2.3). A missão 002 a autoriza em PR próprio. Os requisitos são:
- resposta uniforme;
- limites de abuso;
- token de uso único, protegido e com prazo;
- consumo e troca na mesma transação;
- invalidações por contrato explícito;
- nenhuma sessão criada;
- MFA preservado.

As contas antigas por organização mantêm o fluxo próprio, que não muda.

## 1. Contrato

Chave própria `nekkora.recuperacao-senha.enabled` (`NEKKORA_RECUPERACAO_SENHA`), desligada por padrão. Só abre com a entrada geral também ligada. Desligada, as duas rotas respondem 404 antes da cadeia (`IdentityRoutesGate`) e de novo no controller.

### Pedido: `POST /api/v1/auth/password-reset/request`

**Acesso.** Pré-autenticação: cookie `__Host-nekkora_pre` e o CSRF dele. Corpo `{"email"}`.

**Resposta uniforme.** Sempre `202` sem corpo, com piso de tempo (`recovery-response-floor`, 300 ms), nestes casos:
- e-mail desconhecido;
- identidade PENDING, SUSPENDED ou CLOSED;
- e-mail não verificado;
- R14 excedido;
- token emitido.

E-mail malformado responde 400; isso depende só do texto enviado, sem enumeração.

**Emissão.** Só a identidade ACTIVE com e-mail verificado recebe token. Sob o lock dela:
1. os tokens de recuperação vivos são revogados (um válido por vez);
2. o novo é gravado só como hash, com propósito 2 e prazo de 30 min (`nekkora.session.password-recovery`);
3. o evento 16 é registrado.

O e-mail `PASSWORD_RECOVERY`, com o token, sai fora do caminho da resposta (fila do cadastro), depois do commit.

**Limites.**
- **R14:** por identidade global e por IP, com os valores de R5 (3 e 10 por hora). Excedido, a resposta é a mesma e nada é emitido.
- **R12:** 429.
- **Redis fora:** 503 (falha fechada).

### Confirmação: `POST /api/v1/auth/password-reset/confirm`

**Acesso.** Pré-autenticação. Corpo `{"token", "newPassword"}`; a senha é zerada depois do uso.

**Antes da transação.** Formato do token, política da senha nova (a da Platform) e Argon2.

**Na transação.** `identity_password_reset` (020) executa um só comando:
1. **Locks**, na ordem global da 017: identidade → token → credencial → demais tokens de recuperação → desafios → sessões.
2. **Hora** medida depois dos locks; o **prazo do token** é reconferido com ela.
3. **Efeitos:**
   - token consumido;
   - senha trocada;
   - **todas** as sessões de identidade revogadas; as sessões de organização derivadas caem pela DC-17.4;
   - desafios pendentes invalidados;
   - demais tokens de recuperação revogados;
   - evento 17.

**Nenhuma sessão é criada.** O MFA não muda: fator e códigos ficam intocados, e a entrada seguinte exige o fator ou um código de recuperação.

**Respostas.**
- 204 em caso de sucesso.
- 400 sem efeito para qualquer recusa: token desconhecido, de cadastro, consumido, revogado ou vencido (inclusive durante a espera), identidade fora de ACTIVE, senha fora da política.
- O aviso `NOTICE_PASSWORD_CHANGED` sai depois do commit; a falha do aviso não desfaz a troca.

**Tokens dos dois propósitos não se cruzam.**
- O cadastro só trava e consome propósito 1 (`IdentityRepository`, já existente).
- A recuperação só aceita propósito 2 (função 020).

Nos dois sentidos, o token alheio é recusado e não é consumido.

## 2. Migration 020

| Mudança | Detalhe |
|---|---|
| Propósito 2 | CHECK `identity_email_tokens_purpose_check`: `purpose = 1` → `purpose IN (1, 2)`, mesmo nome |
| Eventos 16 e 17 | CHECK do evento: `1..15` → `1..17`, mesmo nome |
| Função | `identity_password_reset(text, text)`: SECURITY DEFINER, dona `nekkora_identity_owner`, `search_path` fixo, EXECUTE só para `nekkora_platform` |
| Privilégios do dono | `SELECT (purpose, created_at)` e `UPDATE (consumed_at, revoked_at)` em `identity_email_tokens`. A 017 só lhe dava a leitura do lookup; sem eles, a função falhou com **42501 e nada mudou (medido)**. É o mesmo padrão da 018: o dono é NOLOGIN, sem membros; só as colunas usadas; nenhum papel de LOGIN muda |

**Pré-condição:** 019 aplicada, função inexistente e as duas CHECKs exatamente as anteriores.

**Invariantes:**
- atributos e EXECUTE da função;
- lista exata das funções executáveis pela Platform (substitui as da 017 e da 019);
- as duas CHECKs;
- privilégios do dono exatamente os novos, sem INSERT nem UPDATE de origem do token;
- a Platform continua sem `SELECT` e sem `UPDATE` em `identity_credentials`.

**Elevação:** janela de SUPERUSER, como 017–019. O bootstrap inclui a 020 no padrão.

**Instantes:**
- encerramentos (revogação de tokens e sessões) com `greatest(instante, created_at)`, como a 018;
- consumo do token (escrita de **conclusão**) com o instante medido. Com o relógio recuado, a CHECK `consumed_at >= created_at` recusa sem efeito parcial, e o titular repete dentro do prazo. O consumo não participa de nenhuma autorização posterior: só a presença conta.

## 3. Evidências

**Testes de integração** (`IdentityPasswordRecoveryIT`, 7):

| Garantia | Teste |
|---|---|
| Pedido → token só como hash, propósito 2, prazo de 30 min, evento 16 → confirmação: senha nova, nenhuma sessão criada, **todas** as sessões revogadas, evento 17, aviso, MFA intocado; uso único; senha antiga 401; entrada seguinte exige o fator | `pedidoEConfirmacaoTrocamASenhaRevogamTudoEPreservamOMfa` |
| Mesma resposta (202 vazia, piso de 300 ms) para e-mail desconhecido, PENDING, SUSPENDED e ACTIVE; só a ACTIVE recebe token e e-mail; malformado 400 | `respostaUniformeSemEnumeracao` |
| Novo pedido revoga o token anterior | `novoPedidoRevogaOTokenAnterior` |
| Recusas sem consumo nem troca: política, token de recuperação no cadastro, token de cadastro na recuperação (que continua vivo), malformado, corpo vazio, identidade suspensa, token vencido | `recusasNaoConsomemNemTrocam` |
| Prazo reconferido depois da espera pelo lock da identidade | `prazoReconferidoDepoisDaEspera` |
| Confirmações concorrentes: exatamente uma troca | `confirmacoesConcorrentesTrocamUmaVez` |
| R14 por identidade (o quarto pedido não emite) e CSRF de pré-autenticação exigido | `limiteDePedidosECsrf` |

**Chave:** `IdentityRoutesGateTest.recuperacaoDeSenhaExigeAsDuasChaves`. A rota só abre com as duas chaves, o caminho escapado recebe a mesma decisão e o step-up não depende da chave.

**Contrato publicado:** OpenAPI e `SignupContractTest`.

**Suíte de banco** `db/tests/recuperacao-020`:

| Caso | Resultado |
|---|---|
| A, instalação limpa | oito janelas com retirada `0\|0\|0`; invariantes depois da retirada, adaptadas onde indicado |
| B1, sem SUPERUSER | recusada, nada muda |
| B2, base populada 019 → 020 (80 tabelas) | dados idênticos; catálogo com exatamente 11 linhas previstas; igual à instalação limpa |
| B3, reaplicação | recusada |

**E2E pela borda** (001–020; `NEKKORA_RECUPERACAO_SENHA` ligada só na stack descartável), `IdentityCredentialsE2E` 7/7. A jornada 6 cobre:
- pedido pela borda com resposta idêntica para e-mail desconhecido;
- token emitido (hash) e evento 16 conferidos no banco;
- confirmação com token de **fixture declarada**: sem transporte real, o token do e-mail não chega ao cliente. A fixture grava o token como a aplicação grava (só o hash) e revoga o emitido;
- 204 sem cookie de sessão, todas as sessões revogadas e uso único;
- entrada seguinte com `mfa_required`.

Os logs dos containers não contêm token, senha nem e-mail. `CrossProcessE2E` 6/6 e `LinkedAccountsE2E` 6/6.

**Regressão completa.** A primeira execução falhou em `IdentityDependencyRulesTest`, regra R6: um serviço da identidade não depende de outro.
- **Causa:** o serviço de recuperação usava `SignupService.validEmail`.
- **Correção:** a forma do e-mail passou para `EmailFormat`, usada pelos dois serviços, sem mudar a regra.
- **Na mesma execução:** core 21 e ops 164 passaram; o portal não rodou porque o módulo platform falhou antes.
- **Depois da correção:** dependências, cadastro, recuperação e chave passam (40 testes). A regressão completa do head final e o CI estão registrados no PR.

## 4. Limitações e pendências

- **Entrega real do e-mail (PR 5).** Enquanto o transporte for o local, o token não sai do servidor e a recuperação não é utilizável fora dos testes. A chave fica desligada por padrão. A fila persistente precisa proteger o token na mensagem persistida (PR 5).
- **Perda de senha e de todos os códigos:** continua lacuna declarada. A recuperação de senha devolve o acesso só a quem ainda tem o fator ou um código.
- **Ação de ambiente:** linha de elevação da 020 em `elevacao.txt`; sha256 no PR.
