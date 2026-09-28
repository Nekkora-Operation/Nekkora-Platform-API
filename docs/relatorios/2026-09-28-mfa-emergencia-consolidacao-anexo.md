# Consolidação das decisões do §5: anexo (revisão 4 proposta, matriz e ajustes)

**Versão 5** (2026-09-28): acompanha a v5 de `2026-09-28-mfa-emergencia-consolidacao-decisoes.md` (os § citados são de lá) e o inventário v2 `2026-09-28-mfa-emergencia-inventario-locks.md`. **Base:** `effa516`; contrato `ca4033b`. O desenho de concorrência **não** está encerrado, e a implementação completa não está autorizada.
**Preservação do histórico:**
- o contrato aprovado **não** foi editado;
- os trechos entram como **revisão 4**, com a revisão 3 (`ca4033b`) mantida como histórico;
- **marcas:**
  - `[R4-DECIDIDO item N]`: regra aprovada; só falta a redação final;
  - `[R4-DESENHO item N]`: depende da conferência do desenho v3;
  - `[R4-ABERTO]`: nenhum ponto aberto nesta versão.

## A.1 Contrato — revisão 4

**Item 1 (§5, C:139; D2, C:323; D6, C:327)**
- Vigente C:139: "canal registrado alterado há menos de 30 dias;"
- `[R4-DECIDIDO item 1]` "canal registrado — incluído, inclusive na contratação, ou alterado — há menos de **720 horas** completas;"
- D2(f), acréscimo `[R4-DECIDIDO item 1]`: "O procedimento só fica disponível quando os dois contatos elegíveis tiverem pelo menos 720 horas completas desde o registro do canal. O onboarding informa essa carência e pede o cadastro dos dois contatos no ato da contratação."
- D6(c), vigente: "No máximo 1 recuperação executada por alvo a cada 7 dias e 3 por organização a cada 30 dias". `[R4-DECIDIDO item 1]` "a cada **168 horas** […] a cada **720 horas**".

**Item 2 (§4, C:118)**
- `[R4-DECIDIDO item 2]` "[…] nem ter vínculo comprovado com qualquer contato autorizado da organização alvo, participante ou não da solicitação."

**Item 3 (§7, depois de C:229)**
- `[R4-DECIDIDO item 3]` "Recusa do ato (registrada; a solicitação não muda) e recusa da solicitação (terminal: `RECUSADA`, ou `EXPIRADA` por prazo), conforme a tabela. `RECUSADA_NA_EXECUCAO` designa `RECUSADA` com o ato `EXECUTAR`. Erros técnicos (guarda de isolamento, invariantes, restrições, conexão) causam rollback integral e nunca são convertidos em recusa de negócio."

| Ato | Recusa só do ato | Recusa terminal |
|---|---|---|
| Todos | Autorização (`SEM_CERTIFICADO`, `CERTIFICADO_DIVERGENTE`, `OPERADOR_DESCONHECIDO`, `OPERADOR_INATIVO`, `CERTIFICADO_NAO_REGISTRADO`, `CERTIFICADO_REVOGADO`, `CERTIFICADO_FORA_DA_VALIDADE`, `SEM_HABILITACAO`); `SOLICITACAO_INEXISTENTE` | — |
| ABRIR | Vínculo, organização, conta, alvo inelegível, contatos, contato do solicitante; **R4:** `ENTRADA_INVALIDA`, `ENTRADA_DE_OUTRO_OPERADOR`, `ENTRADA_USADA`, `ENTRADA_VENCIDA`, `ORIGEM_NAO_COMPROVADA`, `RETORNO_INVALIDO`, `LIMITE_ORIGEM` (nenhuma solicitação criada) | — |
| VERIFICAR | `ESTADO_INVALIDO`, `VINCULO_IMPEDITIVO`, `PARAMETRO_INVALIDO`, `SEGUNDO_CONTATO_INELEGIVEL`, `SOLICITACAO_VERIFICADA_ABERTA`; **R4:** `DECLARACAO_DIVERGENTE`, `ADMIN_PODE_CADASTRAR` | `PRAZO_ENCERRADO`; alvo inelegível; `CONTATOS_INSUFICIENTES`; contato do solicitante inelegível; `EVIDENCIA_NEGATIVA`; `LIMITE_ALVO`/`LIMITE_ORGANIZACAO` |
| PRE_APROVAR | `ESTADO_INVALIDO`, `APROVADOR_IGUAL_VERIFICADOR`, `VINCULO_IMPEDITIVO` | `PRAZO_ENCERRADO`; verificador inválido; alvo inelegível; limites; **R4:** `COMPOSICAO_ALTERADA`, `DECLARACAO_CONTRADITA`, `ADMIN_PODE_CADASTRAR` |
| APROVAR | `ESTADO_INVALIDO`, `APROVADOR_IGUAL_VERIFICADOR`, `VINCULO_IMPEDITIVO`, `AVISO_NAO_CONFIRMADO`, `ESPERA_NAO_CUMPRIDA`; **R4:** `AVISO_DEMAIS_PENDENTE` | `PRAZO_ENCERRADO`; participante inválido; `FATOR_DIVERGENTE`; alvo inelegível; limites; **R4:** as três de PRE_APROVAR |
| EXECUTAR | `JA_EXECUTADA`, `ESTADO_INVALIDO`, `EXECUTOR_IGUAL_APROVADOR`, `VINCULO_IMPEDITIVO`; **R4:** `CONDICAO_DE_AVISO_ALTERADA` | `PRAZO_ENCERRADO`; participante inválido; `FATOR_DIVERGENTE`; alvo inelegível; limites; **R4:** as três de PRE_APROVAR |
| CANCELAR / REENVIAR / CONSULTAR | `PARAMETRO_INVALIDO`, `ESTADO_INVALIDO` / `ESTADO_INVALIDO` / `TRAVA_NAO_OBTIDA` | — / `PRAZO_ENCERRADO` / — |

**Item 4 (D6(a), C:327; §13 dependências; D9)**
- `[R4-DECIDIDO item 4]` "Não existe cota por contato alegado."
- `[R4-DESENHO item 4]` "A abertura por **entrada** exige um registro de entrada de uso único, criado pelo serviço de atendimento (identidade própria), designado ao operador e com **origem comprovada** por desafio de ida e volta ao endereço ou número de origem. DMARC, número apresentado, cabeçalhos ou declaração não comprovam origem. Cada origem comprovada abre até 3 solicitações a cada 24 horas, contadas entre todas as organizações. **Entrada sem origem comprovada não abre solicitação e não consome cota de ninguém:** o atendimento segue pelo **retorno pelo canal registrado**, que registra operador, contato, canal utilizado (versão) e instante, não é contado e não dispensa nenhuma evidência nem etapa. A origem chega ao banco só como **pseudônimo HMAC**; as chaves secretas ficam no serviço de entrada e nunca chegam ao banco. Na rotação, por 25 horas contadas da ativação da versão **nova** (24 horas de janela mais 60 minutos de validade da entrada), toda entrada nova traz **dois pseudônimos HMAC**. A contagem une os dois e conta cada solicitação **uma** vez, e a abertura se coordena com a ativação pelo estado das versões no banco."
- Dependências `[R4-DESENHO item 4]`: "Serviço de registro de entrada, com desafio de ida e volta e chave de pseudonimização em cofre."
- D9 `[R4-DESENHO item 4]`: "A origem pseudonimizada é dado pessoal e entra na análise de retenção."

**Item 5 (§2, cenário C, C:58)**
- `[R4-DECIDIDO item 5]` "Na verificação, o verificador declara somente `NENHUM` (conferido pelo banco) ou `TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS` (declarado). Indisponibilidade por outros motivos não abre o cenário C. Se algum administrador considerado não tiver fator confirmado e ativo, a verificação é recusada (`ADMIN_PODE_CADASTRAR`). Essa recusa indica um caminho potencial de cadastro, sem comprovar disponibilidade da pessoa nem conhecimento da senha."
- `[R4-DESENHO item 5]` "O banco grava a fotografia dos considerados (fatos do banco, distintos da declaração; o banco não comprova posse nem disponibilidade). Nos atos seguintes, sob os locks das contas dos considerados, a solicitação termina em `COMPOSICAO_ALTERADA`, `DECLARACAO_CONTRADITA` (fator confirmado, TOTP ou código usado, sessão com MFA de um considerado depois da fotografia) ou `ADMIN_PODE_CADASTRAR`. Um fluxo de autenticação do considerado que chega durante o ato espera o fim dele."

**Item 6 (§6.1, Auditoria, C:187)**
- `[R4-DECIDIDO item 6]` "Token malformado ou não atribuível (desconhecido, ou de outra organização que não a da rota) não gera evento no banco e não escreve em nenhuma organização. A Platform registra só finalidade, categoria, organização da rota (não validada) e correlação; nunca token, hash, destinatário ou link."

**Item 7 (§7, C:235):** sem mudança de texto; a implementação passa a cumprir o alerta da consulta avulsa.

**Item 8 (§9, C:274)**
- `[R4-DECIDIDO item 8]` "aviso aos demais administradores — todos os `ORG_ADMIN` efetivos além do alvo, incluindo o ator — no reset administrativo de outro `ORG_ADMIN`."

**Item 9 (§6, depois de C:159)**
- `[R4-DECIDIDO item 9]` "Uma confirmação feita dentro da validade do token, mas com `confirmação + 24 h ≥ T_total`, é registrada, não altera `T_total` nem substitui uma confirmação anterior, não permite aprovação final, e a solicitação expira em `T_total`."

**Item 10 (§6, C:163–169; §6.1, C:179; D11, C:332)**
- `[R4-DECIDIDO item 10]`, **mudança explícita de espera:** "A aprovação final também exige, para cada outro `ORG_ADMIN` notificado, que o aviso vigente dele tenha sido **aceito pelo transporte** há pelo menos 24 horas. Aceite não é recebimento nem confirmação. Preservados: a primeira confirmação do titular e suas 24 horas, `T_total` de 72 horas sem prorrogação e a janela de execução." O operador pode reenviar a qualquer destinatário notificado enquanto a solicitação não for terminal, e o resultado de cada tentativa é registrado.
- `[R4-DESENHO item 10]`:
  - "Cada emissão ou reenvio é uma tentativa numerada (`seq`) com estados `EMITIDA`, `ENTREGANDO`, `ENFILEIRADA`, `ACEITA_PELO_TRANSPORTE`, `FALHOU` e `DESCONHECIDO`. O sistema grava `ENTREGANDO` **antes** de entregar a mensagem, então nenhum resultado do transporte chega antes de o banco saber da entrega. Resultados são idempotentes: repetição não altera o primeiro aceite nem repete efeitos. Nenhum caminho presume aceite. Tentativa nunca entregue é `FALHOU`; parada depois de iniciada a entrega é `DESCONHECIDO`.";
  - `[R4-DECIDIDO item 10, v4]` "A vigente é a de maior `seq` **não falhada**: uma falha definitiva do reenvio devolve a vigência à tentativa anterior aceita. Resultado desconhecido não é falha e bloqueia essa volta.";
  - (**muda C:179**) "O aceite da tentativa `n` invalida os tokens das tentativas anteriores (`seq < n`) do mesmo destinatário e finalidade, nunca de posteriores; invalidação é permanente.";
  - "Resultado de entrega, reenvio, confirmação, contestação, aprovação e execução se serializam no lock da solicitação.";
  - "A aprovação registra as versões das tentativas consideradas; qualquer reenvio posterior, a qualquer destinatário, inclusive ao titular, impede a execução, **mesmo que depois falhe**, sem reiniciar a primeira confirmação nem ampliar prazos."

## A.2 Relatório de implementação

As quatro correções factuais (itens 4, 5, 7 e 10 do §5) foram aplicadas na v2.

**Cópia em `../relatorios/`: sincronizada em 2026-09-28T11:06Z.** `cmp` sem diferença; SHA-256 `19938a2a…dfc573` nos dois arquivos. A v3 deste anexo dizia "sincronizada" antes de a cópia ocorrer, o que estava errado; a cópia ficou pendente até este registro. Depois da aprovação da revisão 4 e da implementação, o §5 do relatório passa a apontar para a revisão 4 como regra.

## A.3 Matriz da 016 (depois da conferência dos desenhos)

- **§1, papéis:** `nekkora_ops_intake` (LOGIN; `EXECUTE` só em `ops_intake_register` e no registro de versão de chave).
- **§2, tabelas:**
  - `ops_intake_entries` (mecanismo, pseudônimos HMAC com as versões, operador designado, uso);
  - `ops_intake_key_state` (linha única: versão vigente, instante de ativação, versão anterior; nunca chaves secretas);
  - `ops_origin_buckets`;
  - `ops_mfa_callbacks`;
  - `ops_mfa_request_admins` (fotografia);
  - `ops_mfa_notice_attempts` (`seq`, estado, instantes, categoria);
  - `ops_mfa_approval_notice_versions`;
  - em `ops_mfa_recovery_requests`: modo, entrada ou retorno, declaração, `notice_seq`, `notice_seq_aprovacao`;
  - `ops_mfa_notice_tokens.attempt_id`.
- **§3, funções** (todas com a guarda de isolamento primeiro):
  - `ops_intake_register`;
  - `ops_intake_key_activate` (só o estado das versões `FOR UPDATE`; recusa ativação antes de 25 h desde a anterior);
  - `ops_mfa_callback_register`;
  - `ops_mfa_open(org, alvo, contato, entrada | retorno)`;
  - `ops_mfa_verify(…, demais_admins)`;
  - `ops_mfa_resend_notice(solicitação, destinatário)`;
  - `ops_notice_delivery_result(tentativa, estado, categoria)`;
  - `ops_notice_recover_stalled()` (Platform);
  - `ops_notice_confirm`/`ops_notice_contest` com categoria interna.
- **§3, recusas:** tabela de A.1, item 3.
- **§5, locks** (propostos; o inventário implícito está pendente):
  - abertura: organização → conta alvo → estado das versões (`FOR SHARE`) → baldes (crescente) → entrada ou retorno → operador;
  - ativação de versão: só o estado das versões (`FOR UPDATE`);
  - atos que conferem a declaração: organização → contas (alvo e considerados, crescente) → solicitação → tentativas → tokens → operadores;
  - resultado, reenvio, confirmação, contestação e recuperação: organização → conta alvo → solicitação → tentativas (crescente por `seq`) → tokens (crescente).

## A.4 Ajustes de código e testes (depois da conferência)

**Pré-requisitos** (inventário v2):
- **I-1 a I-4 decididos**, com os ajustes abaixo;
- **decisão sobre I-5**: reset administrativo que pede a conta do ator; avisos da 016 aos demais; resets cruzados; e a verificação da sessão do ator antes dos locks, sem reconferência;
- inventário das tabelas novas quando o esquema for detalhado (inventário v2 §7); nenhuma ordem nova é dada como livre de ciclo antes disso.

**Ajustes decididos (I-1 a I-4):**
- **Platform, I-1:** `confirmEmail` lê o token sem lock → organização → conta → reconferência e consumo atômico do token → `UPDATE users`. Regra organização → conta → token para qualquer emissor ou revogador futuro de tokens de verificação.
- **016, I-2:** `ops_mfa_record_failure` com organização → conta → solicitação (`KEY SHARE`) → operador e reconferência depois dos locks.
- **016, I-3 e I-4:** retirada **pontual** da FK `ops_registry_events.organization_id`, com o teste de consistência do inventário v2 §6. As demais FKs ficam.

**Ajustes propostos (I-5, a decidir):**
- **Platform:** `resetMfa` trava alvo e ator em ordem crescente e reconfere a sessão, o step-up e a permissão do ator **depois** dos locks.
- **016:** pré-aprovação e execução travam o alvo e os destinatários dos avisos juntos, em ordem crescente, antes da solicitação.
- **Itens 5 e 10:** considerados e destinatário no mesmo conjunto crescente.

**Para todos:**
- ensaios determinísticos com contraprova (`40P01` antes, espera simples depois);
- dois resets cruzados;
- matriz §5.1–§5.3 e relatório §4 corrigidos.

| Item | Código | Testes (decisões, §) |
|---|---|---|
| 1 | 016: `'720 hours'` na carência; `'168 hours'`/`'720 hours'` na D6(c) | §3.1: `limite − 1 µs`, `limite`, `limite + 1 s` por `p_agora` explícito; `TimeZone` com horário de verão; fluxo a 719 × 720 h |
| 2 | — | §3.2 |
| 3 | — | §3.3 (`matrizDeRecusas`, erros técnicos, mutação) |
| 4 | 016: papel, tabelas e funções de entrada, versões de chave, baldes e retorno; `ops_mfa_open`; CLI `abrir --entrada`/`--retorno`; simulador do serviço de entrada (com desafio) nos testes | §4.7, com mutação "cota por contato alegado" e ausência de deadlock |
| 5 | 016: parâmetro, fotografia, sinais, `ADMIN_PODE_CADASTRAR`, locks das contas dos considerados; CLI: parâmetro | §5.5: duas ordens de chegada por disputa, com espera conferida em `pg_locks`; mutação "sinais lidos antes dos locks" |
| 6 | 016: categoria interna; Platform: log categorizado | §3.4 |
| 7 | CLI: alerta em `consultar` | §3.5 |
| 8, 9 | — | Existentes |
| 10 | 016: tentativas com `seq`, estados (com `ENTREGANDO`), resultados idempotentes, vigente (mais recente não falhada), invalidação por `seq < n`, serialização, recuperação (`EMITIDA` parada → `FALHOU`; `ENTREGANDO` parada → `DESCONHECIDO`), reivindicação de entrega (`claim_id`) e reconciliação sem entrega presumida nem repetida (§6.8), versões na aprovação e conferência na execução; Platform: grava `ENTREGANDO` antes de entregar, `MailSender` devolve o desfecho com `attempt_id`, `LocalMailSender` → `ENFILEIRADA`, varredura de recuperação; transporte simulado | §6.7, incluindo resultado antes de `ENFILEIRADA`, queda entre as etapas e repetição do mesmo resultado; uso real dos tokens nos casos fora de ordem; mutações "invalida `seq > n`" e "ignora `notice_seq_aprovacao`" |

**Também:**
- `IdentidadeIT` (lista exata das entradas);
- o bloco de invariantes da 016 (EXECUTE, guarda, políticas);
- a contagem de mutações de `validar.sh`;
- a matriz.
