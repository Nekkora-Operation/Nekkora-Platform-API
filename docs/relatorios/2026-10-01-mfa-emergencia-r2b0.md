# R2b-0: pré-voo da 014, semeadura pré-014 e conferência (T1–T3), para o portão

**ESTADO: PARA REVISÃO NO PORTÃO (D1).**
- **A R2b-1 continua em HOLD:** S1′/S2′ não montados, `ensaio-016b` não criado.
- **O P4 continua não aprovado.**
- **Ficam fora:** 5b (`hml03` parado), P5, ativação operacional da 014 e commit/push.

**Data:** 2026-10-01, das 21:48Z às 22:20Z.

**Base:** proposta `2026-10-01-mfa-emergencia-r2b-proposta.md`, com as decisões D1 (portão obrigatório), D2 (R2b-0 autorizada), D3 (R2b-1 não autorizada) e D4 (recusas da 014 no `ensaio-r2b0`).

**Evidências:** `ops/homologacao/saidas/r2b0-20261001T214834Z/`, fora do Git.

## 1. Resultado

| Item pedido | Resultado | Evidência |
|---|---|---|
| T1, T2 e T3 implementados | 4 arquivos novos e 2 alterados (§2). O patch sobre o estado anterior reproduz os 6 arquivos byte a byte | `P_patches/` |
| Base descartável: positivo | Pré-voo `PREFLIGHT_OK` na base 013; semeadura aplicada; T3 `antes` OK; 014 **com** BYPASSRLS → T3 `depois` OK. Em seguida 015 e 016 rev5 sobre a base semeada: §19 OK depois da retirada, T3 continua OK | `T_base/execucao2/` |
| **Contraprova sem BYPASSRLS** | A 014 aplica "com sucesso" (código 0, nenhuma janela). **T3 acusa FALHA:** 6/6 + 6/6 não revogadas, 0 instantes de revogação, 0 de 12 na janela, 12 não revogadas sem marcador | `T_base/execucao2/C_*` |
| **Mutações negativas** | **7/7 detectadas como FALHA**, cada uma pela conferência certa (§3.3). Controles INCONCLUSIVO: manifesto ausente e identidade sem visão de todas as linhas | `T_base/execucao2/m*`, `T3_*` |
| Recusas de T1 e T2 (base) | T1: 5 negativos recusados, cada um pelo item certo. T2: 5 recusas, todas com a transação desfeita | `T_base/execucao2/` |
| **Recusas da 014 pelo executor real** (`ensaio-r2b0`, rodada A) | sha256 errado, outro ambiente e falta de `HOMOLOG_AUTORIZA_ELEVACAO`: **código 2, "RECUSADA antes de conceder"**. Em todos os casos: nenhuma janela da 014 no registro, histórico 001–013 inalterado, migrador `0|0|0` | `R_runner/A/` |
| **Ensaio curto real** (`ensaio-r2b0`, rodada B) | `criar` → T1 `PREFLIGHT_OK` (7/7) → pausa antes de conceder → `semear-pre014` → T3 `antes` OK → pausa liberada → **uma janela BYPASSRLS da 014** (22:05:38.926Z–22:05:43.767Z, `0|0|0`) → 015 → **PARADA antes da 016 conforme** → T3 `depois` **20/20 OK** | `R_runner/B/` |
| `validar-config` | **142/0** na árvore de trabalho, e 142/0 no clone de lab depois de corrigido o fim de linha de um anexo (O1, §4) | `G_validar/` |
| Limpeza | Só os recursos da R2b-0, por ID: 2 containers descartáveis da base, 2 rodadas do `ensaio-r2b0` (containers, redes, volumes, imagens `d052a16`), 2 volumes anônimos do Redis e os segredos das 2 rodadas. **B12 início × fim OK** (81 volumes, 10 redes, 19 imagens idênticos por ID) | `A_baseline/b12_inicio_x_fim.txt` |

## 2. Alterações (T1–T3)

`P_patches/r2b0-T1-T3.patch`, mais um diff por arquivo em `P_patches/diffs/`. Valores em sha256 LF.

| Arquivo | Tipo | sha256 | Conteúdo |
|---|---|---|---|
| `ops/homologacao/implantacao/preflight/014.sql` | novo | `9eebb93b…ee54` | **T1.** `BEGIN TRANSACTION READ ONLY … ROLLBACK` com limites de duração e 7 itens. Termina em `PREFLIGHT_OK` ou `PREFLIGHT_FALHOU`; um erro de consulta não produz a última linha, e o resultado é recusa. Itens: servidor 16; histórico 001–013 com sucesso, nada da 014 em diante, sem falha; `user_sessions` sem colunas da 014; tabelas e triggers da 014 ausentes; RLS habilitada e forçada; banco do migrador; migrador sem atributo nem ACL de parâmetro |
| `ops/homologacao/sql/fixture_014_pre.sql` | novo | `6684af85…271c` | **T2.** Guardas na mesma transação, antes de qualquer `INSERT`: `ensaio-*`; superusuário; histórico como em T1; sem colunas da 014; migrador sem atributo; sem semente anterior. Grava a organização sintética, 2 usuários `.invalid` e 18 sessões (V, E, R × 6). O manifesto, só com contagens e digests, é gerado depois do `COMMIT` |
| `ops/homologacao/sql/semente_014_estado.sql` | novo | `913917dd…3aee` | **T3**, leitura em `READ ONLY` e `ROLLBACK`. Identifica os grupos por hash determinístico (`sha256('r2b-semente-014|G|n')`), sem exportar valor. Lê as colunas novas por `to_jsonb`, então funciona antes e depois da 014 |
| `ops/homologacao/testes/semente_014.sh` | novo | `28dabb85…db34` | **T3:** `antes` (estado inicial declarado, grava o manifesto) e `depois` (as conferências da proposta, §4). Códigos: 0 OK, 1 FALHA, 3 INCONCLUSIVO (leitura incompleta, manifesto ausente ou identidade sem visão de todas as linhas) |
| `ops/homologacao/homologacao.sh` | alterado (+58 −0) | `a6775e1e…cafe` | Subcomando `semear-pre014`: só `ensaio-*`; exige `HOMOLOG_ENSAIO_PAUSA=antes-de-elevar@014:<arq>`, `<arq>.chegou` presente, pausa ainda não liberada, trava local, `0|0|0` e nenhuma semente registrada. **Não libera a pausa.** Subcomando `conferir-semente014 antes|depois`: só `ensaio-*`; a janela da 014 vem do registro do executor. Mais a documentação no cabeçalho |
| `ops/homologacao/testes/validar_config.sh` | alterado (+28 −0) | `d36e6e17…2415` | Seção 12, com 8 verificações: T1 e T3 somente leitura; guardas de T2; `semear-pre014` e `conferir-semente014` recusados fora de `ensaio-*`; pré-condições da pausa; ganchos do runner restritos a `ensaio-*`; `elevacao.txt` operacional sem 014/016. Controles com cópias adulteradas: 5 de 5 reprovaram (`G_validar/controles_secao12.txt`) |

**Nada mudou em:**
- `implantar.sh` e no `criar`;
- o `elevacao.txt` operacional (sha `7fd92fae…` conferido);
- as migrations: 014 `5e5726b9…d8fc`, 016 `f37d9841…3732`.

## 3. Provas

### 3.1 Base descartável no modelo (`T_base/base.sh`, execução 2: 28 OK, 0 FALHA)

- **Ambiente:** container próprio (`--init`, rótulo `nekkora.r2b0=base`), com `aplicar_como_migrador.sh` até a 013.
- **Histórico Flyway sintético.** A base descartável não usa Flyway. Por isso foi criada uma tabela com as colunas do Flyway e as versões 001–013, só para exercitar as guardas de T1 e T2. O Flyway real é exercitado na §3.2.
- **Bases derivadas:** cada caso usa uma cópia `TEMPLATE` da base 013 ou da base depois da 014.
- **T1:**
  - positivo (`PREFLIGHT_OK`);
  - histórico com falha → só o item 2 FALHA;
  - coluna da 014 presente → só o item 3;
  - banco de outro dono → só o item 6;
  - migrador com BYPASSRLS → só o item 7;
  - sem histórico → erro e código 3, sem `PREFLIGHT_OK`.
- **T2:** positivo, com recusas:
  - fora de `ensaio-*`;
  - semente repetida;
  - coluna da 014 presente;
  - histórico com falha;
  - migrador com atributo: 0 sessões depois da recusa, transação desfeita.
- **014 com BYPASSRLS** (janela registrada pelo modelo, com resolução de 1 s, por isso o `fim` recebe +1 s): T3 `depois` OK.
- **Sobre a base semeada:** 015 e 016 rev5 aplicadas com §19 OK depois da retirada; T3 `depois` continua OK.

### 3.2 Executor real (`ensaio-r2b0`, clone de lab `d052a16` → `f0f582c`)

- **Clone com lista fechada.** S1 do P4 (`327c4d4`) mais exatamente 19 arquivos: os 15 aprovados e T1–T3.
  - `diff 327c4d4..HEAD` é igual à lista, com 0 divergências de sha256 LF em relação à árvore.
  - Sem remoto.
  - Tag das imagens: `d052a16`, distinta das imagens preservadas (`327c4d4`, `d608a96`).
  - O commit `f0f582c` só aplica a correção F-R2b0-1 (§4) ao `homologacao.sh`.
- **Listas de laboratório** (fora do clone, via `HOMOLOG_ELEVACAO_ARQ`; `R_runner/listas/`):
  - A: 014 com sha256 errado (último dígito trocado), vinculada a `ensaio-r2b0`;
  - B: 014 vinculada a `ensaio-outro`;
  - C: 014 `5e5726b9…d8fc tipo=bypassrls ambiente=ensaio-r2b0` e 016 `f37d9841…3732 tipo=superuser ambiente=ensaio-r2b0`. A 016 não foi aplicada.
- **Rodada A (recusas):**
  - `criar` com a lista A → janelas 011–013 concluídas e recusa da 014 por sha256 (código 2).
  - Na mesma base, `implantar` com a lista B (outro ambiente) e com a lista C sem `HOMOLOG_AUTORIZA_ELEVACAO`: código 2 nas duas.
  - Depois de cada recusa: histórico 001–013, `0|0|0`, nenhuma linha da 014 no registro de janelas, 0 sessões.
  - Ao fim: `destruir` por rótulo, volume anônimo do Redis removido por ID (sem uso e ausente do inventário de início), segredos removidos (só os nomes registrados) e B12 OK.
- **Rodada B (fluxo da R2b-1):**
  1. `criar` com a lista C e `HOMOLOG_ENSAIO_PAUSA=antes-de-elevar@014:<B>/pausa014`.
  2. T1 oficial: código 0 e `PREFLIGHT_OK` (7/7), depois a pausa. Fotografia: histórico 001–013, `0|0|0`, 0 sessões, sem janela da 014, trava do executor ativa.
  3. Recusas do subcomando no contexto real, as três com código 1 e 0 sessões gravadas: sem a variável de pausa; pausa de outra versão (`@016`); arquivo de pausa não alcançado.
  4. `semear-pre014` → `SEMENTE_APLICADA`, 18 sessões (12 não revogadas). T3 `antes` OK, com manifesto de 21 linhas. Repetição recusada (código 1).
  5. Pausa liberada. O **mesmo processo** concedeu BYPASSRLS só para a 014, aplicou, revogou (`0|0|0`), aplicou a 015 e fez a parada verificável: `migrador recuperado`, `BLOQUEIO: … (faltam: 016; nao esperadas: nenhuma)`, `BLOQUEADO`. O `criar` terminou com código 0.
  6. **T3 `depois`, 20/20 OK:**
     - V e E com 0 não revogadas, **um único instante**, 12 de 12 dentro da janela registrada;
     - R com `revoked_at` inalterado;
     - digests das colunas preservadas idênticos nos 3 grupos;
     - colunas novas nulas;
     - organização e usuários inalterados;
     - 0 sessões fora da semente, total de 18;
     - 0 sessões não revogadas sem marcador.
  7. Estado final: histórico 001–015, triggers da 014 habilitadas, migrador com os 5 atributos `false`.
  8. Limpeza como na rodada A; B12 início × fim da missão OK.

### 3.3 Mutações (cópias da base depois da 014; triggers desligadas só na transação da mutação)

| Mutação | Conferências que acusaram |
|---|---|
| m1 `revoked_at` de uma sessão R | grupo R: `revoked_at` inalterado |
| m2 `last_seen_at` de uma sessão V | grupo V: colunas preservadas |
| m3 sessão a mais | nenhuma fora da semente; total inalterado |
| m4 sessão V reativada | V/E não revogadas; 12 na janela; não revogadas sem marcador |
| m5 sessão E com outro instante | instante único; 12 na janela |
| m6 `mfa_verified_at` preenchido numa R | grupo R: colunas novas nulas |
| m7 usuário semeado alterado | organização e usuários inalterados |

## 4. Achados e incidentes

1. **F-R2b0-1, corrigido na R2b-0:**
   - **Sintoma:** `conferir-semente014 depois` saiu com código 2 e sem saída.
   - **Causa:** sob `set -euo pipefail`, o `ls` de um glob sem correspondência (`implantacao-*/`, que não existe quando a 014 roda dentro do `criar`) derrubou o comando.
   - **Correção:** laço só sobre arquivos existentes, na ordem raiz → `implantacao-<instante>`, valendo o último com a janela da 014.
   - **Efeito na prova:** é só leitura; o ensaio não foi refeito. A conferência foi repetida com a correção (commit de lab `f0f582c`) e deu 20/20 OK. A saída com código 2 está preservada (`B9_conferir_depois.txt`).
2. **O1 do P4 de novo, no clone, sem relação com T1–T3:**
   - **Sintoma:** com o `core.autocrlf=true` herdado, o anexo `docs/relatorios/2026-09-29-p3it-anexos/elevacao-016-proposta.patch` é gravado em CRLF no checkout e o `git apply` do `validar-config` o recusa (5 FALHAs da seção 5).
   - **Efeito da correção:** com os bytes exatos do blob (`autocrlf=false`; `git status` limpo), o resultado é **142/0**, e o arquivo fica idêntico ao da árvore original (`653eca1a…`).
   - **Recomendação para a R2b-1:** montar o clone S1′ com `core.autocrlf=false`.
3. **Erros de harness, corrigidos antes do resultado:** registrados em `T_base/execucao1/`.
   - O `'-'::timestamptz` era avaliado mesmo no ramo não usado do `CASE`; corrigido com `nullif`.
   - Conversão de caminho do Git Bash no `docker exec cat /tmp/...`.
   - A execução 2 é a válida.
4. **Desvio do texto da proposta: duas rodadas no `ensaio-r2b0`.** Uma recusa da 014 interrompe o `criar` antes da pausa, por isso não cabe na mesma criação do fluxo completo. A rodada A (recusas) foi destruída por ID antes da rodada B (fluxo), com o mesmo id e B12 limpo entre as duas.
5. **Falha de transporte em Windows:** não houve teste da caixa nesta missão, e o achado continua aberto.
6. **Maven:** não foi repetido. Fontes Java do clone = os 2 arquivos de suporte de teste já cobertos pela regressão aprovada (C2, 256/0). T1–T3 não tocam Java. O `criar` compila com `-DskipTests`, o que não é regressão.

## 5. Estado preservado e escopo

- **Preservados:**
  - `ensaio-016a` (só o Postgres no ar, não tocado);
  - `principal`, `portal-dev` e `portal-013`;
  - `hml03` parado;
  - os clones `lab-p4-…` e `lab-p4r2ac-…`;
  - as imagens `327c4d4` e `d608a96`;
  - o cache de build.
- **Clone de lab da R2b-0** (`../lab-r2b0-20261001T215832Z/nekkora-platform`, `d052a16`/`f0f582c`, sem remoto): **preservado como proveniência do ensaio curto, para o portão.** Não é o S1′. Descarte por decisão posterior.
- **Repositório original:**
  - sem stage, commit ou push;
  - na árvore de trabalho entram os 6 arquivos do §2 e este relatório;
  - `elevacao.txt` operacional intacto.

## 6. Para o portão (D1)

Pontos que a revisão pediu para examinar primeiro:

| Ponto | Onde está |
|---|---|
| Diffs T1–T3 | `P_patches/r2b0-T1-T3.patch` e `P_patches/diffs/` |
| Evidência das recusas | Rodada A (`R_runner/A/`); recusas de T2 em `R_runner/B/B3_*` e `B6_*`; base em `T_base/execucao2/T1_neg_*` e `T2_recusa_*` |
| Contraprova sem BYPASSRLS | `T_base/execucao2/C_*` e `contra_aplica_014.txt` |
| Ensaio curto real | `R_runner/B/`: `B1_criar.txt`, `preflight_014.txt`, `B4_semear.txt`, `janelas_de_elevacao.txt`, `parada_antes_016.txt`, `B9b_conferir_depois.txt` |

**Insumos para a montagem do S1′, se o portão aprovar:**
- **Lista fechada:** os 15 arquivos aprovados mais os 6 do §2, nas versões com os sha256 do §2.
- **Clone com `core.autocrlf=false`.**

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | R2b-0 (T1–T3) |
| Pacote | `homologacao-016-r2b0-20261001T221750Z.zip`, `7ddd6713a279e30511e19a6f96d9a9e8112c7b380b93c57f435f85810ab2f2c4` (manifesto 180/180) |
| Decisão | 2026-10-01T22:26:43Z: "D1 está aprovado. A R2b-0 fecha PASS e a R2b-1 pode sair de HOLD." Quadro: "D2 R2b-0 ENCERRADA / APROVADA; D3 R2b-1 GO; D4 desenho das recusas MANTIDO" |
| Escopo aprovado | os 6 arquivos de T1–T3; `validar-config` 142/0 |
| Aceitos | F-R2b0-1 não bloqueante (falha original preservada, correção só de leitura); as duas rodadas do `ensaio-r2b0` como "desvio justificado, não finding aberto" |
| Condição | "montar S1′ com core.autocrlf=false" passa de recomendação a **obrigatória** |
| Substituição | nenhuma |
| Autorização anterior | 2026-10-01T21:45:30Z: "aprovar a R2b-0 e manter a R2b-1 bloqueada até o portão de revisão" |
