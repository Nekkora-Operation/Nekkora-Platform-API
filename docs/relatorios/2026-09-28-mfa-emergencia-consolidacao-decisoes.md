# Recuperação emergencial de MFA — consolidação das decisões do §5

**Data:** 2026-09-28.
**Base:** `feat/mfa-emergencia` em `effa516`, com o contrato aprovado em `ca4033b` (revisão 3, SHA-256 `d22f3b6c…4353`, idêntico).
**Natureza:** documental. **Nenhuma** migration, código ou teste foi alterado. Sem commit.
**Estado:**
- as decisões do §1 aprovam regras, mas nada foi implementado ainda;
- os desenhos dos itens 4, 5 e 10 (§4 a §6) incorporam os ajustes pedidos e **aguardam nova conferência**;
- a revisão 4 do contrato só fecha depois deles;
- a implementação continua candidata, e a integração continua pendente.

**Estado na v5:**
- itens 4 e 10 com as duas precisões pedidas (§4.5 e §6.8);
- item 5 aceito, condicionado a I-1 (corrigido, desenho no inventário v2) e a **I-5** (novo, a decidir);
- o desenho de concorrência **não** está encerrado, e a implementação completa não está autorizada.

**Estado na v4:**
- item 5 aceito, condicionado ao inventário;
- itens 4 e 10 com as correções pontuais da conferência da v3, aguardando nova conferência.

**Versões deste documento:**
- **v1:** proposta dos dez itens.
- **v2:** decisões da primeira revisão e desenhos de 4, 5 e 10.
- **v3:**
  - aprova os dois pontos abertos (720/168 h; `ADMIN_PODE_CADASTRAR`);
  - item 4: classificação da origem pelo mecanismo que comprova o controle; sem balde para origem não comprovada; rotação do HMAC que preserva a janela; conclusões de "sem ciclo" retiradas;
  - item 5: coordenação da execução com os fluxos dos administradores considerados, com as duas ordens de chegada;
  - item 10: aceite fora de ordem, serialização, falha antes da fila e recuperação, e versões das tentativas registradas na aprovação.
- **v6 (esta):**
  - I-5 aprovado (inventário v3 §11.1);
  - restrição da reivindicação de entrega (§6.8, passo 2);
  - esquema detalhado e inventário das tabelas novas (inventário v3 §10);
  - **implementação candidata autorizada** (inventário v3, cabeçalho).
- **v5:**
  - registra as decisões sobre I-1 a I-4 (inventário v2);
  - item 4: entrada com dois pseudônimos registrada perto do fim da transição;
  - item 10: perda da confirmação do commit de `ENTREGANDO`, com reivindicação explícita do envio e sem repetição automática;
  - itens 5 e 10: contas dos destinatários no conjunto crescente (I-5).
- **v4:**
  - item 4: rotação do HMAC corrigida (prazo contado da ativação da versão **nova**; entrada anterior usada depois; coordenação da abertura com a ativação; contagem única por solicitação; 25 h derivadas da janela mais a validade da entrada);
  - item 10: protocolo de coordenação entre enfileiramento e resultado (estado `ENTREGANDO` gravado **antes** da entrega, resultados idempotentes);
  - decisão explícita sobre a tentativa vigente;
  - onboarding reescrito;
  - sincronização da cópia marcada como pendente e depois executada e conferida (§7);
  - item 5 aceito, condicionado ao **inventário de locks**, agora em documento próprio (`2026-09-28-mfa-emergencia-inventario-locks.md`).

**Anexo:** `2026-09-28-mfa-emergencia-consolidacao-anexo.md` (revisão 4 proposta, matriz, ajustes de código e testes).
**Referências:** "C:N" = linha do contrato; "016:N" = linha da migration em `effa516`; código da Platform por arquivo e linha em `effa516`.

**Inventário de locks implícitos:** as ordens de lock deste documento são **propostas**.
- A ausência de ciclos **não** está afirmada.
- **Inventário v2:** `2026-09-28-mfa-emergencia-inventario-locks.md` cobre a 016 atual, as migrations 001–015 que ela toca e os fluxos da Platform.
  - Achados I-1 a I-4 decididos.
  - **I-5 proposto:** reset administrativo com ator, avisos da 016 e resets cruzados.
- As tabelas **novas** dos itens 4, 5 e 10 entram no inventário quando o esquema for detalhado. Até lá, as ordens delas continuam propostas.
- Cada ordem abaixo diz o que o inventário precisa confirmar.

## 1. Registro das decisões

| # | Decisão | Onde |
|---|---|---|
| 1 | Carência na inclusão, **inclusive** na contratação: pelo menos **720 horas** completas. Janelas da D6(c): **168 h** e **720 h**. Testar imediatamente antes, exatamente no limite e depois | §3.1 |
| 2 | Impedimento por qualquer contato da organização; teste com contato que não participa | §3.2 |
| 3 | Distinção e lista aprovadas, com os estados resultantes testados; **erros técnicos continuam com rollback**, sem virar recusa | §3.3 |
| 4 | Sem cota por contato alegado. Desenho substituto ajustado | §4 (em conferência) |
| 5 | Declaração (dois valores) e fotografia. Motivos amplos rejeitados. **`ADMIN_PODE_CADASTRAR` aprovado** como restrição conservadora | §5 (em conferência) |
| 6 | (a)–(c) aprovados; (d) rejeitado: nenhuma escrita na organização do token | §3.4 |
| 7 | Alerta na consulta avulsa, inclusive na consulta impossível; sem registro posterior | §3.5 |
| 8 | Confirmado: o ator recebe | §3.6 |
| 9 | Confirmado, preservando a primeira confirmação e `T_total` | §3.6 |
| 10 | Registro de resultados e reenvio por destinatário. **Aprovada** a política de 24 h desde o aceite do aviso de cada outro administrador, preservando a primeira confirmação e as 72 h; aceite **não** é recebimento. Mecanismo ajustado | §6 (em conferência) |
| 10 (v4) | **Aprovada** a tentativa vigente como a **mais recente não falhada**: com a mais nova `FALHOU`, a vigente volta a ser a anterior aceita. **Muda o comportamento da v2** (lá, a vigente era sempre a mais recente). Uma tentativa `DESCONHECIDO` (ou `ENTREGANDO`/`ENFILEIRADA`) não é falha e **bloqueia** essa volta. Qualquer reenvio posterior à aprovação continua impedindo executar, **mesmo que depois falhe** | §6.3, §6.6 |
| Inventário (v5) | **I-1** aprovado: organização → conta → token (lock antes do consumo do token). **I-2** aprovado: ordem canônica com reconferência. **I-3/I-4** aprovados: retirada **pontual** da FK `ops_registry_events.organization_id`, sem virar regra. **I-5** (reset administrativo com ator; avisos da 016; resets cruzados) proposto | Inventário v2, §1 e §4 |
| 5 (v4) | Desenho aceito, **condicionado ao inventário** dos locks implícitos, que inclui os fluxos existentes. Se o inventário revelar inversão, a ordem é ajustada antes de implementar. Ensaios concorrentes complementam o inventário; ausência de `40P01` em algumas rodadas não prova ausência de ciclo | Inventário (documento próprio) |

## 2. Situação por item

| # | Regra | Estado |
|---|---|---|
| 1, 2, 3, 6, 7 | Aprovadas | Prontas para implementar |
| 8, 9 | Confirmadas | Concluídas (testes existem) |
| 4 | Princípio aprovado | Desenho v3 em conferência; dependência nova (serviço de entrada) |
| 5 | Regra e desenho aceitos | **Condicionado a I-1** (decidido; desenho no inventário v2 §4.1) **e a I-5** (proposto: contas envolvidas travadas juntas em ordem crescente, incluindo os destinatários dos avisos) |
| 10 | Política aprovada | Mecanismo v4 (protocolo de enfileiramento) em conferência |

## 3. Itens decididos sem desenho pendente

### 3.1 Item 1 — 720 horas; janelas de 168 e 720 horas

- **Regra:**
  - contato elegível ⇔ ativo e `channel_set_at ≤ v_agora − interval '720 hours'`, para inclusão (na contratação ou depois) e alteração;
  - D6(c): `executed_at > v_agora − interval '168 hours'` (alvo) e `executed_at > v_agora − interval '720 hours'` (organização).
  - Horas, e não dias, tornam a regra independente do `TimeZone` da sessão. Hoje a 016 usa `'30 days'` e `'7 days'` (016:785, :794, :811, :813).
- **Limites exatos:**
  - carência: com 720 h completas, **elegível**; um instante antes, recente;
  - D6(c): a execução anterior deixa de contar quando `executed_at = v_agora − 168 h` (desigualdade estrita no banco).
- **Onboarding:**
  - os dois contatos na contratação;
  - o procedimento só fica disponível quando ambos tiverem 720 h completas;
  - antes disso, **cenário B quando aplicável; a sucessão (D8) depende de procedimento próprio e não contorna a carência**.
- **Testes:**
  - por `p_agora` explícito (superusuário, só na base de teste), em `limite − 1 µs`, `limite` e `limite + 1 s`, para a carência e para as duas janelas;
  - repetição com `TimeZone` de sessão com horário de verão;
  - fluxo com contatos a 719 h e a 720 h.

### 3.2 Item 2 — impedimento por qualquer contato

- **Regra:** comportamento atual (016:727–745).
- **Teste:** operador vinculado a um **terceiro** contato (nem solicitante, nem segundo) → `VINCULO_IMPEDITIVO` em cada ato de que participaria e na reconferência da execução.

### 3.3 Item 3 — recusa do ato × terminal

- **Regra:** tabela do anexo §A.1, item 3. Erros técnicos (guarda `25N01`, exceções de invariante, restrições, conexão) causam rollback integral e **não** viram recusa.
- **Teste `matrizDeRecusas`:**
  - cada motivo → estado e evento;
  - cada erro técnico → estado inalterado e nenhum evento de recusa;
  - mutação de `encerrar`.

### 3.4 Item 6 — tokens não atribuíveis

- **Regra:** (a) sem linha no banco para malformado ou desconhecido; (b) categoria interna com resposta pública idêntica; (c) log sanitizado com finalidade, categoria, organização da rota (não validada), correlação e estado da R7. **Nenhuma escrita** na organização do token quando a rota indicar outra organização.
- **Testes:** categoria sem segredo, com controle positivo; resposta idêntica; nenhum evento na organização do token.

### 3.5 Item 7 — alerta na consulta avulsa

- **Regra:** `consultar` alerta em `TRAVA_NAO_OBTIDA` e em `CONSULTA_IMPOSSIVEL`, mantendo `DESCONHECIDO` e o código 4 (C:235). Sem registro no banco.
- **Testes:** trava presa → alerta, código 4, nada no banco; banco inacessível → alerta, código 4.

### 3.6 Itens 8 e 9 — confirmados

- **8:** todos os `ORG_ADMIN` efetivos além do alvo, incluindo o ator (`SessionsAndAdministrationIT`, linha 219).
- **9:** a confirmação dentro da validade é registrada, e a **primeira** fixa o instante; `T_total` não muda; com `confirmação + 24 h ≥ T_total`, expira sem aprovação (`AvisosIT.confirmacaoValidaTardiaDemaisNaoPermiteAprovarEExpiraSemReset`).

## 4. Item 4 — origem de entrada, retorno e cota (desenho v3)

### 4.1 Princípios

1. A cota protege o esforço operacional. Ela **nunca** é o único caminho, e o caminho legítimo (retorno pelo canal registrado) não é contado nem bloqueável por alegações.
2. A origem só conta quando o **controle dela foi comprovado** por um mecanismo identificado. Nenhuma origem é classificada por indício de transporte.
3. Uma entrada sem origem comprovada pode **iniciar o atendimento**, mas **não abre** solicitação por entrada. A abertura segue pelo retorno, sem consumir cota do titular, da organização ou de ninguém.

### 4.2 Registro de entrada (dependência nova)

- **Captura:** a integração do sistema de atendimento, pela identidade `nekkora_ops_intake` (LOGIN, `EXECUTE` só em `ops_intake_register`). Operadores sem acesso. É uma dependência proposta, simulada nos testes.
- **Registro:** `ops_intake_register(canal_tipo, mecanismo, pseudônimos HMAC com as versões, operador_designado)` → `intake_id`, com o instante do banco.
- **Abertura em modo `ENTRADA`** exige um `intake_id`:
  1. criado pelo papel de entrada;
  2. designado ao mesmo operador (`session_user`);
  3. não usado (uso único, na transação da abertura);
  4. com até 60 min;
  5. com `mecanismo ≠ NAO_COMPROVADA`.
- **Recusas do ato:** `ENTRADA_INVALIDA`, `ENTRADA_DE_OUTRO_OPERADOR`, `ENTRADA_USADA`, `ENTRADA_VENCIDA`, `ORIGEM_NAO_COMPROVADA`.
- **Fronteira de confiança:** um papel de entrada comprometido fabrica origens, a mesma classe da CA (C:89–93). A identidade é separada dos operadores e auditada.

### 4.3 Classificação pelo mecanismo que comprova o controle da origem

"Confiável" deixa de existir como classe genérica. O registro traz o **mecanismo**, e só estes contam como comprovação:

| Mecanismo | Como comprova | O que **não** comprova |
|---|---|---|
| `DESAFIO_EMAIL_IDA_E_VOLTA` | O serviço de entrada envia um código de uso único **para o endereço de origem**, e a pessoa o devolve na mesma entrada | Identidade da pessoa; controle da caixa depois do desafio |
| `DESAFIO_TELEFONE_IDA_E_VOLTA` | O serviço de entrada **liga ou envia código para o número de origem** (ligação de saída ou SMS), e a pessoa devolve o código | Identidade; proteção contra troca de chip ou desvio na operadora |
| `NAO_COMPROVADA` | — | Tudo |

- **Não bastam:**
  - DMARC aprovado (autentica o **domínio**, não a parte individual do endereço);
  - número apresentado (pode ser falsificado);
  - cabeçalhos, formulário sem autenticação ou declaração do operador.
- Quem falsifica a origem de outra pessoa não recebe o desafio. Com isso, não consome a cota dessa pessoa.

### 4.4 Cota

- **Só entradas com origem comprovada** contam: até **3 aberturas por origem a cada 24 h**, em balde **global** entre organizações (`bucket_key` = versão e pseudônimo HMAC da origem; na rotação, a contagem une os dois pseudônimos e conta cada solicitação uma vez, §4.5).
- **Não há balde** para `NAO_COMPROVADA`, nem por organização, nem coletivo. Essa entrada só leva ao retorno (§4.6).
- Contam as solicitações criadas por entrada, em qualquer estado.

### 4.5 Pseudonimização e rotação da chave secreta, sem perder a janela (v4)

- **Pseudônimo HMAC da origem:** `HMAC-SHA-256(K_v, canal_tipo ‖ identificador_normalizado)`.
  - As **chaves secretas** `K_v` ficam só no serviço de entrada (cofre) e **nunca** chegam ao banco. O banco recebe e guarda apenas **pseudônimos HMAC** e o número da versão da chave que os gerou.
  - Não é anonimização: é dado pessoal pseudonimizado e entra em D9.
- **Estado das versões (no banco, todos os instantes são do banco):**
  - linha única `ops_intake_key_state` com a versão vigente `v`, o instante de ativação `ativada_em(v)` e a versão anterior `v−1`;
  - só o papel de entrada altera, por `ops_intake_key_activate(v+1)`, que trava essa linha `FOR UPDATE`;
  - uma nova ativação só é aceita se `v_agora ≥ ativada_em(v) + T_trans` (sem transições sobrepostas; o intervalo operacional proposto entre rotações é de 30 dias).
- **Duração da transição, derivada:** `T_trans = 24 h + 60 min = 25 h`.
  - **24 h** é a janela da cota;
  - **60 min** é a validade máxima de uma entrada: uma entrada registrada **antes** da ativação, só com o pseudônimo da versão anterior, ainda pode ser usada até `ativada_em(v) + 60 min`;
  - a última abertura registrada **somente** com o pseudônimo anterior ocorre, no máximo, nesse instante, e precisa continuar visível para a contagem por 24 h, até `ativada_em(v) + 25 h`;
  - depois disso, nenhuma abertura só-anterior está na janela.
  - **Não há margem de relógio** na conta porque ativação, registro de entrada, abertura e `v_agora` usam o relógio do banco. O relógio do serviço de entrada não participa.
- **Exigência na transição:** enquanto `v_agora < ativada_em(v) + T_trans`, contado a partir da ativação da versão **nova** `v`:
  - toda entrada **nova** deve trazer **dois pseudônimos HMAC**, o da versão `v` e o da `v−1`. Faltando o de `v`, ou trazendo só o de `v−1`, a recusa é `ENTRADA_INVALIDA`;
  - depois do fim da transição, só o de `v` é aceito, e um pseudônimo de `v−1` é recusado.
- **Entrada com dois pseudônimos registrada pouco antes do fim da transição e usada depois dele (v5):**
  - **continua válida até o próprio vencimento** (60 min desde o registro);
  - a abertura usa os dois pseudônimos gravados, trava os dois baldes e conta as solicitações distintas com qualquer um deles. O pseudônimo anterior é redundante, mas inofensivo;
  - a recusa do pseudônimo anterior depois do fim da transição vale **só para registros novos** de entrada (validação no `ops_intake_register`), **nunca** para o uso de uma entrada já registrada;
  - uma entrada **só** com o pseudônimo anterior não pode existir depois do fim da transição: ela teria sido registrada antes da ativação e venceria em até `ativada_em + 60 min`, muito antes das 25 h.
- **Entrada registrada antes da ativação e usada depois (dentro dos 60 min):**
  - continua válida;
  - a abertura usa os pseudônimos **gravados na entrada**, só o de `v−1`, e conta no balde desse pseudônimo;
  - as entradas novas da mesma origem trazem também o de `v−1`, então a contagem as enxerga juntas;
  - essa entrada vence, no máximo, em `ativada_em(v) + 60 min`, antes do fim da transição.
- **Coordenação da abertura com a ativação:**
  - a abertura trava `ops_intake_key_state` `FOR SHARE` **antes** dos baldes e captura `v_agora` depois de todos os locks;
  - a ativação (`FOR UPDATE`) espera as aberturas em curso, e as aberturas seguintes veem a versão nova;
  - o **fim** da transição não é um evento: é calculado de `ativada_em(v)` e do `v_agora` do próprio ato, então nenhuma abertura valida versões com uma configuração ultrapassada;
  - o registro de entrada usa o mesmo `FOR SHARE` para validar os pseudônimos recebidos contra o estado das versões no instante do registro.
- **Contagem única por solicitação:**
  - a solicitação guarda até dois pseudônimos (com as versões);
  - a abertura trava os baldes dos pseudônimos que traz, em ordem crescente de (versão, pseudônimo);
  - conta as solicitações **distintas** das últimas 24 h que tenham **qualquer** desses pseudônimos (`count(DISTINCT request_id)`). Uma solicitação com dois pseudônimos conta **uma** vez, não uma vez por balde.
- **Depois da transição:** a chave `K_{v−1}` é destruída no serviço de entrada.
- **Retenção:** os pseudônimos na solicitação e nos baldes são apagados no prazo de D9. A auditoria mantém `canal_tipo`, `mecanismo` e a versão.

### 4.6 Retorno pelo canal registrado

- **Registro:** `ops_mfa_callback_register(contato)`, pelo verificador (`ops.autorizar` com VERIFICAR). O banco grava:
  - operador (`session_user`);
  - contato;
  - **canal utilizado** (tipo e versão: `channel_set_at` vigente, nunca o valor);
  - **instante do retorno** (o do banco no registro, declarado como imediatamente depois do contato).
- **Uso:** `callback_id` de uso único, 60 min, mesmo operador. A abertura em modo `RETORNO` não conta em nenhum balde.
- **Conferências:** o contato precisa estar elegível no registro e na abertura, e o canal não pode ter sido alterado entre os dois.
- **Não dispensa nada:** as duas evidências da D2(a) (retorno com código de uso único na verificação e confirmação independente do **segundo** contato) e todas as etapas seguintes continuam exigidas.

### 4.7 Concorrência

- **Ordem proposta da abertura (v4):** organização → conta alvo → **estado das versões de chave** (`FOR SHARE`) → baldes (crescente por versão e pseudônimo) → entrada ou retorno (`FOR UPDATE`) → operador (`FOR SHARE`). `v_agora` depois de todos os locks; contagem sob o lock dos baldes.
- **Outros caminhos:**
  - a ativação de versão trava **só** o estado das versões (`FOR UPDATE`);
  - o registro de entrada trava o estado das versões (`FOR SHARE`) e insere a entrada.
  - Nenhum dos dois pede organização, conta, balde ou operador.
- **Entre organizações:** duas aberturas da mesma origem para organizações diferentes se serializam nos baldes.
- **Inventário:** as tabelas novas do item 4 entram no inventário quando o esquema for detalhado (inventário, §5). Nenhuma ordem é dada como livre de ciclo antes disso.
- **Testes:**
  - 4 aberturas concorrentes da mesma origem comprovada na mesma organização → exatamente 3;
  - a mesma origem em duas organizações, concorrendo → 3 no total;
  - **rotação:**
    - entrada registrada 59 min antes da ativação e usada depois dela → aceita; conta com as entradas novas (dois pseudônimos) da mesma origem;
    - entrada nova na transição sem o pseudônimo da versão vigente, ou só com o da anterior → `ENTRADA_INVALIDA`;
    - abertura em `ativada_em + 25 h − 1 µs` ainda exige os dois; em `ativada_em + 25 h`, só o vigente;
    - solicitação com dois pseudônimos conta **uma** vez: 2 aberturas com os dois pseudônimos + 1 só com o anterior → a 4ª recusada, e o contador é 3, não 5;
    - ativação concorrente com aberturas: a ativação espera as aberturas em curso; nenhuma abertura posterior aceita entrada nova só com a versão anterior;
    - ativação antes de `T_trans` desde a anterior → recusada;
    - **(v5)** entrada com dois pseudônimos registrada em `ativada_em + 25 h − 1 min` e usada em `+ 25 h + 30 min` → aceita e contada uma vez; registro **novo** com o pseudônimo anterior em `+ 25 h` → `ENTRADA_INVALIDA`; a mesma entrada usada em `+ 25 h + 61 min` → `ENTRADA_VENCIDA`;
  - entrada `NAO_COMPROVADA` → `ORIGEM_NAO_COMPROVADA`, sem consumir nada;
  - **o ataque:** alegar ser o contato X com origem falsificada não passa no desafio; alegar com origem própria comprovada esgota só a origem do atacante; X abre por retorno;
  - organização com exatamente dois contatos, ambos alegados → retorno abre;
  - entrada de outro operador, usada ou vencida → recusa;
  - retorno com canal alterado → recusa;
  - operador sem `EXECUTE` em `ops_intake_register`;
  - contraprova por mutação: contar por contato alegado é detectado;
  - teste de ausência de deadlock (execuções concorrentes repetidas sem `40P01`).

## 5. Item 5 — demais administradores no cenário C (desenho v3)

### 5.1 Regra (aprovada)

- **Parâmetro obrigatório em VERIFICAR:**
  - `NENHUM` (conferido pelo banco; senão, recusa do ato `DECLARACAO_DIVERGENTE`);
  - ou `TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS` (declarado; exige pelo menos um outro `ORG_ADMIN` efetivo).
- **`ADMIN_PODE_CADASTRAR`:**
  - em VERIFICAR, com `TODOS_…`, se algum considerado **não** tem fator confirmado e ativo → recusa **do ato**;
  - nos atos seguintes, o mesmo fato surgido depois (fator do considerado desabilitado) → **terminal**;
  - isso indica um caminho **potencial** de cadastro (`mfa_enrollment_required`, `AuthService.java:152`); **não** comprova que a pessoa está disponível nem que conhece a senha.

### 5.2 Fotografia (aprovada)

- Uma linha por administrador **considerado**: `user_id` (FK composta para `users`) e instante.
- **Fatos do banco:**
  - `fator_confirmado_ativo`;
  - `ultimo_passo_totp` do fator ativo (o passo consumido mais recente);
  - `codigos_ativos`;
  - `sessoes_ativas`.
- **Declaração:** `declarado_sem_fator_utilizavel`, `declarado_sem_codigos_utilizaveis`.
- A composição (o conjunto dos considerados) fica vinculada à declaração.
- O banco **não** comprova posse do dispositivo nem disponibilidade dos códigos.

### 5.3 Sinais que contradizem a declaração

Para um considerado, qualquer um destes, **depois** do instante da fotografia:

| Sinal | Fluxo existente que o produz | Onde fica no banco |
|---|---|---|
| Fator confirmado | `confirmEnrollment` (`AuthService.java:188`) | `user_mfa_factors.confirmed_at` |
| TOTP usado (login ou step-up) | `verify` (`:225`), `stepUp` (`:300`) | passo consumido do fator avança além de `ultimo_passo_totp`; step-up também grava `EV_STEP_UP` |
| Código de recuperação usado | `verify` com código (`:243–264`) | `user_mfa_recovery_codes.used_at`; `EV_RECOVERY_CODE` |
| Sessão com MFA criada | `confirmEnrollment`, `verify` | `user_sessions.created_at` (a 014 exige MFA para toda sessão) |

Resultado: **terminal** `DECLARACAO_CONTRADITA`. A composição diferente continua **terminal** `COMPOSICAO_ALTERADA`.

### 5.4 Coordenação com os fluxos existentes (sem inverter ordens)

- **Fato verificado no código:** todos os fluxos da tabela acima travam a **conta do próprio usuário** como **primeiro** lock (`accounts.lock`: `AuthService.java:304` no step-up; `lockedChallenge`, `:447–455`, em `confirmEnrollment` e `verify`, conta → desafio). A suspensão e a reativação seguem a disciplina da 003, organização → conta (`AdminService.java:54–59`, `:72–77`). ~~O reset administrativo trava só a conta alvo (`:151`). A busca não encontrou fluxo da Platform que trave duas contas.~~ **Corrigido na v5:** o reset administrativo trava a conta alvo (`:151`) **e** pede `KEY SHARE` na conta do **ator** pelo evento (`mfa_events`, 014:223). É um fluxo de **duas contas** sem ordem definida, que forma ciclo com resets cruzados e com a execução (inventário v2, I-5).
- **Proposta:** nos atos de solicitação que conferem a declaração (VERIFICAR, que tira a fotografia; PRE_APROVAR, APROVAR, EXECUTAR, que reconferem), a ordem passa a ser:
  - **organização → contas (alvo e considerados, juntas, em ordem crescente de `user_id`, `FOR UPDATE` no alvo e `FOR SHARE` nos considerados) → solicitação → operadores**;
  - os sinais do §5.3 são lidos **depois** desses locks e do `v_agora`, em comandos novos (READ COMMITTED, com a guarda de isolamento).
- **Por que não inverte os locks explícitos conhecidos** (os implícitos dependem do inventário abaixo):
  - os fluxos de autenticação travam primeiro **a própria conta** e nada antes dela;
  - a suspensão trava organização → conta, a mesma direção da proposta;
  - as contas são tomadas em ordem crescente. **(v5)** Isso só fecha os ciclos se o reset administrativo, hoje sem ordem entre alvo e ator, também passar a travar as duas contas em ordem crescente (I-5, proposto).
- **Resultado do inventário** (v1, com a correção da v2 sobre o reset administrativo):
  - as FKs de `user_sessions`, `auth_challenges`, `mfa_events`, fatores, códigos e tokens apontam só para `users`, então os fluxos de autenticação **não** pedem `KEY SHARE` na organização;
  - o inventário encontrou **uma inversão preexistente**: `confirmEmail` faz `UPDATE users` sem travar a organização antes, e o trigger de presença de `ORG_ADMIN` (003) então trava a organização. É conta → organização, contra a 016 atual, a suspensão da Platform e esta proposta.
  - I-1 está decidido (organização → conta → token, inventário v2 §4.1). O inventário v2 acrescentou **I-5**: o reset administrativo pede a conta do ator. Até a decisão sobre I-5, este desenho **não** pode ser implementado.
- **Duas ordens de chegada (resultado esperado):**

| Disputa | Fluxo do considerado confirma primeiro | Execução trava as contas primeiro |
|---|---|---|
| Cadastro de fator (`confirmEnrollment`) | A execução, ao obter o `FOR SHARE`, vê `confirmed_at` posterior → `DECLARACAO_CONTRADITA`, terminal, sem reset | O cadastro espera no próprio `accounts.lock` até a execução confirmar. O reset vale no seu instante; o cadastro conclui depois, normalmente |
| Consumo de código de recuperação (`verify`) | Execução vê `used_at` posterior → terminal, sem reset | O `verify` espera; reset confirmado; o login do considerado conclui depois |
| TOTP em login (`verify`) | Execução vê o passo além de `ultimo_passo_totp` ou a sessão nova → terminal, sem reset | O login espera; reset confirmado; o login conclui depois |
| Step-up (`stepUp`) | Execução vê o passo avançado ou `EV_STEP_UP` → terminal, sem reset | O step-up espera; reset confirmado; o step-up conclui depois |
| Desabilitação do fator do considerado (reset administrativo por outro admin) | Execução vê o considerado sem fator ativo → `ADMIN_PODE_CADASTRAR`, terminal | O reset administrativo espera no lock da conta dele; reset emergencial confirmado primeiro. **(v5)** Com o ator do reset sendo o alvo da emergência, sem I-5 há ciclo (inventário v2, I-5 (c)); com I-5, espera simples |
| Suspensão ou retirada de `ORG_ADMIN` de um considerado (003: organização primeiro) | Execução vê a composição diferente → `COMPOSICAO_ALTERADA`, terminal | A suspensão espera na organização; o reset vale no seu instante |

- **Nas mesmas disputas, a verificação** (fotografia) se coordena igual: o fluxo que confirma antes aparece na fotografia; o que chega depois espera e passa a ser sinal contra a declaração nos atos seguintes.

### 5.5 Testes

- **Para cada linha do §5.4, as duas ordens:**
  - com o fluxo do considerado confirmado antes;
  - com a execução mantendo a transação aberta depois de obter os locks (o fluxo do considerado precisa **esperar**, conferido por `pg_locks`/`pg_stat_activity`, e concluir depois do commit).
- `NENHUM` com outro admin → `DECLARACAO_DIVERGENTE`;
- `TODOS_…` com considerado sem fator → `ADMIN_PODE_CADASTRAR` (ato);
- fotografia completa;
- parâmetro ausente → recusa;
- estresse de ausência de deadlock (logins, step-ups e execuções concorrentes, sem `40P01`);
- contraprova por mutação: leitura dos sinais **antes** dos locks é detectada pela ordem "fluxo durante a execução".

## 6. Item 10 — tentativas, tokens e espera (mecanismo v3)

### 6.1 Política (aprovada) e significado

- **Aprovação final exige:**
  - (1) `confirmação + 24 h ≤ v_agora < T_total` (vigente);
  - (2) para cada outro `ORG_ADMIN` notificado, a **tentativa vigente** de `CONTESTAR` aceita pelo transporte há pelo menos 24 h.
- **Preservados:** a primeira confirmação, `T_total` de 72 h sem prorrogação e a janela de execução.
- **Aceite pelo transporte não é recebimento nem confirmação.** O retorno de `MailSender` não é aceite: `LocalMailSender` só registra a intenção e nunca passa de `ENFILEIRADA`, então a condição (2) nunca é satisfeita com ele (D3).

### 6.2 Tentativas, sequência e estados

- **Tentativa:** cada emissão ou reenvio é uma tentativa `(solicitação, destinatário, finalidade)` com `attempt_id` e **`seq`**: sequência monotônica **da solicitação**, atribuída sob o lock da solicitação. Os tokens (hash) ficam vinculados à tentativa.
- **Protocolo de coordenação entre enfileiramento e resultado (v4):** a Platform grava **`ENTREGANDO`**, com commit, **antes** de entregar a mensagem à fila ou ao transporte. Só então entrega, com o `attempt_id` como identificador de correlação e idempotência da mensagem. Com isso:
  - um resultado do transporte **nunca** pode chegar antes de o banco saber que a entrega começou: o worker só recebe a mensagem depois do commit de `ENTREGANDO`;
  - o registro `ENFILEIRADA` passa a ser **informativo**: se o resultado do transporte chegar antes dele, `ENTREGANDO → ACEITA` ou `→ FALHOU` é permitido, e um `ENFILEIRADA` que chegue depois é gravado só no histórico, **sem** mudar o estado;
  - `EMITIDA` parada significa, com certeza, "**não entregue**", porque a entrega só começa depois de `ENTREGANDO`. `ENTREGANDO` parada é **ambígua**.
- **Estados e transições permitidas:**

| De | Para | Quem, quando |
|---|---|---|
| — | `EMITIDA` | Banco, na transação que gera os hashes |
| `EMITIDA` | `ENTREGANDO` | Platform, com commit, **antes** de entregar |
| `EMITIDA` | `FALHOU` | Platform: sem destinatário ou erro ao montar, **antes** de tentar entregar; ou recuperação de `EMITIDA` parada (nunca entregue) |
| `ENTREGANDO` | `ENFILEIRADA` | Platform, depois da entrega à fila (informativo) |
| `ENTREGANDO` | `FALHOU` | Platform: a fila ou o transporte **recusou sincronamente** a entrega (fila cheia ou indisponível), com certeza de não entregue |
| `ENTREGANDO` ou `ENFILEIRADA` | `ACEITA_PELO_TRANSPORTE` | Worker do transporte, **só** com aceite do transporte real correlacionado ao `attempt_id` |
| `ENTREGANDO` ou `ENFILEIRADA` | `FALHOU` | Worker, com recusa definitiva do transporte |
| `ENTREGANDO` ou `ENFILEIRADA` | `DESCONHECIDO` | Tempo esgotado ou recuperação de queda (§6.5) |
| `DESCONHECIDO` | `ACEITA_PELO_TRANSPORTE` ou `FALHOU` | Worker, só com evidência do transporte correlacionada ao `attempt_id` |

- **Idempotência e ordem:**
  - `ACEITA` e `FALHOU` são finais;
  - repetir o **mesmo** resultado para a mesma tentativa não muda nada: preserva o **primeiro** instante de aceite e **não** repete efeitos (invalidação de tokens, eventos de aceite);
  - um resultado **atrasado** que "volta" o estado (por exemplo, `ENFILEIRADA` depois de `ACEITA`) vai só para o histórico;
  - um resultado **conflitante** com um estado final (por exemplo, `FALHOU` depois de `ACEITA`) mantém o final, grava o evento `RESULTADO_CONFLITANTE`, sem efeito, e alerta;
  - transição sem origem permitida (por exemplo, `ACEITA` sobre `EMITIDA`) é recusada e registrada como violação do protocolo, sem efeito.
- O instante de cada estado é o do banco no registro.
- **Nenhum caminho produz aceite presumido.**

### 6.3 Tentativa vigente e tokens

- **Vigente** de um destinatário e finalidade (**decisão explícita da v4, muda a v2**): a de maior `seq` que **não** está `FALHOU`.
  - Uma resposta tardia de tentativa antiga **nunca** muda qual é a vigente, que depende só de `seq` e de `FALHOU`.
  - Com a mais nova `FALHOU` (falha **definitiva**) e a anterior `ACEITA`, a vigente volta a ser a anterior: o destinatário tem um token entregue.
  - Com a mais nova `DESCONHECIDO`, `ENTREGANDO` ou `ENFILEIRADA`, ela **continua** vigente e **bloqueia** a volta: a condição (2) fica insatisfeita até ela ser resolvida ou um reenvio ser aceito.
  - A volta à anterior **não** afeta a regra do §6.6: um reenvio feito depois da aprovação impede a execução **mesmo que depois falhe**.
- **Validade dos tokens:**
  - os tokens de uma tentativa `FALHOU` são invalidados na falha (nunca saíram);
  - quando a tentativa de `seq = n` chega a `ACEITA`, são invalidados os tokens **ainda válidos** das tentativas do mesmo destinatário e finalidade com **`seq < n`**. Nunca os de `seq > n`;
  - invalidação é **permanente**: nenhum caminho limpa `invalidated_at`;
  - até lá, os tokens das anteriores (não falhadas) continuam válidos. Isso muda C:179 ("invalida o anterior") para "invalida o anterior quando o novo for aceito".
- **Aceite fora de ordem** (tentativa 2 aceita, depois chega o aceite da 1):
  - a 1 passa a `ACEITA` no histórico;
  - invalida só tentativas com `seq < 1` (nenhuma);
  - **não** invalida o token da 2, **não** reativa o da 1 (já invalidado quando a 2 foi aceita) e **não** muda a vigente (a 2).
- **Emissão depois de substituição confirmada (correção da revisão da rodada 3):** o aceite de `seq = n` só invalida tokens que **já existem**. Por isso, uma tentativa `seq < n` ainda sem tokens **não** emite token de finalidade para a qual já existe tentativa posterior do mesmo destinatário `ACEITA`. Sem finalidade restante, ela vai a `FALHOU` (`SUBSTITUIDA_POR_TENTATIVA_ACEITA`) sem sair. Tentativa posterior `FALHOU`, `DESCONHECIDO`, `ENFILEIRADA` ou `ENTREGANDO` **não** impede a emissão da anterior.
- **`CONFIRMAR`:** dois tokens válidos do titular ao mesmo tempo não mudam nada. Uso único; a **primeira** confirmação fixa o instante (C:179); nenhum `CONFIRMAR` é emitido depois da confirmação.

### 6.4 Serialização

- **Todos** os caminhos que mudam tokens, tentativas ou condições tomam a **mesma ordem proposta:** organização → contas → **solicitação** → tentativas envolvidas (`FOR UPDATE`, crescente por `seq`) → tokens envolvidos (`FOR UPDATE`, crescente) → operador, quando houver.
  - "Contas" = todas as contas de que o caminho precisa, juntas e em ordem crescente (regra do inventário v2 §5, achado I-5): a conta alvo; os considerados, nos atos do §5; **o destinatário**, no reenvio; **os destinatários dos avisos aos demais**, na pré-aprovação e na execução.
- **Caminhos:**
  - registro de resultado de entrega (`ops_notice_delivery_result`, porque invalida tokens e altera a condição 2);
  - emissão;
  - reenvio;
  - confirmação;
  - contestação;
  - aprovação final;
  - execução;
  - recuperação de tentativas paradas.
- **Consequência:** resultado, reenvio, confirmação, contestação e execução se serializam no lock da **solicitação**, que é o mesmo da §7 do contrato.
  - Uma execução em curso faz o registro de um aceite esperar, e vice-versa. Nenhum deles lê estado intermediário do outro.
  - O registro de resultado **não** trava apenas a tentativa.
- **Inventário:** as FKs de tentativas e tokens para a solicitação e para o destinatário (`KEY SHARE` na conta do outro administrador ao **inserir** uma tentativa de reenvio) e o índice único de token ativo entram no inventário com o esquema detalhado (inventário, §5).
  - **(v5, corrigido)** O `KEY SHARE` na conta do destinatário **forma ciclo** com um reset administrativo desse destinatário cujo ator é o alvo da emergência (inventário v2, I-5 (d)). Por isso o destinatário entra no conjunto crescente de contas, antes da solicitação. A v4 dizia que não havia ciclo encontrado, o que estava incompleto.

### 6.5 Falha antes da fila e recuperação de queda

- **Antes da entrega** (sem destinatário, erro ao montar): `EMITIDA → FALHOU` (`SEM_DESTINATARIO`/`ERRO_DE_MONTAGEM`).
- **Recusa síncrona da fila** (cheia ou indisponível): `ENTREGANDO → FALHOU` (`FILA_INDISPONIVEL`), só quando a chamada de entrega retornou recusa definitiva. Exceção ou tempo esgotado na chamada **não** é recusa: vai para `DESCONHECIDO`.
- Em `FALHOU`, os tokens da tentativa são invalidados, porque nunca saíram.
- **Queda do processo:** uma varredura periódica da Platform, pelas mesmas funções e com a mesma ordem de locks, trata as tentativas paradas:
  - `EMITIDA` há mais de 10 min → **`FALHOU`** (`ABANDONADA_ANTES_DA_ENTREGA`). É seguro porque, pelo protocolo, a entrega só começa depois do commit de `ENTREGANDO`;
  - `ENTREGANDO` há mais de 10 min sem `ENFILEIRADA` nem resultado → **`DESCONHECIDO`** (`QUEDA_DURANTE_ENTREGA`). A mensagem pode ter saído;
  - `ENFILEIRADA` há mais de 24 h sem resultado → `DESCONHECIDO` (`SEM_RESULTADO_DO_TRANSPORTE`).
- **Efeito:**
  - `DESCONHECIDO` nunca satisfaz a condição (2) e bloqueia a volta à anterior (§6.3); os tokens continuam válidos, porque podem ter saído;
  - resolução posterior só com evidência do transporte correlacionada ao `attempt_id`;
  - o operador pode reenviar.
- A varredura e um worker podem disputar a mesma tentativa. Os dois passam pela mesma função, sob o lock da solicitação e da tentativa (§6.4), e o segundo encontra o estado já mudado e segue as regras de idempotência (§6.2).

### 6.6 Aprovação registra as versões; execução recusa reenvio posterior

- **Na aprovação final:** a solicitação grava `notice_seq_aprovacao` (o `seq` máximo da solicitação no instante) e, por destinatário, a tentativa vigente de cada finalidade considerada (tabela `ops_mfa_approval_notice_versions`).
- **Na execução**, sob o lock da solicitação:
  - se existe **qualquer** tentativa com `seq > notice_seq_aprovacao`, para **qualquer** destinatário, **inclusive o titular** → recusa do ato `CONDICAO_DE_AVISO_ALTERADA`;
  - senão, reconfere que as tentativas registradas continuam no estado considerado (`ACEITA` é final, então isso é integridade).
- **Não** reinicia a primeira confirmação nem amplia prazos: a solicitação continua sujeita a `min(T_total, aprovação + 60 min)` e, na prática, expira. Para parar de vez, o operador cancela.
- **Precedência do prazo (correção da revisão da rodada 3):** depois das conferências de autorização, estado, executor, vínculo e contas, a execução confere **primeiro** o prazo: vencida, a solicitação vai a `EXPIRADA` mesmo com reenvio posterior à aprovação. Antes do vencimento, a recusa é `CONDICAO_DE_AVISO_ALTERADA`, e a solicitação continua `APROVADA`. Na implementação anterior, a condição vinha antes do prazo e mantinha `APROVADA` depois do vencimento.

### 6.7 Testes (transporte simulado; e-mail real pausado)

- cada transição permitida e proibida;
- **corrida entre enfileiramento e resultado (v4):**
  - resultado `ACEITA` do transporte **antes** do registro de `ENFILEIRADA` → aceito a partir de `ENTREGANDO`; o `ENFILEIRADA` posterior só vai ao histórico;
  - **queda entre as etapas:** depois do commit de `EMITIDA` e antes de `ENTREGANDO` → recuperação para `FALHOU` e tokens invalidados. Depois de `ENTREGANDO` e antes da entrega ou do registro de fila → recuperação para `DESCONHECIDO`, tokens válidos e nenhum aceite presumido;
  - **repetição do mesmo resultado:** dois `ACEITA` da mesma tentativa → o primeiro instante de aceite é preservado, a invalidação de tokens e o evento de aceite ocorrem **uma** vez (contagem de eventos e de `invalidated_at` inalterados);
  - resultado conflitante (`FALHOU` depois de `ACEITA`) → estado mantido, `RESULTADO_CONFLITANTE` e alerta;
  - `ACEITA` sobre `EMITIDA` → recusado como violação do protocolo;
  - worker e varredura disputando a mesma tentativa → um único desfecho;
- **vigente (decisão v4):** 2 `FALHOU` e 1 `ACEITA` → a vigente volta à 1; 2 `DESCONHECIDO` e 1 `ACEITA` → a vigente é a 2 e (2) fica insatisfeita; reenvio depois da aprovação que falha → a execução continua recusada;
- **aceite fora de ordem, com uso dos tokens:**
  - tentativa 2 aceita e depois aceite da 1 → `CONTESTAR` da 2 **aceito** pela rota; o da 1 recusado (invalidado);
  - a vigente continua a 2;
  - repetido para `CONFIRMAR` do titular antes da confirmação;
- 2 `FALHOU` com 1 `ACEITA` → vigente é a 1; o token da 1 contesta; o da 2 recusado;
- reenvio que falha (antes da fila e no transporte) → o token anterior continua utilizável;
- queda simulada com tentativa `EMITIDA` e `ENFILEIRADA` → recuperação para `DESCONHECIDO`; nenhum aceite presumido; a condição (2) continua insatisfeita;
- `ENFILEIRADA` e `LocalMailSender` não satisfazem (2);
- aceite dos demais com menos de 24 h → `AVISO_DEMAIS_PENDENTE`; aceite com `aceite + 24 h ≥ T_total` → expira;
- reenvio depois da aprovação ao **titular** e a um **outro administrador** → `CONDICAO_DE_AVISO_ALTERADA`, com a primeira confirmação e os prazos inalterados;
- **serialização:** aceite registrado com a execução segurando a solicitação (as duas ordens); contestação concorrente com registro de aceite; reenvio concorrente com aceite da tentativa anterior;
- categorias sem endereço nem mensagem do servidor, com varredura e controle positivo;
- contraprovas por mutação: invalidar `seq > n` no aceite; ignorar `notice_seq_aprovacao` na execução.

### 6.8 Perda da confirmação do commit de `ENTREGANDO` (v5)

- **Premissa:** o `attempt_id` **correlaciona** registros, mas **não** garante deduplicação pelo SMTP nem pelo provedor. Uma mensagem entregue duas vezes chega duas vezes. Por isso, a autorização para entregar precisa ser **explícita**, e nenhuma entrega é repetida automaticamente para a mesma tentativa.
- **Reivindicação:** a transição `EMITIDA → ENTREGANDO` grava, na mesma linha, uma **reivindicação** (`claim_id` aleatório gerado pelo processo e instante do banco). Só o processo dono da reivindicação pode entregar aquela tentativa, e só uma vez.
- **Se a confirmação do commit se perder** (conexão caída depois do `COMMIT`, sem resposta), o processo **não** entrega e **não** repete. Ele reconcilia:
  1. reconecta e lê a tentativa sob o lock da solicitação e da tentativa (ordem do §6.4);
  2. **`ENTREGANDO` com o próprio `claim_id`, sem transição posterior:** o commit ocorreu e a reivindicação é dele. **Restrição (decisão v6):** pode entregar, **uma vez**, **somente** se a **mesma execução** do processo sabe que ainda **não** chamou a fila nem o transporte para essa tentativa, isto é, está resolvendo a perda da confirmação do commit **anterior** ao envio.
     - Encontrar o próprio `claim_id` não prova, isoladamente, que a mensagem não saiu.
     - Depois de reinício, de perda dessa certeza ou de chamada ao transporte com resultado incerto: **não** reentrega; mantém ou registra `DESCONHECIDO`; um novo envio usa **outra** tentativa;
  3. **`EMITIDA`:** o commit não ocorreu. Pode repetir a transição para `ENTREGANDO` (nova reivindicação) e só então entregar;
  4. **qualquer outro estado** (`DESCONHECIDO` pela varredura, `FALHOU`, `ENTREGANDO` com outro `claim_id`): **não** entrega e registra no log local `ENTREGA_NAO_AUTORIZADA`;
  5. **reconciliação impossível** (banco inacessível): **não** entrega; registra no log local e alerta. A varredura (§6.5) resolve depois: `EMITIDA` parada → `FALHOU` (nunca entregue); `ENTREGANDO` parada → `DESCONHECIDO`, e a mensagem **não** sai, porque o processo não tem autorização confirmada.
- **Depois de entregar**, uma queda antes de registrar o resultado deixa `ENTREGANDO`, e a varredura leva a `DESCONHECIDO`. O processo reiniciado **não** reentrega aquela tentativa.
- **Toda nova entrega é uma nova tentativa** (novo `seq`, novos tokens), por reenvio do operador (§6.3). Não há repetição automática.
- **Varredura × processo:** a varredura só move `ENTREGANDO` para `DESCONHECIDO` depois de 10 min. Um processo que reconcilie depois disso encontra `DESCONHECIDO` e não entrega (passo 4).
- **Alcance dos testes (v6):** o transporte simulado conta as **chamadas observadas pelo harness** por `attempt_id`. Isso **não** é garantia de recebimento exatamente uma vez pelo destinatário, e não é descrito assim.
- **Testes (transporte simulado que conta as chamadas por `attempt_id`):**
  - resposta do commit de `ENTREGANDO` perdida, com o commit aplicado (proxy que derruba a resposta, como o `ProxyTcp` dos testes) → reconciliação encontra a própria reivindicação → **exatamente uma** mensagem;
  - resposta perdida **sem** o commit aplicado → reconciliação encontra `EMITIDA` → nova reivindicação → uma mensagem;
  - banco inacessível na reconciliação → **zero** mensagens, alerta; a varredura depois leva a `DESCONHECIDO` ou `FALHOU` conforme o estado;
  - varredura antes da reconciliação → `DESCONHECIDO` → zero mensagens;
  - dois processos com a mesma tentativa → só o dono da reivindicação entrega;
  - queda depois de entregar e antes do resultado → reinício sem reentrega; `DESCONHECIDO`; uma mensagem no total.

## 7. Correções factuais do relatório (aplicadas na v2)

`docs/relatorios/2026-09-27-mfa-emergencia-implementacao.md`, §5, itens 4, 5, 7 e 10. Texto anterior em `effa516`.

**Cópia em `../relatorios/`: sincronizada em 2026-09-28T11:06Z.**
- A cópia e a comparação foram executadas: `cmp` sem diferença; SHA-256 `19938a2a23144ea5fa9f4bb9b1dd91c1eeacce0f17c5ab6a679648f921dfc573` nos dois arquivos.
- Na v3 e no início da v4 ela estava **pendente**: a verificação de segurança dos comandos de shell esteve indisponível, e a v3 dizia "sincronizada" antes de a cópia ocorrer.

## 8. Fora deste documento

- **Bloqueios de produção:** notificação real, CA/IdP, operação implantada, D9, elevação da 016 e relógio.
- **Dependências novas propostas:** serviço de registro de entrada com desafio de ida e volta e chave de pseudonimização em cofre (item 4); transporte de e-mail que devolva aceite correlacionado ao `attempt_id` (item 10, parte da notificação real, D3).
- **Pré-requisito da implementação:**
  - implementação de I-1 a I-4 (decididos) e decisão sobre I-5 (inventário v2);
  - inventário das tabelas novas quando o esquema for detalhado.
- **E-mail:** pausado. **Frontend:** fora do escopo.
