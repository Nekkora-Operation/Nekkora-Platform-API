# Missão de consolidação da API, gestão de acessos e recuperação operacional (2026-10-27)

**Situação:** implementado e testado. **Nada mesclado**, nada implantado e nenhuma liberação pública; o merge fica com a responsável. Nenhum envio real de e-mail. Nenhuma alteração na AWS, no Microsoft 365, no DNS ou em ambiente preservado.

**Revisão de 2026-10-27 (noite), depois da revisão da responsável:**
- CI dos HEADs finais conferido.
- Decisões de acesso registradas. Duas mudaram o código, na migration 024: o convite de emissor que perdeu a autoridade não ressuscita, e a aprovação de concessão externa exige uma segunda pessoa.
- Recusa explícita do reset operacional de conta vinculada, também na 024.
- B11 com prova de privilégios. A comparação achou e corrigiu um defeito real do procedimento.
- Base exata do #25 informada.
- Este relatório, o contrato e os documentos anteriores (inventário de 23/10 e e-mail) foram atualizados.

**Segunda revisão da noite (2026-10-27), depois da resposta da responsável:**
- CI dos HEADs `c4caeeb` (#24) e `9eed1fe` (#25) conferido, sem reaproveitar resultado de commit anterior (§5).
- Regra de renovação e KEEP aceita (D-GRANT-1a) e implementada na 024, com a aprovação vinculada aos valores pedidos (`8527954`, contrato §7).
- Suíte de banco da 024 (`db/tests/acessos-024`): instalação, atualização populada, privilégios, invariantes, contraprovas e reaplicação recusada.
- Corrida vinculação × execução do reset, nos dois ordenamentos, com a vinculação real da 017 (contrato §10).
- E2E completo com 001–024, com a 024 final.
- Proposta das linhas de elevação da 023 e da 024: hashes finais, ambiente e pré-voos (§8). Nenhuma linha foi acrescentada a `elevacao.txt`.
- A7 confirmada pela responsável: a restauração do B11 em cluster descartável está coberta por esta missão (não vale para produção nem para ambiente preservado).

**Homologação dirigida (2026-10-28), depois do parecer da responsável:**
- O runner foi estendido até a 024 (`d196aea`): o `atualizar` faz 021 → 022, 022 → 023 e 023 → 024, um passo por vez, e 023 e 024 passam a ter vínculo obrigatório. `elevacao.txt` continua sem as linhas da 023 e da 024.
- A homologação rodou em descartáveis novos, com a lista de elevação vinculada só a cada um e o e-mail simulado. O `ensaio-api-v1-003` e as evidências do e-mail real não foram tocados.
- **Achado real na primeira execução (`ensaio-api-v1-004`):** a R015 mostrou que duas restrições de `access_events` voltam de um dump com outra forma. A 024 seria recusada numa base em 023 restaurada de backup. Corrigido em `539a2e0`. Isso **trocou o hash da 024**: a linha da 024 da revisão anterior não vale mais.
- A segunda execução (`ensaio-api-v1-004b`) usou a 024 final, com tudo como esperado, inclusive a R015 (§9).
- O fechamento dos convites antigos foi provado com massa declarada, e o KEEP "sem data" ficou explícito (§9).

Legenda: **implementado** (código commitado e enviado); **testado** (local e/ou CI, com o commit indicado); **homologado** (em ambiente de homologação descartável); **mesclado** e **implantado** (nada nesta missão).

## 1. Ordem de merge e dependências

**Ordem:** #22 → #20 → #21 → (#25 e #23, em qualquer ordem) → #24. O #25 parte **exatamente** de `75640b1`, o HEAD do #21; ele não contém nem precisa do #23 e do #24. A migration 024 fica no #24, porque a numeração segue a 023; o #25 não tem migration.

| Ordem | PR | Branch | Base (branch @ commit) | Commits próprios | HEAD | CI do HEAD (core + ops + platform + portal) |
|---|---|---|---|---|---|---|
| 1 | #22 teste de data | `fix/ops-teste-prazos-mudanca-horario` | `main` @ `b036dc0` | `e9f56bf` | `e9f56bf` | verde |
| 2 | #20 consolidação | `ops/api-v1-003-homologacao` | `main` @ `b036dc0` | os da consolidação + `0f23355` (merge do #22) | `0f23355` | 21 + 177 + 374 + 12 = **584**, 0 falhas, 0 ignorados |
| 3 | #21 e-mail real | `feat/email-real-homologacao` | `main` @ `b036dc0` | os do e-mail + `75640b1` (merge do #20) | `75640b1` | 21 + 177 + 408 + 12 = **618**, 0/0 |
| 4 | #25 recuperação da 016 | `fix/016-b10-varredura-b11-restauracao` | `feat/email-real-homologacao` @ `75640b1` | `9afd1a1`, `d51a05c`, `a627f0a` (privilégios do B11), `9eed1fe` (fim de linha), commit da revisão do texto do B11 (ver o PR) | ver o PR | `9eed1fe`: 21 + 177 + 411 + 12 = **621**, 0 falhas, 0 ignorados |
| 5 | #23 acessos, parte 1 | `feat/acessos-023-banco` | `feat/email-real-homologacao` @ `75640b1` | `dbc4366` | `dbc4366` | **618**, 0/0 |
| 6 | #24 acessos, parte 2 | `feat/acessos-convites-papeis` | `feat/acessos-023-banco` @ `dbc4366` | `8471e15` (rotas), `0d3f8c5` (E2E e documentos), `c4caeeb` (024: decisões e reset vinculado), `8527954` (regra de renovação e KEEP, corridas, suíte e pré-voos), commit destes documentos | ver o PR | `c4caeeb`: 21 + 180 + 434 + 12 = **647**, 0 falhas, 0 ignorados. Os commits seguintes rodam o CI no PR |

**Atenção à dependência:** a conclusão do B10/B11 está no #25, mas a proteção explícita do reset de conta vinculada (`CONTA_VINCULADA`) está na 024, dentro do #24. Mesclar só o #25 **não** incorpora essa proteção.

Com o #22 mesclado antes, o merge dele no #20 e no #21 vira no-op. Depois de cada merge, a base do PR seguinte passa a ser a `main`. O GitHub retarga quando a branch base é apagada; se não for apagada, é preciso retargetar à mão.

## 2. Frente A: consolidação (#22 → #20 → #21)

- As falhas do CI do #20 (`8dd22fc`) e do #21 (`a25332d`) eram **só** as duas do `Revisao4PrazosEImpedimentosIT` (conferido nos logs dos jobs), e por causa delas o reator **pulava** `nekkora-platform` e `nekkora-portal`.
- O #22 foi trazido ao #20 por merge (`0f23355`), e o #20 ao #21 (`75640b1`), sem force-push. Junto veio o #18, que já está na `main`.
- Revisão dos diffs: nenhum segredo, identificador privado ou `.eml` (o repositório é público); o tenant, que aparece, é público e já está na `main`.
- #20 conferido: 022, `atualizar`, elevação vinculada ao ambiente, pré-voos, retirada da elevação em sucesso e falha, semeaduras, jornada da ativação. Validação estática local OK.
- #21 conferido: certificado confirmado antes de apagar a cópia; chave própria da fila; lista fechada e teto persistente; desligamento conferido pelo Docker; homologação real **não** repetida (nada que a afete mudou desde `a25332d`).

## 3. Frente B: convites, papéis e concessões (#23, #24)

Contrato: `2026-10-27-acessos-convites-papeis-concessoes-contrato.md` (matriz antes/depois, regras, garantias do banco separadas das do serviço, decisões assumidas como proposta).

| Jornada | Implementado | Testado |
|---|---|---|
| Convites: emitir, listar, revogar, reenviar | sim (API e 023) | ITs, suíte da 023, E2E |
| Aceite pela identidade (conta nova, readmissão; recusas sem efeito; sem vinculação por e-mail) | sim (função da 023) | ITs (com concorrência), E2E |
| Papéis: consultar, atribuir, alterar, revogar | sim | ITs (com concorrência), suíte |
| Retirada de vínculo (sessões revogadas, identidade intacta) | sim | ITs, E2E |
| Concessões: criar, aprovar (segunda pessoa, 024), pedir e aprovar renovação (segunda pessoa e valores vinculados, 024), revisar, revogar, **sobre cliente e conta externa já existentes** | sim | ITs, suítes da 023 e da 024 |
| Jornada externa completa: criar cliente, entrada da pessoa externa, convite externo | **não** (D-EXT: direção definida, contrato a fechar) | — |
| Auditoria (`access_events`) e avisos na fila | sim | ITs, suíte, E2E |

**Defeito anterior encontrado:** a revogação de concessão PENDENTE respondia 500 (CHECK da 003); corrigido na 023, com o 500 demonstrado antes.

**Decisões aceitas e o que mudou (024, PR #24):**
- **D-CONV-1, 2 e 3:** prazo de 7 dias conferido no aceite; step-up só para ampliar acesso (reduzir continua exigindo sessão, CSRF, autoridade e auditoria); readmissão sem restaurar papéis. Já era o comportamento.
- **D-CONV-4:** antes, o convite de emissor sem autoridade só ficava inaceitável enquanto durasse a falta. Devolver o papel o fazia valer de novo. Agora o banco revoga o convite no mesmo ato da perda (motivo `EMISSOR_SEM_AUTORIDADE`), e ele não volta.
- **D-GRANT-1:** a concessão grava o solicitante, e a aprovação INITIAL exige outro ORG_ADMIN, no serviço (403) e no banco (42501). Uma organização com um único ORG_ADMIN não aprova concessão externa.
- **D-GRANT-1a (aceita na segunda revisão):** aprovação inicial, renovação que estende, reativação e ampliação com duas pessoas; KEEP com uma pessoa só sem estender nada; redução e revogação com uma pessoa autorizada e auditoria. A aprovação fica vinculada aos valores pedidos: qualquer mudança de validade descarta o pedido pendente. Nenhum ato amplia escopo ou permissões (origem imutável desde a 003). Tabela completa no contrato, §7.
- **Convites já inaceitáveis antes da 024:** a 024 fecha na aplicação, com o motivo 4, os convites abertos cujo emissor já estava sem autoridade. Sem isso, eles voltariam a valer se o emissor recuperasse o papel. É a única mudança de dados da 024 (suíte, B2).
- **Reset operacional de conta vinculada:** recusa declarada `CONTA_VINCULADA`, com as corridas da vinculação nos dois ordenamentos (contrato, §10).

## 4. Frente C: recuperação operacional da 016 (#25)

Relatório: `2026-10-27-recuperacao-016-b10-b11.md`.

B10 e B11 **concluídos** em homologação descartável (`ensaio-c016-b10b11`, lista de elevação alternativa fora do repositório). B11 revisto à noite: agora há prova de privilégios, além das contagens:
- **B10:** gatilho manual da varredura (`VarreduraAvisos`) e espera **real** (SIGKILL durante a entrega segurada; nada antes dos 10 min; DESCONHECIDO/`QUEDA_DURANTE_ENTREGA` depois de 641 s reais; nada publicado);
- **B11:** restauração num cluster descartável com:
  - contagens iguais em 82 tabelas;
  - invariantes da 016 OK;
  - **742 itens comparados iguais, com a exceção declarada do concedente das pertinências** (papéis, pertinências, donos, ACL, RLS, políticas, funções e extensões). Isso **não** quer dizer que todo o catálogo de segurança ficou idêntico: o concedente (`GRANTED BY`) das 5 pertinências é normalizado na comparação e, na restaurada, é o superusuário da restauração.

Achados reais do procedimento, todos corrigidos e explicados no relatório da frente C (§4.2):
- as 5 pertinências com `GRANTED BY` (o concedente passa a ser o superusuário da restauração: limite declarado);
- o dono da `sslinfo`;
- um achado novo da comparação de privilégios: criada dentro da janela, a `pgcrypto` deixava o migrador dono das 36 funções dela.

**Origem da recusa da restauração:** decisão da responsável na P4 (A7, "não autorizada agora; mini missão própria"). Não foi proteção do roteiro nem recusa do mecanismo de aprovação da ferramenta. **Confirmado pela responsável na segunda revisão:** esta missão inclui expressamente o B11 e testes em ambientes descartáveis, então a restauração descrita está coberta e o B11 não volta a "parcial". Isso não autoriza restauração em produção nem em ambiente preservado.

Continuam abertos: C9 (agendamento), B4 completo, B9 (≥ 73 h), 5b, P5 e ativação (HOLD) e as decisões externas. A recusa explícita do reset de conta vinculada foi **fechada** na 024.

## 5. Testes executados (por commit)

| Prova | Onde | Commit | Resultado |
|---|---|---|---|
| CI (regressão inteira) | GitHub Actions | #20 `0f23355` | 21 + 177 + 374 + 12 = **584**, 0 falhas, 0 ignorados |
| CI | GitHub Actions | #21 `75640b1` | 21 + 177 + 408 + 12 = **618**, 0/0 |
| CI | GitHub Actions | #23 `dbc4366` | **618**, 0/0 |
| CI | GitHub Actions | #24 `8471e15` | 21 + 177 + 433 + 12 = **643**, 0/0 |
| CI | GitHub Actions | #25 `d51a05c` | 21 + 177 + 411 + 12 = **621**, 0/0 |
| CI | GitHub Actions | #24 `0d3f8c5` | 21 + 177 + 433 + 12 = **643**, 0/0 |
| CI | GitHub Actions | #24 `c4caeeb` (024 com as decisões e o reset vinculado) | 21 + **180** + **434** + 12 = **647**, 0 falhas, 0 ignorados (ops +3: `ResetContaVinculadaIT`; platform +1: `bancoExigeSegundaPessoaEOSolicitanteGravado`) |
| CI | GitHub Actions | #25 `9eed1fe` | 21 + 177 + 411 + 12 = **621**, 0 falhas, 0 ignorados |
| CI | GitHub Actions | #24 `ec76c37` (`8527954` + documentos) | 21 + **182** + **436** + 12 = **651**, 0 falhas, 0 ignorados |
| CI | GitHub Actions | #25 `ff36ff1` | 21 + 177 + 411 + 12 = **621**, 0 falhas, 0 ignorados |
| CI | GitHub Actions | #24 depois da homologação dirigida (`d196aea`, `539a2e0` e documentos) | roda no PR: ver o PR |
| Regressão local `-pl nekkora-platform -am` | Windows, Docker | árvore do #24 antes do commit | core 21, ops 177, platform 433 com **1 falha minha** (`MailTemplatesTest`: o convite leva token), corrigida e repetida (2/2) antes do commit |
| Suíte de banco `db/tests/acessos-023` | local | `dbc4366` | A, B0–B3, C (34 recusas, 9 controles positivos), C6 (7 contraprovas): OK |
| E2E entre processos (`api/e2e/run.sh`) | local, stack descartável | árvore do #24 + `AccessInvitationsE2E` (commit seguinte do #24) | `AccessInvitationsE2E` 5/5, `CrossProcessE2E` 6/6, `IdentityCredentialsE2E` 7/7, `LinkedAccountsE2E` 6/6 |
| Homologação da 016 (laboratório) | local, descartável | `9afd1a1` e `d51a05c` | execução 1: B5–B8a, B10, B12 OK, B11 FALHA (achados); execução 2: B11, B12 OK |
| Validação estática | local | #20 `0f23355`; #25 `d51a05c` | 274 OK; OK com 3 verificações novas |
| Homologação externa (e-mail real) | — | — | **não repetida**: nada que a afete mudou desde `a25332d` |
| ITs da 024 (Testcontainers, papéis reais) | Windows, Docker | árvore do commit da 024 | `ResetContaVinculadaIT` 3/3; `GrantLifecycleIT` 4/4; `AccessInvitationsIT` 12/12; `RoleAdministrationIT` 8/8; `AdminReadRoutesIT` 13/13; `AccessContractTest` 2/2 |
| Contraprova sem a 024 (arquivo retirado de propósito) | local | idem | `ResetContaVinculadaIT`: a abertura volta a `SEM_FATOR_CONFIRMADO` e a execução a `FATOR_DIVERGENTE` (recusa por efeito); 2 falhas esperadas |
| E2E entre processos com 001–024 | local, stack descartável (removida) | idem | 24/24 (5 + 6 + 7 + 6) |
| **024 final (`23dca1c3…`, `539a2e0`): suíte `db/tests/acessos-024`** (rodada `2026-10-28T025200Z_nekkora-a024`) | local, dois clusters descartáveis (removidos) | `539a2e0` | A (forma instalada das restrições), B0, B1, **B1b** (falha depois do fechamento: os 2 convites continuam abertos), B2 sobre a **forma restaurada** (catálogo final igual ao da limpa; 41 linhas previstas), B3, C (30 recusas e 16 controles positivos), C5 (8 casos): OK |
| 024 final: ITs-alvo | Windows, Docker | `539a2e0` | `ResetContaVinculadaIT` 5/5; `GrantLifecycleIT` **7/7** (com `keepSemDataPreservaRevisaoEPrazo`); `AccessContractTest` 2/2; `AccessInvitationsIT` 12/12; `RoleAdministrationIT` 8/8; `AdminReadRoutesIT` 13/13; `ClientGrantRevokeRouteIT` 4/4; `PortalIT` 10/10 |
| 024 final: pré-voos (`prevoo_023_024.sh`) | local, descartável | `539a2e0` | os 6 casos como esperado |
| 024 final: E2E entre processos (rodada `2026-10-28T030758Z_nekkora-e2e`) | local, stack descartável (removida) | `539a2e0` (024 `23dca1c3…`) | 5 + 6 + 7 + 6 = **24/24**; 24 migrations pelo modelo do migrador, fim 0\|0\|0 |
| Homologação dirigida (§9) | `ensaio-api-v1-004` (024 anterior) e `ensaio-api-v1-004b` (024 final) | imagens `8527954`, runner `d196aea`/`539a2e0` | P, F e S da 023 e da 024 e dados da 024: OK nos dois; R015: achado no primeiro, OK no segundo |
| B11 com privilégios (laboratório) | local, descartável | `a627f0a` | execução 3: FALHA (achado da `pgcrypto`); execução 4: OK, 742 itens comparados iguais, com a exceção declarada do concedente das pertinências; validação estática 297 OK |
| Suíte de banco `db/tests/acessos-024` (rodada `2026-10-28T004618Z_nekkora-a024`) | local, dois clusters descartáveis (removidos) | árvore de `8527954` (024 `b6390fe8…`) | **A:** 001–024, 11 janelas com retirada 0\|0\|0, §19 da 016 depois de tudo, invariantes 012–024 depois da retirada. **B0:** a 024 sobre 022 é recusada pela pré-condição, sem efeito. **B1:** sem janela, recusada por privilégio, sem efeito. **B2:** dados idênticos, exceto os 2 convites previstos (motivo 4); catálogo 023 → 024 com exatamente 41 linhas previstas e igual ao da instalação limpa; invariantes. **B3:** reaplicação recusada, sem efeito. **C:** 30 recusas e 16 controles positivos. **C5:** controle e 7 contraprovas. Tudo OK |
| ITs da regra de renovação e do reset (024 final) | Windows, Docker | árvore de `8527954` | `ResetContaVinculadaIT` **5/5** (3 + as 2 corridas); `GrantLifecycleIT` **6/6** (ciclo com pedido e aprovação de renovação; descarte e substituição do pedido; garantias do banco); `AccessContractTest` 2/2; `AccessInvitationsIT` 12/12; `RoleAdministrationIT` 8/8; `AdminReadRoutesIT` 13/13; `ClientGrantRevokeRouteIT` 4/4; `PortalIT` 10/10 |
| Contraprova das corridas sem a 024 | local | idem | vinculação primeiro: `FATOR_DIVERGENTE` (recusa por efeito); execução primeiro: a abertura nova cai em `SEM_FATOR_CONFIRMADO`; 2 falhas esperadas |
| E2E entre processos com 001–024 (rodada `2026-10-28T004820Z_nekkora-e2e`) | local, stack descartável (removida) | árvore de `8527954` (023 `dcb47661…`, 024 `b6390fe8…`) | `AccessInvitationsE2E` 5/5, `CrossProcessE2E` 6/6, `IdentityCredentialsE2E` 7/7, `LinkedAccountsE2E` 6/6: **24/24**. Migrations pelo modelo do migrador: 24 aplicadas, 023 e 024 com janela, §19 da 016 OK, fim 0\|0\|0 |
| Pré-voos propostos 023 e 024 (`ops/homologacao/testes/prevoo_023_024.sh`) | local, cluster descartável (removido), histórico do Flyway sintético | idem | P1 023 sobre 022: OK; P2 024 sobre 022: FALHOU (sem erro de SQL); P3 023 sobre 023: FALHOU; P4 024 sobre 023: OK (0 convites a fechar); P5 com um convite de emissor sem autoridade: OK (1 a fechar; a 024 avisa 1); P6 024 sobre 024: FALHOU |

Testes ignorados: nenhum nos CIs acima. O E2E fica fora do padrão do surefire (só roda pelo `run.sh`).

## 6. Decisões restantes

| Id | Decisão | Estado |
|---|---|---|
| D-CONV-1 a 4 | prazo, step-up, readmissão, perda de autoridade do emissor | **aceitas**; a D-CONV-4 mudou o código (024) |
| D-GRANT-1 | segunda pessoa na aprovação de concessão externa | **decidida**; código na 024 |
| D-GRANT-1a | renovação, reativação e ampliação com duas pessoas; KEEP só sem estender; aprovação vinculada aos valores | **aceita**; código na 024 (`8527954`) |
| D-EXT | entrada da pessoa externa: identidade global reutilizada, conta e permissões externas delimitadas, sem senha implícita; convite externo só depois do contrato | **direção definida**; contrato a fechar antes de habilitar |
| D-ELEV-023/024 | linhas de elevação da 023 e da 024 para aplicar em homologação | **proposta (§8), sem aprovação**: a responsável escolhe o ambiente e autoriza ou não cada linha |
| C9 | agendamento da varredura de avisos em produção, com monitoração e alerta | pendente |
| A7 (B11) | a missão cobre a restauração em cluster descartável | **confirmada** pela responsável (não vale para produção nem ambiente preservado) |

## 7. O que falta para a V1 (não está completa)

Base: `2026-10-23-api-v1-003-inventario-v1.md`, com o que mudou nesta missão.

| Requisito | Antes | Agora |
|---|---|---|
| Convite e onboarding (D-INV-02) | decisão; nenhuma rota | **PR #23/#24**: convites internos completos. Falta D-EXT (convite externo) e o e-mail real ligado fora do ensaio |
| Atribuição e remoção de papéis (GAP-14) | nenhuma rota | **PR #24** |
| Clientes e concessões (GAP-15) | só leitura e revogação | **PR #24**: criar, aprovar (segunda pessoa), pedir e aprovar renovação (segunda pessoa), revisar e revogar, com auditoria e aprovação vinculada aos valores, **sobre registros existentes**. A jornada externa completa **não** está entregue: faltam criar cliente, a entrada da pessoa externa e o convite externo (D-EXT) |
| Reset operacional de conta vinculada | recusa só por efeito | **PR #24 (024)**: recusa declarada `CONTA_VINCULADA` |
| Retirada de vínculo | sem rota | **PR #24** (administrativa). Suspensão de organização continua sem rota (operação) |
| Emissão do token de verificação de e-mail da conta (GAP-04) | decisão | inalterado (com a identidade global, provavelmente desnecessária; decidir) |
| Portões da 016 | B10/B11 parciais; nenhum portão operacional | **B10 e B11 concluídos em homologação descartável (#25)**; portões operacionais (5b, P5, ativação) continuam em HOLD; C9, B4, B9 abertos |
| E-mail real (GAP-10) | homologado (#21) | inalterado; ligar fora do ensaio é decisão de liberação |
| Origem do cliente atrás do proxy (B10 do inventário) | aberto (D-BORDA) | inalterado |
| L06 | decisão D-L06 | inalterado |
| Sessões individuais (GAP-13) | decisão (recomendado fora) | inalterado |
| **GAP-09 canal do Agent** | desenho pendente | **inalterado: fora desta missão** (será planejado a partir do relatório do Agent) |
| GAP-07 execução manual e cancelamento; GAP-08 workspaces, agendamentos e Workers | dependem do GAP-09 | inalterados |
| Capacidade sob carga; saúde e prontidão (GAP-11) | abertos | inalterados |

**A V1 não está completa:** sem o canal Platform–Agent (GAP-09) e as rotas de execução e agendamento (GAP-07/08), ela não pode ser declarada completa.

## 8. Linhas de elevação da 023 e da 024 (D-ELEV-023/024): linhas completas para a aprovação

**Não há aprovação genérica.** Nada foi acrescentado a `ops/homologacao/implantacao/elevacao.txt`. A homologação usou listas de laboratório, fora do repositório, cada uma vinculada só ao próprio ambiente descartável.

**Linhas completas** (sha256 dos bytes LF versionados, conferidos por inteiro contra os arquivos finais, a cópia do ambiente e a lista do laboratório em `evidencias/ensaio-api-v1-004b/janela-02x/hashes.txt`):

```
023 dcb47661867fa451a20460e6b941a003999569d3c0185dd4962f75c1e8843498 tipo=superuser ambiente=<id aprovado>
024 23dca1c367918279165bf9d70278e721fe20c69715e3f776d9ea8a2886e1262e tipo=superuser ambiente=<id aprovado>
```

- A 023 é a de `dbc4366`, inalterada.
- A 024 é a de `539a2e0`. A linha anterior da 024 (`b6390fe8…`) **não vale mais**: a correção da R015 trocou o arquivo.
- Qualquer mudança nos arquivos troca o hash e invalida estas linhas.

**Por que a janela é necessária** (suítes, caso B1: sem a janela, cada uma é recusada por privilégio e nada muda):
- **023:** transfere as funções do aceite para `nekkora_identity_owner`.
- **024:** transfere `identity_user_is_linked` e `identity_invitations_close_unauthorized` para `nekkora_identity_owner` e substitui `ops.alvo_inelegivel` e `ops_mfa_execute`, que são do dono da 016.

**Pré-voos** (somente leitura, executados como `admin_cluster` antes de conceder):

| Arquivo | sha256 | Conferências |
|---|---|---|
| `implantacao/preflight/023.sql` | `52ebb88a7f0d2dc9cf245e8fc92266b87a59344a67a469e133f3a07fb8103028` | 9: PostgreSQL 16; histórico exatamente 001–022; migrador sem atributo nem ACL de parâmetro; migrador dono do banco; 022 presente; objetos dos convites ausentes; nenhum convite aberto duplicado; `nekkora_identity_owner` sem login nem participação; as restrições que a 023 confere |
| `implantacao/preflight/024.sql` | `34f23ec3218fbf925e7e7e989141bb4115e6c2e414ae26f1f33e1e003530889a` | 10: os comuns (histórico 001–023); 023 presente; objetos da 024 ausentes; as quatro restrições que a 024 troca, **na forma instalada ou na restaurada**; donos sem login nem participação; funções da 016/018 com o dono esperado; quantos convites a 024 fechará (informativo) |

**Ambiente:** o parecer da responsável foi um descartável novo. Por causa da correção da R015, a homologação final ficou no `ensaio-api-v1-004b` (§9). O id da aprovação concreta é decisão dela: um descartável ou outro ambiente, sempre com uma linha por versão vinculada a esse id. **Produção:** decisão separada.

## 9. Homologação dirigida (ensaio-api-v1-004 e ensaio-api-v1-004b)

**Caminho (runner real):**
1. `criar` (001–015 e parada);
2. `implantar` 016–021 (janelas com pré-voo) e `concluir-criacao`;
3. `atualizar` 022, `implantar` e `subir`;
4. `atualizar` 023 e `testes/janela_023_024.sh 023`, depois `subir`;
5. `atualizar` 024 e `testes/janela_023_024.sh 024`, depois `subir`;
6. `testar`.

Imagens do commit `8527954`. Os dois ambientes são descartáveis novos, com a lista de laboratório vinculada só ao próprio id e o e-mail simulado.

| Prova | `ensaio-api-v1-004` (024 anterior, `b6390fe8…`) | `ensaio-api-v1-004b` (**024 final**, `23dca1c3…`) |
|---|---|---|
| 023, caso P: recusa pelo pré-voo antes de conceder (papel membro de `nekkora_identity_owner`) | OK: nenhuma janela aberta, 0\|0\|0 | OK |
| 023, caso F: falha dentro da janela (migrador somente leitura) | OK: "início" e "fim … revogado … [falha]", 0\|0\|0, 023 fora do histórico, nenhum `success=false` | OK |
| 023, caso S: sucesso | OK: "[fim da versão 023]", 0\|0\|0, LIBERADO | OK |
| 024, caso P | OK | OK |
| 024, caso F: falha **depois** do fechamento dos convites (gatilho homônimo criado por fora) | OK: o runner registra "fechados na aplicação: 2" e depois o erro; dados conferidos iguais antes e depois da falha; 0\|0\|0 | OK |
| 024, caso S | OK | OK |
| 024, caso D: dados (abaixo) | OK | OK |
| R015: backup e restauração | **FALHA, achado real**: duas restrições voltam com outra forma | **OK**: catálogo idêntico (4926 linhas) e dados idênticos |
| H01, H05–H09 | OK | OK |
| H02, H03, H04 | falham por limites conhecidos do `testar` (desenho 001–015): o H03 só tem perfis 015/016; o H02 espera as janelas da lista da criação e conta as das falhas provocadas; o H04 marca os padrões vazios das chaves da fila de e-mail da 021, já na `main` | idem |

Evidências: `ops/homologacao/evidencias/ensaio-api-v1-004/` e `ensaio-api-v1-004b/`, com um LEIA-ME em cada uma.
- `ensaio-api-v1-004`: recursos Docker removidos.
- `ensaio-api-v1-004b`: continua no ar até a revisão.

**Fechamento dos convites antigos (única mudança de dados da 024):** a prova usa a massa declarada `ops/homologacao/sql/massa_024_convites.sql` e a conferência somente leitura `conferencia_024_convites.sql`, que é a mesma antes e depois.

| Exigência da responsável | Evidência |
|---|---|
| Só convites ainda abertos de emissores sem autoridade são encerrados | `a_fechar` 2 → 0; `fechados_motivo_4_sem_revogador` 0 → 2. O critério é o da 023 (`access_can_invite`), só para convites abertos e com emissor vinculado |
| Convites válidos de emissores autorizados ficam intactos | aberto do ORG_ADMIN, aceito e revogado com o mesmo md5 de linha antes e depois |
| Motivo 4 documentado | contrato §4.5/§9, comentário da coluna (`4=EMISSOR_SEM_AUTORIDADE (024)`), OpenAPI (`revokeReason`), relatório |
| A alteração está na mesma transação da migration | a 024 é uma transação só (`BEGIN … COMMIT`). Provado duas vezes com uma falha **depois** do fechamento: no ambiente, caso F da 024 (aviso de 2 fechados, erro, dados iguais) e na suíte, caso B1b |
| Nenhum acesso já concedido é removido | vínculos, papéis de organização e de workspace, vínculos de cliente, concessões e contas com o mesmo md5 antes e depois; na suíte (B2), os dados de `public` e `ops` são idênticos, exceto as duas colunas desses convites |

**Limite declarado:** a limpeza usa a autoridade do emissor **no momento da migration**. Se alguém perdeu e recuperou a autoridade antes da 024, o convite dele volta a ser aceitável pela regra da 023, e a 024 **não** o fecha. Para detectar esse caso seria preciso um histórico que a 024 não consulta. A massa inclui esse caso de propósito, e o convite continua aberto (`perdeu_e_recuperou_antes=aberto`). Depois da 024, a perda de autoridade fecha os convites no ato, e o caso não se repete.

**KEEP "sem data":** preserva a próxima revisão e o prazo existentes; nunca remove o vencimento nem torna a concessão ilimitada. O KEEP não altera `expires_at` em caso nenhum. Teste: `GrantLifecycleIT.keepSemDataPreservaRevisaoEPrazo`, numa concessão com prazo. Regra explícita no contrato (§7).
