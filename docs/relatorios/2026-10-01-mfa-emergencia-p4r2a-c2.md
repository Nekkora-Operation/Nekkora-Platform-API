# P4-R2a-C2: configuração de desenvolvimento, local do script e ajustes do relatório

**ESTADO: PARA REVISÃO.**
- O P4 continua não aprovado.
- Ficam fora: R2b, P5, ativação operacional, commit/push e a frente B.
  - A frente B depende da autorização do 5a; o 5b é decisão separada.

**Data:** 2026-10-01, das 19:44Z às 20:04Z.

**Base:** revisão da P4-R2a-C; pacote `homologacao-016-p4r2ac-20261001T193632Z.zip` (`eab2a4e4…459a`), preservado.

**Evidências:** `ops/homologacao/saidas/p4r2ac2-20261001T194439Z/`, fora do Git.

## 1. O que mudou

| Item | Decisão aplicada | Resultado |
|---|---|---|
| Stack principal (`docker-compose.yml`) | **Preservar**: configuração anterior restaurada | Arquivo idêntico ao HEAD (`792e63a7…`, `git diff` vazio). O volume `nekkora_postgres_data` não foi tocado nem recriado, e não houve marcador manual |
| Bootstrap em volume novo | Configuração **explícita** e separada: `docker-compose.modelo.yml` | Projeto `nekkora-modelo`, container `nekkora-postgres-modelo`, volume `nekkora_modelo_postgres_data`, porta `${POSTGRES_MODELO_PORT:-5433}`. Nunca usa o volume da stack principal. Healthcheck exige o marcador. Uso: `docker compose -f docker-compose.modelo.yml up -d` |
| Script compartilhado | Movido para `db/bootstrap/aplicar_como_migrador.sh` | Conteúdo idêntico ao de antes da mudança (só o comentário de cabeçalho atualizado). `db/tests/lib/` removido (vazio) |
| Consumidores | Atualizados | `RealDatabase.java`, `OpsBanco.java` (`resolveSibling("bootstrap/...")`), `db/tests/mfa-emergencia-016/run.sh` (`MODELO=`), `docker-compose.modelo.yml` |
| Verificações de caminho | `validar_config.sh`, seção 11 (+4) | Script em `db/bootstrap/` e não em `db/tests/lib/`; consumidores apontando para o caminho novo; nenhuma referência ao caminho antigo em código (api, db/tests, ops/homologacao, compose); compose do modelo isolado da principal (projeto, container, volume e porta diferentes). Controle positivo: referência plantada ao caminho antigo → a verificação reprovou |

Os relatórios anteriores (R2a, R2a-C) citam `db/tests/lib/` porque descrevem o estado da época e não foram reescritos.

## 2. Revalidação

| Prova | Resultado |
|---|---|
| `validar-config` | **133/0**: 129 da R2a-C + 4 da seção 11 |
| Suíte da 016 no modelo (script no caminho novo) | **A e B OK**: 4 janelas SUPERUSER e 1 BYPASSRLS com retirada `0|0|0`; invariantes 012/013/014/016 depois da retirada; dados idênticos; restauração lógica (`S_suite/`) |
| C1–C4 em compose descartável gerado de `docker-compose.modelo.yml` | **Todos OK** (`C_dev/execucao1/`): C1 volume novo healthy com marcador (4 SUPERUSER + 1 BYPASSRLS, §19 pos-retirada); C2 restart e down+up sem reaplicar (catalogo identico); C3 falha no meio aborta o initdb com retirada; C4 volume parcial e SIGKILL na janela ficam **unhealthy** (residuo recuperado para 0|0|0, volume continua invalido); limpeza `down -v` sem residuo |
| Regressão Maven completa | **BUILD SUCCESS, 256 testes, 0 falhas, 0 erros** na primeira execucao (core 5, ops 155, platform 84, portal 12), com as bases Java lendo o script de `db/bootstrap/` |
| Inventário início × fim desta rodada | **B12 OK, codigo 0** (protegidos identicos); `ensaio-016a`: 14 objetos, 0 atributos diferentes. Diffs da rodada (antes reconstruido e conferido por hash) em `P_patches/` |

## 3. Correções de redação no relatório da R2a-C

Feitas no arquivo da árvore de trabalho; o pacote entregue fica preservado.
- **H02 (A6b):** a função `h02_janelas`, extraída do `executar.sh`, foi executada sobre os registros reais. Isso comprova a função com registros BYPASSRLS, **mas não equivale ao H02 completo** chamado por `testar`. O `testar` completo é da R2b.
- **Falha da caixa:** o stack trace passa por `CaixaHomologacaoMailSender.desfecho()` e `sendEmergencyNotice()`. Registro: **falha intermitente no transporte em Windows, causa ainda não encerrada; achado aberto**. Não invalida a prova da 014. Uma execução posterior bem-sucedida não demonstra que seja só defeito do teste.
- **Regressão:** registro aprovado = a execução final de 256/0 da R2a-C. Os dois incidentes anteriores continuam visíveis: colisão do sufixo de 24 bits em `Revisao4TentativasIT`, e a caixa.
- **C5:** a adoção no `docker-compose.yml` principal não foi aprovada e foi substituída por esta rodada.

## 4. Situação das decisões

| Decisão | Situação |
|---|---|
| Volume principal | Preservado; configuração anterior. Transição definida: volumes novos pela `docker-compose.modelo.yml`. A migração da stack principal para o modelo fica para decisão própria |
| Script compartilhado | Em `db/bootstrap/` |
| Frente B | **5a pendente de autorização** (consultas somente leitura nas bases no ar: principal, `portal-dev`, `portal-013`; `ensaio-016a` como exceção de leitura). 5b (iniciar `hml03`) é decisão separada |
| Cache de build e clone `lab-p4r2ac` | Preservados |
| Linha operacional da 014 | Patch pronto (R2a-C), não ativado |
| Achado aberto | Transporte da caixa em Windows (`AccessDeniedException` na trava de diretório sob concorrência) |
| Recomendação aberta | Ampliar `OpsBanco.sufixo()` (24 bits) |
