# WIN-TRANSPORT-0: investigação e reprodução controlada da falha de transporte da caixa no Windows

**ESTADO: PARA REVISÃO.**
- Missão de investigação. **Nenhuma correção de produto.**
- Se a classificação for aceita, a correção vem na WIN-TRANSPORT-1, com proposta e contraprova.
- O achado histórico continua **OPEN**. Esta missão não o encerra; uma reprodução bem-sucedida não apaga a primeira falha.

**Data:** 2026-10-02.

**Evidências:** `ops/homologacao/saidas/wintransport0-20261002T121716Z/`, fora do Git.

## 1. Primeira falha, congelada (`A_historico/`)

**Onde:** regressão Maven da R2a-C, execução 2, de 2026-10-01T19:05:14Z a 19:20:48Z, código 1. Original em `ops/homologacao/saidas/p4r2ac-20261001T172523Z/R_regressao/execucao2_flaky_platform/`.

**Preservação:**
- O original **não foi alterado**: `maven.txt` sha256 `6022e37b0cff6290…`, mesmo horário de modificação, conferido no fim.
- Cópias e sha256 dos três arquivos estão em `A_historico/`.

**Teste e erro:**
- Teste: `CaixaHomologacaoMailSenderIT.escritorExternoETransportesConcorrentesNaoPerdemNemDuplicamRegras`. Ele roda dois transportes e um escritor externo com o protocolo da ferramenta (`mkdir` da trava, anexa, `rmdir`), em paralelo.
- Exceção: `java.nio.file.AccessDeniedException: …\nekkora-caixa-p1c-…\controle.trava`.
- Caminho: `WindowsFileSystemProvider.createDirectory` ← `Files.createDirectory` ← **`CaixaHomologacaoMailSender.desfecho(CaixaHomologacaoMailSender.java:192)`** ← `sendEmergencyNotice(:97)`.

## 2. Mapa do caminho de execução (`B_mapa`, sobre o código atual, sem alteração)

| Etapa | Onde | Operação | Semântica |
|---|---|---|---|
| Aquisição da trava | `desfecho()`, linha 192 | `Files.createDirectory(caixa/controle.trava)` em laço até 5 s | NIO; no Windows, `CreateDirectoryW` |
| Contenção tratada como "ocupada" | linha 194 | **só** `FileAlreadyExistsException` (espera 20 ms e tenta de novo) | — |
| Qualquer outra `IOException` | linha 206 | `UncheckedIOException`: **falha a chamada** | — |
| Leitura e consumo da regra | linhas 214 a 234 | `readAllLines`, `write CREATE_NEW` em `.tmp`, `move ATOMIC_MOVE` sobre `controle.conf` | NIO; *rename* com substituição |
| Liberação | `finally`, linha 244 | `Files.deleteIfExists(trava)`; falha só gera log | NIO; `RemoveDirectoryW` |
| Serialização interna | `synchronized (monitor)` | só **dentro da mesma instância** do transporte | duas instâncias (ou a ferramenta) disputam pelo sistema de arquivos |
| Ferramenta do runner | `homologacao.sh caixa controle` | `mkdir /caixa/controle.trava && … rmdir` **dentro de um container Linux** | semântica POSIX do volume, não do NTFS |
| Escritor externo do IT | `CaixaHomologacaoMailSenderIT`, linhas 370 a 393 | mesmo protocolo em Java; **também só** trata `FileAlreadyExistsException` | NIO no Windows |

## 3. Harness de concorrência controlada

`ops/homologacao/testes/wintransport/`: `Harness.java`, `rodar.sh` e `correlacao.py`.

**Como funciona:**
- Compila, num diretório temporário, **cópias verbatim** de `MailSender.java` e `CaixaHomologacaoMailSender.java`, com o sha256 registrado em cada `contexto.txt`: `2d81e52d…aeeb`, idêntico à árvore. Usa o `slf4j-api` do cache local.
- Chama o **`desfecho()` real**.
- Não usa Docker nem Maven, não toca `api/*/target` nem nenhum ambiente.
- Cada caso roda num diretório temporário exclusivo, removido ao fim (0 restantes).
- **Cada tentativa** é gravada em CSV: caso, rodada, ator, tentativa, início, duração, resultado e exceção com a **origem** (`método:linha` do transporte).

**Casos:**

| Caso | O que faz |
|---|---|
| P1 | NIO puro, duas threads criando e apagando a mesma pasta |
| P2 | NIO contra um **processo** Python criando e apagando |
| H1 | um transporte, sequencial |
| H2 | **dois transportes na mesma caixa**, com barreira |
| H3 | dois transportes em caixas **diferentes** |
| H4 | dois transportes + escritor externo em **thread**, o protocolo do IT |
| H5 | dois transportes + escritor externo em **outro processo** |

## 4. Matriz de reproduções (`C_harness/matriz.txt` e `resumo.csv` de cada execução)

**Execuções que valem** (5 a 7, com o harness final):

| Execução | Escala | Resultado relevante |
|---|---|---|
| 5 | 5 × 400, todos os casos (7 de 7 com código 0) | P1: 240 `AccessDenied` em `Files.createDirectory`. P2: 20 (Java) e 221 `PermissionError` (Python). **H1 e H3: 0.** H2: 1. H4 e H5: 0 nessa escala |
| 6 | 40 × 1.000, H4/H5 | **H4: 41 `AccessDeniedException@desfecho:192`** (~80 mil chamadas), mais 2 no escritor. **H5: 17** (~80 mil), mais 7 `PermissionError` no Python |
| 7 | 40 × 1.000, H2/H4, com origem e liberações registradas | **H2: 16**, sem escritor nenhum, só os dois transportes. **H4: 16.** **Todas com origem `desfecho:192`**, o mesmo ponto do stack histórico |

- **Taxa observada no `desfecho()`:** ~1 a 2,5 por 10 mil chamadas sob contenção.
- **H1 e H3, sem disputa da mesma trava:** 0.
- É compatível com uma falha intermitente rara num IT de 60 envios contra 20 regras.

**Correlação temporal** (`correlacao.py`, execução 7, H4):
- **31,2%** das chamadas com exceção (5 de 16) se sobrepõem a uma liberação da trava pelo escritor.
- Entre as 79.984 chamadas sem exceção, a taxa é **0,3%** (acaso).

**Execuções 1 a 4**, preservadas em `C_harness_v1_nomes_temporarios/` e **fora do pacote**: mostraram o mesmo quadro (P1/P2 reproduzem; H2 6, H4 23/13, H5 13; origem `desfecho:192`; correlação 38,5% × 0,3%). Ficaram de fora por dois motivos:
- a execução 1 tinha um erro de harness: o construtor do transporte exige ambiente `ensaio-*` e eu passei `"harness"`, então H1 a H5 não rodaram. A guarda é do produto e está correta;
- os nomes de diretório temporário do harness da v1 (`nekkora-wintransport-<caso>-<19 dígitos>`) formavam por coincidência palavras de 43 caracteres base64url com maiúscula, o formato de token da v1. A varredura do pacote os acusou corretamente (§6).
- As demais exceções são compatíveis com a liberação feita pelo **outro transporte**, que o harness não instrumenta por estar dentro do produto. Isso fecha com a reprodução do H2 sem escritor.

## 5. Classificação

**Causa confirmada no Windows:**

> A trava é um diretório usado como mutex (criar = adquirir, apagar = liberar). No Windows, um `CreateDirectory` que coincide com a remoção do mesmo diretório por **outro ator** pode falhar com **`ERROR_ACCESS_DENIED`** (exclusão pendente), que o Java traduz em `AccessDeniedException`, e não em `FileAlreadyExistsException`.
>
> O `desfecho()` trata como "ocupada" **só** `FileAlreadyExistsException`. Qualquer outra exceção na aquisição é **fatal para a chamada**: `UncheckedIOException` → falha do envio.

**O que a sustenta:**
- reprodução direta da primitiva (P1, P2);
- reprodução com o código real no **mesmo ponto** do stack histórico (`desfecho:192`);
- presença **só sob disputa da mesma trava**: H2, H4, H5 sim; H1, H3 não;
- correlação temporal com as liberações;
- o lado Python reproduz a mesma semântica entre processos (`PermissionError` = `EACCES`).

**Na taxonomia pedida:**
- **semântica Windows de delete/create** de diretório;
- **somada a** um tratamento incompleto da contenção no ciclo de vida da trava: só um tipo de "ocupada" é reconhecido.

Não é *cleanup* concorrente de outro tipo nem corrupção de dados: nas execuções, as regras não se perdem; as chamadas que falharam terminam em exceção antes de ler o controle.

**Também identificado:** o escritor externo do próprio IT tem o mesmo tratamento parcial (6 `AccessDenied` nele na execução 3). O teste pode falhar pelos dois lados.

**Não testado nesta missão (sem Docker), portanto INCONCLUSIVO:**
- se a mesma falha ocorre no ambiente de homologação real, onde transporte e ferramenta rodam em containers Linux sobre volume Docker, com semântica POSIX de `mkdir`/`rmdir`;
- a evidência atual é só do sistema de arquivos do Windows (NTFS do host, em `%TEMP%`).

## 6. Entrega e escopo

- **Arquivos novos:** só instrumentação de teste em `ops/homologacao/testes/wintransport/`. Diff fechado em `P_patches/wintransport0.patch`, que reproduz os 3 arquivos (sha256 em `P_patches/sha256.txt`):
  - `Harness.java` `c81e5065…35f0` (prefixo temporário com pontos, que nunca forma palavra no formato de token)
  - `rodar.sh` `dd858d82…bfe3`
  - `correlacao.py` `354a012b…1915`
- **Produto não alterado:** `CaixaHomologacaoMailSender.java` `2d81e52d…aeeb`.
- **`validar-config`: 177/0, sem mudança.** O harness fica fora das seções automáticas. Se a WIN-TRANSPORT-1 o promover a teste de regressão, as verificações entram lá.
- **Maven:** não foi usado.
- **Inventário B12 início × fim:** idêntico.
- **Não tocados:** `ensaio-016a/016b/016c`, `hml03`, 014/016 e `elevacao.txt`.
- **Repositório:** sem stage, commit ou push.
- **Pacote anterior descartado:**
  - A 1ª montagem (`homologacao-016-wintransport0-20261002T123117Z`) teve **4 achados** na varredura de tokens. Eram os nomes temporários da v1, não segredos.
  - O controle positivo detectou 200 de 200 tokens e 5 de 5 marcadores, mas marcou FALHOU pela linha de base não vazia.
  - O pacote não foi entregue e foi renomeado para `.DESCARTADO-falso-positivo-varredura`, sem apagar.
  - A varredura aprovada **não** foi alterada.
  - O harness foi corrigido e as execuções que contam foram refeitas.

**Para a WIN-TRANSPORT-1 (só proposta, nada implementado):**
- tratar a aquisição da trava como **ocupada também para a exclusão pendente**, com espera limitada pelo mesmo prazo, sem mascarar outras falhas de E/S;
- **ou** trocar o mutex por um primitivo sem a janela de exclusão pendente;
- em qualquer dos dois, ajustar o escritor do IT de forma coerente.
- A contraprova seria o mesmo harness, H2/H4/H5 em escala, com 0 exceções, mais P1/P2 continuando a mostrar a semântica do Windows.
- A decisão de como distinguir "pendente" de "acesso negado de verdade", por exemplo permissões da caixa, fica para essa missão.

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | WIN-TRANSPORT-0 (investigação) |
| Pacote | `homologacao-016-wintransport0-20261002T123828Z.zip`, `98d5b7cbba29b70f7f65cdf7b4b1c5844038abcd98a4f5ed83478a8a18e5c7a7` (109/109) |
| Decisão | 2026-10-02T12:55:26Z: "Aprovo a WIN-TRANSPORT-0. PASS." Finding "OPEN" até a correção; produto "ainda não corrigido"; o fenômeno em containers Linux "permanece corretamente INCONCLUSIVA" |
| Pacote descartado | `…123117Z.DESCARTADO-falso-positivo-varredura.zip` (`ba7ce5ae…d415`): falso positivo aceito, porque a solução foi mudar os nomes temporários e refazer as execuções, "não enfraquecer o scanner" |
| Desfecho posterior | finding CLOSED pela WIN-TRANSPORT-1 (2026-10-02T14:00:38Z) |
