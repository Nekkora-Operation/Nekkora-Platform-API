# R2b-1c: ensaio integrado `ensaio-016c`, STOP no passo 9 (`testar`: H03 incompatível com ambientes com a 016)

**ESTADO: STOP, PARA REVISÃO. A R2b-1 continua não aprovada.**
- Passos 1–9 até `concluir-criacao` OK. **H02 OK, com 5 janelas concluídas e 1 interrompida.**
- `testar` terminou com código 1 só por causa do **H03**, que nunca tinha rodado num ambiente com a 016 (§2).
- Pela regra da missão, parei aqui: `preparar-016`, `testar-016`, `validar-config` final, S3″ e B12 final **não foram executados**. Nada foi repetido ou corrigido.
- **O P4 continua não aprovado.**

**Data:** 2026-10-01, das 23:33Z às 23:56Z.

**Evidências:** `ops/homologacao/saidas/r2b1c-20261001T233339Z/`, fora do Git.

## 1. Resultado por passo

| Passo | Resultado | Evidência |
|---|---|---|
| Pré-condições | **OK.** Porta 55721: `porta.py elegivel` → `PORTA_APTA` (listener, bind e fora das 8 faixas reservadas), sem publicação por container algum. Tabela `netsh` registrada antes do `criar`. Sub-redes 10.252.86/87 livres; nenhum recurso `ensaio-016c`; árvore × 22 aprovados: 22/22 | `00_base/` |
| 1. S1″ | **OK.** `d9b39c16a81d71b44f72a1eb3978b7c2939ff925` = S1 `327c4d4` mais **exatamente os 22 caminhos**; 22/22 sha256 no commit e na cópia; `autocrlf=false` antes do checkout (0 CRLF); sem remoto; `git status` limpo; **`validar-config` 154/0** | `01_S1/` |
| 2. S2″ | **OK.** `e01884f27aa830eddf798bccac919978b786299d`. Diff = só `elevacao_lab_ensaio-016c.txt` (`3d3121c6…d940`), com 014 BYPASSRLS e 016 SUPERUSER vinculadas a `ensaio-016c`. `elevacao.txt` operacional intacto | `02_S2/` |
| 3. `criar` até a pausa da 014 | **OK.** O `criar` aplicou a ferramenta da R2b-1a (`PORTA_APTA 127.0.0.1:55721`) antes de qualquer recurso. T1 `PREFLIGHT_OK` 7/7 e pausa às 23:39:57Z. Fotografia: Flyway 001–013, migrador `0|0|0` com 5 atributos `false`, 0 sessões, sem janela da 014, trava ativa | `03_criar/` |
| 4. Semeadura | **OK.** V=6, E=6, R=6, 18 no total. T3 `antes` 11/11 OK; manifesto preservado | `04_semente/` |
| 5. 014 | **OK.** Mesmo executor: uma janela BYPASSRLS (23:40:42.192Z → 23:40:46.933Z), `0|0|0`, 015 e parada antes da 016 conforme. **T3 `depois` 20/20 OK** | `05_014/` |
| 6. B3 da 016 | **OK, 4 de 4.** Códigos 2, 2, 2 e 3; estado idêntico antes e depois de cada caso (0 janelas da 016, histórico 001–015, `0|0|0`, sem `ops`) | `06_B3/` |
| 7. J1, recuperação e reconciliação | **OK.** Pausa com a janela aberta (`1|0|0`), sem Flyway. `kill -9` só no pid 163 (dono da trava); pai com 137; nenhum `implantar.sh` restante. BLOQUEADO, e os 4 comandos recusados. `recuperar` → `0|0|0`. Reconciliação `READ ONLY`: pré-voo 016 `PREFLIGHT_OK`; 016 fora do histórico; `ops`, `sslinfo` e `ops_request_id` ausentes. **Catálogo e papéis idênticos aos de antes da J1** (`e04b759e… 807`) | `07_J1/` |
| 8. J2 | **OK.** LIBERADO, 001–016. **§19 OK depois da retirada** (`admin_cluster`, `READ ONLY`, `SECAO19_OK`). Janelas da 016: exatamente 2 (J1 sem fim; J2 23:43:48.278Z → 23:43:54.035Z); nenhuma J3. Migrador sem privilégio residual | `08_J2/` |
| 9. `concluir-criacao` | **OK, código 0.** Nova conferência de porta antes dos serviços: `PORTA_APTA`. Platform, portal e **gateway** em pé. "AMBIENTE ensaio-016c CRIADO" | `09_testar/` |
| 9. `testar` (com `HOMOLOG_ELEVACAO_ARQ` = lista do S2″) | **Código 1.** **H02 OK:** expectativa pela lista em vigor (sha registrado) = `011 012 013 014 016`; concluídas 011, 012, 013 (SUPERUSER), 014 (BYPASSRLS) e 016/J2; **1 interrompida (016/J1), RECUPERADA pela própria recuperação**. Também OK: H01, H04, H05, H06, H07, H08, H09 e R015. **H03 FALHA** (§2) | `09_testar/` |
| 10–14 | **Não executados** (STOP) | — |
| B12 início × STOP | **OK:** idêntico por ID fora do ambiente. O ambiente tem 5 containers, 2 redes, 4 volumes e 3 imagens `d9b39c1`. O container de referência do H03 foi removido pelo próprio teste, com volume | `14_encerramento/` |

## 2. O STOP: H03 em ambiente com a 016

**Como chegou aqui:**
- O roteiro do P4 rodava `HOMOLOG_TESTES=h02 homologacao.sh testar` (proposta D-ELEV/P4, linha 265). Por isso o H03 nunca tinha rodado num ambiente com a 016.
- A ordem da R2b-1 diz "testar" sem filtro, e eu rodei a suíte inteira. **A divergência veio dessa diferença de escopo**, e a registro como tal.
- O H03 (`executar.sh`) foi desenhado para o desenho 001–015. Diagnóstico somente leitura em `09_testar/diagnostico_h03.txt`.

**As três falhas do H03, todas estruturais:**
1. **Catálogo de referência.** O H03 instala a cópia de migrations do ambiente (001–016) por superusuário, via `initdb`, num container temporário. A 016 rev5 exige na §19 a identidade declarada `migrador_nekkora` (linha 3178), que não existe nesse container. O `initdb` falha, o container sai e o lado de referência do diff fica "container … is not running".
2. **Invariante da 014 (§8).** O H03 a reexecuta, e ela exige **exatamente uma** política RLS nas 4 tabelas da 014. A 016 cria, de propósito, a segunda (`ops_function_context`, linha 891). Resultado: "tabelas da 014 sem RLS/política esperada".
3. **Papéis com senha.** O H03 compara com a lista fixa do desenho 015. Desde a 016, `nekkora_ops_ca` e `nekkora_ops_intake` também fazem login com senha.

**Classificação:**
- Lacuna da suíte (achado **F-R2b1c-1**): **o H03 não se aplica a ambientes com a 016**.
- Não é defeito do ensaio, da 014 nem da 016.
- Nenhum outro teste falhou.

**Estado preservado:**
- Banco LIBERADO, 001–016, migrador `0|0|0`.
- 5 serviços em pé (o Redis foi reiniciado pelo próprio H07, como no desenho).
- Nada foi alterado depois do `testar`.

## 3. Decisão necessária

| Opção | O que é |
|---|---|
| **A (recomendo)** | Registrar o H03 como **não aplicável a ambientes com a 016**, como o baseline do P4 fez ao rodar só H02, e **continuar o `ensaio-016c` do passo 10**: `preparar-016`, `testar-016` com `H016_B8B=1`, `validar-config` 154/0, Maven, S3″ e B12 final. É o mesmo ambiente, sem repetição nem correção. A adaptação do H03 à 016 vira missão de ferramenta separada |
| B | Adaptar o H03 à 016 agora (referência no modelo suportado, invariantes depois da 016, lista de papéis), com portão próprio, e depois um ensaio novo `ensaio-016d` do zero |
| C | Ensaio novo do zero com `HOMOLOG_TESTES=h02`, como o P4, sem mudar ferramenta |

O `ensaio-016b` e o `ensaio-016c` ficam preservados até a decisão.

## 4. Achados

1. **F-R2b1c-1:** H03 incompatível com ambientes com a 016 (três causas no §2).
2. **Nota de escopo:** `testar` rodou a suíte inteira, e o baseline do P4 rodava só H02.
3. **Ferramenta da R2b-1a validada no ensaio real:** `PORTA_APTA` no `criar` e de novo antes dos serviços; o gateway subiu.
4. **H02 corrigido no portão R2b-0b, validado no ensaio real:** expectativa pela lista em vigor = `011 012 013 014 016`, exatamente igual às concluídas.
5. **Transporte em Windows:** fora do escopo; o achado continua aberto.

## 5. Inventário preservado

- **`ensaio-016c`:**
  - containers `gateway`, `platform`, `portal`, `postgres` e `redis`, todos em pé;
  - volumes `_caixa`, `_operadores`, `_pgdata` e 1 anônimo do Redis;
  - redes `_borda` e `_interna`;
  - imagens `d9b39c1`;
  - segredos em `~/.nekkora/homologacao/ensaio-016c`.
- **Clone:** `../lab-r2b1c-20261001T233433Z/nekkora-platform`. S1″ `d9b39c1` e S2″ `e01884f` (HEAD); sem remoto; `autocrlf=false`. A lista de lab ainda tem a 014 e a 016, porque o S3″ não foi feito.
- **`ensaio-016b`:** idêntico ao início desta missão (mesmos inícios, 0 reinícios).
- **Outros preservados:** `ensaio-016a`, `hml03` (parado), clones anteriores e bases de desenvolvimento.
- **Repositório original:** sem stage, commit ou push; `elevacao.txt` operacional intacto; 014 e 016 rev5 inalteradas. Na árvore de trabalho, só este relatório é novo nesta missão.

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | R2b-1c, `ensaio-016c` até o STOP no passo 9 (H03) |
| Pacote | `homologacao-016-r2b1c-20261001T235410Z.zip`, `cc79988d39386158386c7d1856cad2df8f25ddc72d304382a62cb39c656501c2` (105/105) |
| Decisão | 2026-10-02T00:28:08Z: "Aprovo a opção A, mas eu faria isso como uma continuação formalmente autorizada após o STOP, e não apagaria/reclassificaria retroativamente o resultado do testar." Quadro: "ensaio-016c até o STOP Evidência válida e preservada; Repetir testar Não; Corrigir H03 dentro do 016c Não; Continuar o mesmo 016c do passo 10 GO; Adaptação do H03 para 016 Missão de ferramenta separada" |
| Nuance exigida | não escrever que "o P4 provou que H03 é N/A"; o P4 apenas não executou o H03 nesse cenário |
| Desfecho posterior | R2b-1 e P4 aprovados em 2026-10-02T00:46:03Z (registrado no §6 de `2026-10-02-mfa-emergencia-r2b1c-continuacao.md`). **H03 = FALHA** neste ensaio permanece para sempre |
