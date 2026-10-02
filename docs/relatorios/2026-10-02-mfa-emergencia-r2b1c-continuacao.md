# R2b-1c, continuação autorizada após o STOP: `ensaio-016c`, passos 10–15

**ESTADO: PARA REVISÃO DA APROVAÇÃO DA R2b-1 E DO P4.**
- Os passos 10–15 fecharam com os resultados esperados.
- O STOP do passo 9 continua registrado como ocorreu (`2026-10-01-mfa-emergencia-r2b1c.md`, pacote `homologacao-016-r2b1c-20261001T235410Z.zip`). Este documento não o reclassifica.

**Data:** 2026-10-02, das 00:28Z às 00:40Z.

**Autorização:** continuação do mesmo `ensaio-016c` a partir do passo 10, sem repetir passos já executados. `testar` não foi rodado de novo; H03 não foi corrigido nem marcado OK.

**Evidências:** `ops/homologacao/saidas/r2b1c-20261001T233339Z/`, subdiretórios `10_h016/` a `14_encerramento/`.

## 1. Fotografia de retomada (somente leitura, antes do passo 10)

Igual ao estado do STOP (`10_h016/fotografia_retomada.txt`):
- LIBERADO; Flyway 001–016; `flyway validate` OK.
- Migrador `0|0|0`, 5 atributos `false`, 0 participações, 0 ACL de parâmetro, 0 sessões.
- Janelas da 016: 2 inícios e 1 fim (J1 interrompida, J2 concluída). O último registro de implantação continua sendo o da J2, logo **não há J3**.
- S2″ `e01884f` e lista de lab `3d3121c6…d940` inalterados.
- Container temporário do H03: 0. Sem trava local.
- Containers do `ensaio-016c`: todos iniciados antes do STOP, 0 reinícios. O Redis tem início às 23:49Z, reiniciado pelo H07 dentro do `testar`, antes do STOP.
- Só o diretório de testes do `testar` original. Nada mudou entre o STOP e a retomada.

## 2. Resultados dos passos 10–15

| Passo | Resultado | Evidência |
|---|---|---|
| 10. `preparar-016` | **OK, código 0.** `[PREP] OK`: organização sintética, operadores (bootstrap + 3 cadastrados e habilitados), 2 contatos, MFA dos 2 ORG_ADMIN pelo gateway. `[SEGREDOS] OK` (36 valores) | `10_h016/preparar*.txt` |
| 11. `testar-016` (`H016_B8B=1`) | **OK, código 0** (detalhe abaixo) | `10_h016/testar016*.txt`, `10_h016/registros/` |
| 12. `validar-config` final | **154/0** no clone (S2″, `autocrlf=false`), incluindo a verificação real do `criar` com porta reservada (49739). Repetido no S3″: **154/0** | `11_validar/`, `14_encerramento/` |
| 13. Maven | **C2 (256/0) reaproveitada com justificativa por hash** (detalhe abaixo) | `12_maven/` |
| 14. S3″ | **OK.** `fbd5cdb92a7b04ef78e8cdc123819ef5d3c6f07c` retira as 2 linhas temporárias (014 e 016) da lista de lab; ficam 011–013. `elevacao.txt` operacional intacto no clone (sem diff desde S1) e no original: `7fd92fae…` | `14_encerramento/S3duas.txt` |
| 15. Encerramento | **OK.** Migrador: 5 atributos `false`, 0 participações, 0 ACL de parâmetro, 0 sessões, `0|0|0`. LIBERADO. 31 sessões, 0 não revogadas sem marcador. **B12 início × fim OK**, idêntico por ID fora do ambiente. SEGREDOS OK no `preparar-016` (36) e no `testar-016` (59). Pacote varrido (§5) | `14_encerramento/` |

**Passo 11, `testar-016`, caso a caso:**
- **B5 OK:** §19 da 016 no ambiente depois da retirada; 37 funções `ops_*`, 21 tabelas em `ops`, 9 políticas `ops_function_context`. **É o caso que encerrou o P4 em FALHA e agora passa.**
- **B6 OK:** CA operacional aceita; senha e CN diferente recusados; CA estranha recusada no TLS.
- **B7 OK.**
- **B8a OK.**
- **B8b OK:** fixture declarada, aprovação, execução e novo MFA do titular pelo gateway.
- **B12 OK:** inventário completo por ID idêntico antes e depois. Sem o erro de template do P4.
- **B10 PARCIAL** e **B11 PARCIAL**, nos limites já declarados: varredura sem gatilho no ambiente; restauração num segundo cluster pendente.
- **B4:** sem caso próprio na suíte; segue o limite já documentado.
- **B9:** fora do ensaio.
- **SEGREDOS OK** (59 valores).

**Passo 13, Maven: justificativa por hash.** A C2 não registrou manifesto das fontes, só a janela 19:51:30Z → 20:03:36Z. A equivalência foi composta assim (`12_maven/maven_reaproveitamento.txt`):
- **(a)** Os 150 arquivos relevantes (poms, wrapper, `api/*/src/**`, `db/migrations/*.sql`, `db/bootstrap/aplicar_como_migrador.sh`) são iguais, byte a byte LF, entre o clone e a árvore de trabalho.
- **(b)** 146 são idênticos ao S1 do P4. Os 4 diferentes são `RealDatabase.java`, `OpsBanco.java`, `aplicar_como_migrador.sh` e a 016 rev5, todos nas versões aprovadas.
- **(c)** Os dois `.java` são o lado "depois" do diff da própria C2: o reverso aplica exatamente.
- **(d)** Nenhum arquivo relevante da árvore foi modificado depois do início da C2.
- Manifesto `hashes_clone_arvore_S1.txt`, sha256 `bfa8dfd506541b58bbaa4af208d55cc39ff2c0a1df3ebc27659fe86512e444a5`.

## 3. Situação do ensaio integrado (passos 1–15)

| Critério de PASS da ordem R2b-1 | Situação |
|---|---|
| 014: semeadura comprovada e revogação correta | **OK.** T3 20/20, uma janela BYPASSRLS (passo 5, relatório do STOP) |
| 016: J1 interrompida, recuperação limpa, J2 concluída | **OK.** Catálogo e papéis idênticos após a J1 (passos 7–8) |
| Janelas: 5 concluídas + 1 interrompida | **OK no H02 real**: 011, 012, 013, 014 (BYPASSRLS), 016/J2; J1 recuperada pela própria recuperação |
| Flyway 001–016 | **OK** |
| Migrador sem privilégio residual | **OK** (passo 15) |
| §19 depois da retirada | **OK** (passo 8, `READ ONLY`) e B5 OK (passo 11) |
| T3 20/20 | **OK** |
| `validar-config` 154/0 | **OK** (S1″, S2″ e S3″) |
| B12 sem vazamento | **OK** (início × STOP e início × fim) |
| Segredos fora do pacote | **OK** (§5) |
| Ambiente preservado | **OK** |

## 4. Achados

1. **F-R2b1c-1: H03 incompatível com o modelo 016.**
   - Status: **OPEN, ferramenta.**
   - Impacto no ensaio: **não bloqueante após revisão de escopo.**
   - O H03 atual não é evidência válida para ambientes pós-016, porque suas premissas representam o modelo 001–015: referência instalada por superusuário, invariante da 014 com uma única política, lista fixa de papéis com senha.
   - O resultado do H03 neste ensaio continua sendo **FALHA**, registrado no STOP.
   - O P4 não executou o H03 nesse cenário: rodou só H02. Isso não prova nada sobre a aplicabilidade do H03; a conclusão acima é desta revisão.
   - A adaptação do H03 à 016 fica como missão de ferramenta separada.
2. **Nota de escopo:** a ordem da R2b-1 dizia "testar" sem filtro, o que ampliou o escopo em relação ao baseline do P4 (`HOMOLOG_TESTES=h02`). Registrado no STOP; corrigido por decisão de continuação.
3. **H02 pela lista em vigor:** achado conhecido e não bloqueante desde o portão R2b-0b. Deve ser decidido antes da ativação operacional da 014.
4. **Transporte da caixa em Windows:** fora do escopo; continua aberto.
5. **Validados no ensaio real:**
   - pré-voo da 014 (T1), semeadura (T2) e conferência (T3);
   - H02 com a expectativa pela lista;
   - elegibilidade de porta no `criar` e antes dos serviços.

## 5. Inventário preservado e escopo

- **`ensaio-016c`, LIBERADO:**
  - containers `gateway`, `platform`, `portal`, `postgres` e `redis`;
  - volumes `_caixa`, `_operadores`, `_pgdata` e 1 anônimo do Redis;
  - redes `_borda` e `_interna`;
  - imagens `d9b39c1`;
  - segredos em `~/.nekkora/homologacao/ensaio-016c`.
- **Clone** `../lab-r2b1c-20261001T233433Z/nekkora-platform`: S1″ `d9b39c1`, S2″ `e01884f`, S3″ `fbd5cdb` (HEAD); sem remoto; `autocrlf=false`; `git status` limpo.
- **`ensaio-016b`:** idêntico ao início desta missão. Preservado até a revisão.
- **Outros preservados:** `ensaio-016a`, `hml03` (parado), clones anteriores, imagens, evidências e bases de desenvolvimento.
- **Repositório original:**
  - sem stage, commit ou push;
  - `elevacao.txt` operacional `7fd92fae…`, 014 `5e5726b9…` e 016 `f37d9841…` inalterados;
  - na árvore de trabalho, só este relatório é novo nesta continuação.

## 6. Adendo: decisão formal da revisão

*Acrescentado em 2026-10-02, depois da entrega. O corpo histórico acima (§1–§5) não foi alterado. Este adendo prevalece apenas quanto ao estado e à decisão posteriores à revisão. O pacote `homologacao-016-r2b1c-final-20261002T003939Z.zip` (`944eca58…d332`) continua sendo a evidência imutável daquele momento.*

**Decisão posterior à entrega:** a R2b-1 e o P4 estão **APROVADOS (PASS)**. A base é o fechamento integrado do `ensaio-016c` e os critérios registrados neste relatório e no relatório do STOP (`2026-10-01-mfa-emergencia-r2b1c.md`).

**O STOP continua como ocorreu:**
- A aprovação não reclassifica nem apaga o STOP do passo 9.
- O resultado **H03 FALHA** permanece preservado como ocorreu.
- O achado **F-R2b1c-1** continua **OPEN**, classificado como dívida de ferramenta não bloqueante após a revisão de escopo. O H03 atual representa premissas do modelo 001–015 e não constitui evidência válida para ambientes pós-016.

**Esta aprovação não autoriza:**
- a frente 5b ou qualquer ação no `hml03`;
- o P5;
- a ativação operacional das migrations 014/016;
- alteração do `elevacao.txt` operacional;
- stage, commit ou push;
- descarte dos ambientes, clones ou evidências preservados.

**Permanecem preservados e inalterados:**
- `ensaio-016c`, `ensaio-016b`, `ensaio-016a`;
- `hml03`, parado;
- clones de laboratório, imagens e evidências;
- o `elevacao.txt` operacional, a migration 014 e a migration 016 rev5.

**Pendências abertas após o P4:**
1. **H03 / F-R2b1c-1:** adaptar o teste ao modelo pós-016, mantendo intacta a FALHA histórica do `ensaio-016c`.
2. **H02 × lista vigente:** decidir, antes da ativação operacional da 014, se a expectativa será vinculada à lista histórica usada pelo ambiente ou se o comportamento atual será política aceita.
3. **Maven:** registrar manifesto e hash das fontes no próprio instante das futuras regressões.
4. **Transporte em Windows:** investigar separadamente a falha intermitente ainda aberta.
5. **5b, P5 e ativação operacional da 014/016:** exigem autorizações independentes.
6. **Descarte:** `ensaio-016b`, `ensaio-016c` e os clones permanecem preservados até decisão explícita posterior.

**Ordem indicada para as próximas missões:**
1. H03;
2. decisão sobre o H02 e a lista histórica;
3. transporte em Windows;
4. 5b/`hml03`;
5. P5;
6. portão de ativação da 014/016.

O manifesto de fontes do Maven pode entrar cedo, como missão curta de ferramenta.

**Estado oficial após esta revisão: R2b-1 = PASS; P4 = PASS.** Nenhuma outra fase é autorizada por este adendo.
