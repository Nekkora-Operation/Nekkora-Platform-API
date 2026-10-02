# R2b-0b: expectativa da janela da 014 no H02, para o portão antes da R2b-1

**ESTADO: PARA REVISÃO NO PORTÃO.**
- **A R2b-1 continua em HOLD por decisão da responsável:** "Corrigir H02 antes". Nenhum recurso dela foi criado: sem clone S1′, sem `ensaio-016b`, sem container.
- O P4 continua não aprovado.

**Data:** 2026-10-01, das 22:30Z às 22:45Z.

**Evidências:** `ops/homologacao/saidas/r2b0b-20261001T223340Z/`, fora do Git.

## 1. Bloqueio encontrado na preparação da R2b-1

**A expectativa estava fixa.** Em `ops/homologacao/testes/executar.sh` (H02, linha 167 antes da correção), as janelas concluídas esperadas eram `for v in 011 012 013 016`.

**O que aconteceria na R2b-1:**
- No `ensaio-016b`, os registros terão as janelas 011, 012, 013 (SUPERUSER), 014 (BYPASSRLS) e 016/J2 (SUPERUSER).
- O H02 compararia `'011 012 013 014 016' <> '011 012 013 016'` e daria **FALHA no passo 9**, depois da J1 e da J2 da 016.
- Pela regra da missão, a execução pararia ali, com o orçamento da 016 já consumido.

**Por que não apareceu antes:**
- A A6b (R2a-C) exercitou só `h02_janelas`, a classificação dos registros, e não a expectativa.
- O `executar.sh` não está na lista fechada da R2b-0: é idêntico ao S1 do P4.
- É a única expectativa fixa desse tipo em `ops/homologacao` (busca registrada nesta entrega).

## 2. Correção (opção A)

**Arquivos alterados:**

| Arquivo | sha256 LF antes → depois | Diff |
|---|---|---|
| `ops/homologacao/testes/executar.sh` | `474a5a20…40a3` → `5ff9deec31c4f1ce0f3040876cff86bee06bb52bc8f68549bfdbf0c5044cb9e6` | +24 −1 |
| `ops/homologacao/testes/validar_config.sh` | `d36e6e17…2415` (aprovado na R2b-0) → `75189bbed19daceb0e767407f5e929b5dcf56e51a07168972a84f7fa144386ed` | +16 −0 |

Patch: `P_patches/r2b0b-H02.patch`. Aplicado sobre o estado anterior, reproduz os 2 arquivos byte a byte.

**Regra nova: função `h02_esperadas <migrations do ambiente> <lista> <id>`.**
- Mantém 011, 012, 013 e 016 pela cópia de migrations, como antes.
- **Acrescenta a 014 só se as duas condições valerem:**
  - a cópia contém a 014;
  - a lista de elevação **em vigor** autoriza a 014 com `tipo=bypassrls`, sem vínculo ou vinculada **a este id**.
- **Lista em vigor:** `HOMOLOG_ELEVACAO_ARQ`, que o executor só aceita em `ensaio-*`; senão, `implantacao/elevacao.txt`.
- **Leitor:** o mesmo do executor (`lib/lista_elevacao.sh`).
- **Lista ilegível ou fora do formato:** código 1, e o H02 falha. Nunca equivale a "sem 014".
- **Registro:** o H02 grava em `h02_nota.txt` a lista usada, o sha256 dela e a expectativa resultante.

**O que não mudou:**
- `h02_janelas`, o tratamento das interrompidas e recuperações, a reexecução sem elevação e o caminho da versão sintética;
- `implantar.sh`, `criar`, migrations e `elevacao.txt` operacional;
- T1–T3 da R2b-0, exceto `validar_config.sh`, que só recebeu a seção 13.

**Limite registrado:** a expectativa vem da lista em vigor **no momento do teste**, não de um registro da lista usada na criação.
- Quando a linha da 014 for ativada no `elevacao.txt` operacional, ambientes criados antes disso passarão a esperar uma janela da 014 que não tiveram.
- Isso fica para a decisão da ativação operacional, que já é separada.
- **Na R2b-1, o `testar` precisa rodar com o mesmo `HOMOLOG_ELEVACAO_ARQ` usado no `criar` e no `implantar`.** Isso entra no roteiro.

## 3. Validação, sem Docker e sem ensaio

**Teste das funções extraídas** (`T_offline/h02_esperadas.sh`; execução 1: **16 OK, 0 FALHA**). `h02_esperadas` e `h02_janelas` são extraídas do próprio `executar.sh`, não copiadas à mão.

Casos sintéticos:

| Lista | Cópia de migrations | Resultado |
|---|---|---|
| operacional atual | 001–015 | `011 012 013` |
| operacional atual | 001–016 | `011 012 013 016` |
| 016 vinculada, sem 014 | 001–016 | `011 012 013 016` |
| 014 BYPASSRLS vinculada a este id | 001–016 | `011 012 013 014 016` |
| 014 vinculada a outro id | 001–016 | `011 012 013 016` |
| 014 sem vínculo | 001–015 | `011 012 013 014` |
| 014 na lista | 001–013 (sem a 014) | `011 012 013` |
| 014 `tipo=superuser` | — | recusa (a gramática não aceita) |
| fora do formato | — | recusa |
| inexistente | — | recusa |

Registros reais preservados, comparando expectativa com as janelas concluídas registradas (`h02_janelas`):

| Ambiente | Expectativa | Concluídas | Resultado |
|---|---|---|---|
| `ensaio-016a`, lista S2 da criação | `011 012 013 016` | `011 012 013 016`, 1 interrompida | igual |
| `ensaio-016a`, lista operacional de hoje | `011 012 013 016` | `011 012 013 016` | igual |
| `hml03` | `011 012 013` | `011 012 013` | igual |
| A6b `ensaio-r2ac` (014 sem vínculo, cópia 001–015) | `011 012 013 014` | `011 012 013 014` (registro da R2a-C) | igual |
| R2b-0 rodada B (parou antes da 016) | `011 012 013 014 016` | `011 012 013 014` | diferente, **como previsto**: a 016 não foi aplicada |

- **Sem regressão:** nos ambientes já ensaiados a expectativa é a mesma de antes.
- **Expectativa para a R2b-1** (lista de lab com 014 e 016 vinculadas a `ensaio-016b`, cópia 001–016): `011 012 013 014 016`.

**`validar-config`: 147/0**, os 142 da R2b-0 mais a seção 13 (5 verificações):
- o H02 usa `h02_esperadas` e não tem mais a lista fixa;
- lista sem 014 → comportamento anterior;
- 014 vinculada a este id → esperada;
- vinculada a outro id → não esperada;
- fora do formato → recusa.

**Controles com cópias adulteradas:** 4 de 4 reprovados (`G_validar/controles_secao13.txt`).
- executar.sh anterior, com a lista fixa;
- função que ignora o vínculo;
- função que exige outro tipo;
- função que ignora a recusa da lista.

**Fora do alcance desta validação:** o H02 completo pelo `testar` só é exercitado com um ambiente real com a 014. Isso é o passo 9 da R2b-1. O `ensaio-016a` não foi tocado.

## 4. Efeito na R2b-1 (para a decisão)

- **Lista fechada do S1′:** passa de 19 para **20 caminhos**, os 19 aprovados mais `ops/homologacao/testes/executar.sh` (`5ff9deec…cb9e6`).
  - O `validar_config.sh` entra na versão `75189bbe…86ed`, no lugar de `d36e6e17…2415`.
- **`validar-config` esperado no clone final:** 147/0, não 142/0.
- **Roteiro:** `testar` (passo 9) com o mesmo `HOMOLOG_ELEVACAO_ARQ` da lista de lab do S2′.
- **Restante da ordem da R2b-1:** sem mudança.

## 5. Escopo

- **Repositório:** sem stage, commit ou push. A árvore de trabalho recebeu os 2 arquivos do §2 e este relatório.
- **Recursos:** nenhum container, rede, volume ou imagem criado ou removido. `ensaio-016a`, as bases de desenvolvimento, `hml03` (parado) e os clones de lab ficam intactos.

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | R2b-0b (H02 com expectativa pela lista em vigor) |
| Pacote | `homologacao-016-r2b0b-20261001T223908Z.zip`, `33d4800044a12885d374ca7e3d028bb336e26a761dad40f545d884ea6b51c019` (21/21) |
| Decisão de origem | 2026-10-01T22:33:08Z, resposta à pergunta do executor: "Corrigir H02 antes (Recomendado)" |
| Decisão | 2026-10-01T22:50:28Z: "Aprovo o novo portão. A R2b-1 pode começar com S1′ em 20 caminhos e com validar-config esperado em 147/0." Quadro: "R2b-0b / H02 gate PASS" |
| Escopo aprovado | `executar.sh` `5ff9deec…cb9e6`; `validar_config.sh` `75189bbe…86ed`; `testar` com o mesmo `HOMOLOG_ELEVACAO_ARQ` do S2′ (obrigatório) |
| Limitação mantida | a expectativa dependia da lista vigente: "finding conhecido e não bloqueante" |
| Substituição | a regra pela lista em vigor foi **substituída** pela fotografia histórica (missão H02 × lista histórica, aprovada em 2026-10-02T12:16:45Z). Os resultados obtidos com a regra anterior (H02 OK no `ensaio-016c`) **não** são reclassificados |
