# H02 × lista histórica: expectativa pela política de criação do ambiente, para revisão

**ESTADO: PARA REVISÃO.**
- Fecha o limite registrado na R2b-0b: a expectativa do H02 vinha da lista vigente no momento do teste.
- Não inclui rotação de política para ambientes antigos (fica para decisão própria).

**Data:** 2026-10-02.

**Evidências:** `ops/homologacao/saidas/h02hist-20261002T034631Z/`, fora do Git.

## 1. Decisão implementada

**Regra:** a expectativa de janelas do H02 é a da **política sob a qual o ambiente foi criado**, preservada numa fotografia imutável. Nunca é a lista vigente.

**Fotografia** (`lib/elevacao_historica.sh`, `eh_registrar`). O `criar` a grava logo depois dos diretórios vazios e **antes de qualquer segredo, configuração ou recurso Docker**:
- `saidas/<id>/elevacao_criacao/lista.txt`: **bytes exatos** da lista usada, somente leitura;
- `saidas/<id>/elevacao_criacao/proveniencia.txt`: origem, tipo (operacional ou alternativa-ensaio), sha256, bytes, id do ambiente e instante, somente leitura.
- Se a fotografia não puder ser preservada, o `criar` falha ("nenhum recurso foi criado").
- A lista da criação passa a ser validada pela gramática para qualquer `HOMOLOG_ATE`, antes de tudo.

**Âncoras independentes da fotografia**, gravadas na mesma criação:
1. `ambiente.env`: `HOMOLOG_ELEVACAO_CRIACAO_SHA256`;
2. a 1ª linha do log de criação do runner (`implantacao.txt`): `… sha256 <sha256>`.

**H02** (`executar.sh`, via `eh_lista_h02`). Usa a fotografia só se tudo confere: sha256 da lista = proveniência = `ambiente.env` = log de criação, e a proveniência é do próprio id. Qualquer divergência dá **FALHA fechada**. A lista vigente (`HOMOLOG_ELEVACAO_ARQ` ou `implantacao/elevacao.txt`) **nunca** é lida para a expectativa.

**Legado** (ambientes anteriores ao mecanismo: sem fotografia e sem a âncora no `ambiente.env`):
- só com fonte histórica **explícita**, `HOMOLOG_H02_LISTA_LEGADO=<arquivo>`;
- se o log de criação registra o sha256, o arquivo tem de ter **exatamente** esses bytes;
- se não registra (runner anterior ao P3, caso do `hml03`), o modo é "**LEGADO SEM ÂNCORA**", registrado na nota com o arquivo e o sha256;
- sem fonte explícita: FALHA fechada.

**Ambiente novo sem fotografia:** FALHA, mesmo com fonte legada ou com lista vigente.

## 2. Provas

### 2.1 Sem Docker: `test_elevacao_historica.sh`, 28/28

Execução 1 com 2 erros de harness, preservados:
- a mutação do caso 7b com `grep >> mesmo arquivo`;
- no 9a, forneci a versão LF da lista legada do 016a, quando o runner do P4 leu bytes CRLF. A recusa da ferramenta estava correta, porque exige bytes exatos.

| Caso pedido | Resultado |
|---|---|
| criado sem 014; a lista operacional ganha 014 depois | continua **sem** 014 |
| criado com 014 BYPASSRLS | espera 014 |
| lista corrente (ou a de origem) muda depois da criação; e o inverso | expectativa **não muda** |
| 014 vinculada a outro ambiente na fotografia | não entra |
| fotografia adulterada | FALHA |
| sha256 divergente (proveniência / `ambiente.env` / log de criação) | FALHA nos 3 |
| troca coerente de lista + proveniência sem as âncoras | FALHA |
| fotografia malformada (sem sha, sha duplicado, lista fora do formato, de outro ambiente, incompleta) | FALHA |
| ambiente novo sem fotografia (sozinho, com fonte legada, com lista vigente) | FALHA fechada |
| **legado + lista histórica explícita válida** (registros **reais** preservados, só leitura) | `ensaio-016a`: bytes CRLF reconstruídos = sha do log; a versão LF é **recusada**; esperadas `011 012 013 016` = janelas registradas. `ensaio-016c`: `011 012 013 014 016` = registradas. `hml03`: "LEGADO SEM ÂNCORA", `011 012 013` = registradas |
| legado sem evidência histórica; fonte que não é a da criação; fonte inexistente | FALHA (a lista vigente não é usada) |
| `eh_registrar` com lista inexistente / fotografia já existente | recusa (nunca sobrescreve) |

### 2.2 Ensaio real descartável

Clone de lab `c2b4396`, `autocrlf=false`: 29 caminhos aprovados mais esta missão, idêntico à árvore. Dois ambientes `HOMOLOG_ATE=015`:
- **`ensaio-h02a`**, criado com a lista **A**: operacional, sem 014, `7fd92fae…`.
- **`ensaio-h02b`**, criado com a lista **A2**: 014 BYPASSRLS vinculada a ele, `5a9a0bd1…`.
- Nos dois, as **3 âncoras conferem** (fotografia, `ambiente.env` e log de criação).
- Janelas registradas: h02a `011 012 013`; h02b `011 012 013 014`.

**Execução 2, com a versão final do `criar`: 15/15**

| Cenário | H02 |
|---|---|
| C1a h02a, base | OK, MODO HISTÓRICO (A) |
| **C1b h02a com a lista corrente B** (A + 014) via `HOMOLOG_ELEVACAO_ARQ` | **OK, esperadas `011 012 013`** (pela A) |
| **C1c h02a com o `elevacao.txt` operacional do clone ganhando a 014** (revertido depois) | **OK, `011 012 013`** |
| **C2a/C2b (inverso)** h02b com a lista corrente sem 014 (operacional e via `ARQ`) | **OK, `011 012 013 014`** (pela A2) |
| N1 lista da fotografia adulterada | FALHA ("adulterada") |
| N2 sha da proveniência divergente | FALHA |
| N3 sha do `ambiente.env` divergente | FALHA ("diverge do ambiente.env") |
| N4 troca coerente lista+proveniência sem as âncoras | FALHA |
| N5 proveniência malformada | FALHA |
| N6/N7/N8 sem fotografia (sozinho, com fonte legada, com lista corrente) | FALHA fechada ("SEM a fotografia") |
| Prova final depois das reversões (cada uma conferida contra o original) | h02a e h02b OK |

**Contraste com o H02 anterior** (versão aprovada no portão H03, posta temporariamente só no clone e restaurada), no mesmo `ensaio-h02a` com a lista corrente B:
- **FALHA**: "expectativa … pela lista em vigor B: `011 012 013 014`" contra as concluídas `011 012 013`.
- É o defeito que a decisão elimina.

**Execução 1** (preservada em `R_runner/execucao1/`): também 15/15 e o mesmo contraste, mas com uma versão intermediária do `criar`, que separava o `mkdir`. Refiz o ensaio com a versão final para que a evidência corresponda à ferramenta entregue.

### 2.3 `validar-config`

- **177/0** = 170 + 6 da seção 17 + 1 `bash -n` do novo teste.
- **Seção 17:**
  - testes;
  - H02 só por `eh_lista_h02`, sem `HOMOLOG_ELEVACAO_ARQ`/`elevacao.txt`;
  - sem fallback silencioso e legado só explícito;
  - integridade (lista, proveniência, `ambiente.env`, log, id, formato);
  - ordem no `criar`: validação → diretórios → **fotografia** → segredos → Docker, mais o sha no `ambiente.env`;
  - fotografia nunca sobrescrita e somente leitura.
- **Primeira execução:** a mudança inicial no `mkdir` do `criar` quebrava três verificações aprovadas, ancoradas nessa linha (seção 14 e `guarda_fontes_imagens.sh`). Restaurei a linha original e pus a fotografia logo depois, sem alterar ferramentas já aprovadas. A execução está preservada.
- **Controles com cópias adulteradas, 7 de 7 reprovados:**
  - H02 voltando à lista vigente;
  - fallback silencioso sem fotografia;
  - fotografia sem integridade;
  - proveniência substituível sem âncoras;
  - `criar` sem fotografar;
  - fotografia sobrescrevível;
  - legado sem fonte explícita usando a lista vigente.

## 3. Arquivos

Diff fechado em `P_patches/h02hist.patch`. Aplicado sobre as versões aprovadas (origens conferidas por hash), reproduz os 5 arquivos.

| Arquivo | Tipo | sha256 LF antes → depois |
|---|---|---|
| `ops/homologacao/lib/elevacao_historica.sh` | novo (+74) | → `79baae18dac25d4c48264205780c3897891ddd2fc4e6cbaea05703899e4bca4d` |
| `ops/homologacao/testes/test_elevacao_historica.sh` | novo (+91) | → `a417bba0470aa0bca0cdd5cc5790fc209806cdc3798337e6e077bdb73367a0d7` |
| `ops/homologacao/homologacao.sh` (`criar`) | +10 | `665383f0…080c` → `f43d6c9b620b0e92b0ea972e457b88f0b812c2e2f02455f0ea6efdd6aa8e648b` |
| `ops/homologacao/testes/executar.sh` (`h02`) | +12 −7 | `2eedc65f…0b0f` → `fc089034d8bec0f2a828880550464dff0aa6b0c80b79b7a82dc49699fc1d9dba` |
| `ops/homologacao/testes/validar_config.sh` | +25 (seção 17) | `378a5f9e…1092` → `f8a04b78c57680390b7103cb7781ea99a16456bf117496609bbeea9a28bb9849` |

**Não mudaram:** H03, `implantar.sh`, migrations, `elevacao.txt` operacional nem a ferramenta de porta/Maven.

## 4. Escopo e limites

- **Recursos:** `ensaio-h02a` e `ensaio-h02b` (duas rodadas) removidos por ID, com os volumes anônimos do Redis e os segredos (só contagem registrada). B12 início × fim idêntico. `ensaio-016a/016b/016c` e `hml03` intocados. Clone `lab-h02hist` preservado como proveniência.
- **Repositório:** sem stage, commit ou push.
- **Fora desta missão, como decidido:** rotação ou atualização auditável da política de elevação de um ambiente antigo.
- **Limite para essa decisão futura:** o `implantar` posterior pode usar outra lista. O H02 continua esperando a da criação e falha se as janelas divergirem, que é o comportamento desejado até existir a operação de rotação.
- **Incidente do ZIP H03:** restaurado conforme autorizado. O alterado está preservado como `homologacao-016-h03p016-20261002T013045Z.INCIDENTE-alterado-c54216c9.zip`, com registro em `.INCIDENTE.txt`, em `../relatorios`.

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | H02 × lista histórica |
| Pacote | `homologacao-016-h02hist-20261002T043726Z.zip`, `61cc9eed67b526645a225cfddb25f345dda1cc522ba2009c8f4bf8724775e204` (157/157) |
| Decisão arquitetural | 2026-10-02T03:42:53Z: a expectativa do H02 vem da política sob a qual o ambiente foi criado, não da lista vigente |
| Decisão | 2026-10-02T12:16:45Z: "Aprovo o gate do H02 histórico. PASS completo." `validar-config` 177/0 |
| Aceitos | legado `ensaio-016a` com bytes CRLF exatos (LF recusado); `hml03` como "LEGADO SEM ÂNCORA", limitação visível |
| **Correção terminológica** | onde o corpo diz "fotografia **imutável**" (§1), ler: fotografia **histórica, ancorada e detectável a adulteração**, dentro do modelo de confiança. `chmod a-w` e hashes locais não impedem uma reescrita coerente por um administrador; o objetivo é impedir deriva acidental ou silenciosa e tornar detectáveis as adulterações ordinárias |
| Pendência | rotação da política de um ambiente existente: OPEN, antes da ativação operacional |
| Limitação | executado pelo runner só em ambientes 001–015 com `HOMOLOG_TESTES=h02`; ambientes 001–016 só por função extraída sobre registros reais |
| Substituição | substitui a regra pela lista em vigor da R2b-0b, sem reclassificar resultados anteriores |
