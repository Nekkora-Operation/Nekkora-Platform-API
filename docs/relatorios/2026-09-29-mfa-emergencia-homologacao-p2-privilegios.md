# P2 — Medição dos privilégios exigidos pela migration 016

Data: 2026-09-29. Cenário do plano: B1. Subsídio para D-ELEV/P3. **Esta missão não autoriza elevação no runner de
homologação nem libera produção.** `implantacao/elevacao.txt`, migrations, `implantar.sh` e `lib/estado.sh` não foram
alterados.

**Situação: APROVADO COMO MEDIÇÃO B1** pela responsável em 2026-09-29, com base na revisão do código e das evidências,
sem reexecução dos testes. A aprovação **não** autoriza a elevação, o ensaio nem commit/push. A decisão de elevação é
P3 (`2026-09-29-mfa-emergencia-homologacao-p3-decisao-elevacao.md`). Correções documentais posteriores: §11.

**Notação `0|0|0`** (consulta `estado_migrador` de `lib/estado.sh`, repetida no harness). São três contagens, nesta
ordem, e não três atributos individuais:
1. soma dos atributos privilegiados de `migrador_nekkora` (`rolsuper + rolcreaterole + rolcreatedb + rolbypassrls +
   rolreplication`, de 0 a 5);
2. número de participações envolvendo o migrador, como membro ou como papel concedido (`pg_auth_members`);
3. número de sessões do migrador (`pg_stat_activity`).

Assim, `1|0|0` é um atributo elevado, sem participação e sem sessão. Não significa "super=1". Os valores
`retirada|…` e `estado_migrador=…` das evidências seguem esta notação. Por exemplo, `retirada_atributos|0|4|0` em D3
são zero atributos e as 4 participações ADMIN/SET concedidas para a sonda, mantidas até o descarte do cluster.

Evidência: `ops/homologacao/saidas/p2r-20260929T041513Z/` (execução desta missão), `saidas/mapa-20260929T042114Z/` e
`saidas/mapa-20260929T042128Z-precriados/` (mapa), `saidas/p2-20260929T040144Z/` e `saidas/mapa-20260929T0406*`
(tentativa anterior interrompida, preservada sem alteração).

## 1. Resumo para D-ELEV

- **A identidade normal de implantação não aplica a 016 original** (M1: 42501 em `CREATE ROLE`). **A pré-criação dos
  papéis não resolve** (M2: 42501 no `ALTER ROLE … NOSUPERUSER` da linha 61).
- **Nenhuma combinação sem SUPERUSER medida passa da linha 61.** Com tudo exceto SUPERUSER (D3: CREATEROLE,
  REPLICATION, BYPASSRLS, papéis pré-criados com ADMIN/SET, `SET` no parâmetro), sobram 7 recusas em três operações
  que só o superusuário resolve com os bytes originais: `ALTER ROLE … NOSUPERUSER` (61–65), `CREATE EXTENSION sslinfo`
  (73) e a transferência de propriedade da seção 18 (3049–3064), e mais o invariante de *membership* (3094–3241),
  que reprova justamente as concessões que contornariam a terceira.
- **Conjunto demonstrado suficiente:** `SUPERUSER` temporário na identidade dedicada, numa janela só da 016 (M4:
  código 0, catálogo 37/21/9, invariantes OK), com retirada pelo comando do runner e retorno a `0|0|0` sem
  *membership* residual (M5). Não se alega mínimo absoluto; alega-se que, nos casos testados, nada abaixo de SUPERUSER
  basta para os bytes originais.
- **Permanece necessária uma janela de superusuário para a 016 original no modelo atual.** As operações responsáveis
  estão na §5.
- **Pré-instalar `sslinfo` é incompatível com os bytes originais** (M3e: 42P06 em `CREATE SCHEMA ops`; M3f: 42710 em
  `CREATE EXTENSION`). Não é alternativa de preparo sem alterar a migration.
- **Pré-criar os papéis (sem *membership*) é compatível com a janela** (M3g: código 0), mas não dispensa a janela.

## 2. Referência e baseline

| Item | Valor | Situação |
|---|---|---|
| HEAD / branch | `dd74a8f6e9a7b487e5698c11ca026e2c4666177e` / `feat/mfa-emergencia` | sem commit posterior; P1b/P1c/P2 sem commit |
| ZIP P1c | `homologacao-016-p1c-20260929T034607Z.zip`, SHA-256 `302104b7449d1d49fe962abd5a2f98bb5a656ac461eed2cf90c589cc1348e04b` | conferido |
| Manifesto P1c | 197/197 | conferido depois de extrair |
| `repo/` do P1c × árvore de trabalho | 51/51 arquivos idênticos byte a byte | COMPROVADO |
| 016 | SHA-256 `25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61` (repositório e cópia em cada container) | conferido antes de cada tentativa |
| `elevacao.txt` | `eb98911a…0e6d`, inalterado | 011–013 usadas só no preparo da base, com os sha256 autorizados |

Status completo, hashes e `git status` iniciais: `baseline/git_inicial.txt`.

**Tentativa anterior (achada, não desta sessão).** Entre 04:01Z e 04:09Z uma execução anterior de P2 criou
`testes/p2_privilegios.sh` (ausente do P1c), rodou o mapa e os casos M1, M2, D1, D2, M3a e M3b e parou durante M3c.
Os resumos de M3b/M3c ficaram truncados/corrompidos, o container `nekkora-p2-m3c-d87b91` ficou sem registro de remoção
e o daemon Docker estava parado quando esta sessão começou. As saídas foram preservadas sem alteração (hashes em
`baseline/tentativa_anterior_sha256.txt`). A versão anterior do harness está em
`baseline/p2_privilegios.sh.tentativa_anterior`. Os resultados coincidentes (M1, M2, D1 = 52 recusas, D2 = 31, M3a,
M3b) foram reproduzidos nesta execução.

## 3. Preparação e segurança

**Docker parado (condição inicial).** `inventario/00_docker_parado.txt`. O início do Docker Desktop foi autorizado pela
responsável e feito às 04:16:13Z (daemon pronto às 04:16:24Z, servidor 29.7.2). **Efeitos do início, separados da
medição** (`inventario/01_apos_inicio_docker.txt`, `02_containers_detalhe.txt`):

- `nekkora-postgres`, `nekkora-portal-013-postgres` e `nekkora-portal-dev-postgres` (`restart: unless-stopped`) voltaram
  sozinhos às 04:16:23Z. Estavam em execução quando o daemon parou (`finished` 04:16:22Z é a marcação do daemon ao
  subir).
- `hml03` (5 containers) e `nk016-smoke` continuaram parados (exit 255 de 2026-09-28T02:36Z), sem mudança.
- A sobra `nekkora-p2-m3c-d87b91` (rótulo `nekkora.p2=M3c`) foi marcada como exited 255. Com autorização, foi
  removida por id exato junto com seu volume anônimo `33436cb5…` (conferido antes: só ela montava o volume). Registro
  em `recursos_p2.txt`.

**Isolamento.** Cada caso roda num cluster PostgreSQL 16 novo, `--network none` (sem rede e sem porta), SCRAM sem
`trust`, com rótulo `nekkora.p2=<caso>`, removido com `docker rm -f -v` (volume anônimo junto). Nenhuma rede criada;
sem sobreposição de sub-redes possível. Papéis são objetos do cluster, por isso usou-se um cluster por caso e nunca a
reutilização. Senhas efêmeras ficam só em memória e os verificadores SCRAM num diretório temporário fora do
repositório, apagado. Nada de senha na evidência.

**Servidor.** `postgres:16-alpine` `sha256:721873c3…a6080ea`, PostgreSQL 16.15. `createrole_self_grant` vazio,
`password_encryption=scram-sha-256`, `ssl=off` (irrelevante para instalar `sslinfo`), sem `*_preload_libraries`.
`sslinfo` 1.2 disponível, `trusted=false`, `superuser=true` (`casos/*/servidor.txt`, `estado_antes.txt`).

**Base 001–015 (harness).** Bootstrap real (`sql/bootstrap.sql`: `migrador_nekkora` NÃO superusuário, dono do banco).
Migrations aplicadas como `migrador_nekkora` por psql, no modelo de `implantar.sh`: sem BEGIN próprio →
`--single-transaction`. Elevação SUPERUSER só em 011, 012 e 013, com o sha256 de `elevacao.txt`, revogada pelo mesmo
comando do runner e conferida `0|0|0` depois de cada janela (3 janelas por caso, todas revogadas). Antes de cada
caso: `rolsuper/createrole/createdb/replication/bypassrls=false`, sem *membership*, sem ACL de parâmetro.

**Caminho não comprovado.** A 016 foi aplicada por **psql**, não por Flyway. O caminho de implantação pelo Flyway
(histórico, `validate`) fica para o ensaio (B2), fora desta missão.

## 4. Instrumentos

- `ops/homologacao/mapa_privilegios.sh` (do plano; alvo único: container próprio). **Alteração mínima:**
  `--network none` e registro do id do container (criação e remoção) em `container.txt`. Sem mudança de lógica. O mapa
  usa bootstrap próprio (CREATE ROLE + CREATE DATABASE), não `sql/bootstrap.sql`, e só dá o **primeiro** erro por
  migration. Por isso não basta para P2, daí o harness.
- `ops/homologacao/testes/p2_privilegios.sh` (harness de P2, primeira versão da tentativa anterior). **Alterações
  nesta missão:** localização da instrução recusada pelo início (o psql informa a linha final, `$$;`), fotografia do
  servidor, estado com `schema ops`/ACL de parâmetros, atributos explícitos antes/depois, conferência automática do
  catálogo no M4 e casos novos D3, M3f, M3g e sondas M3d reorganizadas (a–d). Diff em relação à versão anterior no
  pacote.
- **Sondas** (D1–D3 e M3d) são rotuladas como tal e **não substituem** a tentativa integral. D* reaplicam os mesmos
  bytes com `ON_ERROR_STOP=0 ON_ERROR_ROLLBACK=on`: cada instrução recusada é desfeita isoladamente e a execução segue,
  listando também recusas posteriores e em cascata.

Comandos: `p2r-…/comandos.txt`. Saída consolidada: `p2r-…/execucao.txt`.

## 5. Resultados por caso

Tentativas **integrais** (bytes originais, `ON_ERROR_STOP=1`, como `migrador_nekkora`):

| Caso | Pré-condição (além da base normal) | Código | SQLSTATE / instrução recusada | Estado depois |
|---|---|---|---|---|
| M1 | nenhuma | 3 | 42501 `permission denied to create role`, DO da seção 1 (44–59) | rollback total: 4 papéis ausentes, sem `ops`, catálogo 0 |
| M2 | papéis pré-criados pelo admin (atributos da 016, sem *membership*) | 3 | 42501 `permission denied to alter role`, linha 61 | rollback; papéis pré-criados intactos |
| M3a | CREATEROLE | 3 | 42501 linha 61 | rollback; retirada 0\|0\|0 |
| M3b | pré-criados + ADMIN/SET do migrador | 3 | 42501 linha 61 | rollback |
| M3c | CREATEROLE REPLICATION BYPASSRLS | 3 | 42501 linha 61 | rollback; retirada 0\|0\|0 |
| M3e | `sslinfo` pré-instalada em `ops` pelo admin + SUPERUSER | 3 | **42P06** `schema "ops" already exists`, linha 69 | rollback da 016; preparo do admin permanece |
| M3f | `sslinfo` pré-instalada em `public` + SUPERUSER | 3 | **42710** `extension "sslinfo" already exists`, linha 73 | idem |
| M3g | pré-criados (sem *membership*) + SUPERUSER | **0** | — | catálogo 37/21/9 |
| **M4** | **SUPERUSER temporário** | **0** | — | catálogo 37/21/9, `sslinfo` em `ops`, invariantes OK |

Sondas:

| Sonda | Pré-condição | Recusas | Classes |
|---|---|---|---|
| D1 | nenhuma | 52 | 1 CREATE ROLE; 26 × 42704 papel inexistente (cascata); 1 sslinfo; 22 × `permission denied to set parameter "nekkora.fn_organization_id"`; 2 × 42883 (cascata) |
| D2 | pré-criados | 31 | 4 ALTER ROLE (61–65); 1 sslinfo; 22 SET de parâmetro; 1 `must be able to SET ROLE "nekkora_ops_mfa_owner"` (3049–3064); 2 × 42883 (cascata); 1 invariante (matriz EXECUTE, cascata) |
| **D3** | tudo exceto SUPERUSER (CREATEROLE REPLICATION BYPASSRLS + pré-criados com ADMIN/SET + `GRANT SET ON PARAMETER`) | **7** | 4 ALTER ROLE (61–65); 1 sslinfo (73); 1 **`permission denied for schema ops`** (DO 3049–3064, `ALTER FUNCTION … OWNER TO`); 1 invariante **`membership indevida envolvendo papel operacional`** (3094–3241) |

Sondas SQL isoladas (M3d, `casos/M3d/sondas.txt`), com o DETAIL do servidor:

| Sonda | Resultado |
|---|---|
| a1/a2 `CREATE EXTENSION sslinfo` (dono do banco; com CREATEROLE REPLICATION BYPASSRLS) | 42501, *Must be superuser to create this extension* |
| b1 `CREATE ROLE` com CREATEROLE | ok; b2: o criador recebe ADMIN automático (`admin=true set=false inherit=false`) |
| b3 / c6 `ALTER ROLE … NOSUPERUSER` (CREATEROLE + ADMIN) | 42501, *Only roles with the SUPERUSER attribute may change the SUPERUSER attribute* |
| b4 / b7 cláusulas sem NOSUPERUSER | 42501, *Only roles with the CREATEDB attribute may change the CREATEDB attribute* (NOCREATEDB também exige o atributo) |
| b5 / b6 `NOREPLICATION` / `NOBYPASSRLS` sem os atributos | 42501, exigem REPLICATION / BYPASSRLS |
| c1 / c7 `ALTER ROLE` sem CREATEROLE | 42501, exige CREATEROLE + ADMIN |
| c2 `OWNER TO` sem *membership* | 42501 `must be able to SET ROLE` |
| c4 `OWNER TO` com SET | 42501 `permission denied for schema public`: o **novo dono** precisa de CREATE no schema. A sonda revelou um requisito, não um sucesso |
| d1 / d3 `CREATE FUNCTION … SET nekkora.fn_organization_id` | recusado sem ACL; **ok** com `GRANT SET ON PARAMETER` (d4: revogado) |

## 6. Tabela para D-ELEV

| Operação da 016 | Privilégio ou pré-condição | Sem ela | Com ela | Evidência | Implicação |
|---|---|---|---|---|---|
| DO seção 1 (44–59) `CREATE ROLE` ×4 | CREATEROLE, ou papéis pré-criados | 42501 (M1) | passa (M3a; M2) | M1, M2, M3a | Resolve só esta. Com CREATEROLE o criador ganha ADMIN automático (b2), que o invariante reprova |
| `ALTER ROLE … NOSUPERUSER …` (61–65) | **SUPERUSER** (cláusula NOSUPERUSER); NOCREATEDB/NOREPLICATION/NOBYPASSRLS exigem ainda os respectivos atributos | 42501 em M2, M3a, M3b, M3c, D3 | passa (M3g, M4) | M3d b3–b7, c6 | **Impedimento principal.** Nenhum atributo abaixo de SUPERUSER basta com os bytes originais |
| `CREATE EXTENSION sslinfo SCHEMA ops` (73) | **SUPERUSER** (`trusted=false`) | 42501 (D1–D3, a1/a2) | passa (M4) | M3d a1/a2 | Pré-instalação incompatível: 42P06 (M3e) ou 42710 (M3f) |
| 22 funções com `SET nekkora.fn_organization_id` | SUPERUSER, ou `GRANT SET ON PARAMETER nekkora.fn_organization_id` | 42501 (D1, D2) | some em D3 | D3, M3d d1/d3 | Resolvível sem superusuário, **mas o GRANT exige superusuário** e deixa ACL a retirar |
| DO seção 18 (3049–3064) `ALTER FUNCTION … OWNER TO nekkora_ops_mfa_owner` | SUPERUSER; sem ele: SET ROLE no novo dono **e** CREATE do novo dono em `ops`/`public` | 42501 SET ROLE (D2); 42501 schema ops (D3) | passa (M4) | D2, D3, c2/c4 | CREATE para o dono contraria o invariante "papel operacional com CREATE efetivo". **Só o superusuário cumpre as duas** |
| Invariantes seção 19 (3094–3241) | nenhuma *membership* envolvendo papéis `ops_*` (exceto `op_*` em `nekkora_ops_mfa`) | P0001 em D3 (ADMIN/SET do migrador) | OK (M4) | D3, M4 | Proíbe as concessões que contornariam a seção 18 e o ADMIN automático do CREATEROLE |

## 7. Conclusões

| Requisito | Classificação |
|---|---|
| M1: primeira recusa com a identidade normal (42501, CREATE ROLE, seção 1; rollback total) | **COMPROVADO** |
| M2: a pré-criação elimina o CREATE ROLE, mas não o impedimento; próximo = 42501 no ALTER ROLE da linha 61 | **COMPROVADO** |
| M3: decomposição (CREATE/ALTER ROLE, sslinfo, propriedade, parâmetro, invariantes) | **COMPROVADO** (integral até a linha 61; o que vem depois só por sonda, rotulada) |
| M4: 016 integral conclui com SUPERUSER temporário; catálogo 37/21/9; invariantes OK | **COMPROVADO** (por psql; Flyway não comprovado) |
| M5: retirada volta a `rolsuper=false rolcreaterole=false rolcreatedb=false`, sem *membership*, sem ACL de parâmetro; o migrador não recupera elevação (SET ROLE, CREATE ROLE e ALTER ROLE SUPERUSER recusados) | **COMPROVADO** |
| Preservação de hml03, principal, nk016-smoke e volumes | **COMPROVADO** (§8), com os efeitos do início do Docker declarados |
| Caminho Flyway da 016 | **FORA DO ESCOPO DE P2 / PENDENTE PARA P4** (ensaio B2–B4). Não bloqueia o fechamento da medição (correção de 2026-09-29, §11) |

**Fatos medidos:** os resultados das tabelas §5 e §6 (PostgreSQL 16.15). **Inferências:** que as mesmas recusas
ocorrem no cluster de homologação real depende da mesma versão maior (16) e de `sslinfo` não confiável. Em
PostgreSQL 16 a regra de NOSUPERUSER é do servidor (`user.c`, AlterRole) e não de configuração. **Alternativas não
testadas:** alterar a 016 (retirar `NOSUPERUSER` e demais cláusulas, `CREATE EXTENSION IF NOT EXISTS`, dono
pré-concedido). Isso muda o hash aprovado e exige nova revisão. Também não testados: `pg_create_subscription` e
outros papéis predefinidos, que não se aplicam a nenhuma das recusas.

**Janela de superusuário:** permanece necessária para a 016 original no modelo atual. Operações responsáveis:
`ALTER ROLE … NOSUPERUSER` (61–65), `CREATE EXTENSION sslinfo` (73) e `ALTER FUNCTION … OWNER TO
nekkora_ops_mfa_owner` (seção 18, 3049–3064) sob o invariante da seção 19. O SET de parâmetro (22 funções) seria
resolvível sem superusuário, mas não muda a conclusão.

## 8. Recursos e inventário

- Criados e removidos nesta missão: 13 containers `nekkora-p2-*` (um por caso) e 2 do mapa, cada um com volume
  anônimo removido junto. Ids e horários em `p2r-…/casos/recursos_p2.txt` e `mapa-*/container.txt`. Nenhuma rede,
  volume nomeado ou imagem criados. Imagem `postgres:16-alpine` preexistente, reutilizada.
- Removida por autorização: a sobra da tentativa anterior (`nekkora-p2-m3c-d87b91` + volume `33436cb5…`).
- Inventário depois (`inventario/03_depois.txt`, `04_diff_containers.txt`): containers idênticos (id, estado,
  `StartedAt`, `FinishedAt`, reinícios) ao inventário pós-início. Redes e imagens idênticas. Volumes: só a ausência de
  `33436cb5…`. Build cache inalterado (58 entradas, 1,619 GB), preservado. Os 3 containers de desenvolvimento
  religados pelo início do Docker foram **deixados em execução** (estado anterior à queda do daemon).

## 9. Proposta de decisão (NÃO AUTORIZADA nesta missão; substituída pela decisão proposta de P3)

A minuta original desta seção foi **corrigida** em 2026-09-29 (texto original preservado na §11). A decisão proposta
vigente é `2026-09-29-mfa-emergencia-homologacao-p3-decisao-elevacao.md` (P3, PROPOSTA — AGUARDANDO AUTORIZAÇÃO DA
RESPONSÁVEL). Pontos corrigidos:

- **Recuperação.** A atomicidade da 016 (uma transação) e o rollback **observado** nos casos M1–M3f (erro devolvido
  pelo servidor, psql encerrado com código 3) **não** permitem concluir rollback a partir de uma desconexão. Se a
  conexão cair durante o `COMMIT`, ou se a resposta do `COMMIT` se perder, o resultado é **desconhecido**: a 016 pode
  ter sido efetivada sem que o cliente saiba, e o Flyway pode não ter registrado a versão. A recuperação exige
  retirada da elevação, encerramento das sessões do migrador, conferência `0|0|0` e **reconciliação** do estado
  (histórico do Flyway, catálogo e invariantes), preservando o procedimento de recuperação de B4. **Descartar o
  ambiente não substitui essa prova.**
- **Flyway.** Fora do escopo de P2, pendente para P4 (B2–B4). Não bloqueia a medição.
- **`0|0|0`:** ver a notação no início deste relatório.

Verificações que devem acompanhar P3: versão maior do servidor = 16, `sslinfo` disponível com `trusted=false`, papéis
`ops_*` pré-existentes sem *membership* (compatível, M3g), ausência de `ops`/`sslinfo` prévios (M3e/M3f), e o caminho
Flyway medido no ensaio.

## 10. Observação sobre a varredura

`ops/mfa-emergencia/varredura.py controle` imprime o texto fixo "gerados por Tokens.newToken da v1". No P1c e neste
pacote os 200 tokens foram gerados em **Python** (`secrets.token_bytes(32)`, base64url sem `=`). O pacote de P2
declara o gerador real ao lado dessa saída. O script não foi alterado (fora do escopo). Recomenda-se corrigir o texto
numa missão própria.

## 11. Histórico de correções (documentais; evidências de execução inalteradas)

| Data | Correção | Motivo |
|---|---|---|
| 2026-09-29 (entrega) | Versão entregue no pacote `homologacao-016-p2-20260929T042812Z.zip` (SHA-256 `4452fc55…f02cc`) | — |
| 2026-09-29 (P3) | Situação "APROVADO COMO MEDIÇÃO B1", sem autorização operacional | Aprovação da responsável |
| 2026-09-29 (P3) | Notação `0\|0\|0` explicitada como três contagens | Pedido da revisão: não ler como três atributos |
| 2026-09-29 (P3) | Flyway: de "BLOQUEADO" para "FORA DO ESCOPO DE P2 / PENDENTE PARA P4" | Pedido da revisão: não é bloqueio da medição |
| 2026-09-29 (P3) | §9: minuta de recuperação corrigida (resultado desconhecido; reconciliação; descarte não substitui B4) | Pedido da revisão |

Nenhum arquivo de `ops/homologacao/saidas/p2r-20260929T041513Z/`, `saidas/mapa-*` ou `saidas/p2-20260929T040144Z/`
foi alterado. A versão entregue deste relatório está em `repo/docs/relatorios/` do pacote P2.

**Texto original da §9 (preservado):**

> ## 9. Proposta de decisão (NÃO AUTORIZADA nesta missão)
>
> > Autorizo, **somente para o ambiente de homologação isolado** (projeto `nekkora-homologacao-ensaio-016a`), uma janela
> > de `SUPERUSER` para `migrador_nekkora` restrita à versão `016`, com SHA-256
> > `25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61`, acrescentando a linha correspondente a
> > `implantacao/elevacao.txt` ao lado das de 011–013, sem alterá-las. Condições: revogação pelo comando do runner
> > (`NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS NOREPLICATION`), encerramento das sessões do migrador e
> > conferência `0|0|0` imediatamente depois da janela e ao fim; nenhuma *membership* do migrador em papéis `ops_*`;
> > nenhuma ACL de parâmetro remanescente; `flyway validate` e o invariante da seção 19 com sucesso. Recuperação em caso
> > de falha: a 016 é atômica (rollback observado em M1–M3f). Revogar, conferir `0|0|0` e descartar o ambiente. Produção:
> > decisão separada.
