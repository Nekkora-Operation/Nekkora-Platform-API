# P4-R2a-C: janela BYPASSRLS da 014, runner real e bootstrap de desenvolvimento

**ESTADO: PARA REVISÃO.** O P4 continua não aprovado; R2b, P5, ativação operacional e commit/push ficam fora.

**Data:** 2026-10-01, das 17:25Z às 19:36Z.

**Autorização:**
- itens 1–4 da proposta v2 (`2026-10-01-mfa-emergencia-p4r2a-c-proposta.md`);
- o clone de laboratório para a A6b (autorizado durante a missão).

**Item 5 (frente B):** não autorizado explicitamente; **PENDENTE**.

**Evidências:** `ops/homologacao/saidas/p4r2ac-20261001T172523Z/`, fora do Git.

## 0. Resultado por prova

| Prova | Resultado | Evidência |
|---|---|---|
| A1: o que a 014 precisa atravessar | Só a `UPDATE user_sessions` de ativação e o `SELECT` do invariante, ambos em `user_sessions` (`rls=true force=true`, sem política para o migrador). `permissions`/`role_permissions` não têm RLS. Sem elevação, as **480** sessões ficam ativas e o migrador vê 0 | `A_bypass/execucao5/A1_resultado.txt` |
| A2: BYPASSRLS sozinho basta | Sim: janela `BYPASSRLS` (sem SUPERUSER) concedida e retirada (`0|0|0`); 0 sessões ativas sem marcador (pelo administrador) | `A2_resultado.txt` |
| A2b: o invariante da 014 enxerga as linhas | Cópia da 014 **sem** o `UPDATE`, dentro da janela: a 014 **reprova** ("sessão ativa sem marcador"), é revertida e a janela é retirada | `A2b_resultado.txt` |
| A3: efeito exato | Conjunto esperado tirado do **texto** da 014 (`INSERT permissions`, `INSERT role_permissions`, `UPDATE user_sessions`), conferido em p1 e p2. Mudam só essas tabelas; +1 linha em cada uma das duas primeiras; o diff de catálogo da 014 é idêntico com e sem a janela (250 linhas). Em `user_sessions`: **exatamente as 480** do conjunto S (ids capturados antes), **uma só coluna** (`revoked_at`) em cada; nenhuma outra linha muda | `A3_resultado.txt`, `linhas/` |
| A4a: retirada no sucesso | `fim versao=014 BYPASSRLS revogado, estado 0|0|0` | A2 |
| A4b: retirada na falha | Falha induzida no fim da janela: transação revertida, janela retirada **antes** de abortar, 480 sessões intactas, `0|0|0` | `A4b_resultado.txt` |
| A4c: interrupção abrupta | `kill -9` do cliente com a janela aberta. Resíduo **`1`** (BYPASSRLS) detectado; 014 **não** aplicada e 480 ativas antes de qualquer ação; reexecução **recusada**; recuperação → `0|0|0`; reexecução aplica a 014 uma vez. O log do servidor não registra queda | `A4c_resultado.txt`, `postgres_log.txt` |
| A5: suíte da 016 no modelo, sem opção diagnóstica | **A e B OK**: na base populada a 014 roda com BYPASSRLS; dados idênticos; invariantes 012/013/014/016 depois da retirada; restauração lógica | `A5_suite/` |
| A6: gramática inequívoca | `lib/lista_elevacao.sh`; parser 24/24; 5 mutações reprovam; `validar-config` 129/0 (111 + 18 novas) | `A6_lista/`, `G_validar/` |
| A6b: runner real | Janela real `014 BYPASSRLS` no `ensaio-r2ac`; `kill -9` no dono da trava → BLOQUEADO; `testar` recusado; `recuperar` retira o BYPASSRLS; reconciliação READ ONLY (014 não registrada); reexecução com janela concluída e LIBERADO; **função `h02_janelas`** (extraída do `executar.sh` e executada sobre os registros reais): 011–014 concluídas, 1 interrompida (014) com recuperação própria, 0 problemas. *Revisão:* isso comprova a função com registros BYPASSRLS, **não** o H02 completo chamado por `testar`; o `testar` completo fica para a R2b | `A6b_runner/` |
| C1: volume novo (dev) | healthy; 16 versões no modelo (4 SUPERUSER + 1 BYPASSRLS); §19 pós-retirada; marcador gravado | `C_dev/execucao1/` |
| C2: reinício sem reaplicar | restart e `down` (sem `-v`) + `up`: 0 linhas do script, "Skipping initialization", catálogo idêntico, healthy | idem |
| C3: falha no meio | initdb aborta; BYPASSRLS retirado antes; o container reinicia (`unless-stopped`) | idem |
| C4: volume parcial | Depois de C3 e de SIGKILL com a janela aberta: o PostgreSQL sobe mas fica **unhealthy** (sem marcador). Depois do SIGKILL, o resíduo `1` é retirado pela recuperação, mas o volume **continua unhealthy** (incompleto: recriar) | idem |
| C5: `docker-compose.yml` | Proposta testada em C1–C4 aplicada só no arquivo. *Revisão:* adoção **não aprovada** para a stack principal (volume antigo sem marcador ficaria unhealthy); substituída na rodada R2a-C2 por `docker-compose.modelo.yml` (volumes novos), com `docker-compose.yml` restaurado | `C_dev/docker-compose.proposto.yml`; adendo R2a-C2 |
| Regressão Maven | 3 execucoes: (1) 1 erro INTERMITENTE em nekkora-ops (`Revisao4TentativasIT`, colisao do sufixo aleatorio de 24 bits; reator parou); (2) ops **155/0** e 1 erro INTERMITENTE em nekkora-platform (`CaixaHomologacaoMailSenderIT`, `AccessDeniedException` do Windows ao criar o diretorio de trava em concorrencia; portal nao rodou); (3) `-pl nekkora-platform,nekkora-portal -am`: **BUILD SUCCESS, 256 testes, 0 falhas, 0 erros** nos 4 modulos (core 5, ops 155, platform 84, portal 12). Nenhum dos dois envolve banco ou o modelo. *Revisão:* registro aprovado = execução 3 (256/0), com os dois incidentes anteriores mantidos visíveis; o da caixa passa por `CaixaHomologacaoMailSender.desfecho()`/`sendEmergencyNotice()` (código do transporte, não só do teste): **falha intermitente no transporte em Windows, causa ainda não encerrada**, achado aberto | `R_regressao/` |

## 1. Alterações na árvore de trabalho

Nada em stage. Antes e depois conferidos por hash. Diffs em `P_patches/diffs/`; patch único em `P_patches/p4r2ac-alteracoes.patch`.

| Arquivo | Mudança |
|---|---|
| `ops/homologacao/lib/lista_elevacao.sh` | **Novo.** Gramática da lista (legado posicional = SUPERUSER; forma nova `tipo=superuser\|bypassrls [ambiente=]`; 014 só bypassrls; 016 só superuser e vinculada). Erros de formato começam por "linha fora do formato", preservando as checagens existentes |
| `ops/homologacao/implantacao/implantar.sh` | Lista validada pelo parser. Concessão por tipo (`SUPERUSER`/`BYPASSRLS`). Registros `inicio/fim versao=… <TIPO> …`; o texto de SUPERUSER é o mesmo de antes. A retirada (`revoga_e_confere`) e o `recuperar` já removiam BYPASSRLS |
| `ops/homologacao/homologacao.sh` | Conferência da linha da 016 pelo mesmo parser |
| `ops/homologacao/testes/validar_config.sh` | 18 verificações da gramática |
| `db/tests/lib/aplicar_como_migrador.sh` | Janela BYPASSRLS da 014 (`NEKKORA_BYPASS`, padrão `014`, disjunta de `NEKKORA_ELEVAR`); retirada no sucesso e na falha; modo `NEKKORA_RECUPERAR=1`; **marcador** "nekkora-inicializacao-completa" apagado no início e gravado só no fim de execução completa |
| `db/tests/mfa-emergencia-016/run.sh` | Resumo com a janela BYPASSRLS; remoção com `-v` (O-R2a-4) |
| `docker-compose.yml` | Migrations em `/nekkora/migracoes`; script do modelo no `initdb.d`; healthcheck exige o marcador; `start_period` 180 s |

**Linha da 014 para a lista operacional:** só em patch (`P_patches/elevacao-014-proposta.patch`, `git apply --check` OK). `elevacao.txt` está **intacto** (`eb98911a…`). A 016 continua `f37d9841…`.

## 2. Incidentes e correções desta missão

1. **A4c, execuções 1 a 4 inválidas, por causa de defeitos do meu harness** (`A_bypass/NOTA_execucoes_1_4.txt`). Nenhum desses defeitos é do modelo:
   - verificação fora de ordem;
   - `pkill` dentro de `sh -c` matando o próprio shell;
   - **queda do servidor**: o `psql` órfão foi reparentado ao PID 1, que no `postgres:16-alpine` é o próprio postmaster, e o `kill -9` nele foi tratado como queda de processo do servidor. Confirmado pelo log ("server process (PID 2025) was terminated by signal 9").
     - **Afirmei erradamente "sem queda"** na execução 2 com base em `pg_postmaster_start_time()`, que **não muda** em reinício por queda.
     - Corrigido com `--init` no container de teste e verificação pelo log do servidor.
   - execução 4: matar a folha primeiro deixou o script revogar (caminho da A4b).

   A execução 5 é a válida.
2. A primeira tentativa de teste do parser tinha um erro de precedência no harness (`parser_casos_execucao1_harness_errado.txt`).
3. **A6b:** o processo em segundo plano do `criar` foi encerrado pelo limite de tempo da ferramenta enquanto o runner estava na pausa. O runner (PID 1122) e o `criar` continuaram vivos e em pausa, então o roteiro seguiu exatamente como planejado.
4. **Regressão, 1ª execução:** 1 erro **intermitente** em `Revisao4TentativasIT` (`role "op_exe_ba6943" already exists`). `OpsBanco.sufixo()` usa só 24 bits aleatórios numa base compartilhada pela JVM, o que causa colisão de nomes. O código não foi alterado nesta missão nem na R2a. O reator parou no `nekkora-ops`. Execução repetida: §0. **Recomendação:** ampliar o sufixo (ex. `hex(8)`) numa próxima etapa.

## 3. Consequências e decisões

- **D-C-1, stack principal de desenvolvimento.** O volume atual da `nekkora-postgres` foi inicializado pelo caminho antigo e, **[INF]** (não consultado: a frente B não foi autorizada), não tem o marcador. No próximo `docker compose up`, o container é recriado com o healthcheck novo e fica **unhealthy**. Os dados continuam, mas a instalação não é a do modelo. Opções:
  - (a) recriar o volume de desenvolvimento;
  - (b) manter o healthcheck antigo até uma decisão;
  - (c) gravar o marcador manualmente: **não recomendo**, porque daria a uma base fora do modelo o aspecto de instalação válida.
- **D-C-2, script como ferramenta de desenvolvimento.** `aplicar_como_migrador.sh` continua em `db/tests/lib/` e agora também é usado pelo compose de desenvolvimento. Decidir se ele vai para `db/bootstrap/` ou equivalente.
- **D-C-3, cache de build do Docker.** O `criar` do `ensaio-r2ac` construiu imagens. O cache de build (80 entradas, 2,5 GB no total, não só desta missão) não entra no inventário por classe e só sai com `docker builder prune`, que é limpeza global. **Não removido.**
- **D-C-4, linha operacional da 014.** Patch pronto, não ativado.
- **Frente B (item 5):** pendente. Sem 5a/5b não houve consulta à base principal, `portal-dev`, `portal-013` nem `hml03`, e o `ensaio-016a` não foi lido nesta missão.

## 4. Recursos e preservação

- **Bases descartáveis:**
  - `p4r2ac-pop` (5 execuções) e a suíte `p4r2ac-mfa016`, removidas com `-v`;
  - laboratório de dev `p4r2ac-dev`, com `down -v` em cada caso;
  - `ensaio-r2ac`: `destruir`, o volume anônimo do Redis por ID e os segredos de `~/.nekkora/homologacao/ensaio-r2ac` (criados nesta missão).
- **Clone de laboratório** `../lab-p4r2ac-20261001T174850Z/nekkora-platform`:
  - commit de snapshot `9f786fc` ("não integrar"), sem remoto;
  - inclui o `node_modules` não rastreado de `api/e2e/email`;
  - **preservado** até a decisão de descarte.
- **Inventário início × fim da missão:** coletor B12 entre 17:25Z e o fim: **B12 OK, codigo 0** (14 containers, 10 redes, 81 volumes, 19 imagens; protegidos identicos). `ensaio-016a`: 14 objetos, 0 atributos diferentes
- **Preservados:** `ensaio-016a`, clone P4, cópia da 016 do `ensaio-016a` e o histórico do Flyway (não tocados), base principal (rodando, inalterada), `elevacao.txt` operacional, HEAD `dd74a8f`, stage vazio.
