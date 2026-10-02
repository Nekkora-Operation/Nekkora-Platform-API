# MFA-CONSOL-1: E1 concluído, STOP no E2.1 e portão da correção do teste 9c

**ESTADO:**
- **E1: PASS**, para revisão.
- **E2: STOP no pré-voo do snapshot**, antes de qualquer recurso. **Nenhuma janela de privilégio foi aberta.**
- **Mini-correção do teste 9c**, com GO da responsável: implementada e provada. **Para o portão.** O E2 não continua sem a decisão.

**Data:** 2026-10-02, das 15:11Z às 15:25Z.

**Autorização:** D2–D6, registrada em 2026-10-02T15:11:08Z (`B_decisoes/D2_D6_autorizacao.txt`). GO para a mini-correção depois do STOP, como resposta à pergunta do executor.

**Evidências:** `ops/homologacao/saidas/consol1-20261002T143413Z/E1_lab_r2b1/` e `E2_ensaio/`.

## 1. E1: encerramento da autorização residual do `lab-r2b1` (PASS)

| Passo | Resultado | Evidência |
|---|---|---|
| Conferência do clone | `…/lab-r2b1-20261001T225153Z/nekkora-platform`, HEAD `36eabb31d0ab4a804768cbbeedf5c0e06c62c591`, limpo, 0 remotos, lista `b3323116…7759` (014/016 vinculadas a `ensaio-016b`) | `D_proposta/e1_lab_r2b1_leitura.txt` |
| 1. Leitura administrativa | `admin_cluster`, `READ ONLY` (o auxiliar da R2b-1c com clone e id trocados): **5 atributos `false|false|false|false|false`, participações 0, ACL de parâmetro 0, sessões 0, `estado_migrador 0|0|0`**; histórico 001–016 com sucesso. Containers do `ensaio-016b` **idênticos antes e depois** (ID, estado, `StartedAt`, reinícios) | `E1_lab_r2b1/1_leitura_migrador.txt`, `containers_antes/depois.txt` |
| 2. Preservação | cópia da lista (`elevacao_lab_ensaio-016b.antes.txt`, `b3323116…`) e da âncora histórica, a 1ª linha do log de criação com o mesmo sha256 | `E1_lab_r2b1/2_antes.txt` |
| 3. Commit administrativo | `b1b4a1a9b803d74aab728a3692ef8934aec40743` (pai `36eabb3`), "S3′-pendente": retira **só** as linhas 014 e 016; ficam 011–013 (lista `039f3209…`, 448 bytes). O commit altera só esse arquivo. Clone limpo, sem remoto, sem push | `E1_lab_r2b1/3_lista.diff`, `3_depois.txt`, `e1_passos_2_3.sh` |
| Histórico | `36eabb3` continua com a lista original (`b3323116…`); log de criação intocado; `elevacao.txt` operacional do clone `7fd92fae…` | `3_depois.txt` |

- **Não houve** janela, `implantar`, `recuperar`, recriação de serviço nem troca de ferramenta ou imagem.
- O `ensaio-016b` **continua não aprovado** (STOP da R2b-1).

## 2. E2.1: clone e S1 (OK até o `validar-config`)

**Clone:** `…/lab-consol016-20261002T151225Z/nekkora-platform`.
- `core.autocrlf=false` antes do checkout;
- remoto removido;
- hook `pre-push` como proteção adicional; publicação proibida independentemente dele;
- ramo `consol016` a partir de `dd74a8f`.
- O único CRLF do checkout é a evidência intencional `900_variante_crlf.sql` (`-text`), igual no S1″.

**S1** `f9477405ababbc121ecabf78774533ff8a30b695`:
- 99 arquivos gravados com o blob do manifesto r1: **99/99 blobs conferidos**; os 10 CRLF da árvore original entraram como o conteúdo LF do blob;
- stage = exatamente a lista;
- `diff dd74a8f..S1` = os 99 caminhos;
- **S1 × S1″ do 016c, por blob: 5 alterados + 13 novos (os 18 deltas aprovados), nenhum ausente**;
- status limpo.
- Evidência: `E2_ensaio/01_S1/`.

## 3. STOP: `validar-config` no S1 = 183/1 (esperado 184/0 por nome)

**A falha:** a verificação divergente é a única: "testes de lib/elevacao_historica.sh", caso **9c** (legado `hml03` com fonte explícita). Ver `E2_ensaio/02_validar/diff_nomes_S1_x_referencia.txt`.

**Causa** (diagnóstico só de leitura, `02_validar/diag_*`):
- `test_elevacao_historica.sh`, linha 70: `BH03="$AQUI/saidas/hml03"`, um caminho **relativo ao repositório testado**;
- `ops/homologacao/saidas/` é ignorado pelo Git, e os registros reais do `hml03` existem **só na árvore original**;
- os casos do 016a e do 016c usam caminhos absolutos dos clones de lab e passam;
- **mesmo arquivo** (`a417bba0…`): 28/28 no original, 27/1 no clone.

**Classificação:** portabilidade do teste (dependência de evidência local fora do snapshot), não conteúdo do snapshot. Nada foi alterado para contornar.

**Estado no STOP:**
- S1 commitado; S2 **não** feito;
- Maven `verificar` e regressão **não** executados;
- **nenhum** recurso do `ensaio-consol-016`;
- **nenhuma** janela de privilégio.
- Ver `E2_ensaio/STOP_E2_1_validar_config.txt`.

## 4. Mini-correção do caso 9c (para o portão)

**Mudança, só em `ops/homologacao/testes/test_elevacao_historica.sh`:**
- `a417bba0470aa0bca0cdd5cc5790fc209806cdc3798337e6e077bdb73367a0d7` → `364d7b69d2d012f829617e99292198e21a25ef6ab0d00d4a1c5452d7fcc7ce71`;
- blob Git `65eaf6830bcd3a3632548f64e9f0ddbacd9a9013`;
- diff: `02b_portao_teste9c/teste9c.patch`, +4 −1.
- `BH03` passa a apontar para o caminho absoluto da árvore original (`ORIG=/c/Users/nicol/nekkora/nekkora-platform`), como 016a e 016c já fazem com os clones de lab.
- Nenhum caso, expectativa ou critério muda. Registros ausentes continuam fazendo o 9c falhar.

| Prova | Resultado | Evidência |
|---|---|---|
| P1: teste corrigido no original | **28/28** | `P1_original.txt` |
| P2: teste corrigido numa cópia exportada do S1 (`git archive f9477405`, **sem** `saidas/hml03`) | **28/28**: independente do local | `P2_copia_S1_corrigido.txt` |
| P3: controle, teste **antigo** na mesma cópia | **27/1**, 9c FALHA: reproduz o STOP | `P3_copia_S1_antigo.txt` |
| P4: controle adulterado, `ORIG` inexistente | **27/1**, 9c FALHA: falha fechada, nunca passa em silêncio | `P4_adulterado_orig_inexistente.txt` |
| P5: `validar-config` no original | **184/0**, **nomes idênticos à referência** | `P5_validar_original.txt`, `P5_nomes.txt` |

**Limite declarado:** o teste continua dependendo dos registros reais preservados no host (os clones `lab-p4` e `lab-r2b1c` e a árvore original), como já dependia. Ele não roda numa máquina sem essas evidências. Isso continua sendo verdade para os casos 9a e 9b.

**Manifesto do snapshot, revisão 2:** `C_lista/manifesto_snapshot_execucao_r2.tsv` (`98061f32f06b0920ebf132cbbe8a3ca8692c62e2ec37442d032934fba225bab2`). Os mesmos 99 caminhos; só muda a linha desse teste.

## 5. Proposta de continuação, depois do portão

1. **Preservar** o clone `lab-consol016-20261002T151225Z` em `f9477405` como evidência do STOP. Não reescrever.
2. **Clone novo** `lab-consol016-<instante>`, com o mesmo procedimento, e **S1 pelo manifesto r2**. Conferências:
   - 99 blobs;
   - `diff dd74a8f..S1`;
   - S1 × S1″ = os 18 deltas aprovados, com o teste na versão do portão.
3. `validar-config` no novo S1, com exigência de **184/0 e nomes idênticos à referência**.
4. Seguir o E2 como aprovado: `verificar` do Maven, regressão se PROIBIDO, S2, `criar`… (preparação revisada, §3).

**Escopo mantido:**
- sem stage, commit ou push no original;
- o arquivo corrigido está **só na árvore de trabalho** do original e entra na integração (C3) junto com o restante;
- `ensaio-016a/b/c`, `hml03`, bases e clones anteriores intocados, exceto o commit administrativo autorizado no `lab-r2b1`.
