# P4-R2a: implementação e testes da revisão 5 da 016

**ESTADO: PARA REVISÃO.**
- O P4 continua não aprovado.
- Nada de stage, commit ou push.
- A linha operacional da 016 não foi ativada.
- O `ensaio-016b` e o P5 não foram iniciados.

**Data:** 2026-10-01, das 16:14Z às 16:55Z.

**Autorização:** P4-R2a, nos termos apresentados: aplicar as alterações revisadas, adaptar as suítes ao modelo, contraprovas, regressão e laboratório T10. Inclui criar e remover os recursos descartáveis destas suítes e do laboratório.

**Evidências:** `ops/homologacao/saidas/p4r2a-20261001T161430Z/`, fora do Git.

## 0. Resumo por requisito

| # | Requisito | Resultado |
|---|---|---|
| — | **Novo hash da candidata 016** | **`f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732`**, idêntico à proposta revisada (rev. 2). Os predicados (a), (b) e (c) estão na 016 byte a byte (`D_contraprovas/predicados_na_016.txt`) |
| R1 | §19 completa passando após a retirada de SUPERUSER | **Atendido em toda base montada no modelo**: suíte da 016 (A e B), bases Java (`RealDatabase`, `OpsBanco`), base das contraprovas e base T8. Todas com `invariantes_016_pos_retirada=OK` e migrador `0|0|0` |
| R2 | Contraprovas reais (dono incorreto, ACL indevida, permissões herdadas) | **17/17** em catálogo real (§2). Ao final, a §19 passa e a impressão digital do catálogo é idêntica |
| R3 | Regressão dos módulos afetados pelo setup compartilhado | **PARCIAL** (reclassificado na revisão de 2026-10-01: a suíte shell populada não passou). **`mvnw clean verify` completo: BUILD SUCCESS. 256 testes, 0 falhas, 0 erros**, nos 4 módulos e 42 classes (39 integrações com PostgreSQL real), todas com as bases no modelo. Suíte shell da 016: base A OK; base B falha pelo **F-R2a-1 (014)**, o que o diagnóstico isola. As demais suítes shell não aplicam a 016 e não foram afetadas |
| R4 | B12 integrado ao runner, com INCONCLUSIVO reprovando | **Atendido para os trechos reais do runner** (reclassificado na revisão: o `testar-016` completo no novo ensaio continua sendo evidência da R2b). A integração com o `h016.sh` passa 5/5 casos. Ela **encontrou e corrigiu** um defeito: erro do avaliador aparecia como FALHA. 31/31 testes locais; 7 mutações, todas detectadas. Laboratório T10 em Docker real: **8/8 cenários + 2 de desaparecimento real (L8/L9)**, resíduo zero por inventário |
| R5 | Contagem do `validar-config` explicada | **111/0**, explicada por nome contra o P4 (109/0): +2 verificações de sintaxe dos `.py` novos (§5) |

**Achados que exigem decisão (fora do escopo autorizado; nada foi alterado por eles):**
- **F-R2a-1 (alto, na 014, não na 016).** No modelo do migrador, a "ativação" da 014 (`UPDATE user_sessions SET revoked_at …`) roda **sem efeito** numa base com dados: `FORCE ROW LEVEL SECURITY`, sem política para o migrador. O invariante da própria 014 também roda como migrador e não vê as linhas.
  - Base populada: 480 sessões pré-MFA **continuaram ativas**; o superusuário vê 480, o migrador vê 0.
  - O setup antigo (superusuário) e os ensaios (base vazia na 014) mascaravam isso.
  - **Decisão D-R2a-1.**
- **F-R2a-2 (médio).** O `docker-compose.yml` de desenvolvimento aplica todas as migrations como o superusuário em volume novo. Com a revisão 5, o initdb **aborta** na 016, por desenho. A base principal existente não é afetada. **Decisão D-R2a-2.**

## 1. O que foi aplicado na árvore de trabalho

Base: HEAD `dd74a8f`. Nenhum arquivo em stage. Hashes antes e depois em `B_patches/hashes_{antes,depois}.txt`; diffs em `B_patches/diffs/`.

| Arquivo | Mudança | Origem |
|---|---|---|
| `db/migrations/016_recuperacao_emergencial_mfa.sql` | §19: V09 trocado pelo bloco (a)/(b)/(c)/(d) com identidade declarada; cabeçalho com o modelo suportado | patch rev. 2 aprovado, sem edição (`25bef818…` → `f37d9841…`) |
| `ops/homologacao/testes/h016.sh` | `fotografia()` pelo coletor; B12 OK/FALHA/INCONCLUSIVO; INCONCLUSIVO reprova o `testar-016`. **R2a:** código 1 só vale como FALHA com o veredito `B12 FALHA:` no relatório | patch rev. 2 + correção F-R2a-3 |
| `ops/homologacao/testes/inventario_b12.py`, `test_inventario_b12.py` | Coletor e testes da rev. 2. **R2a:** exceção inesperada na avaliação vira INCONCLUSIVO (3), e o teste 23 cobre isso | rev. 2 + correção F-R2a-3. O conteúdo normalizado em LF é igual ao da rev. 2, exceto essa correção. O disco está em CRLF, convenção do repositório para `*.py` (`autocrlf`) |
| `ops/homologacao/testes/validar_config.sh` | Hash revisado da 016: `25bef818…` → `f37d9841…`, com nota da revisão 5 | consequência do novo hash |
| `db/tests/lib/aplicar_como_migrador.sh` | **Novo**: modelo suportado, compartilhado pelas suítes shell e pelas bases Java | P4-R2a |
| `db/tests/mfa-emergencia-016/run.sh` | Bases A, `nekkora_ref` e B pelo modelo; estado `0|0|0` conferido antes dos invariantes; dono do índice novo da 016 = `migrador_nekkora` no regex de acréscimos previstos; entrada de diagnóstico `TESTE_ELEVAR` (padrão = modelo) | P4-R2a |
| `api/nekkora-core/.../RealDatabase.java`, `api/nekkora-ops/.../OpsBanco.java` | Migrations fora do `initdb.d`; script do modelo; a base só é aceita com `fim … estado=0|0|0` e `invariantes_016_pos_retirada=OK` no log | P4-R2a |
| `docs/relatorios/2026-09-27-mfa-emergencia-matriz-016.md` | §1: exceções `prevent_*` e `sslinfo`; linha `migrador_nekkora` (identidade declarada, modelo suportado). §2: EXECUTE implícito do dono da `sslinfo` e donos das `prevent_*` | D-B5-1/D-B5-2 |
| `ops/homologacao/IMPLANTACAO.md` | Linha do migrador: contagens com a 016 e identidade declarada | D-B5-1 |

**Modelo nas suítes** (`aplicar_como_migrador.sh`), igual a `bootstrap.sql` + `implantar.sh`:
- `migrador_nekkora` é LOGIN, sem atributos, dono do banco;
- SUPERUSER só em 011, 012, 013 e 016, com retirada e conferência `0|0|0` depois de cada uma;
- 001–010, 014 e 015 sem elevação;
- ao final, §19 da 016 de novo.

**Diferença registrada:** a aplicação roda numa sessão do administrador com `SET ROLE migrador_nekkora`:
- `current_user`, os donos, os privilégios e a perda do SUPERUSER são os do migrador;
- `session_user` é o administrador;
- nenhuma migration usa `session_user`/`current_user` fora de corpo de função (conferido por busca);
- motivo: no entrypoint o servidor só escuta no socket, e o `pg_hba` da forma "homologação" recusa o migrador no socket.

## 2. R1 e R2: §19 após a retirada e contraprovas reais

**§19 após a retirada (R1):**

| Base | Evidência |
|---|---|
| Teste isolado do script (001–016) | `C_suites/smoke/log.txt`: 4 janelas (011, 012, 013, 016), cada uma com `SUPERUSER revogado, estado 0|0|0`; `invariantes_016_pos_retirada=OK super=f` |
| Suíte da 016, base A (limpa) | `C_suites/mfa016_execucao4_saidas/`: entrypoint no modelo; `A02_estado_migrador = 0|0|0 dono_do_banco=migrador_nekkora`; invariantes 012/013/014/016 OK **depois** da retirada |
| Suíte da 016, base B (populada) | 016 aplicada no modelo, com `invariantes_016_pos_retirada=OK`. A suíte **falha** depois, no invariante da **014** (F-R2a-1) |
| Bases Java (`RealDatabase`, `OpsBanco`, inclusive a forma homologação com `admin_cluster`) | Cada base só é aceita se o log do container tiver `fim db=nekkora … estado=0|0|0` e `invariantes_016_pos_retirada=OK`; senão, `IllegalStateException`. A regressão inteira passou (§3), logo **toda** base Java criada nela cumpriu o R1 |
| Base das contraprovas e base T8 (forma homologação) | `invariantes_016_pos_retirada=OK` |

**Contraprovas reais (R2)**, em `D_contraprovas/execucao2/`. Base no modelo; cada caso é `BEGIN; mutação; §19; ROLLBACK`.

| Caso | Mutação | Reprovado por |
|---|---|---|
| C00 | nenhuma (controle) | passa |
| T2a | `prevent_operator_change()` → dono operacional | (c) dono |
| T2b | interna `alvo_inelegivel(uuid,uuid)` → migrador | (c) dono |
| T2c | membro `ssl_is_used()` → superusuário | (b) membro de `sslinfo`* |
| T2d | schema `ops` → dono operacional | V03 (CREATE efetivo), antes do (b) |
| T2e | tabela `ops_registry_events` → `dono_inesperado` (fora de todo prefixo e lista) | (b), tabela e pkey |
| T2f | banco → `dono_inesperado` | (a2) |
| T2g | banco → superusuário `nekkora` | (a2) |
| T2h | identidade declarada ausente (`migrador_nekkora` renomeado) | (a1) |
| T3a | EXECUTE de `nekkora_platform` em gatilho | (c) ACL |
| T3b | EXECUTE do grupo `nekkora_ops_mfa` em interna | (c) ACL |
| T3c | EXECUTE de PUBLIC em `ssl_cipher()` | (c) ACL |
| T3d | EXECUTE de `op_teste_r2a` em interna | (c) ACL |
| **T3e** | **herança: `GRANT migrador_nekkora TO op_teste_r2a`** | **(d) EXECUTE efetivo** (11 funções) |
| T3f | herança: `GRANT nekkora_ops_mfa_owner TO nekkora_platform` | V02 (participação) |
| T3g | grant option ao dono operacional na `sslinfo` | (c) ACL `EXECUTE*` |
| T4a | permissão legítima retirada (REVOKE da `sslinfo` do dono operacional) | (c) ACL faltante |

\* Na execução 1 a expectativa escrita para T2c era (c). A detecção estava certa: o (b) roda antes e cobre os membros da extensão. Só essa expectativa foi corrigida (`nota_execucao1.txt`).

Ao final: §19 código 0; impressão digital do catálogo `8d6c7c0b… 4321` igual antes e depois; base removida por ID.

**T8, sintéticos no modelo** (`D_contraprovas/T8/`): 40/40; controles (a2)/(b)/(c) reprovam 5/6/8.

Precisou da forma homologação: superusuário `admin_cluster` e papel `op_hml_ver`, que os fixtures sintéticos referenciam. Duas tentativas anteriores falharam por fixture e ficam registradas:
- `tentativa1`: papel `op_hml_ver` ausente;
- `tentativa2`: `admin_cluster` ausente. O resumo dessa tentativa mediu o código errado (`$?` do `sha256sum`), e o erro foi visto pelos arquivos.

## 3. R3: regressão

**Maven** (`E_regressao/`): `./mvnw -B clean verify`, das 16:25:44Z às 16:38:05Z, código 0. O mesmo conjunto de testes do runner do projeto (`*Test` e `*IT`). Usei `verify` no lugar de `install` para não gravar artefatos no `~/.m2`.

| Módulo | Testes | Falhas | Erros | Base de teste |
|---|---|---|---|---|
| nekkora-core | 5 | 0 | 0 | — |
| nekkora-ops | 155 | 0 | 0 | `OpsBanco` (formas padrão e homologação) |
| nekkora-platform | 84 | 0 | 0 | `RealDatabase` |
| nekkora-portal | 12 | 0 | 0 | `RealDatabase` |
| **Total** | **256** | **0** | **0** | 42 classes, 39 de integração |

Os containers do Testcontainers foram removidos pelo ryuk depois da JVM (0 restantes).

**Suítes shell:**

| Suíte | Situação no modelo |
|---|---|
| `db/tests/mfa-emergencia-016` | A OK. B falha por F-R2a-1 (`mfa016_execucao4.txt`) |
| `db/tests/mfa-emergencia-016`, **diagnóstico** `TESTE_ELEVAR="011 012 013 014 016"` (014 elevada, **fora do modelo**) | **Tudo OK**: A e B, dados idênticos, histórico preservado, invariantes pós-retirada, restauração lógica (`mfa016_diagnostico_014_elevada.txt`). Isola o F-R2a-1 na 014: as partes da 016 na base populada estão íntegras |
| `seguranca-012`, `portal-013`, `mfa-014`, `restauracao-015`, `atualizacao-populada`, `portal-8-2`, `regressao-renomeacao`, `api/e2e` | **Não afetadas**: aplicam listas fixas até 015 ou menos, sem a 016, nem o script novo. Não reexecutadas |
| `ops/homologacao` (runner) | Usa Flyway como migrador, com elevação por `elevacao.txt`. Não exercitado nesta etapa (R2b) |

**Setup antigo × revisão 5** (`C_suites/setup_antigo/`): todas as migrations no `initdb.d` como superusuário → initdb **aborta** na 016 (`identidade de implantacao declarada invalida ou inexistente`, exit 3). É o comportamento esperado do modelo declarado e a razão da adaptação. Também é o F-R2a-2.

## 4. R4: B12 no runner

- **Integração** (`F_t10/integracao_h016_b12.sh`): extrai do próprio `h016.sh` (árvore de trabalho) `registra()`, `fotografia()`, o bloco B12 do `testar()` e a linha final, e roda contra um avaliador falso.

  | Código do avaliador | Registro | Código final do runner |
  |---|---|---|
  | 0 | OK | 0 |
  | 1 com veredito | FALHA | 1 |
  | 3 | **INCONCLUSIVO** | **1** |
  | exceção | **INCONCLUSIVO** | **1** |
  | 1 sem veredito | **INCONCLUSIVO** | **1** |

- **F-R2a-3, achado e corrigido aqui.** Na rev. 2, uma exceção não tratada do avaliador saía com **1**, o mesmo código de FALHA. O runner registrava "diferença em recurso protegido": diagnóstico errado, embora reprovando (`integracao_runner_execucao1_achado/`). Correções:
  - `main()` devolve 3 em qualquer exceção da avaliação;
  - o `h016.sh` só aceita 1 como FALHA com a linha `B12 FALHA:`.
- **Testes locais:** 31/31. **Mutações:** 7/7 reprovam (código de saída, identidade, estrutura, monotonicidade, `MontadoPor`, anônimo compartilhado, exceção).
- **Laboratório T10** (`F_t10/laboratorio_t10.sh`): Docker real; ambiente `p4r2a-lab`; recursos `p4r2a-lab-*`, removidos por ID. Execução final em `F_t10/lab/`:

  | Cenário | Esperado | Obtido |
  |---|---|---|
  | L0 controle | OK | OK |
  | L1 volume protegido montado pelo ensaio | FALHA | FALHA (`MontadoPor`) |
  | L2 anônimo do ensaio compartilhado com protegido | FALHA | FALHA (**classificação mudou** + protegido novo) |
  | L3 container protegido recriado com montagem | FALHA | FALHA (o Docker não altera montagens de container existente, daí a recriação) |
  | L4 imagem sem rótulos re-taggeada | FALHA | FALHA (`RepoTags`; imagem feita por `docker commit` de container sem rótulos, sem cache de build) |
  | L5 `--rm` do ensaio | OK | OK |
  | L6 `--rm` protegido | FALHA | FALHA |
  | L7 anônimo novo só do ensaio | OK | OK |

  Resíduo por inventário completo, início × fim do laboratório: **B12 OK**.
- **L5/L6 não exercitaram o desaparecimento durante a coleta:** nas 3 execuções o container ainda estava vivo na inspeção. Por isso, `F_t10/desaparecimento_real.py` (L8/L9): coletor do módulo com executor Docker **real**; a única injeção é remover o container logo depois da listagem. O Docker respondeu `No such container` e a relistagem confirmou a ausência:
  - L8, do ambiente: OK;
  - L9, protegido: FALHA;
  - resíduo: B12 OK.
- **Comportamento conservador observado** (L8 com `postgres`, `lab_L8L9_execucao1_postgres_com_volume/`): o container do ensaio removido deixou seu volume anônimo **implícito**. Na foto "depois", nenhum container comprova mais que o volume é do ambiente → "classificação mudou" → FALHA.
  - É a regra pedida na revisão (associação comprovada, proteção monotônica).
  - Consequência prática: um teardown do ensaio entre as fotos que deixe volume anônimo órfão reprova o B12.
  - A remoção do container precisa levar os anônimos junto (`rm -v`/`--rm`). Sem isso, o volume órfão é, de fato, um recurso que ficou.
- **Resíduos do próprio laboratório, removidos:**
  - 1ª e 2ª execuções: 7 e 1 volumes anônimos **implícitos** (`VOLUME` do `postgres:16-alpine`), sem rótulo `p4r2a`, que o registro do script não cobria;
  - L8/L9, 1ª execução: mais 2.

  Cada um foi conferido como ausente antes, criado na janela, sem uso, e removido por ID. O script foi corrigido: registra os anônimos de todo container e mede o resíduo pelo inventário, não pelo rótulo.

## 5. R5: `validar-config`

| Execução | Resultado | Arquivo |
|---|---|---|
| Sem ajuste | 111 = 110 OK + **1 FALHA**, a esperada: "migration 016 com o sha256 revisado", que ainda pedia `25bef818…` | `G_validar/validar_config_1_sem_ajuste.txt` |
| Com a constante atualizada para `f37d9841…` | **111 OK / 0 FALHA**, código 0 | `G_validar/validar_config_2.txt` |

Explicação por nome contra o P4 (109/0; `G_validar/contagem_explicada.txt`):
- **+2:** `sintaxe python inventario_b12.py` e `sintaxe python test_inventario_b12.py`. A seção 1 faz `ast.parse` em todo `testes/*.py`;
- 1 renomeada: o check do hash ganhou "(revisão 5)";
- 1 com nome diferente e resultado OK nas duas: "015 em ensaio-* com as duas sub-redes iguais". O nome embute o motivo da recusa. No P4 a recusa veio por "transporte exige a 016"; agora vem por "sub-rede sobrepõe". Ambos recusam antes de criar qualquer recurso.
  - **[INF]** O P4 rodou com `HOMOLOG_TRANSPORTE` exportado na sessão.
  - Nenhuma rede Docker usa 10.252.23.

## 6. Desvios e incidentes desta etapa

1. **`run.sh` da 016 corrompido por mim.** Um `sed` com `\|` (alternância no GNU sed) inseriu texto em toda posição. Efeitos:
   - uma execução falhou por sintaxe (código 2);
   - o arquivo foi **restaurado de HEAD**, com hash igual ao de antes da missão (`ea4410be…`);
   - foi reeditado só por substituição exata (`C_suites/editar_run_016.py`);
   - registro em `C_suites/incidentes.txt`.
2. Primeira execução da suíte: falha de conversão de caminho do Git Bash no `docker exec` (`MSYS_NO_PATHCONV`), corrigida.
3. Segunda execução: o regex de acréscimos previstos tinha o dono antigo (`nekkora`) do índice novo da 016. Ajustado só esse termo para `migrador_nekkora`; nenhum outro critério foi afrouxado.
4. T8: duas tentativas com fixture incompleta e uma medição errada de código, descritas no §2.
5. Containers que as suítes deixaram em falha: removidos por ID (`C_suites/remocoes.txt`).

## 7. Decisões necessárias

| Id | Decisão | Opções (sem recomendação fechada onde muda contrato aprovado) |
|---|---|---|
| **D-R2a-1** | 014 × modelo do migrador (F-R2a-1) | (a) Incluir a 014 nas versões elevadas: muda a lista aprovada e a homologação; o superusuário ignora RLS. (b) Dar BYPASSRLS ao migrador só na janela da 014. (c) Corrigir em migration nova com política ou função própria. (d) Exigir base vazia na 014 (não vale para produção). **Até a decisão, a base B da suíte da 016 no modelo falha por isso, e assim deve ficar** |
| **D-R2a-2** | `docker-compose.yml` de desenvolvimento (F-R2a-2) | Adotar o script do modelo no compose, ou fixar 001–015 no compose de desenvolvimento |
| R2b | Linha de `elevacao.txt` da revisão 5 (`016 f37d9841… ensaio-016b`) | Não criada nem ativada; é a autorização da R2b. A proposta do P3 (`25bef818…` + `ensaio-016a`) fica obsoleta |

## 8. Recursos

**Início × fim da missão** (`A_baseline/comparacao_inicio_fim.txt`, coletor novo):
- **B12 OK, código 0**: 14 containers, 10 redes, 81 volumes e 19 imagens;
- **todos os recursos protegidos idênticos** por ID e atributos;
- nada criado pela missão permaneceu.

**`ensaio-016a`**, à parte (`A_baseline/ensaio016a_inicio_fim.txt`):
- os mesmos 14 objetos, 0 atributos diferentes;
- leitura READ ONLY: histórico 001–016 (016 checksum `2127844423`), migrador `0|0|0`, catálogo `3c1b2e0f… 1211`, igual ao P4-R1;
- o Postgres continua rodando (start autorizado no P4-R1); os outros 4 containers continuam `exited`.

**Clone:** `1c2f26b`, limpo, sem remoto; cópia da 016 `25bef818…`.

**Volumes anônimos implícitos removidos:**

| Origem | Quantidade |
|---|---|
| Containers da missão removidos com `rm -f` sem `-v`: teste isolado; suíte (execuções 1, 2 e 4); diagnóstico; contraprovas; T8; setup antigo | 13 (`A_baseline/remocao_volumes_anonimos.txt`) |
| Laboratório | 10 (`F_t10/*/remocoes.txt`) |

Todos com o mesmo critério: ausente no início, criado na janela da missão, sem uso, só o rótulo de anônimo.

**Observação O-R2a-4:** o próprio `run.sh` da 016 (código anterior, mantido) remove os containers com `docker rm -f` sem `-v` e deixa um volume anônimo por cluster. Os 77 anônimos protegidos antigos provavelmente vêm, em parte, de execuções passadas das suítes **[INF]**. Recomendo `rm -f -v` nas suítes numa próxima etapa. Não alterei nesta, porque não é necessário ao modelo.
