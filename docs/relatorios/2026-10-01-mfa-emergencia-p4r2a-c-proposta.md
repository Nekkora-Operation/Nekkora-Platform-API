# P4-R2a-C: mini missão corretiva (janela BYPASSRLS da 014 e bootstrap de desenvolvimento), proposta v2

**ESTADO: PROPOSTA v2, NÃO EXECUTADA.** Incorpora os ajustes da revisão de 2026-10-01 à v1.

**Base:**
- pacote R2a `homologacao-016-p4r2a-20261001T165554Z.zip` (`102a66c2…41c1`), preservado;
- 016 candidata `f37d9841…3732`, mantida.

**Fora desta missão:**
- P4, que continua não aprovado;
- R2b (`ensaio-016b`);
- P5;
- ativação operacional de qualquer linha de `elevacao.txt`;
- commit e push.

## 1. Pontos de partida (estáticos, conferidos)

- Na 014, só tocam linhas sob RLS forçada:
  - o `UPDATE user_sessions SET revoked_at …` da ativação (linha 252);
  - o `SELECT … FROM public.user_sessions` do invariante da §8.
- O invariante da 014 que recusa BYPASSRLS só examina `v_protegidos` (papéis `nekkora_*`); `migrador_nekkora` não está na lista. A2 tem de provar na prática.
- `estado_migrador` já soma `rolbypassrls` nos 5 atributos.
- Hoje `elevacao.txt` é `VVV <sha256> [<id>]`, em que o terceiro campo é o **id do ambiente** e o tipo implícito é SUPERUSER.

## 2. Frente A: janela BYPASSRLS só para a 014

**Escopo de elevação nas bases de teste:**
- SUPERUSER **só** em 011, 012, 013 e 016;
- BYPASSRLS **só** na 014;
- nenhuma outra combinação.

Toda base é descartável (prefixo `p4r2ac`), removida por ID com `rm -v`, com inventário de início e fim.

**A1: o que a 014 precisa atravessar.** Base **P1**, populada até 013 (massa `p01`/`p02`), recebe a 014 aplicada pelo migrador **sem** elevação. Registra:
- linhas afetadas pelo `UPDATE` (esperado 0);
- resultado do `SELECT` do invariante como migrador (esperado cego);
- RLS e FORCE de cada tabela tocada pela 014.

Saída: lista fechada das operações que exigem atravessar RLS. A P1 fica como **contraprova do caminho antigo**: não recebe a 014 de novo.

**A2: BYPASSRLS sozinho basta.** Base **P2**, outra, **equivalente** à P1: mesma massa, mesma semente e impressão digital de dados até 013 igual à da P1, conferida antes. Recebe a 014 com `migrador_nekkora BYPASSRLS` sem SUPERUSER, só durante a 014. Conferir que:
- a 014 aplica;
- o invariante dela enxerga as linhas;
- nenhuma reaplicação ocorreu (cada base aplica a 014 uma vez).

**A3: efeito exato da 014.** As mudanças esperadas são definidas **antes** a partir do texto da 014: objetos de catálogo, linhas em `permissions` e `role_permissions`, colunas, triggers, ACLs e a revogação das sessões.
- **Catálogo:** diff P2 (antes × depois) = conjunto esperado. Diff P1 × P2 depois da 014 = só os efeitos da ativação.
- **Dados:** instantâneo por tabela antes e depois. Só mudam as tabelas e linhas previstas. Em `permissions`/`role_permissions`, exatamente as linhas inseridas pela 014.
- **Sessões:** exatamente as 480 que estavam ativas sem marcador passam a revogadas, conferidas por id contra a lista tirada antes. Nas 480, só `revoked_at` muda (demais campos idênticos). Nas outras linhas de `user_sessions`, nada muda.
- Toda contagem e todo instantâneo são feitos pelo **administrador superusuário** (enxerga todas as linhas).

**A4: retirada do BYPASSRLS em todo desfecho.**

| Desfecho | Como | Esperado |
|---|---|---|
| (a) sucesso | aplicação normal | `0|0|0` |
| (b) falha dentro da janela | cópia da 014 com erro no fim, só na base descartável | transação revertida, retirada pela saída de erro, `0|0|0` |
| (c) interrupção abrupta | `kill -9` do processo que aplica, com a janela aberta | Não há `trap` que cubra. A prova é a **detecção** do resíduo (estado `≠ 0|0|0`, base BLOQUEADA no runner) e a **recuperação** que retira o BYPASSRLS e confere `0|0|0`; nas suítes, a próxima execução recusa começar com resíduo |

**Depois de toda retirada:** conferência das sessões **pelo administrador** (contagens e lista por id). O `SELECT` cego como migrador não vale como prova.

**A5: suíte da 016 completa no modelo.** `db/tests/mfa-emergencia-016/run.sh` com o script do modelo estendido para a janela BYPASSRLS da 014, **sem** `TESTE_ELEVAR`. Esperado:
- A e B OK;
- invariantes 012/013/014/016 após a retirada;
- `estado_migrador 0|0|0` em cada fase.

Regressão Maven completa de novo (as bases Java passam pela 014 na base vazia; a janela não pode quebrar).

**A6: gramática inequívoca da lista de elevação** (proposta; a lista operacional não muda).

```
linha     := VVV SP SHA256                          ; legado: SUPERUSER, sem vinculo
           | VVV SP SHA256 SP ID                     ; legado: SUPERUSER, vinculado ao ambiente ID
           | VVV SP SHA256 (SP CHAVE "=" VALOR)+     ; forma nova
CHAVE     := "tipo" | "ambiente"
tipo      := "superuser" | "bypassrls"               ; obrigatorio na forma nova
ambiente  := id ensaio-* (mesmo formato atual)
```

Regras: o terceiro campo é legado (id) **se e só se** não contém `=`. As linhas antigas continuam válidas e com o mesmo significado. Recusas:
- chave desconhecida ou repetida;
- forma nova sem `tipo`;
- mistura de id posicional com chaves;
- tipo desconhecido;
- versão repetida;
- sha inválido;
- **014 com tipo ≠ `bypassrls`**;
- **016 com tipo ≠ `superuser`**;
- **016 sem `ambiente`**: continua obrigatoriamente vinculada ao ambiente exato **e** ao pré-voo oficial (`preflight/016.sql`), como hoje.

A linha proposta da 014 (`014 <sha> tipo=bypassrls`, com ou sem `ambiente=`) só em **patch separado e listas de laboratório**.

Testes no `validar_config.sh`:
- a lista operacional continua aprovada pelo hash atual;
- cada recusa acima;
- legado posicional;
- forma nova válida.

**A6b: laboratório com o runner real, incluído nesta missão.** Ambiente descartável `ensaio-r2ac`, lista de laboratório com a linha nova da 014, **sem janela da 016** (`criar` até 015, ou parada antes da 016):

| Prova | Esperado |
|---|---|
| janela real da 014 | `inicio versao=014 BYPASSRLS concedido` e `fim … BYPASSRLS revogado … 0|0|0` em `janelas_de_elevacao.txt` |
| interrupção abrupta com a janela aberta | `kill -9` no dono da trava, como a J1 do P4 → BLOQUEADO; `testar`/`concluir` recusados; `recuperar` retira o BYPASSRLS e devolve `0|0|0` |
| H02 | consome os registros: janela concluída, interrompida e com recuperação própria, classificadas sem erro |
| reconciliação | 014 não registrada na interrupção; reexecução aplica a 014 uma vez |

O ambiente é removido ao final por ID e inventário.

## 3. Frente B: bases onde a 014 já consta como aplicada (separada; depende de autorização própria)

**Somente leitura**, em `BEGIN TRANSACTION READ ONLY`, por identidade que enxergue todas as linhas. Saída: classificação **afetada (N sessões) / não afetada / INCONCLUSIVA**.

**Limite declarado:**
- `installed_by` e os atributos **atuais** do papel não provam com quais privilégios a 014 rodou;
- sem log ou outra evidência histórica, "quem aplicou e com que privilégio" fica **INCONCLUSIVO**, e a hipótese de superusuário **não** vira fato;
- a contagem de sessões ativas sem marcador anteriores à 014 é a evidência direta;
- se houver sessões afetadas, a base é afetada independentemente de quem aplicou.

| Base | Situação | Observação |
|---|---|---|
| `ensaio-016a` | **Exceção explícita de leitura**, a confirmar: consulta READ ONLY pelo `admin_cluster` no caminho do runner, como na P4-R1 | Nenhum outro uso; preservado |
| Base principal, `portal-dev`, `portal-013` | Postgres no ar | **Leitura só com autorização** (recursos protegidos) |
| `hml03` | Parado | Ler exige **iniciar** o Postgres, o que muda o inventário. Só com autorização explícita e registro (estado antes, start, estado depois). Sem autorização: **PENDENTE**. A e C não dependem dele |
| Produção | Fora do alcance | O operador informa |

Base afetada leva a uma **proposta** de correção própria (ex.: migration corretiva idempotente com invariante), sem `repair`, reaplicação informal ou `UPDATE` manual.

## 4. Frente C: bootstrap de desenvolvimento no modelo

**Revisão do script** como ferramenta de desenvolvimento:
- configuração por variável com padrões explícitos;
- falha aborta o initdb (código ≠ 0);
- retirada da elevação na falha (saída de erro);
- interrupção abrupta coberta por **marcador de inicialização incompleta** (abaixo);
- mensagens e registro.

| # | Prova | Esperado |
|---|---|---|
| C1 | Volume novo, compose descartável `p4r2ac-dev`, nunca a stack principal | 001–016 no modelo (com a janela BYPASSRLS da 014), `0|0|0`, §19 pós-retirada |
| C2 | Reinício com o mesmo volume, inicializado por completo | Nenhuma linha `aplicar_como_migrador`; histórico, catálogo e `0|0|0` idênticos |
| C3 | Falha no meio, na cópia descartável | initdb aborta; migrador `0|0|0`; container sai com código ≠ 0 |
| **C4** | **Reinício de volume PARCIALMENTE inicializado** (depois de C3 e depois de `kill -9` no meio do initdb) | O entrypoint não roda `initdb.d` de novo e o Postgres subiria com uma base incompleta. **Não pode ser aceito como instalação válida.** Proposta: o script grava um marcador "inicializacao completa" (ex.: tabela/comentário de catálogo ou arquivo no volume) só no fim do sucesso, e uma verificação de saúde ou de início recusa o volume sem o marcador. Provar a recusa nos dois casos e a aceitação no C2 |
| C5 | Stack principal | Mudança **só** no `docker-compose.yml`, depois de C1–C4. A base principal existente não é recriada nem tocada |

## 5. Autorizações pedidas

| # | Item | Proposta |
|---|---|---|
| 1 | Bases e compose descartáveis `p4r2ac-*`; elevação nelas: SUPERUSER **só** 011/012/013/016, BYPASSRLS **só** 014 | A1–A5, C1–C4 |
| 2 | Árvore de trabalho, sem stage: script do modelo, `run.sh` da 016, `implantar.sh`/`recuperar`/`estado.sh`/H02 se preciso, `validar_config.sh`; `docker-compose.yml` só depois de C1–C4 | A5, A6, C5 |
| 3 | Linha `014 … tipo=bypassrls` **só** em patch e listas de laboratório; lista operacional intacta | A6, A6b |
| 4 | Laboratório com o runner real `ensaio-r2ac`, **sem janela da 016** | A6b |
| 5 | Frente B, **separada**: (5a) leitura READ ONLY do `ensaio-016a` (exceção) e das bases no ar (principal, `portal-dev`, `portal-013`); (5b) iniciar o Postgres do `hml03` | Sem 5a/5b: a frente B fica pendente; A e C seguem |

**Critérios de aceite:**
- A1–A6b e C1–C5 provados;
- suíte da 016 com A e B OK no modelo, sem opção diagnóstica;
- Maven sem regressão;
- `validar-config` com contagem explicada;
- inventário início × fim sem diferença em protegidos;
- pacote com manifesto, varredura e controle positivo;
- frente B classificada ou registrada como pendente.
