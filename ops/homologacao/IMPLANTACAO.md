# Identidade de implantação e Flyway — matriz e procedimento

Etapa C da missão de integração e homologação (2026-09-26). **Revisão 2**, com os ajustes pedidos na revisão do responsável.

**Decisão (2026-09-26):** o responsável aceitou a **direção** de elevação temporária para o bootstrap 011–013 **na homologação isolada**, condicionada aos controles da §3. Isso **não** aprova o mesmo procedimento em produção. O gate do Flyway só fecha depois da revisão do mecanismo concreto (scripts e evidências). Nenhuma migration nem invariante foi alterado.

## 1. Identidades separadas

| Identidade | Tipo | Privilégios permanentes | Onde pode conectar (`pg_hba`) | Para quê |
|---|---|---|---|---|
| `admin_cluster` | administrador do cluster (bootstrap) | SUPERUSER | socket local do container PostgreSQL; pela rede, **só** do container efêmero de ferramentas (TLS, SCRAM) | `initdb`, bootstrap, concessão e revogação da elevação, recuperação, senhas por verificador SCRAM, verificação |
| `migrador_nekkora` | implantação (Flyway) | **Sem atributos administrativos elevados permanentes** (SUPERUSER, CREATEROLE, CREATEDB, BYPASSRLS e REPLICATION desligados fora da janela), **mas com os privilégios de ownership**: dono do banco `nekkora` e dos objetos que as migrations não reatribuem (46 tabelas, 67 funções, o histórico do Flyway). Ownership permite alterar e excluir esses objetos | só do container do Flyway (`10.252.10.30`), `hostssl` + SCRAM, só o banco `nekkora` | aplicar migrations |
| `backup_nekkora` | backup lógico | LOGIN, BYPASSRLS, membro de `pg_read_all_data` | só do container de backup (`10.252.10.32`) | `pg_dump` e `pg_dumpall --roles-only --no-role-passwords` |
| `nekkora_platform`, `nekkora_portal` | execução dos serviços | os das migrations (sem atributo privilegiado, sem membership) | cada um só do próprio container | Platform e Portal |
| demais `nekkora_*` | owners e serviços das migrations | NOLOGIN, ou LOGIN sem senha | nenhuma | donos protegidos e papéis ainda sem serviço |

**A credencial do migrador fica restrita à implantação, mesmo fora da janela:**
- a senha existe só no `flyway.conf` (secret do job do Flyway, fora do Git) e não está em nenhum `.env` de serviço;
- o `pg_hba` só aceita o migrador a partir do container efêmero do Flyway, que existe apenas durante a implantação;
- nenhum serviço conecta como ele: H03 confere as conexões de serviço pelo próprio papel e IP.

**Nomes:** as identidades novas não seguem o padrão `nekkora_*`. O invariante da 012 recusa qualquer papel `nekkora_*` com atributo administrativo elevado, e a consulta dele não tem a exceção "de migration" que o comentário cita.

**Senhas:** chegam ao servidor como verificador SCRAM. O texto claro nunca vai ao banco.

## 2. O que a baseline 001–015 exigiu nos caminhos ensaiados

Medido por `mapa_privilegios.sh` (evidência em `evidencias/mapa-privilegios/`). Cada migration foi aplicada pelo migrador sem elevação e, quando falhou, com elevação. As que não têm `BEGIN` próprio foram aplicadas em transação única.

| Migrations | Sem elevação | Bloqueio | Com os papéis já criados pelo bootstrap |
|---|---|---|---|
| 001–010 | aplicam | — | — |
| 011 | não | `permission denied to create role` | `must be able to SET ROLE "nekkora_retention_tables_owner"` |
| 012 | não | `permission denied to create role` | `permission denied to alter role` |
| 013 | não | `permission denied to create role` | `permission denied to alter role` (inclusive `ALTER ROLE nekkora_portal SET log_*`) |
| 014, 015 | aplicam | — | — |

**Conclusão (limitada ao que foi medido):** nos caminhos ensaiados, com as migrations atuais e sem memberships em papéis protegidos, 011–013 exigiram elevação. Os caminhos que evitariam a elevação e que foram considerados (membership com SET nos owners; CREATEROLE, que no PostgreSQL 16 cria membership do criador) conflitam com o invariante de membership da 012–014. Não foram adotados nem enfraquecidos. Isto **não** demonstra que nenhuma outra alternativa exista, por exemplo uma alteração aditiva futura revisada.

O pgcrypto é *trusted* e instala sem elevação. Os objetos dele ficam com o superusuário do bootstrap como dono.

## 3. Controles da elevação temporária

| Controle | Mecanismo (`implantacao/implantar.sh`, `lib/estado.sh`, `homologacao.sh`) | Evidência |
|---|---|---|
| Lista explícita de versões **e checksums** | `implantacao/elevacao.txt`: versão + sha256 exato do arquivo autorizado, **calculado dos bytes LF versionados** (sem normalizar na comparação e com um único sha256 por versão). Versão fora da lista nunca é elevada; sha256 diferente, inclusive o de uma cópia CRLF, → recusa **antes** de conceder | Criação de `hml03` e `ensaio-controles` (sha256 registrado em cada janela); **I1** (sha256 errado recusado sem abrir janela); `ensaio-lf` (clone limpo: 012 pelo hash LF; variante CRLF recusada) |
| Autorização explícita | `HOMOLOG_AUTORIZA_ELEVACAO=1`; sem ela, a versão da lista para a implantação | — |
| Exclusão de implantações e recuperações simultâneas | **Trava no banco** (`lib/estado.sh`): um único advisory lock de sessão (`pg_try_advisory_lock(20260926, 1)`) no cluster do ambiente. Todo caminho que eleva, revoga ou encerra sessões disputa o mesmo lock: `implantar.sh` (chamado por `criar` e `implantar`) e `recuperar`. O lock é tomado **antes de qualquer ação**, sem espera, e a sessão fica aberta durante toda a execução, cobrindo a janela inteira, da pré-condição à conferência final. Quem não obtém sai **sem fazer nada** (código 3 na implantação; `RECUSADO` na recuperação): não eleva, não revoga, não encerra sessões nem remove containers. Continuam como camadas adicionais: a **trava local** (`saidas/<id>/implantacao.trava`, marca implantação interrompida neste host) e a pré-condição `0\|0\|0` do migrador. Se o processo morre, a sessão cai e o lock é liberado; o resíduo continua sendo detectado pela liberação (abaixo) | **I6** (5 rodadas de largada simultânea **antes** da elevação, com travas locais diferentes: em todas, só um prosseguiu; o perdedor saiu com código 3, com uma única linha de registro, sem janela e sem trava local; o vencedor seguiu elevado depois disso e terminou pela falha de ensaio, `division by zero`, não por conexão encerrada); **I7** (`recuperar` durante implantação ativa: recusado pela trava no banco; o migrador seguiu elevado e a implantação terminou normalmente pela falha de ensaio); **I2**/**I3** (segunda implantação com o primeiro já elevado: recusada pela trava no banco, com a mesma trava local e com outra) |
| Janela = uma versão | Elevar → `flyway migrate -target=<versão>` → revogar e conferir, antes da versão seguinte | `janelas_de_elevacao.txt` |
| Encerramento das conexões e conferência final | Depois de cada janela e no fim: `NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS NOREPLICATION`, `pg_terminate_backend` das sessões do migrador e conferência `0\|0\|0` (atributos, memberships, sessões). Só então a trava é liberada | Registro "revogado, sessões encerradas, estado conferido (0\|0\|0)" em cada janela; **I4** (a recuperação encerrou a sessão órfã do Flyway) |
| **Perda da sessão da trava** (só a conexão do lock cai; runner e banco vivos) | O executor antigo não age por nenhuma outra conexão: (1) conceder e revogar atributos e encerrar sessões rodam **dentro da própria sessão da trava** (`sql_na_trava`), num bloco que antes confere que essa sessão ainda segura o lock. Com a sessão morta a ação não tem como acontecer, e sem a resposta "OK" daquela chamada ela conta como falha; (2) **cada conexão do Flyway** confere no `initSql` que o lock está com o backend exato desta execução, senão a conexão é recusada e nenhuma migration roda por ela; (3) perdida a trava, o runner **para com código 6**, **sem revogar nem encerrar sessões** (podem ser de um novo vencedor), mantém a trava local e deixa o ambiente BLOQUEADO até `recuperar`. O novo executor só age com o migrador `0\|0\|0`: uma sessão do Flyway antigo ainda aberta ou uma elevação antiga o faz recusar. A conferência pontual antes do Flyway só antecipa a detecção; a garantia é o `initSql`. O `recuperar` também age pela sessão da trava e só remove containers que já existiam antes de obtê-la | **I9a** (sessão derrubada com o Flyway de A em execução: B obteve a trava, viu `1\|0\|1` e saiu sem agir; A terminou a migration em curso e parou com código 6, sem revogar); **I9b** (A parado logo antes de conceder; sessão derrubada; B entrou, elevou e executou; A retomou durante a janela de B e parou com código 6 sem conceder, revogar nem encerrar nada; B terminou normalmente); **I9c** (A elevado, conferência pontual aprovada, sessão derrubada e outro executor dono da trava: o Flyway de A foi recusado na conexão pelo `initSql`) |
| Falha de revogação = falha da implantação | Se a revogação ou a conferência falha com a trava ainda válida, o runner **para** (código 4), **mantém a trava** e declara o ambiente BLOQUEADO até a recuperação. Se a trava se perdeu, vale a linha anterior (código 6). Nunca continua para a versão seguinte | **I5** (banco parado durante a janela: a sessão da trava cai junto, e o runner para com código 6 sem tentar revogar por outra conexão) |
| Revogação nos caminhos de falha tratados | Fim normal, falha de migration e sinais capturáveis (INT, TERM, HUP): a revogação é **executada e conferida**. A trava só sai com o estado limpo | H09 (c)/(d) do `hml02`; I2–I3 |
| Encerramento abrupto (SIGKILL, queda do host, banco indisponível na revogação) | **Não há garantia pelo runner.** SIGKILL não é capturável. A trava e a elevação podem ficar. **Recuperação independente:** `homologacao.sh recuperar <id>` recusa se o runner da trava local ainda vive, **obtém a mesma trava no banco** (senão recusa sem alterar nada), remove containers de implantação restantes **do ambiente**, revoga, encerra sessões, confere `0\|0\|0` e só então remove a trava local. A recuperação devolve o **migrador**; ela **não** libera o ambiente por si (linha seguinte) | **I4** (`kill -9` + `docker kill`), **I5**, **I7**, **I8**, e a recuperação de um estado residual real (`recuperacao-estado-residual-real-*.txt`) |
| Liberação em duas partes: **migrador recuperado** ≠ **ambiente apto** | `homologacao.sh estado <id>` (`confere_liberacao` em `lib/estado.sh`) só responde LIBERADO com as duas partes: (1) **migrador recuperado** — sem trava local, trava no banco livre, migrador `0\|0\|0`; (2) **ambiente apto** — conjunto de migrations do ambiente idêntico ao registrado na criação (sha256), histórico do Flyway com **exatamente** as versões esperadas (001–015) com sucesso, nenhuma linha `success=false` e `flyway validate` aprovado. **Consulta que falha bloqueia** (nunca é lida como "sem resíduo"). `recuperar` informa separadamente "migrador recuperado" e "ambiente LIBERADO" ou "CONTINUA BLOQUEADO", com as versões que faltam. `testar` e a subida dos serviços no `criar` **recusam** ambiente BLOQUEADO | **I4** (estado, `testar` e implantação recusados); **I8** (criação interrompida com a 011 em execução: depois do `recuperar`, migrador `0\|0\|0` mas ambiente BLOQUEADO — faltam 011–015 — e `testar` recusado; com o postgres parado, BLOQUEADO por consulta falha; `implantar` concluiu as pendentes → LIBERADO) |

- **Duração:** as janelas medidas levaram de 3,6 a 5,3 s (incluindo a subida do container do Flyway; o runner da revisão 2 também encerra sessões e confere o estado). É uma **medição, não um prazo máximo garantido**.
- **Risco residual:** as funções SECURITY DEFINER pertencem aos owners protegidos, não ao migrador (conferido em H03). Durante uma janela, porém, qualquer código que rode **como migrador** tem poder de superusuário. Por isso existem a exclusão, a restrição da credencial e a janela de uma versão.

## 4. Execução pelo Flyway

- **Modo por arquivo:**
  - 001–011 não têm `BEGIN`/`COMMIT` → `executeInTransaction=true`: o Flyway embrulha cada uma numa transação;
  - 012+ têm `BEGIN`/`COMMIT` próprio → `executeInTransaction=false`.
- **Comportamento medido numa falha:**
  - `executeInTransaction=true`: falha desfeita por inteiro, **sem** linha no histórico;
  - `executeInTransaction=false` num arquivo **sem** `BEGIN` deixa estado parcial **e** `success=false`. Por isso não é usado para 001–011.
- **Configuração fixa:** `baselineOnMigrate=false`, `validateOnMigrate=true`, `cleanDisabled=true`, `outOfOrder=false`, `postgresql.transactional.lock=false`. O runner não contém `repair`, `baseline` nem `clean`.
- **Baseline manual:** só no procedimento de banco reconhecido e conferido (registro de decisões, §6.2). Uma instalação nova começa de banco vazio.

## 5. Falha e recuperação de implantação

1. O runner para na primeira falha, sem nova tentativa. Com o estado conferido limpo, libera a trava; senão, mantém a trava (ambiente BLOQUEADO). Códigos: 1 validate; 2 migration ou autorização; 3 recusado sem agir (trava ocupada, pré-condição); 4 revogação ou conferência falhou com a trava válida; 5 sinal; **6 trava no banco perdida** (nenhuma ação depois disso). `homologacao.sh implantar` devolve o código do runner e, com o runner concluído, **7** se a conferência final recusar a liberação: só retorna 0 com o ambiente LIBERADO.
2. **Interrupção abrupta ou falha na revogação:**
   - confirmar que o runner não está ativo (o `recuperar` confere o pid no mesmo host e exige confirmação explícita para trava de outro host);
   - depois, `homologacao.sh recuperar <id>` (recusa sem alterar nada enquanto houver implantação ou outra recuperação ativa, pela trava no banco);
   - se o resultado for "migrador recuperado; ambiente CONTINUA BLOQUEADO" por instalação incompleta: `HOMOLOG_AUTORIZA_ELEVACAO=1 homologacao.sh implantar <id>` aplica só as versões pendentes, com os mesmos controles;
   - por fim, `homologacao.sh estado <id>` deve responder LIBERADO (migrador recuperado **e** ambiente apto).
2a. **Conexão já aberta:** se a sessão da trava cai com uma migration em curso, a conexão do Flyway já aberta termina aquela migration (o `initSql` vale na abertura). Um novo executor recusa enquanto ela existir (`0\|0\|0`), e o runner antigo para ao fim dela sem revogar (I9a).
2b. **Limite da exclusão:** a trava no banco vale para quem usa estes scripts. Um `ALTER ROLE` ou uma sessão aberta manualmente por um superusuário, fora deles, não disputa a trava. Nesse caso, a proteção que resta é a pré-condição `0\|0\|0` e a liberação. Por isso a credencial `admin_cluster` fica restrita ao operador da homologação.
3. **Checksum divergente:** o arquivo não é o revisado. Nunca "reparar" o checksum; restaurar o arquivo revisado.
4. **`success=false` numa migration com `BEGIN` próprio:** conferir que o esquema não mudou (catálogo × anterior). Só então, com revisão registrada, um operador remove a linha pela via do Flyway (`repair`), fora do runner.
5. Dados: recuperação exige backup e o procedimento próprio (restauração 015). Não existe "rollback de schema" garantido.
