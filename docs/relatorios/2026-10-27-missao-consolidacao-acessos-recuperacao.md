# Missão de consolidação da API, gestão de acessos e recuperação operacional (2026-10-27)

**Situação:** implementado e testado. **Nada mesclado**, nada implantado e nenhuma liberação pública; o merge fica com a responsável. Nenhum envio real de e-mail. Nenhuma alteração na AWS, no Microsoft 365, no DNS ou em ambiente preservado.

**Revisão de 2026-10-27 (noite), depois da revisão da responsável:**
- CI dos HEADs finais conferido.
- Decisões de acesso registradas. Duas mudaram o código, na migration 024: o convite de emissor que perdeu a autoridade não ressuscita, e a aprovação de concessão externa exige uma segunda pessoa.
- Recusa explícita do reset operacional de conta vinculada, também na 024.
- B11 com prova de privilégios. A comparação achou e corrigiu um defeito real do procedimento.
- Base exata do #25 informada.
- Este relatório, o contrato e os documentos anteriores (inventário de 23/10 e e-mail) foram atualizados.

Legenda: **implementado** (código commitado e enviado); **testado** (local e/ou CI, com o commit indicado); **homologado** (em ambiente de homologação descartável); **mesclado** e **implantado** (nada nesta missão).

## 1. Ordem de merge e dependências

**Ordem:** #22 → #20 → #21 → (#25 e #23, em qualquer ordem) → #24. O #25 parte **exatamente** de `75640b1`, o HEAD do #21; ele não contém nem precisa do #23 e do #24. A migration 024 fica no #24, porque a numeração segue a 023; o #25 não tem migration.

| Ordem | PR | Branch | Base (branch @ commit) | Commits próprios | HEAD | CI do HEAD (core + ops + platform + portal) |
|---|---|---|---|---|---|---|
| 1 | #22 teste de data | `fix/ops-teste-prazos-mudanca-horario` | `main` @ `b036dc0` | `e9f56bf` | `e9f56bf` | verde |
| 2 | #20 consolidação | `ops/api-v1-003-homologacao` | `main` @ `b036dc0` | os da consolidação + `0f23355` (merge do #22) | `0f23355` | 21 + 177 + 374 + 12 = **584**, 0 falhas, 0 ignorados |
| 3 | #21 e-mail real | `feat/email-real-homologacao` | `main` @ `b036dc0` | os do e-mail + `75640b1` (merge do #20) | `75640b1` | 21 + 177 + 408 + 12 = **618**, 0/0 |
| 4 | #25 recuperação da 016 | `fix/016-b10-varredura-b11-restauracao` | `feat/email-real-homologacao` @ `75640b1` | `9afd1a1`, `d51a05c`, `a627f0a` (privilégios do B11), `9eed1fe` (fim de linha) | `9eed1fe` | `d51a05c`: 21 + 177 + 411 + 12 = **621**, 0/0. Os dois commits seguintes só mudam o roteiro da homologação, a evidência e documentos (fora do Maven): ver o PR |
| 5 | #23 acessos, parte 1 | `feat/acessos-023-banco` | `feat/email-real-homologacao` @ `75640b1` | `dbc4366` | `dbc4366` | **618**, 0/0 |
| 6 | #24 acessos, parte 2 | `feat/acessos-convites-papeis` | `feat/acessos-023-banco` @ `dbc4366` | `8471e15` (rotas), `0d3f8c5` (E2E e documentos), commit da 024 e das decisões (ver o PR) | ver o PR | `0d3f8c5`: 21 + 177 + 433 + 12 = **643**, 0/0. O commit da 024 roda o CI no PR |

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
| Concessões: criar, aprovar (segunda pessoa, 024), renovar, revisar, revogar, **sobre cliente e conta externa já existentes** | sim | ITs, suíte |
| Jornada externa completa: criar cliente, entrada da pessoa externa, convite externo | **não** (D-EXT: direção definida, contrato a fechar) | — |
| Auditoria (`access_events`) e avisos na fila | sim | ITs, suíte, E2E |

**Defeito anterior encontrado:** a revogação de concessão PENDENTE respondia 500 (CHECK da 003); corrigido na 023, com o 500 demonstrado antes.

**Decisões aceitas e o que mudou (024, PR #24):**
- **D-CONV-1, 2 e 3:** prazo de 7 dias conferido no aceite; step-up só para ampliar acesso (reduzir continua exigindo sessão, CSRF, autoridade e auditoria); readmissão sem restaurar papéis. Já era o comportamento.
- **D-CONV-4:** antes, o convite de emissor sem autoridade só ficava inaceitável enquanto durasse a falta. Devolver o papel o fazia valer de novo. Agora o banco revoga o convite no mesmo ato da perda (motivo `EMISSOR_SEM_AUTORIDADE`), e ele não volta.
- **D-GRANT-1:** a concessão grava o solicitante, e a aprovação INITIAL exige outro ORG_ADMIN, no serviço (403) e no banco (42501). Uma organização com um único ORG_ADMIN não aprova concessão externa.
- **A confirmar:** a renovação e o KEEP continuam com uma pessoa só (D-GRANT-1a).
- **Reset operacional de conta vinculada:** recusa declarada `CONTA_VINCULADA` (contrato, §10).

## 4. Frente C: recuperação operacional da 016 (#25)

Relatório: `2026-10-27-recuperacao-016-b10-b11.md`.

B10 e B11 **concluídos** em homologação descartável (`ensaio-c016-b10b11`, lista de elevação alternativa fora do repositório). B11 revisto à noite: agora há prova de privilégios, além das contagens:
- **B10:** gatilho manual da varredura (`VarreduraAvisos`) e espera **real** (SIGKILL durante a entrega segurada; nada antes dos 10 min; DESCONHECIDO/`QUEDA_DURANTE_ENTREGA` depois de 641 s reais; nada publicado);
- **B11:** restauração num cluster descartável com:
  - contagens iguais em 82 tabelas;
  - invariantes da 016 OK;
  - **742 itens de catálogo iguais**: papéis, pertinências, donos, ACL, RLS, políticas, funções e extensões.

Achados reais do procedimento, todos corrigidos e explicados no relatório da frente C (§4.2):
- as 5 pertinências com `GRANTED BY` (o concedente passa a ser o superusuário da restauração: limite declarado);
- o dono da `sslinfo`;
- um achado novo da comparação de privilégios: criada dentro da janela, a `pgcrypto` deixava o migrador dono das 36 funções dela.

**Origem da recusa da restauração:** decisão da responsável na P4 (A7, "não autorizada agora; mini missão própria"). Não foi proteção do roteiro nem recusa do mecanismo de aprovação da ferramenta. Esta missão nomeia o B11 e autoriza testes em ambientes descartáveis; li isso como a mini missão prevista, e fica para a confirmação da responsável.

Continuam abertos: C9 (agendamento), B4 completo, B9 (≥ 73 h), 5b, P5 e ativação (HOLD) e as decisões externas. A recusa explícita do reset de conta vinculada foi **fechada** na 024.

## 5. Testes executados (por commit)

| Prova | Onde | Commit | Resultado |
|---|---|---|---|
| CI (regressão inteira) | GitHub Actions | #20 `0f23355` | 21 + 177 + 374 + 12 = **584**, 0 falhas, 0 ignorados |
| CI | GitHub Actions | #21 `75640b1` | 21 + 177 + 408 + 12 = **618**, 0/0 |
| CI | GitHub Actions | #23 `dbc4366` | **618**, 0/0 |
| CI | GitHub Actions | #24 `8471e15` | 21 + 177 + 433 + 12 = **643**, 0/0 |
| CI | GitHub Actions | #25 `d51a05c` | em execução quando este relatório foi escrito: ver o PR (o código da frente C passou nos testes locais e no laboratório) |
| Regressão local `-pl nekkora-platform -am` | Windows, Docker | árvore do #24 antes do commit | core 21, ops 177, platform 433 com **1 falha minha** (`MailTemplatesTest`: o convite leva token), corrigida e repetida (2/2) antes do commit |
| Suíte de banco `db/tests/acessos-023` | local | `dbc4366` | A, B0–B3, C (34 recusas, 9 controles positivos), C6 (7 contraprovas): OK |
| E2E entre processos (`api/e2e/run.sh`) | local, stack descartável | árvore do #24 + `AccessInvitationsE2E` (commit seguinte do #24) | `AccessInvitationsE2E` 5/5, `CrossProcessE2E` 6/6, `IdentityCredentialsE2E` 7/7, `LinkedAccountsE2E` 6/6 |
| Homologação da 016 (laboratório) | local, descartável | `9afd1a1` e `d51a05c` | execução 1: B5–B8a, B10, B12 OK, B11 FALHA (achados); execução 2: B11, B12 OK |
| Validação estática | local | #20 `0f23355`; #25 `d51a05c` | 274 OK; OK com 3 verificações novas |
| Homologação externa (e-mail real) | — | — | **não repetida**: nada que a afete mudou desde `a25332d` |
| ITs da 024 (Testcontainers, papéis reais) | Windows, Docker | árvore do commit da 024 | `ResetContaVinculadaIT` 3/3; `GrantLifecycleIT` 4/4; `AccessInvitationsIT` 12/12; `RoleAdministrationIT` 8/8; `AdminReadRoutesIT` 13/13; `AccessContractTest` 2/2 |
| Contraprova sem a 024 (arquivo retirado de propósito) | local | idem | `ResetContaVinculadaIT`: a abertura volta a `SEM_FATOR_CONFIRMADO` e a execução a `FATOR_DIVERGENTE` (recusa por efeito); 2 falhas esperadas |
| E2E entre processos com 001–024 | local, stack descartável (removida) | idem | 24/24 (5 + 6 + 7 + 6) |
| B11 com privilégios (laboratório) | local, descartável | `a627f0a` | execução 3: FALHA (achado da `pgcrypto`); execução 4: OK, 742 itens iguais; validação estática 297 OK |

Testes ignorados: nenhum nos CIs acima. O E2E fica fora do padrão do surefire (só roda pelo `run.sh`).

## 6. Decisões restantes

| Id | Decisão | Estado |
|---|---|---|
| D-CONV-1 a 4 | prazo, step-up, readmissão, perda de autoridade do emissor | **aceitas**; a D-CONV-4 mudou o código (024) |
| D-GRANT-1 | segunda pessoa na aprovação de concessão externa | **decidida**; código na 024 |
| D-GRANT-1a | renovação e KEEP com uma pessoa só | **a confirmar** |
| D-EXT | entrada da pessoa externa: identidade global reutilizada, conta e permissões externas delimitadas, sem senha implícita; convite externo só depois do contrato | **direção definida**; contrato a fechar antes de habilitar |
| D-ELEV-023/024 | linhas de elevação da 023 e da 024 para aplicar em homologação | pendente, quando houver ambiente alvo |
| C9 | agendamento da varredura de avisos em produção, com monitoração e alerta | pendente |
| A7 (B11) | confirmar que esta missão cobre a restauração em cluster descartável | pendente da responsável |

## 7. O que falta para a V1 (não está completa)

Base: `2026-10-23-api-v1-003-inventario-v1.md`, com o que mudou nesta missão.

| Requisito | Antes | Agora |
|---|---|---|
| Convite e onboarding (D-INV-02) | decisão; nenhuma rota | **PR #23/#24**: convites internos completos. Falta D-EXT (convite externo) e o e-mail real ligado fora do ensaio |
| Atribuição e remoção de papéis (GAP-14) | nenhuma rota | **PR #24** |
| Clientes e concessões (GAP-15) | só leitura e revogação | **PR #24**: criar, aprovar (segunda pessoa), renovar, revisar e revogar, com auditoria, **sobre registros existentes**. A jornada externa completa **não** está entregue: faltam criar cliente, a entrada da pessoa externa e o convite externo (D-EXT) |
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
