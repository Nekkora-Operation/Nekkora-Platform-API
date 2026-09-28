# Recuperação emergencial de MFA — implementação e validação (candidato)

**Data:** 2026-09-27; **rodada 2** em 2026-09-28, com as correções dos cinco achados da revisão (§5d); **rodada 3** (revisão 4 do contrato e achados I-1–I-5 do inventário de locks) em 2026-09-28, §5f, com as correções da revisão dela na §5g, **sem commit**.
**Branch:** `feat/mfa-emergencia`, sobre `ca4033b` (contrato aprovado, revisão 3 final; SHA-256 `d22f3b6ca227dd5bf86dba191e8685e53cae382053076888f296eb0879264353`).
**Estado:**
- **Candidato para revisão.** Não habilitado em produção.
- **Achados da rodada 2 encerrados** na revisão estática de 2026-09-28 (§5e).
- **Commit autorizado** do conjunto revisado na `feat/mfa-emergencia`, como candidata. **Sem push nem merge.**
- Sem aprovação para produção nem para integração: as decisões do §5 e os gates do §7 continuam abertos.
- E-mail segue pausado; o foco é a API.
- O checkpoint pausado `db0164c` (e-mail M365) **não** foi incorporado (`git merge-base --is-ancestor` negativo).

**Documentos:**
- Matriz de funções, chamadores, donos, privilégios e inventário de locks (registrada antes do SQL e atualizada com os nomes finais): `docs/relatorios/2026-09-27-mfa-emergencia-matriz-016.md`.
- Reprodução: `ops/mfa-emergencia/validar.sh` (§9).

## 1. Preparação e limites

| Item | Estado |
|---|---|
| Estado inicial | HEAD `ca4033b` em `feat/mfa-emergencia`. Só itens não rastreados preexistentes (`api/e2e/email/`, `db/tests/*/saidas/…`, `db/tests/portal-8-2/`), **preservados** |
| Base principal, hml03, migrations 001–015 | **Intocados.** A 016 é arquivo novo; nenhuma migration anterior mudou (a suíte confere pelo catálogo) |
| Dados | Só sintéticos, gerados por execução |
| Certificados | CA de infraestrutura, CA operacional e CA estranha **descartáveis**, geradas em memória por execução |
| Notificações | Capturadas localmente: `TestMailSender` nos testes; `LocalMailSender` só registra a intenção. **Sem M365** |
| Relógio | Prazos do contrato fixos no código, sem relógio configurável pelo chamador. Fixtures temporais só na base de teste (matriz, §6) |

## 2. Entregas

**Banco:**
- `db/migrations/016_recuperacao_emergencial_mfa.sql` (aditiva; exige superusuário como 011–013):
  - papéis `nekkora_ops_mfa_owner`, `nekkora_ops_mfa` e `nekkora_ops_ca`;
  - schema `ops` com 14 tabelas e a extensão `sslinfo`;
  - 29 funções públicas `ops_*` (`SECURITY DEFINER`) e as funções internas `ops.*`;
  - `mfa_events.ops_request_id` e `mfa_events.reason_code`;
  - 9 políticas `ops_function_context`;
  - privilégios por coluna;
  - bloco final de invariantes que confere a matriz.
- `db/tests/mfa-emergencia-016/` (runner, README, `p02_popula_mfa.sql`, `c_listagem_ops.sql`).

**CLI, CA e IdP (`api/nekkora-ops`, módulo novo):**
- `idp/VerificadorIdp`: RS256, emissor, audiência, validade, `auth_time` ≤ 5 min e `amr` resistente a phishing.
- `ca/EmissorCertificados`:
  - Bouncy Castle (`bcpkix-jdk18on` 1.86, a mesma versão do `bcprov` já usado);
  - CN = papel, 15 min, EKU `clientAuth`;
  - **registro no banco antes de assinar**, pelo papel `nekkora_ops_ca`;
  - emissor no formato lido pela `sslinfo`.
- `cli/ExecutorAtos`, `Credencial`, `LogLocal`, `OpsCli`:
  - transação explícita por ato;
  - classes `OK`, `RECUSA`, `FALHA_CONFIRMADA` e `DESCONHECIDO`;
  - reconciliação com `ops_mfa_status`;
  - registro de falha em outra transação;
  - log local só com categorias;
  - chave gerada no dispositivo, PKCS#12 temporário e TLS `verify-full`.
- Testes (test-jar reutilizado pela Platform):
  - `OpsBanco`: PostgreSQL 16 com TLS obrigatório, `op_*` só por certificado e demais papéis por senha aleatória sobre TLS;
  - `Pki`, `IdpSimulado`, `ProxyTcp`, `Cenarios`;
  - 11 classes de teste.

**Platform:**
- **Motivo codificado obrigatório no reset administrativo:**
  - corpo `{"reason": "DISPOSITIVO_PERDIDO" | "DISPOSITIVO_TROCADO" | "SUSPEITA_COMPROMETIMENTO" | "OUTRO"}`;
  - a ausência ou um valor fora da lista dá 400 sem efeito;
  - o motivo é gravado em `reason_code`.
- **Aviso aos demais ORG_ADMIN efetivos** quando o alvo do reset é `ORG_ADMIN` (`NOTICE_MFA_RESET_ADMIN_TARGET`).
- **Rotas de aviso** `POST /api/v1/o/{org}/auth/mfa-emergencia/aviso/{confirmar|contestar}` (`EmergencyNoticeController`):
  - públicas, com CSRF de pré-autenticação e Origin;
  - 204, ou 400 `invalid_request` idêntico em qualquer recusa;
  - sem sessão.
- **`EmergencyNotices`:**
  - emissão dos avisos: tokens gerados só em memória, hash no banco, e-mail depois do commit;
  - consumo dos tokens;
  - código de prova de conta `POST /api/v1/o/{org}/me/operational-link-code` (sessão + CSRF de sessão).
- **Emissor periódico desligado por padrão** (`nekkora.emergency-notices.enabled`).
- **`MailSender`:** quatro tipos novos e `sendEmergencyNotice` com até dois tokens (confirmar e contestar).
- **Página estática** `api/nekkora-platform/paginas/aviso-mfa-emergencia.html`:
  - token no fragmento, retirado da barra ao abrir;
  - só o clique envia o POST;
  - **não servida e não ligada à produção**.

**Documentos:** a matriz e este relatório (cópia sincronizada em `../relatorios/`).

## 3. Resultados

**Execução oficial da rodada 2:** `ops/mfa-emergencia/saidas/2026-09-28T041923Z/` (runner `ops/mfa-emergencia/validar.sh`, §9), com as correções do §5d.
- `resumo.txt` gerado pelo runner, com `resultado=APROVADO` e o **código de saída de cada etapa** (`etapas.txt`): suíte de banco, regressão Maven, mutações, varredura, controle positivo da varredura e preservação.
- `testes_por_metodo.txt`: resultado de **cada** método de teste dos quatro módulos, tirado dos XML do surefire. Qualquer método que não esteja `OK` reprova a etapa 2.
- É a evidência que vai no pacote, em `evidencia/`.

**Rodada 1:** a execução oficial foi `2026-09-28T030420Z`, com 126 métodos. Ela continua evidência válida dos cenários executados, mas **não** cobre os achados da revisão. Vai no pacote em `outras-execucoes/`.

**Execuções que não são referência:**
- `2026-09-28T003057Z` (sessão anterior): **inválida/incompleta**. Parou na varredura, sem `resumo.txt` (§4, item 7). Vai no pacote **separada**, em `execucao-anterior-invalida/`, com `INVALIDA.txt` e a classificação.
- `2026-09-28T023718Z` (retomada): completa e sem achados, mas feita **antes** do teste novo da confirmação tardia, da varredura corrigida e do código de saída por etapa. Fica só local.
- `2026-09-28T024805Z`: aprovada em todas as etapas, mas **sem** a lista por método (`testes_por_metodo.txt`), que foi acrescentada para mostrar cada teste nomeado na evidência. Fica só local.
- `2026-09-28T025340Z`: **reprovada** na etapa 1 (código 3), com as demais etapas aprovadas (126 métodos OK).
  - **Causa:** a fixture **preexistente** `db/tests/atualizacao-populada/sql/p01_popula.sql` (commit `d608a96`, não alterada nesta missão) grava `reserved_at = clock_timestamp()`, e o `clock_timestamp()` do comando seguinte voltou cerca de 1,8 ms (…00.609453 → …00.607659), violando `executions_check10` (`dispatched_at >= reserved_at`).
  - É um recuo do relógio da VM do Docker Desktop, iniciada cerca de 20 min antes (sincronização de hora). Não é defeito da entrega.
  - A fixture não foi alterada, e a validação foi repetida.
  - Vai no pacote em `outras-execucoes/`, com as demais.
  - **Relevância para a 016** (texto corrigido na rodada 2; a versão anterior afirmava rollback garantido, o que não se sustenta): as funções usam `clock_timestamp()` como `v_agora`. Um recuo de relógio **não** viola necessariamente uma CHECK:
    - as CHECKs só comparam instantes gravados entre si (por exemplo, `final_approved_at >= notice_confirmed_at + 24 h`, `executed_at < least(t_total, final_approved_at + 60 min)`). Um recuo só as viola quando o instante novo cai **antes** de um marco já gravado, e nesse caso o ato sofre rollback;
    - um recuo **menor** que a folga entre os marcos passa por todas as CHECKs e desloca o `v_agora` para trás. Nesse caso, um prazo (espera de 24 h, `T_total`, janela de 60 min, validade de certificado ou de token, 30 dias do canal, limites de 7 e 30 dias) pode ser aceito até o tamanho do recuo **depois** do instante real, e o ato é confirmado;
    - um avanço do relógio tem o efeito inverso: recusa antecipada, falha fechada.
  - **Não é possível garantir rollback em todos os casos.** O erro de prazo fica limitado ao tamanho do recuo.
  - **Requisito de implantação (novo gate, §7):** relógio do servidor de banco sincronizado com correção gradual (*slew*), sem saltos para trás, e monitorado.
- `2026-09-28T025915Z`: **reprovada** na etapa 1 (código 1), com as demais etapas aprovadas. A suíte recusou começar porque os containers descartáveis `nekkora-mfa016-*` da execução anterior tinham sido mantidos para inspeção, como previsto no `run.sh`. Erro de operação da retomada: faltou removê-los antes de repetir. Eles foram removidos com o comando documentado no §8 (sem rede, sem volume), e a validação foi repetida.

**Suíte de banco (`db/tests/mfa-emergencia-016`):**
- **A. Instalação limpa 001–016:**
  - 16 migrations aplicadas;
  - diff de catálogo de `public` contra 001–015: 172 acréscimos, todos previstos, e **1** remoção, a prevista (INSERT de tabela inteira da Platform em `mfa_events`, trocado por colunas sem `ops_request_id`);
  - invariantes da 012, 013, 014 (com a única adaptação prevista) e 016: OK.
- **B. Base populada 015 → 016:**
  - massa: 3 organizações, 303 usuários, 900 execuções, 60 fatores, 180 códigos, 82 eventos de MFA e 10 resets administrativos **sem motivo**;
  - instantâneo de 49 tabelas **idêntico** antes e depois;
  - os resets históricos sem motivo foram preservados (NOT VALID) e um reset novo sem motivo é recusado;
  - catálogo e schema `ops` iguais aos da instalação limpa;
  - invariantes OK;
  - `pg_dump`/`pg_restore --exit-on-error` com contagens idênticas.

**Regressão Maven** (BUILD SUCCESS; nenhuma falha, erro ou teste pulado):

| Módulo | Testes | Observação |
|---|---|---|
| nekkora-core | 5 | inalterado |
| nekkora-ops | 73 | novos: §10 do contrato, mutações (5), a confirmação tardia (§5, item 9) e, na rodada 2, `IsolamentoIT` (4), `OpsCliContinuidadeIT` (2), a garantia do IdP e a mutação M5 (§5d) |
| nekkora-platform | 44 | inclui `EmergencyRecoveryFlowIT` (2) e o teste novo do motivo; os demais rodam contra a base com a 016 |
| nekkora-portal | 12 | inalterado, contra a base com a 016 |

**Grupos exigidos pela missão** (todos com asserções que abortam e papéis reais):

| Grupo | Onde | Cobertura (contrato §10) |
|---|---|---|
| Identidade | `IdentidadeIT` (8) | Autoria por `session_user`, com emissor e serial lidos pelo servidor; lista **exata** das entradas das 20 funções, sem autor, emissor, serial ou instante. Recusados: `SET ROLE` e `SET SESSION AUTHORIZATION`; certificado de X com usuário `op_y`; senha e conexão sem TLS; CA estranha; certificado da CA sem registro; papel sem cadastro. Serviços não agem como operador; operador não registra confirmação nem lê tabelas |
| Certificados | `CertificadosIT` (7) | CA sem MFA recente (sessão antiga, token renovado), método não resistente, emissor, audiência, validade, assinatura, `alg=none`, sujeito desconhecido. Banco recusa registro fora da política. Conexão aberta atravessando o vencimento; revogado com a sessão viva; retirada de habilitação revoga; espera por locks atravessando a validade do certificado e a janela de 60 min |
| Separação de funções | `SeparacaoDeFuncoesIT` (8) | Aprovador ≠ verificador (pré e final) e ≠ executor; **verificador pode ser o executor**; vínculo impeditivo de conta e de contato; prova de uso único; habilitação por ato; cadastro sem auto-habilitação |
| Estados e prazos | `EstadosEPrazosIT` (10) | 24 h depois da confirmação; `T_total`; `min(T_total, aprovação + 60 min)` dos dois lados; não verificada expira em 24 h; não verificados não bloqueiam (limite por origem, substituição); exclusividade a partir de VERIFICADA; limites de 7 e 30 dias; contatos (canal recente, dois elegíveis, confirmações da D2); transições; alvo inelegível |
| Avisos | `AvisosIT` (8) + `EmergencyRecoveryFlowIT` (2) | Confirmação válida mas tardia demais para aprovar (§5, item 9); finalidade, destinatário, organização, vencido, usado, malformado; resposta pública idêntica; confirmação tardia e contestação dentro da espera; primeira confirmação imutável e nenhum `CONFIRMAR` depois dela; reenvio invalida o anterior; contestação depois de `T_total` ou da execução; aviso de solicitação terminal descartado; só hash no banco; GET sem efeito; CSRF e Origin; sem sessão |
| Concorrência | `ConcorrenciaIT` (8) | Retirada de habilitação, vínculo novo, cancelamento e contestação, **nos dois sentidos** (execução com a transação aberta); N execuções = 1 reset; login do titular e reset administrativo nos dois sentidos (recusa por **fator divergente**); último limite da organização disputado |
| Atomicidade | `ReconciliacaoEAtomicidadeIT` | Falha injetada no meio dos efeitos: nada muda e o registro fica `INFORMADO_PELA_CLI` (SQLSTATE e etapa); registro separado indisponível gera erro, log local e alerta |
| Reconciliação | `ReconciliacaoEAtomicidadeIT` | Resposta perdida depois do COMMIT → `EXECUTADA` confirmada pelo banco, sem falha registrada e sem repetição. Execução segurando a trava → **desconhecido** (`TRAVA_NAO_OBTIDA`), nunca "não executada". Consulta impossível → desconhecido, log e alerta, nada no banco |
| Integridade | `IntegridadeEAuditoriaIT` | FK composta evento ↔ solicitação ↔ organização ↔ alvo (23503); tipo 4 sem solicitação (23514); um reset por solicitação (23505); fator de outra conta (23503); organização cruzada sem efeito; Platform sem `ops_request_id` (42501); reset novo sem motivo (23514); NOT VALID |
| Auditoria | `IntegridadeEAuditoriaIT` | Sequência completa de eventos; certificado de cada ato casado com o registro da CA. Varredura de todo o schema `ops`, de `mfa_events`, do log do servidor e dos logs da CLI por tokens e códigos brutos, PEM e `sslpassword`, **com controle positivo** |

**Contraprovas por mutação** (`MutacoesIT`): cada detector passa com a 016, **falha** com a função mutada e volta a passar depois da restauração, que é conferida pela definição idêntica.

| Mutação | Função mutada | Detectada por |
|---|---|---|
| Autoria vinda de ajuste de sessão, sem amarrar certificado e CN ao `session_user` | `ops.operador_da_sessao`, `ops.autorizar` | Personificação: `expected "RECUSA"` |
| Validade por ato sem `not_after` | `ops.autorizar` | Conexão atravessando o vencimento: `expected "RECUSA"` |
| `CONTESTAR` limitado a emissão + 24 h | `ops_notice_issue` | Contestação dentro da espera: aceita → `false` |
| Exceção do evento de reset engolida | `ops_mfa_execute` | Falha injetada: "nenhum efeito parcial" |
| **Guarda de isolamento retirada** (rodada 2) | `ops_mfa_execute` | REPEATABLE READ com o certificado revogado depois do snapshot: `expected "25N01" but was: "executou:OK"`. Sem a guarda, o reset **foi confirmado** com a credencial revogada |

**Fluxo completo pela API** (`EmergencyRecoveryFlowIT`), com a Platform real ligada ao banco com TLS:
1. pedido → verificação → pré-aprovação, pela CLI com certificado;
2. aviso emitido pela Platform e capturado localmente: titular com confirmar e contestar; demais administradores só com contestar;
3. confirmação por POST: GET sem efeito, sem CSRF → 403, outra Origin → 403, recusas idênticas, 204 sem cookie de sessão;
4. espera de 24 h (fixture temporal), aprovação final e execução;
5. aviso de execução.

Depois da execução:
- login com a **senha atual** leva a `mfa_enrollment_required`; cadastro do novo fator; `/me` 200;
- senha, papéis, vínculos e concessões **idênticos** antes e depois;
- o código de prova de conta, pela sessão, é usado pela CLI para o vínculo impeditivo.

## 4. Falhas encontradas e correções

| # | Onde | Achado | Correção |
|---|---|---|---|
| 1 | 016 (execução e aprovação final) | Com o reset administrativo concorrente vencendo, a execução recusava com `SEM_FATOR_CONFIRMADO` (elegibilidade geral), e não com a recusa por **fator divergente** que o contrato nomeia | O fator aprovado passa a ser conferido **antes** da elegibilidade geral (`ConcorrenciaIT`) |
| 2 | CLI | Na reconciliação, a trava não obtida era rotulada `CONSULTA_IMPOSSIVEL`. A classe já era `DESCONHECIDO`, mas o motivo misturava os dois casos do contrato | Motivo `TRAVA_NAO_OBTIDA` preservado |
| 3 | Inventário de locks (observação, sem mudança) | A emissão de certificado de um operador espera enquanto uma execução segura a linha dele em FOR SHARE, porque o registro trava FOR UPDATE | Coerente com a disciplina do contrato; registrado na matriz §5.3 |
| 4 | Desenho (antes do SQL) | Um vínculo de conta com FK direta para `users` formaria ciclo com a execução: operador FOR UPDATE → KEY SHARE na conta, contra conta FOR UPDATE → operador | O vínculo referencia a **prova**, que referencia `users` (matriz §5.2) |
| 5 | Infraestrutura de teste | A vírgula de `{2,40}` no regex do `pg_ident` separava campos | Regex entre aspas |
| 6 | Testes (expectativas) | Precedência entre "contatos insuficientes" e "canal recente"; formato `grant=f` e linhas do índice novo no diff de catálogo; `python3` do Windows | Expectativas ajustadas, sem mudança de comportamento |
| 7 | Runner (varredura da evidência) | A execução `003057Z` parou na varredura com 41 "ocorrências de formato de token" e o próprio `varredura.txt` marcado como contendo senha. Reclassificadas uma a uma: linhas de traços do surefire, identificadores snake_case, hashes hex e o próprio padrão procurado. **Nenhum token real.** O log bruto do Maven não tem nenhuma ocorrência | **Classificação** (`execucao-anterior-invalida/classificacao.txt` no pacote): o código daquela varredura foi substituído 36 s depois e **não** foi preservado, então o 41 não pode ser reproduzido um a um. A classificação cobre o **superconjunto**: toda sequência de 43 ou mais caracteres base64url daquela evidência, 119 ocorrências. São 44 separadores, 66 identificadores ou nomes em minúsculas, 7 identificadores presentes no código e 2 hashes hex. Nenhuma tem formato de token.<br>**Varredura corrigida** (`ops/mfa-emergencia/varredura.py`), com duas mudanças:<br>• A regra intermediária exigia dígito e deixaria escapar cerca de 0,07% dos tokens reais. Agora basta uma maiúscula, e só se excluem identificadores presentes literalmente no código. Um token aleatório escapa com probabilidade de cerca de 2e-10.<br>• **Controle positivo no runner:** 200 tokens gerados pela classe de produção `com.nekkora.core.token.Tokens` e 5 marcadores de chave/senha, plantados num temporário fora da evidência, têm de ser **todos** detectados.<br>A prova exata no banco e nos logs continua em `IntegridadeEAuditoriaIT` |

## 5. Decisões de implementação que pedem confirmação

Sem divergência deliberada do contrato. Estes pontos são **interpretações** onde o contrato não fixa o detalhe, e ficam para decisão explícita na revisão:

1. **30 dias a partir da inclusão do contato.** O canal "registrado há menos de 30 dias" foi lido como incluindo a **inclusão**, não só a alteração (`channel_set_at` = inclusão ou última alteração).
   - Efeito: uma organização recém-cadastrada só tem acesso ao procedimento 30 dias depois do cadastro dos contatos.
   - A alternativa (contar só alterações) é mais permissiva.
2. **Vínculo impeditivo com contato:** vale para **qualquer** contato autorizado da organização alvo, e não só para o solicitante e o segundo contato. É mais estrito que o texto ("ser o solicitante").
3. **Recusa do ato × recusa da solicitação** (matriz §3):
   - recusas do próprio ator (certificado, habilitação, distinção, vínculo do ator, estado, parâmetro, espera ainda não cumprida) **não** encerram a solicitação;
   - recusas da solicitação a encerram (`RECUSADA`/`EXPIRADA`).
   - O contrato fala em `RECUSADA_NA_EXECUCAO` sem distinguir os dois casos.
4. **Limite por origem:** 3 aberturas por contato solicitante em 24 h. *[Correção factual de 2026-09-28]* O contrato **não** fixa número; 3 é escolha da implementação. A cota conta o contato **alegado** na abertura, e uma origem que só alega ser um contato esgota a cota dele: **lacuna** diante de D6(a). Decisão e desenho substituto em `2026-09-28-mfa-emergencia-consolidacao-decisoes.md`, §1 e §4.
5. **Cenário C ("único ORG_ADMIN"):** o banco exige `ORG_ADMIN` efetivo com fator confirmado, mas **não** que ele seja o único. "Outro administrador disponível" não é aferível pelo banco e fica com o julgamento do verificador. *[Correção factual de 2026-09-28]* Esse julgamento **não é registrado** hoje: `ops_mfa_verify` não recebe declaração sobre os demais administradores (**lacuna**). Decisão e desenho em `2026-09-28-mfa-emergencia-consolidacao-decisoes.md`, §1 e §5.
6. **Tentativas com token desconhecido** não geram evento no banco (não há destinatário a atribuir); a Platform registra só a categoria. Tokens conhecidos geram `AVISO_INVALIDO` com destinatário e finalidade.
7. **Consulta sem a trava** (`TRAVA_NAO_OBTIDA`) não é gravada no banco, porque o registro exigiria a própria trava. *[Correção factual de 2026-09-28]* Na **reconciliação**, fica no log local da CLI, com alerta. Na **consulta avulsa** (`consultar`), fica só no log local, com código de saída 4 e **sem alerta**, e o mesmo vale para `CONSULTA_IMPOSSIVEL` (**lacuna** diante de C:235). Correção decidida em `2026-09-28-mfa-emergencia-consolidacao-decisoes.md`, §3.5.
8. **Aviso de reset administrativo aos "demais" `ORG_ADMIN`:** todos os efetivos exceto o alvo, incluindo o ator.
9. **Confirmação tardia demais para aprovar.** Um `CONFIRMAR` usado dentro da própria validade (`min(T_total, emissão + 24 h)`, §6.1), mas com `confirmação + 24 h ≥ T_total`, é **aceito e registrado**. A solicitação então não alcança a aprovação final e expira em `T_total`, que é o resultado que o §6 exige.
   - A alternativa seria recusar essa confirmação no POST. O efeito prático é o mesmo (nenhuma aprovação possível); a diferença é só o que fica registrado.
   - **Aceito na revisão** como não bloqueante, desde que o token esteja válido.
   - **Evidência:** `AvisosIT.confirmacaoValidaTardiaDemaisNaoPermiteAprovarEExpiraSemReset`, com a solicitação aberta há 50 h e pré-aprovada agora (fixture `recuarAbertura`, só na base de teste). O teste mostra:
     - o token `CONFIRMAR` ainda válido é aceito;
     - a confirmação **não altera `T_total`**, que segue igual a abertura + 72 h;
     - a aprovação final é **recusada** (`ESPERA_NAO_CUMPRIDA`) logo depois da confirmação e a 1 h de `T_total`, com a solicitação ainda `PRE_APROVADA`;
     - passado `T_total`, a aprovação encerra a solicitação como **`EXPIRADA`** (`PRAZO_ENCERRADO`), e a execução é recusada (`ESTADO_INVALIDO`);
     - **nenhum reset:** zero eventos tipo 4, fator aprovado ativo, e códigos, desafios, sessões e identidade da conta idênticos.
   - Além disso, a CHECK `final_approved_at >= notice_confirmed_at + 24 h AND final_approved_at < t_total` torna essa aprovação impossível também na tabela.
10. **Reenvio de `CONTESTAR`** existe só para o titular (`ops_mfa_resend_notice`). Os demais administradores recebem o `CONTESTAR` uma vez por marco. *[Correção factual de 2026-09-28]* O contrato prevê reenvio de `CONTESTAR` **por destinatário** (C:179: "invalida o anterior do mesmo destinatário"), e a falha de entrega hoje só vai para o log da Platform (**lacuna**). Decisão e desenho em `2026-09-28-mfa-emergencia-consolidacao-decisoes.md`, §1 e §6.

*As quatro correções factuais acima (itens 4, 5, 7 e 10) foram autorizadas na revisão de 2026-09-28. O texto anterior está em `effa516`. A consolidação dos dez itens, com as decisões, está em `2026-09-28-mfa-emergencia-consolidacao-decisoes.md` e no anexo `2026-09-28-mfa-emergencia-consolidacao-anexo.md`.*

## 5a. Verificação independente (retomada, 2026-09-28 ~02:30Z)

A missão foi retomada sobre o trabalho não commitado da sessão anterior. Nada foi presumido do relatório anterior:
- **Estado conferido:** HEAD `ca4033b`; `db0164c` não é ancestral; nenhum código M365/OAuth2/XOAUTH2 na árvore de trabalho.
- **Revisão direta do SQL da 016:**
  - `ops.operador_da_sessao` e `ops.autorizar`: `session_user`, CN = `session_user`, emissor e serial da `sslinfo`, registro não revogado, validade contra o `v_agora` depois dos locks, habilitação por ato.
  - `ops_mfa_final_approve`, `ops_mfa_execute`, `ops_mfa_cancel`, `ops_mfa_status` e `ops_mfa_record_failure`: ordem de locks do §7; fator aprovado conferido antes da elegibilidade geral; `lock_timeout` com `TRAVA_NAO_OBTIDA`.
  - `ops_notice_issue` e `ops.usar_token`: finalidade, destinatário, organização, uso único, primeira confirmação fixa, nenhum `CONFIRMAR` depois dela, `CONTESTAR` até `T_total` só em estado não terminal.
  - Bloco de invariantes da matriz.
- **Revisão da Platform:** `EmergencyNoticeController`, `EmergencyNotices`, `SecurityConfig`/`Routes` (CSRF de pré-autenticação) e `AdminService` (motivo). Nenhum token em log; só o hash vai ao banco.
- **Sem desvio de teste em código de produção:** a falha injetada é um trigger criado pelo superusuário só na base descartável do teste; as fixtures temporais deslocam instantes gravados só na base de teste; a 016 não tem parâmetro de relógio.
- **Cobertura:** nomes dos testes mapeados contra cada linha do §10 do contrato; nenhuma linha sem teste.
- **Nova execução completa do runner** (`023718Z`), com os resultados do §3 reproduzidos integralmente. Depois do pedido da revisão, vieram o teste da confirmação tardia, a varredura corrigida com controle positivo, o código de saída por etapa e a etapa de preservação, e a execução **oficial** (`2026-09-28T030420Z`).
- **Efeito colateral do ambiente:** o Docker Desktop estava parado e foi iniciado às ~02:35Z para a validação. Três containers preexistentes com política `unless-stopped` voltaram a subir sozinhos às 02:36:02Z: `nekkora-postgres` (base principal), `nekkora-portal-013-postgres` e `nekkora-portal-dev-postgres`. Nada foi executado neles (§5c).

## 5b. CA e IdP simulados × infraestrutura efetivamente validada

| Componente | Nesta entrega | O que está **validado** | O que **não** está validado |
|---|---|---|---|
| IdP corporativo | **Simulado** (`IdpSimulado`, só teste): emite tokens RS256 com `auth_time`, `acr`, `amr`, emissor e audiência controlados | O **verificador** de produção (`VerificadorIdp`) exige a garantia do §5d.2 (`acr` resistente, `mfa` afirmado, prova de posse) e recusa sessão antiga, token renovado, `hwk` isolado, emissor/audiência/validade/assinatura errados e `alg=none` | Nenhum IdP real, nenhum MFA resistente real, nenhum `acr` ou `auth_time` de IdP real, nenhuma federação. **Que o IdP só emita `phr`/`phrh` quando a política exigiu o método resistente é configuração do IdP, não verificável aqui** |
| CA operacional | **Biblioteca em processo** (`EmissorCertificados`) com raiz **descartável** gerada por execução (`Pki`, só teste). Sem serviço HTTP | Política sujeito→CN, 15 min, EKU `clientAuth`, registro no banco **antes** de assinar pelo papel `nekkora_ops_ca`, recusa pelo banco fora da política, revogação e efeito no ato seguinte | Nenhuma CA de produção, HSM, custódia de chave, transporte CLI↔CA, CRL/OCSP ou administração separada da CA |
| PostgreSQL com TLS e certificado | **Real** (PostgreSQL 16 em container descartável, `hostssl … cert`, `pg_ident` por CN) | Autenticação por certificado, `sslinfo` lida pelo servidor, conexão aberta atravessando o vencimento, papéis e privilégios reais | `pg_hba` de produção (rede de administração, sem pooler) |
| Platform (API e rotas de aviso) | **Real** (Spring Boot da branch contra o banco com a 016) | CSRF de pré-autenticação, Origin, POST explícito, sem sessão, hash no banco, motivo do reset | Página publicada (não servida) |
| E-mail | **Captura local** (`TestMailSender`; `LocalMailSender` só registra a intenção) | Destinatários, finalidades e tokens por destinatário | Entrega real, links reais e aceite SMTP. **M365 não retomado** |

O simulador comprova o comportamento da **integração**, não a implantação de um IdP ou de uma CA. O código de produção não tem desvio de autenticação: os testes usam os mesmos caminhos (certificado real sobre TLS real).

## 5c. Sem frontend; base principal e hml03 intactos

- **Frontend:** nenhuma implementação.
  - O repositório não rastreia código de frontend: nenhum `package.json`, TS/JS, CSS ou HTML de aplicação.
  - A única página é `api/nekkora-platform/paginas/aviso-mfa-emergencia.html`: um arquivo estático de 85 linhas, **fora** de `src/main/resources`, sem referência no build e **não servido**. Foi entregue como protótipo do POST explícito pedido pela missão (§4, "páginas de aviso"), e a publicação continua gate (D3).
  - `api/e2e/email/` é preexistente, da missão de e-mail, e não faz parte desta entrega.
- **Base principal (`nekkora-postgres`) e hml03:**
  - nenhum comando desta missão os usou;
  - os testes usam só containers descartáveis (Testcontainers e `nekkora-mfa016-*`), com portas aleatórias e sem volumes nomeados;
  - a etapa 5 do runner (`preservacao.txt`, `containers_antes.txt`, `containers_depois.txt`) confere id, criação, estado e início de todo container fora da validação, **idênticos** antes e depois;
  - os containers do hml03 continuam `exited` desde 2026-09-27T02:16Z, antes desta missão.
  - O único evento foi o reinício automático do §5a, feito pelo Docker Desktop.
- **Migrations 001–015:** byte a byte iguais a `ca4033b` e a `origin/main`, conferido pela etapa 5.

## 5d. Rodada 2: achados da revisão e correções

A revisão conferiu que os anexos da rodada 1 eram idênticos aos já revisados e manteve cinco achados. Para cada um: a correção, os testes direcionados e a contraprova. Os 126 testes da rodada 1 continuam evidência dos cenários executados, mas não cobriam estes achados.

### 5d.1 Isolamento na 016 (REPEATABLE READ com snapshot anterior à revogação)

- **Achado, reproduzido:**
  - Em `REPEATABLE READ` ou `SERIALIZABLE`, o snapshot fica fixo no primeiro comando da transação, ou no início da própria chamada quando ela espera um lock.
  - Os locks da 016 continuam sendo obtidos, porque a revogação não altera a linha de `ops_operators`, mas as conferências (`ops.autorizar`, `ops.participante_invalido`) liam o snapshot antigo.
  - **Com a guarda retirada, um certificado revogado depois do snapshot autorizou a execução e o reset foi confirmado** (contraprova M5, `executou:OK`).
- **Correção:**
  - `ops.exigir_read_committed()` é a **primeira instrução** das 29 funções públicas `ops_*`: logo depois do `BEGIN` nas 23 plpgsql, e primeiro comando nas 6 SQL.
  - Qualquer isolamento diferente de `read committed` dá erro `25N01` (classe 25, estado de transação inválido), com rollback integral, sem efeito e sem registro.
  - Em `READ COMMITTED`, cada comando da função lê um snapshot novo, tirado depois dos locks.
  - O bloco de invariantes da 016 **reprova a migration** se alguma função pública não tiver a guarda como primeira instrução.
  - Defesa adicional: a CLI (`ExecutorAtos.abrirConexao`) e a CA (`EmissorCertificados`) fixam `READ COMMITTED` na conexão, e a Platform já usava `READ COMMITTED` (`OrgTransactions`).
- **Testes (`IsolamentoIT`, 4):**
  - certificado do executor revogado depois do snapshot, em `REPEATABLE READ` e em `SERIALIZABLE` → `25N01`, sem reset, com fator, códigos, desafios e sessões intactos. **Controle positivo:** a mesma credencial em `READ COMMITTED` → `RECUSA CERTIFICADO_REVOGADO`;
  - `APROVAR` do aprovador (participante) retirado depois do snapshot do executor → `25N01`. Controle em `READ COMMITTED` → `PARTICIPANTE_INABILITADO`, solicitação `RECUSADA`;
  - isolamento padrão da sessão alterado (`SET SESSION CHARACTERISTICS`, `default_transaction_isolation`), sem `BEGIN` explícito → `25N01`;
  - **todas** as funções públicas, lidas do catálogo (29) e chamadas pelo chamador real de cada uma (operador por certificado, `nekkora_ops_ca`, `nekkora_platform`, superusuário para `ops_bootstrap_operator`) → `25N01` em `REPEATABLE READ` e `SERIALIZABLE`. Em `READ COMMITTED`, a mesma chamada não cai na guarda.
- **Contraprova:** `MutacoesIT.mutacaoDaGuardaDeIsolamentoEDetectada` (M5).

### 5d.2 Garantia exigida do IdP (`hwk` isolado não comprova MFA resistente a phishing)

- **Achado:** o verificador aceitava `amr` contendo `hwk` (ou `fido2`/`webauthn`, que nem são valores da RFC 8176) como "método resistente". `hwk` prova posse de chave de hardware; não prova múltiplos fatores nem resistência a phishing.
- **Garantia definida:** para **este** evento de autenticação, o IdP afirma MFA resistente a phishing. As três condições são exigidas:
  1. `acr` = `phr` ou `phrh` (OpenID Connect EAP ACR Values 1.0). Sem isso: `METODO_NAO_RESISTENTE`;
  2. `amr` contém `mfa` (RFC 8176). Sem isso: `MFA_NAO_AFIRMADO`;
  3. `amr` contém prova de posse de chave (`hwk`, `swk` ou `pop`); para `phrh`, `hwk`. Sem isso: `ASSERCAO_INCONSISTENTE`.
- **Registro no banco:** o método registrado é `<acr>+mfa`. `ops_register_certificate` e a CHECK de `ops_operator_certificates.idp_method` aceitam **só** `phr+mfa` e `phrh+mfa`. Um registro com `hwk`, `phrh` sem `mfa` ou `otp` é recusado, mesmo vindo do papel da CA.
- **Testes:**
  - `CertificadosIT.garantiaDoIdpExigeAcrResistenteMfaEProvaDePosse`: tabela de 12 casos, cada um diferente da linha de base aceita em **um** aspecto. A linha de base é o controle positivo, e o teste confere também o método registrado;
  - `bancoRecusaRegistroForaDaPoliticaMesmoVindoDaCa`: `hwk` e `phrh` sem `mfa`.
- **Limite:** a garantia depende de o IdP **só** emitir `phr`/`phrh` quando a política de acesso exigiu o método resistente nesta autenticação. Isso é configuração e implantação do IdP (gate do §7).

### 5d.3 UUID da solicitação na CLI

- **Achado:** `ops_mfa_open` devolvia o id, mas a CLI o descartava. `Resultado` não tinha o campo e a saída não o mostrava, então o operador precisava de consulta administrativa para continuar. Os próprios testes tiravam o id por superusuário ("última solicitação aberta").
- **Correção:**
  - `ExecutorAtos.Resultado` ganhou `solicitacao`: em ABRIR, o id devolvido pelo banco; nos demais atos, o informado. É nulo quando não houve abertura.
  - A saída da CLI começa com `solicitacao=<uuid>`, ou `-` quando não há solicitação.
  - `OpsCli.executar` recebe ambiente e saídas como parâmetros; o `main` passa `System.getenv()`/`System.out`, sem mudança de comportamento.
  - `Cenarios.aberta` usa o id do resultado, então **toda** a suíte depende dele agora.
- **Testes (`OpsCliContinuidadeIT`, 2), pela entrada real da CLI** (certificado, TLS, variáveis de ambiente, saída de texto):
  - abrir → `verificar` → `pre-aprovar` → `consultar` só com o UUID lido da saída de abrir.
  - **Contraprova:** uma segunda abertura para outro alvo vira a "última solicitação", e o teste mostra que essa consulta daria o id errado, enquanto o fluxo continua a primeira pelo UUID da saída.
  - A conferência por superusuário vem só **depois** e é declarada.
  - Abertura recusada: código 2, `solicitacao=-` e nenhuma solicitação criada.
  - A senha da credencial não aparece na saída nem no log local.

### 5d.4 ZIP completo

- **Achado:** o manifesto listava `repo/<caminho>` com hash, mas o ZIP levava só o patch.
- **Correção:** `empacotar.sh` copia a versão **final** de cada arquivo entregue para `repo/<caminho>` no pacote e **falha** se algum item `repo/` do manifesto faltar no pacote ou tiver hash diferente.

### 5d.5 Relógio

- **Achado:** o texto da rodada 1 afirmava que um recuo de relógio levaria a CHECK violada e rollback. Isso não vale em todos os casos.
- **Correção:** texto refeito no §3 ("Relevância para a 016"). Um recuo só causa rollback quando cruza um marco gravado. Um recuo menor pode aceitar um ato até o tamanho do recuo depois do prazo real. O requisito de relógio sem saltos para trás virou gate (§7).

## 5e. Encerramento da revisão (rodada 2)

**Registro da revisão de 2026-09-28:** os cinco achados da rodada 2 estão **encerrados** dentro da revisão estática desta rodada, e nenhum bloqueio novo foi encontrado nas correções examinadas.

| Item conferido | Resultado |
|---|---|
| Pacote `mfa-emergencia-implementacao-20260928T042624Z.zip` | SHA-256 `26291a7c539000f7a0563cfa10c0d4676d77aae7ae78fbf16c9aa6e64ff839b8` correto; 158/158 entradas do manifesto presentes e íntegras |
| Isolamento (§5d.1) | Guarda nas 29 funções públicas, invariante no banco e testes com os chamadores reais. A contraprova M5 reproduz o problema sem a guarda |
| IdP (§5d.2) | Exigência conjunta de `acr`, MFA afirmado e prova de posse. A configuração do IdP real continua dependência explícita (gate 5) |
| CLI (§5d.3) | UUID preservado no resultado e na saída; continuidade testada sem consulta administrativa |
| Relógio (§5d.5) | Retirada a garantia indevida de rollback para qualquer recuo |
| Evidências | Resumo oficial (`2026-09-28T041923Z`) com todas as etapas em zero, 134 métodos e 5 mutações detectadas |

**Escopo da revisão:** estática. O revisor conferiu código, testes e evidências fornecidas, e **não** reexecutou Maven, Docker nem PostgreSQL.

**O que o encerramento NÃO faz:**
- **Não aprova** as decisões de implementação do §5, itens 1 a 10. Elas ainda precisam ser consolidadas antes da integração.
- **Não remove** os bloqueios de produção (§7):
  - notificação real;
  - CA/IdP e operação implantados;
  - retenção D9;
  - autorização de elevação da 016;
  - controle do relógio.
- **Não publica a página:** `paginas/aviso-mfa-emergencia.html` continua **só protótipo não publicado**.
- O foco segue na API, com e-mail pausado e frontend para depois.

**Commit:**
- Conjunto revisado, na `feat/mfa-emergencia`, sem push nem merge: os 54 arquivos da missão do pacote acima e a evidência oficial `ops/mfa-emergencia/saidas/2026-09-28T041923Z/`, sem o log bruto do Maven (`maven.txt`), que não fez parte do pacote revisado.
- Antes do commit, os 54 arquivos foram conferidos, byte a byte, iguais a `repo/` do pacote revisado. A única alteração posterior é este registro de encerramento no relatório (este §5e e o cabeçalho).
- Ficam **fora** do commit:
  - os itens preexistentes de outras missões (`api/e2e/email/`, `db/tests/portal-8-2/` e as saídas de outras suítes);
  - as execuções não oficiais e a inválida (estão só no pacote);
  - as saídas brutas da suíte de banco (`db/tests/mfa-emergencia-016/saidas/`), cujos resumos estão na evidência oficial.

## 5f. Rodada 3 — revisão 4 do contrato e inventário de locks (2026-09-28, sem commit)

**Autorização:** passos 1–4 da mensagem de aprovação de I-5 (consolidar I-5 e a restrição de entrega; detalhar as tabelas novas; corrigir I-1–I-5 e implementar os desenhos aprovados; contraprovas, testes determinísticos, regressão e pacote). **Fora da autorização, e não feitos:** commit, integração, aplicação em bases persistentes, retomada do e-mail real e frontend.

**Documentos de decisão** (não versionados antes desta rodada): `2026-09-28-mfa-emergencia-consolidacao-decisoes.md` (v6), `…-consolidacao-anexo.md` (v5) e `…-inventario-locks.md` (v3, com o esquema detalhado no §10 e a decisão de I-5 no §11.1).

### 5f.1 O que mudou

| Área | Mudança |
|---|---|
| 016 | Revisão 4 descrita no cabeçalho. Papel `nekkora_ops_intake` (LOGIN, só `ops_intake_register` e `ops_intake_key_activate`). 7 tabelas novas (estado das versões de chave, entradas, baldes de origem, retornos pelo canal registrado, fotografia dos considerados, histórico de resultados de entrega, tentativas consideradas na aprovação): 21 tabelas em `ops`. 8 funções públicas novas: 37 `ops_*`. Janelas em horas (720 h e 168 h). FK `ops_registry_events.organization_id` retirada (I-3/I-4; coluna e valor mantidos). Tentativas de aviso numeradas com máquina de estados em trigger. `ops_mfa_record_failure` na ordem canônica (I-2). Contas envolvidas (alvo, considerados e destinatários) travadas juntas em ordem crescente (`ops.travar_contas`), com `CONTAS_EM_MUDANCA` quando aparece conta necessária não travada (I-5) |
| Platform | `confirmEmail`: organização → conta → reconferência e consumo do token (I-1). `resetMfa`: verificação preliminar sem lock → organização → ator e alvo em ordem crescente no modo final → reconferência de sessão, step-up, permissão e alvo ≠ ator → efeitos (I-5). `EmergencyNotices`: emissão → reivindicação `ENTREGANDO` → entrega única → resultado; reconciliação só na mesma execução e só antes de chamar o transporte (restrição v6); `recoverStalled`; categoria do token no log, sem segredo. `MailSender.Outcome` |
| CLI | `retorno <contato>`; `abrir … entrada|retorno <id>`; declaração dos demais na `verificar`; `reenviar-aviso <s> <destinatario>`; alerta na consulta avulsa (item 7) |

**Correção durante a rodada:** o primeiro ensaio de regressão falhou em quase todas as classes com `55000` (`record "v_ent" is not assigned yet`) na abertura por retorno: o registro da entrada só era atribuído no caminho por entrada. Corrigido atribuindo a origem vazia no outro caminho (`ops_mfa_open`). Testes existentes ajustados porque a revisão 4 muda a preparação ou o resultado esperado (nenhum por relaxar regra):
- `ConcorrenciaIT.ultimaRecuperacaoDaOrganizacaoDisputadaPorDoisAlvos`: a perdedora agora termina em `ADMIN_PODE_CADASTRAR` (o alvo vencedor, fotografado com fator, pode cadastrar), conferido antes do limite; o `LIMITE_ORGANIZACAO` passou a ser coberto numa verificação posterior;
- `IntegridadeEAuditoriaIT.eventoDeReset…` e `EstadosEPrazosIT.alvoInelegivel`: o outro administrador passa a existir **antes** da verificação (criado depois, a composição fotografada muda);
- `IntegridadeEAuditoriaIT.fatorAprovadoDeOutraConta…`: o INSERT estrutural preenche as colunas novas obrigatórias, para que só a FK composta falhe;
- contagens: 37 funções (`IsolamentoIT`, com o chamador `nekkora_ops_intake`), 23 tabelas na varredura (21 de `ops` + 2), suíte de banco (37 e 21).

### 5f.2 Testes novos

| Classe | Cobre |
|---|---|
| `InventarioLocksPlatformIT` (12) | I-1: contraprova da ordem anterior (`40P01`), espera simples depois, dois `confirmEmail` com o mesmo token (um consumo), token vencido/revogado/consumido/de outra organização, disputa com ato da 016 nas duas ordens. I-5: contraprova dos resets cruzados (`40P01`), resets cruzados nas duas ordens com **exatamente um** concluído, segundo reset depois do commit do primeiro, recusa depois de sessão revogada, permissão retirada e step-up vencido **durante a espera pelos locks**, e reset concorrente com execução, pré-aprovação e reenvio da 016 nas duas ordens |
| `InventarioLocksOpsIT` (7) | I-2: contraprova e espera simples nas duas ordens, solicitação inexistente. I-3/I-4: contraprova, vínculo e prova de conta sem esperar a organização, organização ainda gravada no evento, só a FK prevista retirada |
| `Revisao4PrazosEImpedimentosIT` (6) | Item 1: `limite − 1 µs`, `limite`, `limite + 1 s` para a carência e as duas janelas, em quatro `TimeZone` (Europe/Berlin atravessa a mudança de horário, conferido); fluxo com contato a 719 h e 720 h. Item 2: terceiro contato em cada ato e na reconferência da execução. Item 3: matriz de recusas (ato × terminal, com evento) e erros técnicos (guarda `25N01`, falha injetada) sem recusa |
| `Revisao4OrigemIT` (8) | Item 4: origem não comprovada, recusas da entrada, retorno com canal alterado ou de outro operador, 4 aberturas concorrentes → 3, mesma origem em duas organizações → 3, rotação (entrada anterior à ativação, contagem única dos dois pseudônimos, 25 h, ativação que espera abertura em curso) |
| `Revisao4DeclaracaoIT` (7) | Item 5: declaração, fotografia completa e imutável, os quatro sinais confirmados antes do ato, considerado sem fator, fluxo que espera a execução, sinal confirmado durante a espera da execução, `CONTAS_EM_MUDANCA` |
| `Revisao4TentativasIT` (11) | Item 10: transições permitidas e proibidas, idempotência, recuperação, worker × varredura, vigente (falhas e desconhecidos), aceite fora de ordem com uso dos tokens, reenvio que falha, `ENFILEIRADA` não satisfaz (2), aceite tardio e expiração, reenvio depois da aprovação ao titular e ao outro administrador, registro de aceite × execução nas duas ordens |
| `EntregaReivindicacaoIT` (7) | §6.8 com o `EmergencyNotices` real sobre `DataSource` com falhas injetadas: confirmação do commit perdida com e sem aplicação, banco inacessível na reconciliação, varredura antes da reconciliação, dois processos, queda depois da entrega, transporte incerto. Contador confirma que cada falha foi disparada. **Alcance:** chamadas observadas pelo harness por `attempt_id`, não recebimento exatamente uma vez |
| `EmergencyRecoveryFlowIT` (+1) | Item 6: token de outra organização, desconhecido e malformado sem escrita; recusa atribuível registrada; log com categoria e organização da rota, sem token, hash nem destinatário, com controle positivo da captura |
| `OpsCliContinuidadeIT` (+2) | Item 7: consulta avulsa sem a trava e com banco inacessível → código 4, alerta no log local, nada no banco |
| `MutacoesIT` (+5) | Cota por contato, invalidação de tentativa posterior, `notice_seq_at_approval` ignorada, sinais lidos antes dos locks, `encerrar` sem efeito: **todas detectadas** |

### 5f.3 Resultado

Execução oficial `ops/mfa-emergencia/saidas/2026-09-28T125940Z/` — `resultado=APROVADO`:
- suíte de banco: 0 (37 funções, 21 tabelas, dados da base populada idênticos, restauração lógica);
- Maven: 0 — core 5, ops 119, platform 64, portal 12 (200 métodos);
- mutações: 0 — **10 de 10** detectadas;
- varredura: 0 achados; controle positivo 200/200 tokens e 5/5 marcadores;
- preservação: containers fora da validação idênticos antes e depois; migrations 001–015 iguais a `ca4033b` e a `origin/main`.

### 5f.4 Limites desta rodada

- **Contraprovas "ordem anterior"** de I-1, I-2, I-3/I-4 e I-5 reproduzem em SQL direto a sequência de locks do código anterior (inventário §4). Elas provam que aquela ordem produz `40P01`; não executam o código anterior.
- **Sinais do item 5** no ops são escritos em SQL na forma da autenticação (conta `FOR UPDATE` → sinal). O login real concorrente com a execução é coberto só pelo reset (Platform), não pelos quatro sinais.
- **`CONTAS_EM_MUDANCA`** só é alcançável quando um papel `ORG_ADMIN` é inserido com responsável (não trava a organização) durante a espera do ato. As demais mudanças de composição esperam a organização.
- **Limites de 25 h da rotação** testados com margem de 5 s, não de 1 µs: o registro de entrada usa `clock_timestamp()` e não recebe `p_agora`.
- **Serviço de entrada** (desafio de ida e volta, chave HMAC em cofre) é dependência proposta, simulada nos testes com pseudônimos aleatórios.
- **Varredura de recuperação** (`recoverStalled`) está implementada e testada, mas **não** é agendada, assim como a emissão periódica (desligada por padrão).
- **Alerta da CLI** vai ao log local e ao `System.err` do processo, não ao fluxo de erro injetado em `OpsCli.executar`.
- **Emissor e revogador de token de verificação de e-mail** não existem no código. A regra para quando existirem (organização → conta → token) está no inventário §4.1.

### 5f.5 Limpeza da rodada

- `nk016-r4` (ensaio manual da 016 revisão 4, rede none): **removido** (`docker rm -f nk016-r4`).
- `nekkora-mfa016-limpa` e `nekkora-mfa016-populada` do primeiro ensaio da suíte, que falhou pela contagem antiga de funções: **removidos**.
- `scratchpad/016_effa516.sql` (cópia da 016 de `effa516`, fora do repositório): pode ser apagado.

## 5g. Correções da revisão da rodada 3 (2026-09-28, sem commit)

A revisão estática do pacote `mfa-emergencia-implementacao-20260928T153401Z` (SHA-256 `4b35f25f…caf93b`, conferido pelo revisor) apontou dois defeitos e dois ajustes documentais. Os resultados da §5f continuam válidos para o que cobriam; as limitações da §5f.4 continuam.

**1. Tentativa antiga emitia tokens depois do aceite da nova** (`ops_notice_issue`).
- Entrelaçamento: tentativa 1 criada sem tokens → tentativa 2 emitida e `ACEITA` → emissão da 1. O aceite da 2 só invalida tokens existentes, e o aceite tardio da 1 só invalida `seq` menores: o token antigo nascia depois da substituição e valia.
- Correção: a emissão não gera token de finalidade para a qual já existe tentativa **posterior** do mesmo destinatário **`ACEITA`** com token dessa finalidade. Sem finalidade restante, a tentativa vai a `FALHOU` com `SUBSTITUIDA_POR_TENTATIVA_ACEITA` e não sai. Uma tentativa posterior `FALHOU`, `DESCONHECIDO`, `ENFILEIRADA` ou `ENTREGANDO` **não** impede a emissão nem a entrega da anterior.
- Testes:
  - `Revisao4TentativasIT.tentativaAntigaNaoEmiteDepoisDoAceiteDaNova`: outro administrador e titular, com `CONFIRMAR` e `CONTESTAR`;
  - `…tokenAntigoRecusadoENovoUtilizavel`;
  - `…tentativaAntigaEmiteQuandoANovaFalhouOuEIncertaOuPendente`: nova em `FALHOU`, `DESCONHECIDO`, `ENFILEIRADA` e `ENTREGANDO`;
  - `EmergencyRecoveryFlowIT.tentativaAntigaEmitidaDepoisDoAceiteDaNovaNaoValePelaRota`, pela **rota HTTP real**: o `CONTESTAR` cujo hash a tentativa antiga ofereceu ao ser emitida recebe 400 com a resposta pública comum, e o da nova recebe 204. O mesmo teste faz a **contraprova na rota**: com a verificação retirada da emissão (definição alterada só na base de teste, restaurada e conferida), o token antigo recebe 204;
  - `EntregaReivindicacaoIT.tentativaAntigaSoSaiSeANovaNaoFoiAceita`, com o despachante real: com a nova aceita, nenhuma chamada ao transporte para a antiga; com a nova recusada pelo transporte, a antiga sai uma vez;
  - mutação **M11** (`MutacoesIT`): detectada.

**2. Reenvio depois da aprovação impedia o encerramento por prazo** (`ops_mfa_execute`).
- `CONDICAO_DE_AVISO_ALTERADA` vinha antes de `ops.vencida`: executar depois do prazo mantinha `APROVADA`.
- Correção: depois das conferências de autorização, estado, executor, vínculo e contas, o **prazo** é conferido primeiro (→ `EXPIRADA`, `PRAZO_ENCERRADO`), e só então a condição de aviso (recusa do ato).
- Testes:
  - `Revisao4TentativasIT.prazoTemPrecedenciaSobreACondicaoDeAviso`, com reenvio depois da aprovação e passagem de 59 min, 60 min e 2 h: antes do vencimento, `CONDICAO_DE_AVISO_ALTERADA` e `APROVADA`; no vencimento e depois, `EXPIRADA`. Nunca há reset, a repetição do ato não muda o desfecho, e a primeira confirmação, `T_total` e a aprovação ficam inalteradas;
  - `…limiteExatoDaJanelaDeExecucao`, por `p_agora`: `aprovação + 60 min − 1 µs` não vencida; `+ 60 min` e `+ 60 min + 1 s` vencida;
  - mutação **M12** (ordem anterior: condição antes do prazo): detectada.

**Ajustes documentais:**
- o comentário de `ops_mfa_record_failure` descreve a ordem implementada (organização → conta → solicitação → operador, com reconferência);
- o manifesto usa caminhos relativos à **raiz do pacote** (a pasta de primeiro nível do ZIP), sem o prefixo `pacote/`. `cd <pacote> && sha256sum -c manifesto.sha256` confere tudo diretamente, e o empacotador faz essa conferência antes de gerar o ZIP. Arquivos entregues removidos, se houver, vão para `arquivos-removidos.txt`;
- a decisão do item 10 (§6.3 e §6.6 de `…consolidacao-decisoes.md`) registra as duas correções.

**Resultado:** execução oficial `ops/mfa-emergencia/saidas/2026-09-28T154847Z/` — `resultado=APROVADO`:
- suíte de banco: 0;
- Maven: 0 — core 5, ops 126, platform 66, portal 12 (209 métodos);
- mutações: 0 — **12 de 12** detectadas;
- varredura: 0 achados, com controle positivo OK;
- preservação: 0.

Antes dela, os testes direcionados passaram: `Revisao4TentativasIT` (16), `MutacoesIT` (12), `EmergencyRecoveryFlowIT` (4) e `EntregaReivindicacaoIT` (8). Depois da execução oficial só mudaram este relatório e o empacotador (rótulo da execução anterior).

## 5h. Encerramento da revisão da rodada 3 (2026-09-28)

- **Revisão concluída** por inspeção do código e das evidências, sem reexecução de Maven, Docker ou PostgreSQL pelo revisor.
- **Conferido:**
  - ZIP `mfa-emergencia-implementacao-20260928T155926Z.zip`, SHA-256 `741a2d3fad07a81fbc12c8477406885657093cc3b3bd56d7dab84c4ab8c7d19d`;
  - manifesto 160/160, sem divergência;
  - migration 016, SHA-256 `25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61`;
  - código das duas correções da §5g, testes direcionados, contraprovas M11 e M12;
  - evidência oficial `2026-09-28T154847Z`: 209 testes, 12/12 mutações e todas as etapas com saída 0.
- **Os dois achados da revisão da rodada 3 estão encerrados** dentro do escopo revisado.
- **Precisões do revisor:**
  - na execução depois de expirar, a repetição retorna `ESTADO_INVALIDO`, mantendo `EXPIRADA` e sem reset. O teste confere isso; não é repetição do mesmo motivo;
  - o texto das definições colado na conversa foi conferido contra o arquivo do ZIP, que está completo e correto.
- **Commit autorizado** do conjunto revisado na `feat/mfa-emergencia`, com a evidência oficial `2026-09-28T154847Z` (sem o log bruto do Maven, como na rodada 2). O conteúdo foi conferido contra o manifesto antes do commit; a única diferença posterior à revisão é esta nota (§5h).
- **Continuam:** os limites documentados (§5f.4, §6) e os bloqueios de produção (§7). A aprovação não autoriza aplicação em bases persistentes, retomada do e-mail real nem frontend.

## 6. Limitações

- **CA e IdP são bibliotecas testadas em processo**, com o IdP simulado. Não há serviço HTTP da CA nem integração com um IdP real; o transporte CLI ↔ CA é dependência de implantação.
- **E-mail:**
  - `LocalMailSender` só registra a intenção, e a entrega real continua pausada (M365, `db0164c`);
  - o formato dos links está documentado na página, mas nenhum remetente real monta links nesta branch;
  - a interface `MailSender` ganhou `sendEmergencyNotice`: as implementações da branch pausada terão de implementá-la quando a missão de e-mail for retomada.
- **A página de aviso** não é servida pela Platform e tem CSP com `unsafe-inline`. A publicação é gate (D3).
- **Emissão periódica** desligada por padrão; nos testes, `dispatchPending()` é chamada diretamente.
- **A 016 exige superusuário** (`CREATE ROLE`, `sslinfo`). A lista de elevação autorizada da homologação **não** foi atualizada.
- **As fixtures temporais simulam** o tempo deslocando instantes gravados (`recuar*`), só na base de teste. A execução real de 24 h ou 72 h não foi feita.
- **Varredura da evidência:** heurística documentada (`varredura.py`), com controle positivo. Um valor igual a um identificador do código do repositório não é marcado, por desenho. A prova exata dos tokens gerados continua nos testes.
- **A regra "uma identidade operacional por pessoa" é administrativa** (contrato §4). O banco não a prova.
- **`pg_hba`/`pg_ident` operacionais** só existem no container de teste; a configuração de produção (rede de administração, sem pooler) é implantação.

## 7. Gates de produção

1. Notificação real validada (entrega de e-mail, links e página publicada) — D3.
2. CA operacional, IdP corporativo (MFA resistente e `auth_time` confiável) e operação efetivamente implantados: cadastro de operadores, contatos e `pg_hba`.
3. Retenção definida pelo jurídico e pelo encarregado de dados — D9.
4. Autorização de elevação para aplicar a 016.
5. **(rodada 2)** IdP configurado para emitir `acr` `phr`/`phrh` e `amr` com `mfa` **só** quando a política exigiu MFA resistente a phishing nesta autenticação (§5d.2).
6. **(rodada 2)** Relógio do servidor de banco sincronizado com correção gradual, sem saltos para trás, e monitorado (§3, §5d.5).

## 8. Recursos descartáveis e limpeza

| Recurso | Criado por | Limpeza |
|---|---|---|
| Containers `postgres:16-alpine` do Testcontainers (TLS/certificado, banco comum) e `redis` | Maven (`nekkora-ops`, `nekkora-platform`, `nekkora-portal`) | Removidos pelo Ryuk ao fim da JVM. Conferir: `docker ps -a --filter label=org.testcontainers=true` |
| `nekkora-mfa016-limpa`, `nekkora-mfa016-populada` (rede none, sem porta, sem volume) | `db/tests/mfa-emergencia-016/run.sh` | Removidos no sucesso. Na falha: `docker rm -f nekkora-mfa016-limpa nekkora-mfa016-populada` |
| `nk016-smoke` (instalação limpa inicial) | Ensaio manual desta missão | `docker rm -f nk016-smoke` |
| Diretórios temporários `nekkora-ops-banco-*` (raiz do servidor, logs da CLI) e PKCS#12 `nekkora-ops-*.p12` | `OpsBanco` / `Credencial` | Os PKCS#12 são apagados no `close()`. Os diretórios ficam no temp do SO: `rm -rf "$TMP"/nekkora-ops-banco-*` (Git Bash: `/tmp` ou `%TEMP%`) |
| `db/tests/mfa-emergencia-016/saidas/`, `ops/mfa-emergencia/saidas/` | Runners | Evidência local não versionada; apagar à vontade |
| Temporário do controle positivo (`mktemp -d`, 200 tokens de teste) | `validar.sh` | Apagado pelo `trap` na saída do runner. Os tokens nunca vão para a evidência |
| Pacote `../relatorios/mfa-emergencia-implementacao-20260928T024313Z(.zip)` | Retomada, antes do pedido da revisão | **Substituído e apagado.** O pacote válido é o do §10 |
| Pacote `../relatorios/mfa-emergencia-implementacao-20260928T025312Z(.zip)` | Da execução `024805Z` | **Substituído e apagado** pela execução com a lista por método |

Nenhuma credencial real foi usada; todas as senhas e chaves são aleatórias por execução e nunca foram gravadas em evidência.

## 9. Reprodução

```sh
ops/mfa-emergencia/validar.sh          # suíte de banco + Maven + mutações + varredura com controle positivo + preservação
ops/mfa-emergencia/empacotar.sh <dir> # pacote (exige resultado=APROVADO no resumo.txt)
db/tests/mfa-emergencia-016/run.sh     # só a suíte de banco
cd api && ./mvnw -pl nekkora-ops -am test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false   # só os grupos do §10
```

Requisitos: Docker, JDK 21, Git Bash/Linux/macOS e Python 3.

## 10. Pacote

O pacote vai para `../relatorios/`. **No Windows**, extraia numa pasta curta (por exemplo `C:\rev`): o maior caminho dentro do ZIP tem 144 caracteres, e extrair sob um diretório profundo esbarra no limite de 260 caracteres. A conferência do manifesto também pode ser feita sem extrair, lendo os membros do ZIP.

**Rodada 3:**
- o diff é contra `effa516` (candidata revisada da rodada 2; `BASE=ca4033b ops/mfa-emergencia/empacotar.sh` gera o diff desde o contrato);
- a evidência é `2026-09-28T154847Z` (depois das correções da §5g); `125940Z` (pacote anterior) e a oficial da rodada 2 (`041923Z`) vão em `outras-execucoes/`;
- o manifesto usa caminhos relativos à raiz do pacote (§5g);
- os três documentos de decisão (`2026-09-28-mfa-emergencia-*.md`) vão na raiz do pacote;
- **depois** da execução oficial só mudaram este relatório e o empacotador (rótulo da execução anterior). Código, migration, testes e runners estão como foram validados.

Conteúdo (rodadas 1 e 2; a rodada 3 segue a mesma estrutura com as diferenças acima):
- diff contra `ca4033b`, com os arquivos novos;
- `repo/<caminho>`: a versão **final** de cada arquivo entregue, conferida contra o manifesto;
- `git status`;
- manifesto SHA-256;
- `evidencia/`: execução oficial da rodada 2, `2026-09-28T041923Z`, com `resumo.txt`, `etapas.txt`, a varredura e o controle positivo;
- `execucao-anterior-invalida/`: `003057Z`, marcada, com `INVALIDA.txt` e `classificacao.txt`;
- `outras-execucoes/`: a oficial da rodada 1 (`030420Z`) e as não oficiais, só com resumo, etapas e o fim da suíte de banco, e um `LEIA.txt` que diz o papel de cada uma;
- este relatório e a matriz.
