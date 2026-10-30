# PLATFORM-AUTOMATION-LIFECYCLE-001 — relatório final e parecer

Data: 2026-10-29. O contrato e a auditoria L0 estão em `2026-10-29-automation-lifecycle-contrato.md`.
As evidências da jornada real ficam no repositório do Agent, em `docs/automation-lifecycle-001/evidencias/`, porque são produzidas pelas ferramentas do laboratório.

## Parecer: MERGE_READY (com ressalvas R1–R4)

Uma pessoa autorizada fez o caminho inteiro só pelas rotas reais:
- criou o workspace, a automação e a versão DRAFT;
- enviou o pacote genuíno do Studio atual, que a API conferiu;
- publicou com step-up e autorizou um Worker.

Depois disso, o Agent empacotado real executou o pacote com o Studio fechado, e a API mostrou o resultado. Nenhuma linha de SQL criou ou publicou versão. As únicas fixtures, declaradas, foram a identidade da pessoa e os limites da organização (010).

## Fases

| Fase | Resultado | Evidência |
|---|---|---|
| L0 auditoria e contrato | divergências corrigidas: OpenAPI `artifact.sha256` × impressão; 365→366; 500→400/409 na autorização local | relatório de contrato §2 |
| L1 estruturas | migration 028 aditiva, sem elevação: `automation_artifacts`, `automation_admin_events` e gatilho de publicação só para `nekkora-artifact:` | `AutomationLifecycleIT` |
| L2 artefato | `PackageInspector`: ZIP lido pelo diretório central, limites, recusas e vetores `cada6a64…`/`5664f1a5…` | `PackageInspectorTest` 12/12 |
| L3 publicação | step-up, expectativa de sha e de impressão, nova leitura dos bytes, idempotente, imutável | `AutomationLifecycleIT` 6/6 |
| L4 consulta | versão publicada → autorização → Worker → Agent → execução, com supervisor e completude | jornada L4 |
| L5 negativos | permissão, sessão, step-up, outra organização ou workspace, estado, artefato ausente ou corrompido, concorrência, repetição, falha no meio, identidade forjada no banco, pacote malicioso | IT 6/6; jornada L5 |
| L6 jornada real | **9/9 conferências OK** | `evidencias/l6-20261029T204415Z/` |

## Regressão completa da API (branch `feat/automation-lifecycle-001`)

`./mvnw -o test`: **716 testes, 0 falhas, 0 erros, 0 ignorados**, BUILD SUCCESS em 31m47s.

| Módulo | Testes |
|---|---|
| core | 21 |
| ops | 182 |
| platform | 468 |
| portal | 12 |
| agent-gateway | 33 |

Fonte: `evidencias/regressao-api-npa-lc.txt`. Uma primeira tentativa foi interrompida pelo ambiente por falta de memória. Ela foi relançada sozinha, com autorização da titular.

## Jornada L6 (agentlab-004, sub-rede 10.251.50.0/24, migrations 001–028 em instalação limpa)

1. **Pacote genuíno.** O Studio `da7bb94` foi conduzido pela própria interface no roteiro `CodegenQuality001Homologation` (rótulo `lc1`) e terminou com 105 verificações e 0 falhas, incluindo V6, a execução real com 5 linhas.
   - Pacote `biografias-python`: manifesto 1.5.0, `nekkora-studio-codegen/1.2.0`, `nekkora-rpa[excel]==0.3.0`, modo lista.
   - O ZIP determinístico tem sha256 `a7e735a2…017ea` e 15 451 bytes.
2. **Preparação** (`ciclo.py preparar`, só rotas reais). Resultado em `evidencias/ciclo-preparacao.json`.
   - A API devolveu o mesmo sha256 calculado localmente.
   - Impressão `nekkora-agent/package-fingerprint/1` = `f199bda4…a8d1`, com 9 arquivos declarados.
   - `artifactReference` = `nekkora-artifact:<id>`.
   - Versão 1 publicada pela membership da própria pessoa.
   - Canal antes da autorização (`agent_runs|autorizações|execuções`) = `0|0|0`: publicar não autoriza nem executa.
   - Trilha de atos 1,2,3,5,6.
   - Versões publicadas sem ato de publicação da API: 0.
3. **Jornada** (`jornada-ciclo.ps1`, Agent empacotado do commit `7986e84`):
   - L1: o pareamento pela janela baixou a autorização da versão publicada com a impressão do pacote.
   - L2: a pessoa aprovou o pacote do Studio e a execução ficou vinculada à autorização (`PLATFORM_RUN_LINKED`).
   - L3 (injetada): com o nginx pausado, a automação seguiu sem ser relançada.
   - L4: SUCCEEDED, com 292 eventos guardados, 292 assentados e 0 recusas; supervisor SUCCEEDED/KNOWN; histórico COMPLETE; planilha com 5 linhas.
   - L5: com Agent e automação encerrados à força, o resultado foi OUTCOME_UNKNOWN sem relançamento, também na Platform. Só existem as 2 execuções iniciadas pela pessoa.
   - L6: nenhum dos 3 segredos aparece em evidências, dados do Agent ou logs dos serviços.

Depois da jornada, o laboratório `agentlab-004` foi parado com os volumes preservados.

## Ressalvas

- **R1:** o Studio usado (`da7bb94`, branch `feat/studio-lean-001`) tem 22 commits só locais, fora da `main` remota `1cbf669`. O pacote é genuíno do Studio atual, mas esse Studio ainda não foi integrado.
- **R2:** o executável do Agent foi gerado do commit `7986e84`. Em relação à `main` `fdaae19`, ele só difere em `tools/` e `docs/`; `src` é idêntico (`2ab110d1…`).
- **R3:** a proveniência do gerador continua `DECLARED`, porque o manifesto não é assinado. A API confere bytes, estrutura e impressão, mas não a autoria. Assinatura está fora do escopo.
- **R4:** a merge não aplica a 028 em ambiente persistente. Aplicá-la continua sendo decisão da titular, com o migrador suportado. O armazenamento local exige `NEKKORA_ARTEFATOS_DIR`; sem ele, as rotas de artefato respondem 503.

Seguem pendentes da missão anterior as ressalvas A8-1, A8-2, A9-1 e o fechamento dos PRs #1–#3 do Agent.
