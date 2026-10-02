# R2b-1a: elegibilidade da porta publicada (Port Eligibility Preflight), para o portão antes do `ensaio-016c`

**ESTADO: PARA REVISÃO NO PORTÃO.**
- O `ensaio-016c` não foi iniciado. O `ensaio-016b` ficou exatamente como no STOP.
- O P4 continua não aprovado.

**Data:** 2026-10-01, das 23:16Z às 23:35Z.

**Evidências:** `ops/homologacao/saidas/r2b1a-20261001T231616Z/`, fora do Git.

## 1. O que mudou

**Problema (F-R2b1-1).**
- Para o `criar`, "porta livre" significava só "nenhum container publica a porta" e "nenhum listener" (`/dev/tcp`).
- No Windows, uma porta sem uso pode estar numa faixa reservada pelo sistema e recusar o bind (WSAEACCES 10013). Foi o STOP da R2b-1.
- O `concluir-criacao`, onde o gateway publica a porta, não conferia nada.

**Arquivos:**

| Arquivo | Tipo | sha256 LF | Conteúdo |
|---|---|---|---|
| `ops/homologacao/lib/porta.py` | novo (+99) | `a64c641f71ee5dad3f8adc47dbcb29135a43c79b617479840714d7dfb0751425` | `porta.py elegivel <endereço> <porta>` → `PORTA_APTA` (0) ou `PORTA_INDISPONIVEL` (1); argumentos inválidos → 3. Três conferências, cada uma com uma linha de evidência (detalhe abaixo) |
| `ops/homologacao/testes/test_porta.py` | novo (+110) | `ca8553229143ca7e9d863d99a0efbb0430106b6f232fa80c35caa15424aba927` | 17 testes sem Docker. Parser do `netsh` (português, inglês, tabela vazia, CRLF, sem tabela, lixo, faixa inválida); falha fechada (código ≠ 0, comando ausente, tempo esgotado, saída sem tabela); dentro, início, fim e fora da faixa; sockets reais só em portas efêmeras (livre aprova, ocupada reprova, a sondagem libera a porta); decisão (qualquer conferência reprova; bind reprova mesmo sem listener e fora de faixa conhecida); argumentos |
| `ops/homologacao/homologacao.sh` | +8 | `665383f0be8ddf54fa419fadb97a6a39c0bcff43038dcb494c3e8aa34540080c` | `criar`: `porta.py elegivel` depois das conferências existentes e **antes de qualquer recurso** (antes da guarda das imagens e do diretório de saída e segredos). `pos_implantacao` (`concluir-criacao`): a mesma conferência **antes de subir qualquer serviço**, gravada em `porta_antes_dos_servicos.txt`. As reservas do Windows são dinâmicas e podem surgir depois do `criar` |
| `ops/homologacao/testes/validar_config.sh` | +32 | `69f6bef2a19b5ff8328d131802e3614d1c014adcfe152cc3bd8f0f109241baae` | Seção 14, 5 verificações (§2) |

**As três conferências do `porta.py`:**
1. **listener:** conexão TCP aceita significa porta em uso.
2. **bind real:** socket exclusivo (`SO_EXCLUSIVEADDRUSE` no Windows), `bind` e `listen`, fechado em seguida. É a conferência de **maior peso**: responde se o host consegue abrir a porta agora.
3. **`netsh interface ipv4 show excludedportrange protocol=tcp`:** diagnóstico e explicação.
   - No Windows, **tabela ilegível é falha fechada**: comando ausente, código ≠ 0, tempo esgotado ou saída sem a tabela.
   - Fora do Windows, "não se aplica".

**Garantias:**
- Não cria recurso Docker e não altera reservas do sistema.
- Patch `P_patches/r2b1a-porta.patch`: aplicado sobre o estado aprovado (homologacao.sh `a6775e1e…`, validar_config.sh `75189bbe…`), reproduz os 4 arquivos byte a byte.
- Nada mudou em `implantar.sh`, migrations, `elevacao.txt` operacional nem T1–T3 e `executar.sh` (fora o `validar_config.sh`).

## 2. Provas

### 2.1 Host real, sem containers e sem alterar reservas (`T_host/`: 8 OK, 0 FALHA)

| Caso pedido no portão | Porta | Resultado | Conferências que reprovaram |
|---|---|---|---|
| porta normal disponível | 55643 (padrão do `criar`) | `PORTA_APTA` | — |
| porta ocupada | efêmera com listener real | `PORTA_INDISPONIVEL` | listener, bind |
| a mesma, depois de liberada | idem | `PORTA_APTA` | — |
| porta do STOP, reservada e sem listener | 56721 | `PORTA_INDISPONIVEL` | **bind (WinError 10013)** e excludedportrange |
| início da faixa reservada 56626–56725 | 56626 | `PORTA_INDISPONIVEL` | bind e excludedportrange |
| fim da faixa | 56725 | `PORTA_INDISPONIVEL` | bind e excludedportrange |
| imediatamente fora, acima | 56726 | `PORTA_APTA` | — |
| imediatamente fora, abaixo | 56625 | `PORTA_INDISPONIVEL` | é o fim da faixa vizinha 56526–56625 |

- **Nas portas reservadas, o bind real e a tabela reprovam de forma independente.** O bind reproduz exatamente o erro do Docker no STOP.
- Tabela de reservas idêntica no início e no fim (`netsh_inicio.txt` / `netsh_fim.txt`).

### 2.2 Testes e `validar-config`

- **`test_porta.py`: 17/17 OK** (`T_unit/`).
- **`validar-config`: 154/0** = 147 (portão R2b-0b) + 5 da seção 14 + 2 de sintaxe Python da seção 1 (`porta.py` e `test_porta.py` novos). Diferença de linhas registrada.
- **As 5 verificações da seção 14:**
  - os testes do `porta.py` passam;
  - o `porta.py` contém o bind exclusivo, a tabela e a falha fechada;
  - no `criar`, a conferência vem antes da guarda das imagens e do diretório de saída e segredos (por número de linha);
  - no `concluir-criacao`, vem antes do `compose up` dos serviços;
  - **execução real do `criar`** com uma porta reservada **sem listener** deste host (49739, limite de faixa): recusado pelo motivo "indisponível para publicação", sem deixar evidência nem segredos. Num host sem faixa reservada, a verificação registra isso e a cobertura fica com os testes.
- **Erro de harness na primeira execução, corrigido antes do resultado:**
  - A verificação real escolheu a primeira porta reservada (27339), que também tem listener ativo de um serviço do sistema.
  - O `criar` a recusou corretamente antes, por "em uso", e a verificação esperava o outro motivo.
  - Corrigido para escolher uma porta reservada sem listener, que é o caso do STOP. Execução preservada em `G_validar/validar_execucao1_erro_de_harness.txt`.

### 2.3 Controles com cópias adulteradas (`G_validar/controles_secao14.txt`: 7 de 7 reprovados)

| Cópia adulterada | Reprovada por |
|---|---|
| `criar` sem a conferência | âncoras da seção 14 |
| `concluir-criacao` sem a conferência antes dos serviços | âncoras da seção 14 |
| conferência movida para depois da criação de saída e segredos | âncoras da seção 14 |
| `porta.py` sem o bind real | testes |
| `porta.py` com falha **aberta** no `netsh` | testes |
| `porta.py` ignorando as faixas | testes, 5 falhas |
| parser que não lê faixas | testes, 10 falhas |

- Os originais passam nas mesmas conferências.
- **Um controle foi refeito:** na primeira tentativa, o `sed` não casou, a cópia ficou igual ao original e passou. Refeito com substituição exata e reprovado. Registrado no próprio arquivo.

## 3. S1″ e números para o `ensaio-016c`

**Lista fechada do S1″: 22 caminhos**, derivada do diff real. Lista com sha256 completos em `P_patches/S1duaslinhas_lista_sha256.txt`.
- Os 20 do S1′, com `homologacao.sh` e `validar_config.sh` nas versões novas (§1).
- Mais 2 novos: `ops/homologacao/lib/porta.py` e `ops/homologacao/testes/test_porta.py`.

**`validar-config` esperado no clone final do 016c: 154/0.**
- A verificação real do `criar` com porta reservada depende do host. Se o host não tiver faixa reservada acima de 1024 sem listener, ela passa registrando a ausência, e o total continua 154.

**Roteiro do `ensaio-016c`:**
- A porta escolhida passa pela própria ferramenta: o `criar` recusa se for inelegível e o `concluir-criacao` confere de novo.
- **Registro antes do `criar`:** a saída de `porta.py elegivel` para a porta escolhida e a tabela `netsh`.

## 4. Escopo

- **`ensaio-016b`:** intocado. Mesmos containers, estados, horários de início e 0 reinícios do STOP (`ensaio016b_agora.txt`). Porta não trocada, `concluir-criacao` não repetido.
- **Windows:** nenhuma reserva liberada; tabela idêntica do início ao fim.
- **Recursos:** nenhum container, rede, volume ou imagem criado. A verificação real do `criar` foi recusada antes de qualquer recurso e sem resíduo.
- **Repositório:** sem stage, commit ou push. Na árvore de trabalho entram os 4 arquivos do §1 e este relatório.

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | R2b-1a, elegibilidade da porta publicada |
| Pacote | `homologacao-016-r2b1a-20261001T232619Z.zip`, `571ec38fbb1ebae38cb94bf66b9e73f4036f05af5968c9e0f06652750dac075b` (34/34) |
| Decisão | 2026-10-01T23:33:20Z: "Aprovo o gate da R2b-1a. Podemos sair com S1″ em 22 caminhos, baseline de validar-config = 154/0 e iniciar o ensaio-016c completo do zero." Quadro: "R2b-1a / Port Eligibility Gate PASS; ensaio-016b PRESERVAR; ensaio-016c GO" |
| Escopo aprovado | `homologacao.sh` `665383f0…080c`; `validar_config.sh` `69f6bef2…1baae`; `porta.py` `a64c641f…51425`; `test_porta.py` `ca855322…ba927` |
| Substituição | `homologacao.sh` e `validar_config.sh` foram depois alterados por missões aprovadas (H02; H03/Maven/H02/WT1). `porta.py` e `test_porta.py` continuam idênticos |
