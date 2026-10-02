# P3-IT — Implementação de B+C e testes reais do runner

Data: 2026-09-29. Base: P3 revisão 3 (`homologacao-016-p3-20260929T120553Z.zip`, SHA-256
`1c50a3d8da389406781eba71f7391cc325c150f03aa3a54f7b7330b5062517c6`, 88/88).

**Esta missão não autoriza a decisão operacional D-ELEV, não ativa a linha da 016 em `elevacao.txt`, não cria
`ensaio-016a`, não encerra B4 completo e não faz commit/push.** O `elevacao.txt` operacional continua com 011–013
(`eb98911a…0e6d`, conteúdo LF `7fd92fae…370d`).

Evidência: `ops/homologacao/saidas/p3it-20260929T125920Z/` (rodada 1, preservada) e
`ops/homologacao/saidas/p3itc-20260929T140305Z/` (rodada corretiva, §10).

**Conclusão (revisada na rodada corretiva): P3-IT PARCIALMENTE COMPROVADA.**
- **Comprovados em execução real:** o vínculo obrigatório da 016, o pré-voo oficial obrigatório, a validação da lista,
  a parada, o pré-voo, a pausa com recuperação e `concluir-criacao` (§10).
- **H02 alterado:** validado **só como função real em teste isolado** (`h02_janelas`, 15/15, §13). A **integração
  completa** (H02 dentro do `testar`, com janela interrompida real e recuperação no ambiente) **permanece pendente**.
  A execução real C10 (sem interrupção) foi feita com a versão da rodada 1 da função.
- **Pendentes explicitamente:** o **caminho 015** do `criar`, a **I8** e a **H09(d)**, todos BLOQUEADOS pelas
  sub-redes da `hml03` preservada. A execução do bloco comum `pos_implantacao` com a 016 **não substitui** a
  regressão do caminho 015.
- **Fora desta missão:** D-ELEV, P4 e commit/push.

**Atualização P3-R015 (2026-09-29, aguardando revisão; a P3-IT NÃO é declarada encerrada aqui):** as quatro
pendências acima têm agora **execução real COMPROVADA**, detalhada em
`2026-09-29-homologacao-p3-regressao-015-h02.md` e em `ops/homologacao/saidas/p3r015-20260929T160508Z/`:
- **caminho 015 completo** do `criar` em sub-rede de laboratório (R2);
- **I8 original**, 6/6 subcasos (R3);
- **H09 com (d)** via `testar` (R4);
- **H02 integrado** via `testar`, com interrupção real de versão sintética, recuperação, reexecução e contraprova
  integrada (R5, R6).

O fechamento depende da revisão dessas evidências (§14).

## 1. Resumo (rodada 1; ver a §10 para os defeitos encontrados na revisão e corrigidos)

| Grupo | Classificação | Onde |
|---|---|---|
| R1 — implantação normal (pausa ausente e vazia) | **COMPROVADO** (real) | C01, D01, E01, F01 (ausente, `criar`); D04/R2e (ausente); D04/R1 (vazia); C11 (I1–I7, I9a–I9c, I10) |
| R2 — vínculo e recusas | Rodada 1: sintéticas OK, mas **FALHOU na revisão** para a 016 (linha sem vínculo aceita no `criar` e no `implantar`, sem pré-voo; fallback alcançava a 016; lista ausente seguia sem elevação). **Corrigido e COMPROVADO** na rodada corretiva (§10) | D04/R2a–R2f; p3itc T-A, T-B, F02–F06 |
| R3 — parada | **COMPROVADO** (real; negativos da conferência exata por simulação isolada do bloco real) | C01, D01, E01; D04/R3a–R3d; `R3_parada_exata_simulacao/` |
| R4 — pré-voo real | **COMPROVADO** (real; contraprova OK + código 1/3 **simulada**, P3r3) | D04/R2e, R4a–R4d; C04; C06; E02 |
| R5 — pré-voo 016 | **COMPROVADO** (real; estado divergente **injetado**) | C04 (001–015), C07 (001–016), D02/D03 (injetado) |
| R6 — pausa e recuperação | **COMPROVADO** (real, versão sintética 908/909) | D04/R6a–R6e |
| R7 — concluir criação | **COMPROVADO** (real) — com **falha parcial documentada** na 1ª tentativa (porta reservada pelo Windows) | C03, C05, C08/C09 (falha parcial), E03, E04 |
| R8 — regressão e isolamento | **PARCIAL**. I1–I7, I9a–I9c e I10 aprovados; H02 (sem interrupção) aprovado; `validar-config` aprovado; inventários iguais. **I8, caminho 015 do `criar` e H09(d): BLOQUEADOS** (sub-redes da `hml03` preservada) | C10 (H02), C11, `R8_validar_config/`, p3itc T-B, inventários |
| F4 | **COMPROVADO** | `R8_validar_config/`, p3itc T-B |
| F5 / H02 | Rodada 1: **FALHOU na revisão**. Rodada corretiva: parcialmente corrigido, mas ainda **FALHOU na revisão** (truncava os milissegundos; uma recuperação podia comprovar duas interrupções no mesmo segundo). Rodada 3 (§13): **função real COMPROVADA em teste isolado** (15/15, com a contraprova .100/.900 e a mutação da versão anterior). **Integração completa PENDENTE** | C10 (rodada 1, sem interrupção); p3itc T-C; p3itd |

**Nada dos recursos protegidos mudou:** inventário depois = antes em containers (inclusive `StartedAt`), redes,
volumes e imagens (§7).

## 2. Baseline e o que foi implementado

- HEAD `dd74a8f`, branch `feat/mfa-emergencia`. `repo/` do P3r3 idêntico à árvore antes de editar (58/58).
- Aplicados à árvore de trabalho: **B sem o hunk de `elevacao.txt`** (`implantar.sh`, `homologacao.sh`) e **C**
  inteiro, dos patches do pacote P3r3 (B `534d4306…`, C `0126ddc6…`).
- A **linha da 016** foi separada:
  `docs/relatorios/2026-09-29-p3it-anexos/elevacao-016-proposta.patch` (proposta, não aplicada; conteúdo resultante
  LF `47bb9596…eef1e3`).
- Diferença entre o implementado e "referência + B(código) + C":
  `00_baseline/diff_implementado_vs_referencia_B_C.patch`. Consiste só nos acréscimos da §3. `preflight/016.sql` é
  idêntico ao do P3r3.

## 3. Alterações além de B+C (todas documentadas e testadas)

| Arquivo | Alteração | Motivo |
|---|---|---|
| `implantacao/implantar.sh` | `HOMOLOG_ELEVACAO_ARQ` recusado (código 3, antes de qualquer ação) fora de `ensaio-*` | Aprovado pela responsável. Aperta F3 (antes aceito para qualquer id) |
| `implantacao/implantar.sh` | registro da lista usada com nome, "ALTERNATIVA DE ENSAIO" e **SHA-256** | Rastreabilidade da lista em cada execução |
| `implantacao/implantar.sh` | versão **sem** pré-voo operacional busca o pré-voo em `<dir da lista alternativa>/preflight/` (**rodada 1**; na correção, restrito a versões sintéticas 9xx, §10) | Pré-voos das versões sintéticas. A 016 usa **sempre** o operacional |
| `homologacao.sh` (`criar`) | a conferência da linha da 016 lê a **mesma** lista que o runner (**rodada 1** aceitava a linha sem id; na correção, id exato obrigatório, §10) (`HOMOLOG_ELEVACAO_ARQ` só em `ensaio-*`, e o arquivo tem de existir) | Aprovado. Permite `criar` com a 016 no laboratório sem tocar a lista operacional |
| `homologacao.sh` (`criar`) | conferência **exata** da parada: `migrador recuperado`, **uma única** linha `BLOQUEIO`, exatamente `faltam: 016; nao esperadas: nenhuma`, e `BLOQUEADO` | Requisito: a mensagem esperada não pode mascarar outro erro. C usava `grep` que aceitava linhas adicionais |
| `homologacao.sh` | ação `ensaio-p3it` (só `ensaio-*`) → `testes/p3it_runner.sh` | Suporte de teste, no padrão do `ensaio-interrupcao` |
| `testes/p3it_runner.sh` (novo) | harness real: `implantar.sh` + Flyway + PostgreSQL do ambiente com versões sintéticas 9xx | R1–R4, R6 |
| `testes/executar.sh` | **H02** com expectativa derivada do ambiente. Versões = cópia do ambiente; janelas esperadas = 011/012/013 (+016 com `HOMOLOG_ATE=016`), cada uma **uma** concluída nos registros de criação e de cada `implantar`; interrompidas contadas à parte e exigindo recuperação `0\|0\|0`; histórico esperado derivado da cópia; migrador `0\|0\|0` | F5. Corrige também um defeito latente: H02 comparava **todas** as migrations do repositório (hoje com a 016) com a cópia do ambiente |
| `testes/executar.sh` | `HOMOLOG_TESTES` (subconjunto; padrão = suíte inteira) | Rodar só H02 |
| `testes/executar.sh` | **H09(d)**: lista com o sha256 da 900 (o runner exige); fora de `ensaio-*` espera a recusa da lista alternativa | Defeito **preexistente**: a lista `900` sem sha256 não podia abrir janela. **Não executado** (BLOQUEADO, §6) |
| `testes/validar_config.sh` | **F4**: conteúdo da lista (LF) tem de ser uma versão aprovada (operacional `7fd92fae…` ou proposta `47bb9596…`); cópia proposta via patch numa cópia temporária; 5 adulterações recusadas | F4 |

## 4. Ambiente de laboratório

- **Clone local descartável** no scratchpad (`git clone` do repositório, `checkout --detach dd74a8f`), com a
  sobreposição da árvore de trabalho **exceto** fontes principais da API. Motivo: o `criar` exige fontes principais
  da API sem alteração não commitada (guarda mantida), e as alterações P1c da API (transporte de caixa) não são
  usadas aqui.
  - Imagens construídas de `03bc72c` (novas tags, sem tocar `d608a96`).
  - Manifesto da sobreposição: `00_baseline/clone_laboratorio.txt`.
  - Nenhuma alteração no repositório original. O clone foi removido ao final.
- **Tentativa 1 do clone:** falhou por caminhos longos. Refeita com `core.longpaths`.
- **Tentativa 1 de C01:** o build falhou porque a sobreposição incluía testes P1c de `nekkora-platform` que dependem
  de classe principal P1c. Nenhum recurso Docker foi criado. Evidência preservada, sobras próprias removidas, os 4
  testes retirados só do clone (`C01_tentativa1_falha_build/`).
- **Listas de laboratório** (`listas/`): o conteúdo operacional mais `016 <sha> <id>` vinculada **só** a cada
  ambiente (`ensaio-p3it-c` `ba9259a9…`, `-d` `dcce1926…`, `-e` `096dbd1a…`). A lista operacional nunca foi usada
  para a 016.
- **Janelas de elevação no laboratório:**
  - 011–013 em cada `criar`;
  - 016 **duas vezes**, autorizadas: C06 em `ensaio-p3it-c` e E02 em `ensaio-p3it-e`;
  - sintéticas 901, 905, 909 (esta interrompida e retomada), e as da regressão (I1–I7, I9a–I9c, I10; versão 900).

  Todas revogadas e conferidas `0|0|0`. Estado final dos três ambientes: `0|0|0`.
- PostgreSQL 16.15 (`postgres:16-alpine` `sha256:721873c3…`), Flyway `flyway/flyway:11-alpine` (`sha256:df06535d…`),
  Docker 29.7.2.
- **Ambientes:**
  - `ensaio-p3it-c` (10.252.60/61, porta 55661): parada, R5, janela 016, R7 negativos, falha parcial, H02, I1–I7/I9a–I9c/I10;
  - `ensaio-p3it-e` (10.252.64/65, porta 56701): R7 positivo;
  - `ensaio-p3it-d` (10.252.62/63, porta 56702): R5 injetado e sintéticos.

## 5. Resultados por caso (execução real, salvo indicação)

**Parada e caminho `criar` com a 016 (R3, R1).**
- **C01, D01, E01:** `criar` com `HOMOLOG_ATE=016`, pausa **ausente**, código 0:
  - 001–015 aplicadas; janelas 011/012/013 abertas e fechadas `0|0|0`;
  - `validate` real ignorando só a pendente;
  - `parada_antes_016.txt` = migrador recuperado + uma linha `faltam: 016` + BLOQUEADO.
- **C02:** `estado` BLOQUEADO só pela 016.
- **Simulação isolada** do bloco real da conferência exata: aceita só o arquivo válido e recusa histórico com
  falha, migrador não recuperado, consulta que falhou, outra versão faltando, versão não esperada e LIBERADO
  indevido.

**R2 (sintética 901, lista própria, D04).** Negativos: id diferente, hash errado, sem `HOMOLOG_AUTORIZA_ELEVACAO`,
sem pré-voo. Todos com código 2, recusa **antes de conceder**, sem janela, 901 não executada, histórico inalterado,
`0|0|0`. Positivo com o id exato e a pausa **ausente**: pré-voo real código 0 + `PREFLIGHT_OK`, janela 901 aberta e
fechada, 901 aplicada, `0|0|0`. Fora da lista (903, `CREATE ROLE`): executada **sem** elevação, 42501, sem linha no
histórico, papel não criado. **Limite:** a 903 não tem BEGIN próprio (transacional). Com BEGIN próprio (ex.: 016), a
falha pode deixar `success=false`, o que não foi reproduzido aqui.

**R4 (pré-voo real, `admin_sql` real, D04).**
- Recusas antes de conceder:
  - marcador negativo (código 0, `PREFLIGHT_FALHOU`);
  - erro **real** de SQL (código 3);
  - erro **real** de conexão (`\connect` a banco inexistente; código 3);
  - saída vazia (código 0, última linha vazia).
- Aprovado → janela (R2e, C06, E02).
- Contraprova `PREFLIGHT_OK` com código 1/3: **simulada** (P3r3, T7, mutação), mantida como tal.

**R5 (pré-voo 016).**
- **C04:** cluster 001–015 → código 0 + `PREFLIGHT_OK`, 7/7 OK.
- **C07:** cluster 001–016 (depois da janela C06) → código 0 + `PREFLIGHT_FALHOU` (conferências 2–5 FALHA).
- **D02/D03, estado INJETADO** em cluster separado: a 016 foi aplicada por psql como `admin_cluster`, fora do
  runner e do Flyway, deixando o catálogo presente **sem histórico**. O pré-voo manual → `PREFLIGHT_FALHOU`
  (sslinfo instalada, `ops` e `ops_request_id` presentes). O `implantar` real com a lista de laboratório → **recusa
  antes de conceder** (código 2), sem janela, sem linha no histórico, `0|0|0`. **Não é** um COMMIT perdido
  observado. Registro: `estado` continua dizendo só `faltam: 016`, e a liberação não vê a divergência (F8); **o
  pré-voo é quem barra**.
- Somente leitura: todas as execuções manuais em `default_transaction_read_only=on`, com impressão digital
  (catálogo, papéis, participações, extensões, ACL de parâmetros, histórico com checksums) **idêntica** antes e
  depois.

**R6 (sintéticas 908/909, D04).**
- `antes-do-flyway@909` **não** parou na 908 (aplicada) e parou na 909 com a janela aberta (`1|0|0`).
- `kill -9` no dono da trava; o lock no banco caiu com o processo. Resultado: BLOQUEADO (trava local, elevação
  residual).
- `recuperar` → `0|0|0`. Reconciliação: "não aplicada" (909 fora do histórico, sem objeto).
- Retomada: pré-voo real OK, janela concluída, 909 aplicada uma única vez, `0|0|0`.
- Classificador real do H02 sobre esses registros: `INTERROMPIDA|909` e `CONCLUIDA|909` (simulação isolada).

**R7 (concluir-criacao).**
- Recusas reais:
  - ambiente BLOQUEADO (C03);
  - sem o registro de parada (C05; estado preparado: arquivo movido e restaurado, sha256 conferido);
  - serviços já criados (C09, E04).
- **Falha parcial (C08, documentada, nada removido para escondê-la):** em `ensaio-p3it-c`, senhas dos serviços,
  volume `nekkora 700` e liberação concluíram; Platform e Portal subiram, e o **gateway não**. O Windows recusou o
  bind de 55661 porque **55588–55687 é faixa excluída** (Hyper-V/WinNAT, `netsh … excludedportrange`). A repetição é
  recusada por "serviços já criados": **não há retomada automática** depois de falha parcial. O ambiente foi
  preservado como evidência até o descarte.
- **Sucesso (E03, `ensaio-p3it-e`, porta 56701, segunda janela 016 autorizada):** senhas, volume `nekkora 700`,
  LIBERADO, 5 serviços, gateway `127.0.0.1:56701`, `GET /api/v1/csrf` = 200, Portal = 401.

**R8.**
- **H02** (C10, `HOMOLOG_TESTES=h02`): histórico = cópia (001–016), janelas concluídas 011 012 013 016,
  interrompidas 0, reexecução sem elevação, `0|0|0`.
- **Regressão de interrupção** (C11, `ensaio-interrupcao`): **I1–I7, I9a–I9c e I10 aprovados**, com as formas antigas da
  pausa (I9b/I9c). **I8 BLOQUEADO**: está em `instalacao_incompleta.sh`, que cria ambiente 015 (sub-redes da `hml03`).
- **`validar-config`** 89 OK / 0 FALHA.

## 6. Bloqueios, limites e achados

| Item | Situação |
|---|---|
| Caminho **015** do `criar` (com `pos_implantacao` extraída) | **BLOQUEADO**. `HOMOLOG_ATE=015` exige as sub-redes padrão 10.252.10/11, ocupadas pelas redes da `hml03` (preservada). Coberto indiretamente: `pos_implantacao` é o mesmo bloco, executado com sucesso em E03 com `HOMOLOG_ATE=016` (os ramos 015-específicos são só a ausência dos passos 016) |
| **I8** (`instalacao_incompleta.sh`) | **BLOQUEADO** pelo mesmo motivo (cria ambiente 015) |
| **H09(d)** alterado | **Não executado** (a suíte H09 roda em `testar` de ambiente 015). Só `bash -n` |
| F15 | **Limitação introduzida pelo novo fluxo `concluir-criacao`** (não preexistente): depois de falha parcial, a repetição é recusada por "serviços já criados" e não há retomada. Procedimento exigido para P4 na §11. Código **não alterado** nesta missão |
| F16 | A conferência de porta do `criar` não detecta faixas **excluídas** do Windows. A porta padrão 55643 está hoje dentro de 55588–55687. Falha só aparece ao subir o gateway. **Não alterado** |
| F17 | `destruir` remove containers com `docker rm -f` **sem `-v`**: o volume anônimo `/data` do Redis de cada ambiente sobra. Preexistente (explica volumes anônimos antigos no inventário). As 3 sobras desta missão foram identificadas e removidas por id. **Não alterado** |
| F8 | Confirmado em execução: a liberação não confere o catálogo; o pré-voo barra |
| Execução pelo Flyway da 016 em `ensaio-016a`, B4 completo | FORA DO ESCOPO (P4 e cenário próprio) |

## 7. Recursos, inventário e destinação

- Inventário antes: `00_baseline/inventario_antes.txt`. Depois: `Z_destinacao/inventario_depois.txt`.
  Comparação: **containers, redes, volumes e imagens idênticos**.
- Criados e removidos (`Z_destinacao/destruir.txt`, com ids):
  - 3 ambientes pelo `destruir` do runner, com a confirmação exigida (a primeira passada sem confirmação foi
    recusada e não removeu nada), incluindo containers, volumes nomeados, redes e imagens `:03bc72c`;
  - 3 volumes anônimos do Redis (F17);
  - segredos em `~/.nekkora/homologacao/ensaio-p3it-*`;
  - o clone de laboratório;
  - a imagem `alpine:latest`, **baixada por engano** numa sonda de conteúdo de volume e removida.
- Preservado: cache de build do Docker (58 → 64 entradas, 1,619 → 1,812 GB) e o cache local do Maven.
- Os 3 containers de desenvolvimento em execução, `hml03`, `nk016-smoke` e seus volumes não foram tocados.

## 8. Proposta da linha 016 (separada, NÃO aplicada)

`docs/relatorios/2026-09-29-p3it-anexos/elevacao-016-proposta.patch` acrescenta ao `elevacao.txt` operacional o
comentário e `016 25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61 ensaio-016a`, com 011–013 byte a
byte. `validar_config.sh` reconhece o conteúdo resultante como aprovado (`47bb9596…`) sem ativá-lo. A aplicação
depende da autorização expressa D-ELEV.

## 9. O que continua separado

Autorização operacional D-ELEV e ativação da linha 016; criação de `ensaio-016a`; P4; commit/push; retirada futura
da linha ao fim de P4; B4 completo (interrupção durante o SQL da 016 e resultado desconhecido real); decisões sobre
F15–F17; B9, B10/B11, e-mail real, frontend, descarte de `nk016-smoke`.

## 10. Rodada corretiva (revisão da P3-IT)

A revisão reproduziu, com trechos reais do pacote e sem banco, quatro defeitos, confirmados:

| Achado da revisão | Correção | Prova |
|---|---|---|
| 016 sem vínculo aceita: no `criar`, o id era opcional na expressão; no `implantar`, alvo vazio passava e dispensava o pré-voo | `criar`: `^016 <sha256> <id>$` (id **obrigatório**). `implantar.sh`: `VINCULO_OBRIGATORIO=" 016 "`. A lista com a 016 sem terceira coluna é recusada na validação (código 3) e, por defesa em profundidade, também no laço (código 2). Com vínculo, o pré-voo é sempre exigido. 011–013 continuam no formato legado | T-A `016_sem_vinculo`; T-B (`criar` e `implantar`); **F04** real (código 3, nada feito) |
| Pré-voo da 016 podia vir da lista alternativa quando o oficial faltasse | O pré-voo alternativo vale **só** para versões sintéticas `9[0-9]{2}`, com lista alternativa (`ensaio-*`) e sem pré-voo oficial. A 016 sem o oficial é recusada | **F02** real: oficial retirado **só do clone**, alternativo permissivo (`PREFLIGHT_OK`) presente → código 2 "sem pré-voo" com o caminho oficial, sem janela, histórico inalterado, `0\|0\|0`. **F06**: sintéticas continuam usando o alternativo (R2e) |
| Lista ausente não recusava de imediato: autorização vazia, caminho sem elevação | `valida_lista` antes de qualquer ação: existe e é legível (erro de leitura = código 3), cada linha útil `VVV <sha256> [<id>]` (`[0-9]{3}`, 64 hex, id `[a-z0-9-]{3,40}`), sem versão repetida, versões de vínculo obrigatório com id. Nunca equivale a "fora da lista" | T-A: inexistente, diretório no lugar do arquivo, 016 sem vínculo, 4 campos, hash malformado, versão com 2 dígitos, versão repetida → código 3, nada feito. Controles positivos: operacional (real), 016 vinculada, CRLF → validação aprovada. **F05** real (código 3) |
| H02: qualquer recuperação `0\|0\|0` valia para qualquer interrupção; 2 inícios + 1 fim reportavam só 1 concluída | `h02_janelas` (função nova em `executar.sh`). Por registro: início seguido de outro início = INTERROMPIDA. Fim de outra versão ou sem início = ORFA. **Cada** interrompida exige **sua** recuperação com `0\|0\|0` **depois** do seu início e **antes** do início seguinte em qualquer registro | T-C, função real extraída, 9/9: a contraprova (2 inícios + 1 fim), recuperação anterior, recuperação depois do início seguinte, duas interrupções com uma recuperação, recuperação sem `0\|0\|0`, órfãs, registros **reais** do `ensaio-p3it-c` (4 concluídas) e do R6 (909 recuperada às 13:37:31) |

**Execuções da rodada corretiva** (`p3itc-20260929T140305Z/`):
- **T-A**: `implantar.sh` **real**, sem banco (as recusas são anteriores a qualquer ação).
- **T-B**: `validar-config` **97 OK / 0 FALHA**, com as novas recusas **permanentes**: `criar` com a 016 sem vínculo,
  com outro id, com lista inexistente e com lista alternativa fora de `ensaio-*`; e `implantar` com lista
  inexistente, 016 sem vínculo, formato inválido e versão repetida.
- **T-C**: `h02_janelas` (simulação isolada da função real). **Superado pela §13**: esta versão truncava os
  milissegundos e ainda aceitava uma recuperação para duas interrupções no mesmo segundo.
- **F01–F06**, novo ambiente descartável `ensaio-p3it-f` (10.252.66/67, porta 56703), no clone de laboratório
  refeito:
  - `criar` com a 016 (parada exata; janelas 011–013);
  - F02–F05: recusas da **016 real**, todas **antes de conceder**, sem janela, histórico inalterado, sem `ops`,
    `0|0|0`;
  - F06: sintéticas R2/R4 (10/10).

  **Nenhuma janela da 016 foi aberta nesta rodada.**
- Destinação:
  - `destruir` confirmado;
  - volume anônimo `/data` do Redis **identificado pelo id antes do `destruir`** e removido por esse id;
  - segredos e clone removidos.

  Inventário depois = antes (containers, redes, volumes, imagens). Cache de build 64 → 70 entradas, preservado.
- Implementação: `implantar.sh` `46b88842…`, `executar.sh` `e8bb2959…` (hashes completos no manifesto).

**Continuam pendentes:**
- **Integração completa do H02 alterado** (§13): validado só como função isolada. **Executada em P3-R015** (§14).

**Continuam pendentes (BLOQUEADO):**
- **Caminho 015 do `criar`:** exige as sub-redes padrão, ocupadas pela `hml03` preservada.
- **I8:** cria ambiente 015.
- **H09(d):** roda em `testar` de ambiente 015.

Para executá-los é preciso uma decisão sobre a `hml03`, ou uma forma aprovada de rodar o 015 em sub-rede não padrão.

## 11. Procedimentos exigidos para P4 (F15–F17; a codificar em missão própria ou seguir manualmente)

1. **Falha parcial de `concluir-criacao` (F15, limitação do novo fluxo):**
   - não repetir o comando (é recusado) e não remover containers para esconder a falha;
   - registrar os containers e os estados, `subida_servicos.txt`, `liberacao.txt` e `volumes.txt`;
   - classificar a causa;
   - se for ambiental (ex.: porta), decidir **expressamente** entre descartar e recriar o ambiente (novo id) ou uma
     intervenção revisada.

   Como a 016 já estará aplicada, **recriar abre nova janela da 016**, não abrangida pelo limite atual da decisão
   P3 e, portanto, dependente de nova manifestação.
2. **Conferência prévia de portas (F16):**
   - antes do `criar`, `netsh interface ipv4 show excludedportrange protocol=tcp`, registrado na evidência;
   - a porta escolhida fora de toda faixa excluída e não publicada por nenhum container. A padrão 55643 está hoje
     dentro de 55588–55687.
3. **Volumes anônimos (F17):**
   - antes do `destruir`, registrar os volumes montados por cada container do ambiente (id exato e destino; hoje, o
     `/data` do Redis);
   - depois, remover **somente** esses ids, conferindo que nenhum container os usa;
   - nunca `prune` nem filtro genérico.

   Procedimento executado nesta rodada: `p3itc…/Z_destinacao/destinacao.txt`.

## 12. Histórico de correções deste relatório

| Data | Correção |
|---|---|
| 2026-09-29 (rodada 1) | Versão entregue em `homologacao-016-p3it-20260929T134319Z.zip` (SHA-256 `409b7bdc…4b92`) |
| 2026-09-29 (P3-R015) | Pendências do caminho 015, I8, H09(d) e H02 integrado com execução real (§14); nome correto da evidência de P3-ITd: `p3itd-20260929T144155Z` |
| 2026-09-29 (rodada 3) | `h02_janelas` corrigido (§13). Conclusão: H02 validado só como função real em teste isolado; integração completa pendente. Tabela F5/H02 e §10 atualizadas |
| 2026-09-29 (corretiva) | Conclusão alterada para **PARCIALMENTE COMPROVADA**. R2, F5/H02 e R8 reclassificados. "I1–I10 todos OK" substituído por "I1–I7, I9a–I9c e I10 aprovados; I8 bloqueado". F15 reclassificado como limitação do novo fluxo. Adicionadas as §§10–12 |

## 13. Rodada 3 — `h02_janelas` (ordem, precisão e unicidade da recuperação)

Achado da revisão da rodada corretiva:
- a função truncava os instantes para segundos;
- dois inícios no mesmo segundo (ex.: .100 e .900) ficavam sem ordem, e uma única recuperação posterior aprovava as
  duas interrupções.

**Correção** (só `ops/homologacao/testes/executar.sh`, função `h02_janelas`; o chamador `h02` não mudou):
- **Precisão preservada.** Cada instante vira um intervalo: com milissegundos (registros de janela) é o próprio ms;
  só com segundos (cabeçalho do `recuperar`) é `[ss.000, ss.999]`.
- **Ordem só quando certa.** A vem antes de B apenas se A.fim < B.início. No mesmo registro, decide a ordem do
  arquivo.
- **Ambiguidade recusa.** Estes casos marcam a interrupção como **AMBÍGUA** e recusam a comprovação (contam como
  problema):
  - sobreposição de instantes entre registros, entre inícios ou entre recuperação e os limites da janela;
  - instante ilegível.
- **Uma recuperação, uma interrupção.** As interrupções são processadas em ordem de início, e cada uma consome no
  máximo uma recuperação `0|0|0` ainda não usada. A recuperação tem de estar certamente depois do início dela e
  certamente antes do início seguinte.

**Teste isolado da função real** (`p3itd-20260929T144155Z/teste_h02_janelas.sh`, reproduzível; resultado em
`resultado_funcao_nova.txt`): **15 OK, 0 FALHA**.

| Casos | Conteúdo |
|---|---|
| c1–c9 | Os nove existentes, mantidos com os mesmos dados, inclusive os registros **reais** do `ensaio-p3it-c` e do R6 |
| **c10** | **Contraprova da revisão**: inícios em 14:00:00.100 e 14:00:00.900 (registros distintos) e uma recuperação em 14:00:01 → a recuperação comprova **só** a interrupção de .900; a de .100 fica SEM RECUPERAÇÃO PRÓPRIA (`\|2\|1`) |
| c11 | Recuperação só com segundos no mesmo segundo do início → AMBÍGUA, recusada |
| c12 | Dois inícios no mesmo milissegundo em registros diferentes → ambas AMBÍGUAS |
| c13 | Mesmo milissegundo no mesmo registro → a ordem do arquivo decide (não ambígua) |
| c14 | Recuperação com milissegundos (.500) entre .100 e .900 → RECUPERADA |
| c15 | Instante ilegível → AMBÍGUA, recusada |

**Mutação** (`mutacao_funcao_anterior.txt`): a c10 aplicada à função **anterior** (pacote p3itc) dá `|2|0`, isto é,
uma recuperação aprovando as duas interrupções. O teste detecta o defeito.

**Limite:** é um teste **isolado** da função real (extraída do arquivo e executada com `bash -u`), sem Docker. A
**integração completa** do H02 alterado no `testar`, com janela interrompida real, recuperação e reexecução no
ambiente, **permanece pendente**. O caminho 015, a I8 e a H09(d) continuam pendentes. A `hml03` foi preservada; nenhum
recurso Docker foi criado nesta rodada.

## 14. P3-R015 — pendências com execução real (aguardando revisão)

| Pendência | Situação | Onde |
|---|---|---|
| Caminho 015 do `criar` | **COMPROVADO** (real). `ensaio-p3r015-a`, 10.252.70/71: 001–015, janelas 011–013 `0\|0\|0`, `pos_implantacao`, serviços, TLS, 200/401, LIBERADO, sem 016. `pg_hba`/`nginx` gerados do caminho 015, só em `ensaio-*` | P3-R015 R2 |
| I8 | **COMPROVADO** (real). Teste original, 6/6 subcasos, `ensaio-p3r015-i8` | P3-R015 R3 |
| H09(d) | **COMPROVADO** (real). H09 via `testar`: (a)–(d), com janela real da 900 revogada | P3-R015 R4 |
| H02 integrado | **COMPROVADO** (real). Normal (R5); interrupção real da sintética 950, recuperação própria, retomada e H02 via `testar`; contraprova integrada reprova sem o registro de recuperação e aprova com ele restaurado byte a byte (R6, tentativa 2; a tentativa 1 falhou por defeito da asserção e está preservada) | P3-R015 R5, R6 |

Alterações de suporte desta etapa:
- 015 em sub-rede de ensaio (`homologacao.sh`, `lib/redes.py`, `compose.015-rede.yml`);
- declaração explícita de versão sintética (`lib/estado.sh`, `h02` em `executar.sh`; `h02_janelas` inalterada);
- caso `R015H` do harness;
- repasse de parâmetros na I8;
- conferências em `validar_config.sh`.
