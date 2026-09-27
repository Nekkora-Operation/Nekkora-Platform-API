# Integração da API v1 e preparação da homologação

**Data:** 2026-09-26 — **revisão 4 aprovada e encerrada**, com a correção de reprodutibilidade da 012 (§0).
**Estado:** **homologação isolada encerrada.** Não é validação de ambiente de produção nem liberação para produção.
**Integração:** a API v1 (`d608a96`) e a homologação (`9491a3e`) foram publicadas na `main` por fast-forward. A correção da 012 entra por um commit próprio (§0).

## 0. Encerramento

- **Revisão:** a revisão r4 foi **encerrada sem bloqueio** pelo responsável. Foi uma revisão **estática e independente**: leitura do código, das evidências e do manifesto do pacote (sha256 do ZIP conferido, manifesto sem divergências, sintaxe dos 7 scripts). **O revisor não reexecutou Docker nem PostgreSQL.** As execuções são as registradas aqui.
- **Os dois achados da r4 estão resolvidos:**
  - **perda da trava:** as ações administrativas usam a própria sessão que detém o lock, e as conexões novas do Flyway conferem o backend esperado no `initSql` (I9a–c);
  - **código de saída do `implantar`:** preserva a falha do runner e devolve 7 quando a conferência final recusa a liberação (I10).
- **Limites mantidos:**
  - uma conexão do Flyway já aberta pode concluir a migration em curso depois de perder a sessão da trava (I9a; um novo executor recusa enquanto ela existe);
  - a aprovação vale **só para a homologação isolada**, dentro da cobertura registrada, e não se estende a produção.
- **Correção de reprodutibilidade depois da integração** (achado na conferência do commit contra o pacote r4):
  - **Achado:** a `elevacao.txt` autorizava a 012 pelo sha256 `fd758edf…`, que é o de uma cópia de trabalho com **CRLF**. O repositório guarda a 012 em LF, e o `.gitattributes` impõe LF no checkout. Num clone limpo da `main` (`9491a3e`), a 012 tem sha256 `d3929cc7…`, e o `criar` **recusava a elevação da 012 antes de conceder**. O controle falhou fechado, sem risco de segurança, mas a homologação não era reproduzível a partir da `main`.
  - **Correção:** a linha 012 da `elevacao.txt` passou a ter o sha256 completo dos bytes LF versionados (`d3929cc76261debb47740bb0167d1e3438e00b14e01eca2a793716e5ce8321f4`), e a cópia de trabalho local da 012 foi normalizada para LF, com o SQL idêntico. A checagem continua **estrita por bytes**: sem normalização na comparação e com um único sha256 por versão.
  - **Ensaio** (`evidencias/ensaio-lf-antes/` e `evidencias/ensaio-lf/`), com clones limpos de `origin/main` em `9491a3e`:
    - **sem a correção:** 001–011 aplicadas e a 012 recusada antes de conceder (código 2), com o migrador `0|0|0` e o ambiente BLOQUEADO por instalação incompleta. O defeito foi reproduzido;
    - **com a correção** (só a linha da `elevacao.txt`; patch nas evidências): criação completa, 001–015 com sucesso, elevação só em 011, 012 e 013 com o sha256 autorizado em cada janela, migrador `0|0|0` ao fim de cada janela e no final, `flyway validate` OK e ambiente **LIBERADO**;
    - **hash divergente continua recusado:** a **I1** (sha256 errado) passou. Um ensaio pontual apresentou ao runner uma versão autorizada pelos bytes LF, mas entregue com CRLF e mesmo conteúdo SQL. Ela foi **recusada antes de conceder** (código 2), sem janela, com migrador `0|0|0` e histórico inalterado.
  - **Hashes históricos:** as evidências anteriores a esta correção (hml02, hml03, ensaio-controles, ensaio-r3, ensaio-i8, ensaio-r4, ensaio-i8b, mapa de privilégios) e o relatório da API v1 registram `fd758edf…` para a 012. Esses valores ficam preservados como foram gerados: correspondiam à cópia de trabalho CRLF, de mesmo conteúdo SQL. O `hml03` também foi criado dessa cópia, com o registro de migrations dele consistente com ela, e continua LIBERADO.
  - **Windows e caminhos longos:** algumas evidências têm até 142 caracteres a partir da raiz do repositório. Num diretório profundo, o checkout no Windows falha sem `core.longpaths`. Orientação no README: clonar num caminho curto ou usar `git clone -c core.longpaths=true`.

**O que mudou na revisão 4** (só os caminhos afetados; I1–I7 foram repetidos porque todos passam pelas ações agora cercadas):
- **Perda da sessão da trava:** antes, só a sessão `TRAVA_PG` segurava o lock, e as ações usavam outras conexões. Agora:
  - conceder, revogar e encerrar sessões rodam **dentro da própria sessão da trava**, num bloco que antes confere que ela ainda segura o lock. Sessão morta = ação impossível, sem conexão de reserva;
  - **cada conexão do Flyway** confere no `initSql` que o lock está com o backend exato da execução;
  - perdida a trava, o runner **para com código 6**, sem revogar nem encerrar sessões, mantém a trava local e deixa o ambiente BLOQUEADO até `recuperar`;
  - o `recuperar` também age pela sessão da trava e só remove containers que já existiam antes de obtê-la.
  Ensaio **I9**: a sessão do lock derrubada com runner e banco vivos, em três pontos (Flyway em execução; logo antes de conceder, com outro executor entrando e executando; logo depois da conferência pontual e antes do Flyway, com outro executor dono da trava).
- **`implantar`:** devolve o código do runner e, com o runner concluído, **7** se a conferência final recusar a liberação. Só retorna 0 com o ambiente LIBERADO. Ensaio **I10**.
- **Defeito encontrado e corrigido durante a revisão 4:** a primeira versão do gancho de pausa de ensaio lia uma variável sem padrão sob `set -u`. Qualquer implantação com versão pendente abortaria logo depois de obter a trava, antes de elevar. A primeira rodada (não oficial, guardada como `...-NAO-OFICIAL-defeito-set-u`) mostrou o defeito. Corrigido antes da rodada oficial.
- **I5 com resultado novo, esperado:** com o banco parado na janela, a sessão da trava cai junto. O runner agora para com código 6 (antes, 4), sem tentar revogar por outra conexão. Elevação residual, bloqueio e recuperação continuam iguais.

**O que mudou na revisão 3** (só os caminhos afetados foram testados de novo, como pedido):
- **Exclusão compartilhada no banco:** a checagem `0|0|0` não excluía runners com travas locais diferentes que chegassem juntos antes de qualquer elevação. Um runner também podia revogar a elevação e encerrar as sessões de outro. Agora **todo** caminho de implantação e de recuperação disputa o **mesmo advisory lock de sessão** no cluster do ambiente. O lock é obtido antes de qualquer ação e segurado durante a execução inteira. Quem não o obtém sai sem fazer nada. Ensaios: **I6**, corrida na entrada em 5 rodadas; **I7**, recuperação durante implantação ativa.
- **Liberação em duas partes:** "migrador recuperado" (sem trava, lock livre, `0|0|0`) deixou de valer como "ambiente apto". A liberação exige também:
  - o conjunto de migrations íntegro (sha256 da criação);
  - o histórico com **exatamente** as versões esperadas com sucesso e nenhuma falha;
  - `flyway validate` aprovado.
  Consulta que falha bloqueia. Ensaio: **I8**, instalação interrompida com a 011 em execução.
- Novo subcomando `homologacao.sh implantar <id>`, para concluir as versões pendentes com os mesmos controles.

**O que mudou na revisão 2:**
- **Controles da elevação temporária**, ensaiados só em ambiente descartável:
  - lista de versões com sha256 autorizado;
  - trava e exclusão de implantações simultâneas;
  - falha de revogação tratada como falha da implantação;
  - encerramento de sessões e conferência final;
  - recuperação independente do runner;
  - bloqueio da liberação enquanto houver resíduo.
- **Redação corrigida:**
  - a revogação é "executada e conferida nos caminhos testados", e não "garantida";
  - o migrador tem os privilégios de ownership;
  - a conclusão da medição fica restrita aos caminhos ensaiados;
  - a duração das janelas é uma medição, não um prazo.
- **Aprovação da 015:** retirada das pendências.
- **H06:** a distinção entre Platform e Portal foi mantida também na conclusão.

## 1. Resumo

| Parte | Resultado |
|---|---|
| Integração Git (Etapa A) | **Concluída.** `origin/main` e `origin/feat/api-v1` em `d608a96` (fast-forward `0a333db..d608a96`, sem merge e sem force). Conteúdo conferido contra o pacote revisado r3 |
| Ambiente de homologação (Etapa B) | **Preparado e reproduzível** (`ops/homologacao/`). Ambiente da equipe `hml03` no ar (criado pelo runner da revisão 2), só em `127.0.0.1:55643`, com dados sintéticos e estado **LIBERADO pela liberação da revisão 3** (migrador recuperado e 001–015 com `flyway validate`) |
| Identidade de implantação e Flyway (Etapa C) | **Direção aceita para a homologação isolada** (elevação temporária em 011–013), condicionada aos controles. Controles implementados e ensaiados (I1–I5 na revisão 2; I1–I8 na revisão 3; I1–I10 com os ajustes da revisão 4). **O gate só fecha após a revisão destes dois ajustes**, e não se estende a produção |
| Testes (Etapa D) | H01–H09 e R015 **OK** (execução de `hml02` com o runner da revisão 1); I1–I5 **OK** (runner da revisão 2); I1–I8 OK com o runner da revisão 3; **I1–I7, I9, I10 e I8 OK** com o runner da revisão 4, em ambientes descartáveis. Limites na §5 |
| Liberação para produção | **Não.** Os gates da §7 continuam abertos |

## 2. Etapa A — integração Git

1. **Estado encontrado:** branch `feat/api-v1`, HEAD `d608a96`, remoto `origin` (GitHub `Nekkora-Operation/Nekkora-Platform-API`). A `main` local e a remota estavam em `0a333db`. `git fetch` confirmou o remoto, com apenas a `main` publicada.
2. **Ancestralidade:** `0a333db` → `9ff34ae` (013) → `d608a96` (API v1). O `d608a96` estava 2 commits à frente e 0 atrás de `origin/main`, então o fast-forward era possível.
3. **Fluxo do repositório:** histórico linear e fast-forward (como na 012). O `gh` não está instalado, então não foi possível consultar PRs nem a proteção de branch pela API.
4. **Conteúdo conferido antes de publicar**, a partir do commit:
   - o delta `9ff34ae..d608a96` bate com o pacote r3 (545 arquivos), e só o relatório difere, pelo registro de encerramento;
   - o delta publicado `0a333db..d608a96` (760 arquivos) não tem `target/`, binários, chaves nem temporários;
   - 1 falso positivo de credencial: um teste que monta `ALTER ROLE … PASSWORD` com valor aleatório gerado em execução.
5. **Publicação:**
   - `git push -u origin feat/api-v1`;
   - `main` avançada só por fast-forward (`git fetch . feat/api-v1:main`), depois `git push origin main`.
   - Resultado no remoto: `refs/heads/main` = `refs/heads/feat/api-v1` = `d608a96eea7e5eef5cc6ea6deee8d0c05b79e299`.
6. **Preservados:** os ensaios não versionados e `db/tests/portal-8-2/`.

## 3. Etapa B — homologação isolada

**Onde está:** `ops/homologacao/`, com `README.md` (operação), `IMPLANTACAO.md` (identidade, controles e recuperação) e `exemplo.env` (variáveis sem valores).

| Aspecto | Configuração |
|---|---|
| Recursos | Projeto `nekkora-homologacao-<id>`. Tudo com o rótulo `nekkora.homologacao=<id>`. Redes `interna` (`internal: true`, `10.252.10.0/24`) e `borda`. Volume `pgdata`. Imagens `nekkora-homologacao-{platform,portal}:d608a96`. A sub-rede é fixa, porque o `pg_hba` autoriza por IP: **um ambiente por vez** por host |
| Proteção | O `criar` recusa id, rótulo, projeto, sub-rede ou porta em uso **antes** de criar qualquer recurso. O `destruir` remove só o rótulo do id, com confirmação explícita. `estado`, `testar` e a subida dos serviços recusam ambiente BLOQUEADO |
| Exposição | Só o gateway é publicado (`127.0.0.1:55643` nesta máquina). PostgreSQL, Redis, Platform e Portal ficam na rede interna, sem porta |
| TLS | CA própria por ambiente (30 dias). Gateway HTTPS com o nome da origem. PostgreSQL com TLS ≥ 1.2 e clientes em `verify-full`. Redis só TLS |
| PostgreSQL | SCRAM local e remoto, **sem trust**. `pg_hba` só `hostssl` + SCRAM, cada identidade só do próprio IP, e o resto `reject` |
| Redis | `default` desligado. ACL por serviço (prefixos e comandos mínimos). Senhas por hash |
| Credenciais | Por identidade e serviço, geradas fora do Git. Senhas PostgreSQL como verificador SCRAM. Chaves de MFA/HMAC exclusivas. Sem segredo com valor padrão. Nomes das variáveis mantidos em português |
| Limites | platform 2 CPU / 1 GiB; portal 1 / 768 MiB; postgres 1 / 1 GiB; redis 0,5 / 256 MiB; gateway 0,5 / 128 MiB. Platform e Portal com sistema de arquivos só leitura |
| Imagens (digest) | `postgres@sha256:721873c3…`, `redis@sha256:858f009f…`, `nginx@sha256:56168782…`, `flyway/flyway@sha256:df06535d…`, `eclipse-temurin@sha256:51ab5e33…` (completos em `evidencias/hml02/testes-*/h01_identificacao.txt`) |
| Dados | Somente sintéticos |

## 4. Etapa C — identidade de implantação e Flyway

Detalhes em `ops/homologacao/IMPLANTACAO.md`.

**Identidades:**
- `admin_cluster`: bootstrap, elevação e recuperação;
- `migrador_nekkora`: implantação;
- `backup_nekkora`: backup lógico;
- papéis de serviço: os das migrations.

**O migrador** fica **sem atributos administrativos elevados permanentes**, mas **mantém os privilégios de ownership**: é dono do banco e dos objetos não reatribuídos (46 tabelas, 67 funções, histórico do Flyway), o que inclui alterar e excluir esses objetos. Por isso a credencial fica restrita à implantação mesmo fora da janela:
- só no `flyway.conf` do job;
- `pg_hba` só do container efêmero do Flyway;
- nenhum serviço conecta como ele.

**Medição** (`mapa_privilegios.sh`, evidência em `evidencias/mapa-privilegios/`): 001–010, 014 e 015 aplicam sem elevação; 011–013 exigiram elevação, também com os papéis pré-criados (`must be able to SET ROLE …`, `permission denied to alter role`).

**Conclusão, limitada ao que foi medido:** nos caminhos ensaiados, com as migrations atuais e sem memberships em papéis protegidos, 011–013 exigiram elevação. Os caminhos considerados para evitá-la conflitam com o invariante de membership e não foram adotados. Isto não prova que nenhuma alternativa exista, por exemplo uma alteração aditiva futura revisada.

**Controles da elevação** (runner da revisão 4):

| Controle | Mecanismo | Evidência | Resultado |
|---|---|---|---|
| Versões e checksums autorizados | `elevacao.txt` = versão + sha256 dos bytes versionados (LF); sha256 diferente → recusa antes de conceder, sem normalizar fim de linha | Criação de `hml03`/`ensaio-controles` (sha256 em cada janela); **I1**; clone limpo com a correção da 012 e variante CRLF recusada (§0) | OK |
| Exclusão de implantações **e recuperações** simultâneas | **Trava no banco:** advisory lock de sessão único no cluster do ambiente (`pg_try_advisory_lock(20260926, 1)`), disputado por `implantar.sh` (usado por `criar` e `implantar`) e por `recuperar`. É obtido **antes de qualquer ação** e segurado até o fim, cobrindo a janela inteira. Quem não obtém sai sem elevar, revogar, encerrar sessões nem remover containers. A trava local e a pré-condição `0\|0\|0` continuam como camadas adicionais | **I6** (5 rodadas de largada simultânea antes da elevação, com travas locais diferentes. Em todas, só um prosseguiu. O perdedor saiu com código 3 e uma única linha de registro, sem janela e sem trava local. O vencedor seguiu elevado e terminou pela falha de ensaio, `division by zero`, não por conexão encerrada); **I7** (`recuperar` durante implantação ativa: recusado sem alterar nada, e o migrador seguiu elevado até o fim normal do runner); **I2**/**I3** (segunda implantação com o primeiro já elevado, com a mesma trava local e com outra: recusada pela trava no banco) | OK |
| Encerramento das conexões e conferência final | Depois de cada janela e no fim: revogar atributos, encerrar sessões do migrador, conferir `0\|0\|0`; só então liberar a trava | `janelas_de_elevacao.txt` de `hml03`; **I4** (sessão órfã encerrada pela recuperação) | OK |
| **Perda da sessão da trava** | Ações privilegiadas só pela própria sessão da trava, que antes confere o lock; cada conexão do Flyway confere no `initSql` o lock com o backend da execução; perdida a trava, o runner para (código 6) sem revogar nem encerrar sessões e mantém a trava local; o `recuperar` também age pela sessão da trava | **I9a** (Flyway de A em execução: B obteve a trava, viu `1\|0\|1` e saiu sem agir; A terminou a migration em curso e parou com código 6 sem revogar); **I9b** (A parado logo antes de conceder: B entrou, elevou e executou; A retomou na janela de B e parou com código 6 sem conceder, revogar nem encerrar; B terminou normalmente, com a própria revogação conferida); **I9c** (A elevado, depois da conferência pontual, com outro executor dono da trava: Flyway de A recusado na conexão pelo `initSql`, nenhuma migration) | OK |
| Falha de revogação = falha da implantação | Com a trava válida: runner para com código 4, **mantém a trava** e não continua. Com a trava perdida: código 6 (linha anterior) | **I5** (banco parado na janela: a sessão da trava cai junto; código 6, sem revogar por outra conexão; bloqueio e recuperação como antes) | OK |
| Código de saída do `implantar` | Devolve o código do runner; com o runner concluído, **7** se a conferência final recusar; 0 só com LIBERADO | **I10** (runner concluído, registro de migrations divergente: `implantar` retornou 7 e imprimiu BLOQUEADO; com o registro restaurado, 0 e LIBERADO) | OK |
| Interrupção abrupta e recuperação | `kill -9` do runner (o PID morto era o dono da trava) + `docker kill` do Flyway na janela → elevação `1\|0\|1` e trava residuais → `estado`, `testar` e nova implantação **recusados** → `recuperar` obtém a trava no banco (ela caiu com o processo morto), revoga, encerra 1 sessão, confere `0\|0\|0` e libera; histórico intacto | **I4**; também uma recuperação de estado residual **real**, deixado pela primeira rodada do ensaio da revisão 2 (`recuperacao-estado-residual-real-*.txt`) | OK |
| **Migrador recuperado ≠ ambiente apto** | LIBERADO exige (1) migrador recuperado: sem trava local, trava no banco livre, `0\|0\|0`; **e** (2) ambiente apto: migrations do ambiente idênticas às da criação (sha256), histórico com exatamente as versões esperadas com sucesso, nenhuma falha e `flyway validate` aprovado. Consulta que falha bloqueia. O `recuperar` informa as duas partes separadamente | **I8** (criação interrompida por `kill -9` + `docker kill` com a 011 **em execução**. `recuperar` → código 0, migrador `0\|0\|0`, mas "ambiente CONTINUA BLOQUEADO", faltando 011–015. `estado` BLOQUEADO só pela instalação incompleta; `testar` recusado; com o postgres parado, BLOQUEADO por consulta falha. `implantar` aplicou as 5 pendentes com revogação conferida → LIBERADO) | OK |

- **Revogação:** executada e conferida nos caminhos testados (fim normal, falha de migration, sinais capturáveis, falha no meio da janela). Para SIGKILL, queda do host ou banco indisponível na revogação, **não há garantia pelo runner**: a garantia é o bloqueio da liberação somado à recuperação independente, ensaiados em I4, I5 e I8.
- **Conexão já aberta na perda da trava:** a conexão do Flyway já aberta termina a migration em curso (o `initSql` vale na abertura). Um novo executor recusa enquanto ela existe, e o runner antigo para ao fim dela sem revogar (I9a).
- **Limite da exclusão:** a trava no banco vale para quem usa estes scripts. Um `ALTER ROLE` ou uma sessão aberta manualmente por um superusuário, fora deles, não a disputa. Nesse caso restam a pré-condição `0|0|0` e a liberação. Por isso a credencial `admin_cluster` fica restrita ao operador da homologação (`IMPLANTACAO.md`, §5).
- **Duração das janelas:** de 3,6 a 5,3 s (incluindo a subida do container do Flyway). É uma medição, **não um prazo máximo garantido**.

## 5. Etapa D — resultados

**Execução de `hml02`** (`ops/homologacao/evidencias/hml02/`, runner da revisão 1):

| Teste | Resultado | Evidência de aceite obtida | Limites |
|---|---|---|---|
| H01 | OK | Commit e digests; limites em todos os serviços; só o gateway publicado; rede interna `internal=true`; preexistentes e stack principal inalterados | Publicação em `127.0.0.1`; o acesso da equipe exige `HOMOLOG_BIND`, DNS e a CA (README) |
| H02 | OK | 001–015 pela identidade dedicada; 3 janelas conferidas; reexecução só valida histórico e checksums | Runner da revisão 1. O runner da revisão 2 foi exercitado de novo na criação de `hml03` e de `ensaio-controles`, sempre `0\|0\|0` ao fim, e em I1–I5. O da revisão 3, na criação de `ensaio-r3`, na conclusão da instalação interrompida (I8) e em I1–I7 |
| H03 | OK | Catálogo igual ao da instalação de referência (3 475 linhas; instalador normalizado; só difere o histórico do Flyway); invariantes 012–015 reexecutados; atributos e memberships; SECURITY DEFINER só nos owners protegidos; serviços pelo próprio IP com TLSv1.3; `pg_hba` sem `trust` | Os donos não reatribuídos passam a ser `migrador_nekkora` |
| H04 | OK | Gateway: cadeia verificada e TLS 1.1 recusado. PostgreSQL: recusados texto claro, CA errada, nome errado, senha errada e papel fora do próprio IP. Rede interna sem saída. Redis: TLS, `NOAUTH`, `NOPERM` por ACL | Redis sem certificado de cliente |
| H05 | OK | 26 verificações só pelo gateway (MFA, Portal com o cookie da Platform, isolamento, logout, revoke-all, CSRF e Origin) | Cliente HTTP, não navegador |
| H06 | OK | **Platform, evidência nova:** uma conexão física, 85 transações alternando duas organizações, 85 `DISCARD ALL`, 0 transações sem reset. **Portal, evidência anterior reutilizada:** `PortalIT` e `ConnectionResetIT`, porque o papel do Portal tem `log_statement=none` por migration | A conclusão da H06 vale **integralmente para a Platform nesta homologação**. Para o Portal, vale pela evidência anterior |
| H07 | OK | 17 verificações (Redis fora; R7 esgotada com logout e revoke-all → 204; logout idempotente; revoke-all sem sessão → 401) | — |
| H08 | OK | 107 marcadores ausentes de 2 589 linhas de log e de todas as evidências; controle positivo | Sem exportador de traces e métricas |
| H09 | OK | Id em uso, checksum divergente, falha simulada com e sem elevação: parou, sem nova tentativa, histórico intacto | Runner da revisão 1. Os cenários de interrupção estão em I1–I5 |
| R015 | OK | Backup como `backup_nekkora`, restauração completa noutro cluster, catálogo e dados idênticos | Repete a parte afetada da 015 |
| Suítes anteriores | reutilizadas | Suítes SQL 012/013/014, restauração-015 original, atualização populada, 58 testes Maven e E2E 6/6 | Sem mudança de schema nem de código de produção |

**Execução de `ensaio-controles`** (descartável, runner da revisão 2; `evidencias/ensaio-controles/`): **I1–I5 OK**, detalhados na §4. Ao final, o histórico estava idêntico ao inicial, sem linha 900, o migrador em `0|0|0`, sem trava, e o Portal voltou a responder depois do banco.

**Revisão 4 — caminhos afetados** (descartáveis, runner da revisão 4):
- **`ensaio-r4`** (`evidencias/ensaio-r4/`):
  - rodada oficial `interrupcao-20260927T011922Z`: I1–I4, I6, I7, I9a, I9b, I9c e I10 OK. O I5 falhou só pela asserção antiga (esperava código 4). Ela foi atualizada para o comportamento novo (código 6), e o I5 foi repetido isoladamente em `interrupcao-20260927T012551Z-I5-asercao-atualizada`: OK;
  - ao fim: histórico idêntico ao inicial, sem linha 900, migrador `0|0|0`, sem trava e trava no banco livre.
- **`ensaio-i8b`** (`evidencias/ensaio-i8b/`): I8 repetido com o runner novo, porque passa por `criar` (001–010 com o `initSql`) e por `implantar` (011–015, com as elevações pela sessão da trava): 6 passos OK.
- **`hml03`:** parado de novo sem remover o volume, religado ao fim, LIBERADO. Stack principal inalterada.

**Revisão 3 — só os caminhos afetados** (descartáveis, runner da revisão 3):
- **`ensaio-r3`** (`evidencias/ensaio-r3/`):
  - criação completa com a nova liberação: LIBERADO com as duas partes;
  - **I1–I7 OK** na rodada oficial `interrupcao-20260927T004307Z`, a primeira e única desta revisão. I1–I5 foram repetidos porque as mensagens e a conferência da trava no banco mudaram. I6 e I7 são novos;
  - ao final: histórico idêntico ao inicial, migrador `0|0|0`, trava local ausente e trava no banco livre.
- **`ensaio-i8`** (`evidencias/ensaio-i8/`, `testes/instalacao_incompleta.sh`): **I8 OK** nos 6 passos.
- **`hml03`:** foi parado com `compose down`, **sem** `-v`, porque a sub-rede é única. O volume foi preservado. Depois do ensaio, o ambiente foi religado com as imagens reconstruídas do mesmo commit e responde LIBERADO pela liberação nova. H01–H09 **não** foram repetidos, como autorizado.
- A stack principal ficou inalterada (`started` e `restarts` iguais aos do início).

**Primeira rodada do ensaio da revisão 2 (depuração, não oficial):** encontrou três defeitos, corrigidos antes da rodada oficial:
- o `recuperar` encerrava cedo por `pipefail` numa linha que só exibe o estado;
- um redirecionamento para um diretório ainda inexistente;
- uma asserção rígida demais na I3.

O estado residual real que essa rodada deixou foi recuperado pelo `recuperar` corrigido, e essa evidência está incluída.

## 6. Achados

1. **As migrations 001–011 não têm `BEGIN`/`COMMIT`.** A afirmação do README do ensaio de atualização foi corrigida. A implantação escolhe o modo por arquivo.
2. **O invariante da 012 não tem a exceção "de migration"** que o comentário cita. Por isso as identidades novas têm nomes fora do padrão `nekkora_*`.
3. **Variáveis de ambiente visíveis a quem tem acesso ao Docker do host.** Produção exige cofre de segredos.
4. **`curl` do Git no Windows (Schannel)** tenta checar revogação; o runner usa `--ssl-no-revoke`, e a cadeia continua verificada.
5. **pgcrypto instalado sem superusuário:** os objetos ficam com o superusuário do bootstrap como dono.
6. **Sub-rede fixa por causa do `pg_hba` por IP:** um ambiente por vez por host. Por isso `hml02` foi removido para o ensaio descartável e o ambiente da equipe foi recriado como `hml03`.

## 7. Gates

| Gate | Situação | Motivo / próxima ação |
|---|---|---|
| Integração da API v1 à `main` | **Encerrado** | `d608a96` em `origin/main` |
| Homologação isolada reproduzível | **Encerrado** | Revisão r4 encerrada sem bloqueio; versionada na `main`. Reprodução a partir de clone limpo conferida depois da correção da 012 (§0) |
| Flyway / credencial de migration dedicada | **Encerrado para a homologação isolada** (não se estende a produção) | Exclusão compartilhada no banco (I6, I7), liberação em duas partes (I8), perda da sessão da trava (I9) e código de saída do `implantar` (I10) implementados e ensaiados, junto com I1–I5. Não se estende a produção |
| Backup e restauração (operação) | Aberto | A 015 foi abrangida pelo encerramento da revisão r3. Continuam os gates operacionais: identidade e rotina de backup em produção, retenção e ensaio periódico de restauração, procedimento para backup antigo |
| E-mail real | Aberto | Missão própria |
| *Break-glass* de MFA | Aberto | Missão própria |
| Argon2 no perfil de produção | Aberto | Comparar concorrência 1, 2 e 4 com a API em funcionamento, sem reduzir parâmetros |
| Capacidade e gateway com várias réplicas | Aberto | Carga, saturação e abuso |
| Operação C1–C11 | Aberto | A homologação cobre parte (TLS, SCRAM, `hba`, identidades); cada controle precisa de fechamento próprio |
| Banco e retenção (virada UTC, fingerprint, expurgo) | Aberto | Fora do escopo |

## 8. Recursos criados e destino

| Recurso | Destino |
|---|---|
| Ambiente `hml03` (5 containers, redes, volume, imagens `:d608a96`) e segredos em `$HOME/.nekkora/homologacao/hml03` | **Mantido** para a equipe e a revisão. Na revisão 3, parado sem remover o volume, durante os ensaios, e religado com os mesmos dados. Remoção: `HOMOLOG_CONFIRMA_DESTRUIR=hml03 ops/homologacao/homologacao.sh destruir hml03`; segredos por descarte manual |
| Ambiente `hml02` (execução H01–H09 e R015) | Removido pelo `destruir` (só o rótulo `hml02`), junto com os segredos, para liberar a sub-rede. Evidências preservadas em `evidencias/hml02/` |
| Ambiente descartável `ensaio-controles` (I1–I5) | Removido pelo `destruir`, com os segredos e as saídas. Evidências em `evidencias/ensaio-controles/` |
| Ambientes descartáveis `ensaio-r4` (I1–I7, I9, I10) e `ensaio-i8b` (I8), revisão 4 | Removidos pelo `destruir` (só o rótulo de cada id), com os segredos e as saídas. Evidências em `evidencias/ensaio-r4/` e `evidencias/ensaio-i8b/` |
| Ambientes descartáveis `ensaio-r3` (I1–I7) e `ensaio-i8` (I8), revisão 3 | Removidos pelo `destruir` (só o rótulo de cada id), com os segredos e as saídas. Evidências em `evidencias/ensaio-r3/` e `evidencias/ensaio-i8/` |
| Ambiente de depuração `hml01` e containers efêmeros (Flyway, ferramentas, backup, referência, restauração, mapa, ensaio do Flyway) | Removidos (0 restantes) |
| Stacks preexistentes e volumes anônimos antigos | Intocados |

## 9. Reprodução

```sh
git switch feat/homologacao
ops/homologacao/mapa_privilegios.sh                      # e MAPA_PAPEIS_PRECRIADOS=1
ops/homologacao/homologacao.sh criar ensaio-x            # descartável
ops/homologacao/homologacao.sh ensaio-interrupcao ensaio-x   # I1-I7, I9, I10 (ou so alguns: ... ensaio-x "I9 I10")
HOMOLOG_CONFIRMA_DESTRUIR=ensaio-x ops/homologacao/homologacao.sh destruir ensaio-x
ops/homologacao/testes/instalacao_incompleta.sh ensaio-y       # I8: cria, interrompe, recupera, conclui
HOMOLOG_CONFIRMA_DESTRUIR=ensaio-y ops/homologacao/homologacao.sh destruir ensaio-y
ops/homologacao/homologacao.sh criar hml04 && ops/homologacao/homologacao.sh testar hml04
```

## 10. Arquivos desta missão (versionados: `9491a3e` e o commit da correção da 012)

- **Novos:**
  - `ops/homologacao/`: compose, `pg_hba`, gateway, implantação (`implantar.sh`, `elevacao.txt`), `homologacao.sh`, `lib/`, `testes/` (inclusive `interrupcao.sh` e `instalacao_incompleta.sh`), SQL, README, `IMPLANTACAO.md`, `exemplo.env`, evidências oficiais;
  - `api/nekkora-platform/src/test/java/com/nekkora/platform/auth/crypto/PhcTool.java` (só teste);
  - este relatório.
- **Correção posterior:** a linha 012 de `ops/homologacao/implantacao/elevacao.txt` (hash LF), com as evidências `ensaio-lf-antes/` e `ensaio-lf/` e as notas de encerramento.
- **Modificado:** `db/tests/atualizacao-populada/README.md`.
- **Não modificados:** migrations 001–015, código de produção da API e a stack principal.
