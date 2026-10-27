# Missão de consolidação da API, gestão de acessos e recuperação operacional (2026-10-27)

**Situação:** implementado e testado. **Nada mesclado**, nada implantado e nenhuma liberação pública; o merge fica com a responsável. Nenhum envio real de e-mail. Nenhuma alteração na AWS, no Microsoft 365, no DNS ou em ambiente preservado.

Legenda: **implementado** (código commitado e enviado); **testado** (local e/ou CI, com o commit indicado); **homologado** (em ambiente de homologação descartável); **mesclado** e **implantado** (nada nesta missão).

## 1. Ordem de merge e dependências

| Ordem | PR | Base | Candidato | CI do candidato |
|---|---|---|---|---|
| 1 | #22 teste de data | `main` | `e9f56bf` | verde |
| 2 | #20 consolidação | `main` | `0f23355` (contém o #22) | 584 testes, 0 falhas, 0 ignorados |
| 3 | #21 e-mail real | `main` | `75640b1` (contém #20 e #22) | 618 testes, 0 falhas, 0 ignorados |
| 4 | #23 acessos, parte 1 (023) | branch do #21 | `dbc4366` | 618 testes, 0 falhas, 0 ignorados |
| 5 | #24 acessos, parte 2 (rotas) | branch do #23 | código `8471e15`; HEAD = commit do E2E e deste relatório | 643 testes, 0 falhas, 0 ignorados em `8471e15`; o commit seguinte só acrescenta o E2E (fora do surefire) e documentos |
| 4' | #25 recuperação da 016 (B10/B11) | branch do #21 | `d51a05c` | em execução quando este relatório foi escrito: ver o PR |

O #25 é independente do #23 e do #24 (só depende do #21). Com o #22 mesclado antes, o merge dele no #20 e no #21 vira no-op. Depois de cada merge, a base do PR seguinte passa a ser a `main` (o GitHub retarga quando a branch base é apagada; se não for apagada, retargetar à mão).

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
| Concessões: criar, aprovar, renovar, revisar, revogar | sim | ITs, suíte |
| Auditoria (`access_events`) e avisos na fila | sim | ITs, suíte, E2E |

**Defeito anterior encontrado:** a revogação de concessão PENDENTE respondia 500 (CHECK da 003); corrigido na 023, com o 500 demonstrado antes.

## 4. Frente C: recuperação operacional da 016 (#25)

Relatório: `2026-10-27-recuperacao-016-b10-b11.md`.

B10 e B11 **concluídos** em homologação descartável (`ensaio-c016-b10b11`, lista de elevação alternativa fora do repositório):
- **B10:** gatilho manual da varredura (`VarreduraAvisos`) e espera **real** (SIGKILL durante a entrega segurada; nada antes dos 10 min; DESCONHECIDO/`QUEDA_DURANTE_ENTREGA` depois de 641 s reais; nada publicado);
- **B11:** restauração num cluster descartável, com contagens iguais em 82 tabelas e invariantes da 016 OK na base restaurada.

A primeira execução do B11 reprovou por dois achados reais do procedimento: as pertinências com `GRANTED BY` e o dono da `sslinfo`. O procedimento foi corrigido e repetido só o B11, que é repetível. Continuam abertos: C9 (agendamento), B4 completo, B9 (≥ 73 h), 5b/P5/ativação (HOLD), decisões externas e a recusa explícita do reset operacional de conta vinculada (proposta).

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

Testes ignorados: nenhum nos CIs acima. O E2E fica fora do padrão do surefire (só roda pelo `run.sh`).

## 6. Decisões restantes

| Id | Decisão | Proposta |
|---|---|---|
| D-CONV-1 a 4 | prazo do convite, step-up só para ampliar, readmissão, efeito da perda de autoridade do emissor | como no contrato |
| D-EXT | convite de conta externa e criação de cliente (como a pessoa externa se autentica depois da 017) | decidir antes da mini-missão do ciclo externo |
| D-GRANT-1 | dupla custódia na aprovação de concessão | sem dupla custódia por ora |
| D-ELEV-023 | linha de elevação da 023 para aplicar em homologação | aprovar quando houver ambiente alvo |
| C9 | agendamento da varredura de avisos em produção, com monitoração e alerta | — |

## 7. O que falta para a V1 (não está completa)

Base: `2026-10-23-api-v1-003-inventario-v1.md`, com o que mudou nesta missão.

| Requisito | Antes | Agora |
|---|---|---|
| Convite e onboarding (D-INV-02) | decisão; nenhuma rota | **PR #23/#24**: convites internos completos. Falta D-EXT (convite externo) e o e-mail real ligado fora do ensaio |
| Atribuição e remoção de papéis (GAP-14) | nenhuma rota | **PR #24** |
| Clientes e concessões (GAP-15) | só leitura e revogação | **PR #24**: criar, aprovar, renovar, revisar, revogar com auditoria. Falta criar cliente e conta externa (D-EXT) |
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
