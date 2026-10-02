# P4-R1: diagnóstico de B5 e conferência de B12

**ESTADO: PARA REVISÃO.** Esta missão **não** aprova o P4. O P5 continua pendente.
**Data:** 2026-10-01, das 14:15Z às 14:45Z.
**Base:**
- pacote P4 `homologacao-016-p4-20260929T231321Z.zip`, SHA-256 `41dfe24b…765736f`;
- 016 aplicada `25bef818…1fbe61`;
- ambiente `ensaio-016a`;
- clone `lab-p4-20260929T192708Z` (S1 `327c4d4`, S2 `296808b`, S3 `1c2f26b`);
- original em HEAD `dd74a8f`.

**Evidências:** `ops/homologacao/saidas/p4r1-20261001T141831Z/`, fora do Git. As referências abaixo são relativas a esse diretório.

**Etiquetas usadas no texto:**
- **[OBS]** observado (catálogo, Docker, arquivo);
- **[INF]** inferido;
- **[PROP]** proposto e não aplicado;
- **[NT]** ainda não testado no ambiente real.

## 0. Resumo

| Frente | Conclusão |
|---|---|
| **B5, causa** | **[OBS]** O invariante "funções internas de `ops`" (016:3163–3167) exclui só `nekkora_ops_mfa_owner` e superusuários. As 21 funções de `ops` que ficam com o migrador por desenho (10 da `sslinfo` e 11 `prevent_*`, excluídas do `ALTER OWNER` na §18) dão ao migrador o EXECUTE **implícito do dono**. Isso aparece assim que ele deixa de ser superusuário. Reproduzido em READ ONLY com o mesmo conjunto de 21. Na janela simulada no predicado: 0. Violadores que não são o próprio dono: 0. Serviços, operadores, `backup_nekkora` e PUBLIC com EXECUTE em qualquer função de `ops`: 0 |
| **B5, não alcançado** | **[OBS]** A falha foi na verificação 9 de 20 da §19 (V09). V10–V20 (11 verificações) não rodaram após a retirada no P4. Uma variante diagnóstica sem parada roda as 20 em READ ONLY: só V09 viola (§2.3.4). É evidência complementar, não o invariante oficial |
| **B5, propriedade final** | Parte vem do contrato: schema `ops`, tabelas e banco com o migrador; públicas e internas com `nekkora_ops_mfa_owner`. Para `prevent_*` e `sslinfo`, **matriz e código divergem** (matriz §1: "internas `ops.*`" → dono operacional; 016 §18: ficam com o migrador). O contrato é omisso sobre o dono da extensão. **Lacuna de contrato: decisão D-B5-1** |
| **B5, recomendação** | **[PROP]** Alternativa A: manter a propriedade do código e trocar o V09 por um bloco que confere **primeiro os donos** por categoria explícita, depois a **ACL exata** e por último o **EXECUTE efetivo**. Na 016 completa em READ ONLY no estado real pós-retirada: código 0 até o fim. 18 cenários + 9 âncoras sintéticos OK; controle com predicado enfraquecido reprova 8. **Versionamento:** revisão 5 da candidata, com novo hash e novo ensaio |
| **B12, erro** | **[OBS]** Não era container `--rm` (a hipótese O2 do P4 está refutada). Era o template de **imagem** do `fotografia()` (`h016.sh:293`): `.Id` força o CLI a reexecutar o template sobre o JSON bruto, onde `Config.Labels` não existe em imagem sem rótulos. O comando sai com código 1 e uma linha vazia, e o código é descartado. Reproduzido byte a byte. Ficaram sem registro, antes e depois, as mesmas 4 imagens protegidas: `postgres:16-alpine`, `redis:7-alpine`, `postgres:17.11-bookworm`, `eclipse-temurin:21-jre-alpine` |
| **B12, classificação** | Resultado original mantido (`B12 OK`). **Revisado:** a medição do `h016` é **PARCIAL**; ela não comprovava 4 de 16 imagens protegidas. A preservação no P4 é **COMPROVADA** por evidência histórica independente: inventário P1b (22:56Z) × P10 (23:11Z), completo nas 4 classes, com 0 diferença sem explicação |
| **B12, correção** | **[PROP]** `inventario_b12.py` (coleta reduzida com código por comando; avaliação OK/FALHA/INCONCLUSIVO) e patch do `h016.sh`, onde INCONCLUSIVO também reprova. 17 testes locais OK; controle de mutação reprova 3; coleta real em leitura: 124 objetos, 0 falhas |
| **Estado preservado** | Nenhum dado, dono, ACL, papel, função, migration ou histórico Flyway foi alterado. Houve efeitos de ciclo de vida autorizados (§4). **Diferença em relação ao encerramento:** os containers do ensaio estavam parados (VM desligada após 2026-09-30 ~13:27Z), não "5 rodando" |

## 1. Baseline e preservação

### 1.1 Pacote P4

**[OBS]** O ZIP tem SHA-256 `41dfe24b…765736f`, igual ao informado. Extraído de novo (fora do repositório): o manifesto tem **247/247 OK**, 0 falha, e 248 arquivos (o manifesto não se lista). O pacote e o diretório original em `../relatorios/` não foram tocados.

### 1.2 Repositórios e linha 016

Arquivo: `A_baseline/repositorios.txt`.

| Item | Encerramento P4 | Agora | Diferença |
|---|---|---|---|
| Original | HEAD `dd74a8f`, nada em stage | HEAD `dd74a8f`, stage 0, ramo `feat/mfa-emergencia` | nenhuma |
| `elevacao.txt` original | `eb98911a…` (disco) | `eb98911a…` (disco), LF `7fd92fae…`, blob `59f31770` | nenhuma |
| Linhas ativas (original e clone) | 011, 012, 013 | 011, 012, 013 | **linha 016 continua retirada** |
| Clone | S3 `1c2f26b`, sem remoto, limpo | igual: 3 commits `1c2f26b 296808b 327c4d4`, 0 remotos, 0 alterações fora de `saidas` | nenhuma |
| `elevacao.txt` clone | LF `7fd92fae…` | disco `eb98911a…` (CRLF, `autocrlf=true`), LF `7fd92fae…` | nenhuma: só a forma de checkout (O1 do P4) |
| 016 (original, clone, cópia do ambiente) | `25bef818…` | `25bef818…` nos três | nenhuma |

### 1.3 Ambiente

**[OBS]**

| Item | Encerramento P4 (2026-09-29T23:09Z) | Início da P4-R1 (2026-10-01T14:15Z) |
|---|---|---|
| Docker Desktop | rodando | **parado** (a API não respondia) |
| Containers do ensaio (`restart=no`) | 5 `running` | 5 `exited (255)`. `StartedAt` do P4 intacto. `FinishedAt=2026-10-01T14:15:48Z`, registrado pelo daemon ao subir |
| Último log do Postgres do ensaio | — | 2026-09-30T13:27:15Z (healthcheck): a VM caiu depois disso **[INF]** |
| Postgres `unless-stopped` (principal, `portal-dev`, `portal-013`) | `running` (19:54:48Z) | subiram sozinhos às 14:15:49Z ao iniciar o Docker, como na O3 |
| Rede `bridge` | `1a675586…` | recriada pelo daemon: `18faaaf6…`, criada às 14:15:49Z |

**Ações de ciclo de vida desta missão (autorizadas pela responsável nesta sessão):**
1. Docker Desktop iniciado às 14:15:37Z.
2. **`docker start` só do `nekkora-homologacao-ensaio-016a-postgres-1`**, às 14:21:02Z. Os demais containers do ensaio continuam `exited`.

Postgres do ensaio, transcrito em `A_baseline/log_postgres_start_20261001.txt`:
- "not properly shut down; automatic recovery";
- redo de 0/1FBEA98 a 0/20292B8;
- checkpoint de fim de recuperação (117 buffers).

Isso foi uma recuperação **física** do que já estava confirmado no WAL; não houve mudança lógica. Histórico, papéis e 0|0|0 estão abaixo.

Inventários antes e depois do start: `A_baseline/inventario_pre_start.txt` e `B12/inventario_atual_pos_start.txt`.

### 1.4 Indicadores do migrador e propriedade, separados

Leituras em READ ONLY às 14:23Z; arquivos `A_baseline/00_baseline.txt` e `catalogo_impressao.txt`.

**[OBS] Indicadores do migrador:**

| Indicador | Valor |
|---|---|
| 5 atributos (super, createrole, createdb, bypassrls, replication) | `false` |
| participações | 0 |
| ACL de parâmetro | 0 |
| sessões | 0 |
| `estado_migrador` | `0|0|0` |
| histórico | 001–016 `true` (016 instalada por `migrador_nekkora` em 2026-09-29 23:03:56, checksum 2127844423) |
| superusuários | só `admin_cluster` |

**Impressão digital do catálogo:** `3c1b2e0f…a38a 1211`. É a primeira registrada **depois** da 016; o P4 só registrou a de antes (`e04b759e… 807`).

**`0|0|0` não é ausência de todo poder: a propriedade fica fora dessa medida.**

**[OBS] Propriedade do migrador hoje:**
- o banco `nekkora`;
- o schema `ops`;
- as extensões `sslinfo` e `pgcrypto`;
- 67 tabelas (46 em `public`, 21 em `ops`);
- 188 índices (137 + 51);
- 88 funções (67 em `public`, 21 em `ops`);
- os tipos de linha;
- o privilégio padrão `{migrador=X/migrador}` para funções novas.

Dono é dono: pode alterar e excluir esses objetos sem atributo elevado. Isso é o que diz `IMPLANTACAO.md:12`.

## 2. Frente B5: propriedade e permissões finais

### 2.1 B5.1: contrato reconstituído

| Decisão | Conteúdo | Origem | Tipo |
|---|---|---|---|
| Papel do migrador após a implantação | Sem os 5 atributos fora da janela, **mantendo a propriedade** do banco e "dos objetos que as migrations não reatribuem" | `IMPLANTACAO.md:12` (HEAD `dd74a8f`) | contrato operacional |
| Papel de `nekkora_ops_mfa_owner` | NOLOGIN, sem atributos, sem membros, "Dono das funções operacionais (`public.ops_*` e internas `ops.*`)" | matriz-016 §1 | desenho revisado (commit `03bc72c`) |
| Dono do schema `ops` | o migrador; USAGE só do dono operacional | matriz-016 §2; 016 §2 | desenho + código |
| Dono das tabelas de `ops` | o migrador. O dono operacional tem `SELECT/INSERT/UPDATE` (só `SELECT/INSERT` nas de auditoria), **sem** `DELETE/TRUNCATE/REFERENCES/TRIGGER` | matriz-016 §2 e §7; 016 §11; V06 | desenho + código + teste |
| Extensão `sslinfo` | em `ops`, "com EXECUTE **só** para o dono operacional". **O dono da extensão não é dito** | matriz-016 §2 | desenho: omisso quanto ao dono |
| Dono das funções da `sslinfo` | o migrador: criadas por ele elevado e excluídas do `ALTER OWNER` (`NOT LIKE 'ssl%'`) | 016 §2 e §18 | código |
| Dono das `prevent_*` | o migrador: excluídas do `ALTER OWNER` (`NOT LIKE 'prevent\_%'`), só `REVOKE … FROM PUBLIC` | 016 §18 | código. **Conflita com a leitura literal da matriz §1** ("internas `ops.*`") |
| O que permanece após a retirada | EXECUTE das públicas conforme a matriz (V08); internas só do dono (V09); USAGE de `ops` só do dono operacional (V04); sem privilégio de serviço em tabelas de `ops` (V05) | 016 §19 | código e teste |
| Backup | `backup_nekkora`: LOGIN, BYPASSRLS, membro de `pg_read_all_data` | `IMPLANTACAO.md:13`, `bootstrap.sql:20` | contrato operacional |

**Lacuna de contrato (D-B5-1).** A propriedade final das 11 `prevent_*` e da `sslinfo` (extensão e 10 funções) não está decidida em texto:
- o código deixa as duas com o migrador;
- a matriz §1 sugere o contrário para as `prevent_*` e cala sobre a extensão;
- a frase "EXECUTE só para o dono operacional" (matriz §2) não considera o EXECUTE implícito do dono da extensão.

O estado observado **não** é tomado como o desejado. A §2.4 recomenda uma decisão.

### 2.2 B5.2: matriz

`docs/relatorios/2026-10-01-mfa-emergencia-p4r1-matriz-propriedade.md` é gerada das saídas READ ONLY por `B5/gerar_matriz.py`. Assinaturas completas, 93 funções e 21 tabelas.

| Grupo | Qtde | Dono observado | Dono esperado | ACL / efetivo | Avaliação |
|---|---|---|---|---|---|
| Schema `ops` | 1 | migrador | migrador | `M=UC`, `O=U`. USAGE efetivo também de `backup_nekkora` (`pg_read_all_data`) | conforme |
| Banco `nekkora` | 1 | migrador | migrador | — | conforme |
| Tabelas de `ops` | 21 | migrador | migrador | `O`: `arw`/`ar`/`rw`, sem `dDxt`. `backup_nekkora:r` | conforme (21) |
| Índices de `ops` / sequências | 51 / 0 | migrador | dono da tabela | — | conforme |
| Públicas `ops_*` | 37 | `nekkora_ops_mfa_owner` | idem | SECURITY DEFINER, `search_path=pg_catalog, pg_temp`. EXECUTE conforme V08 (21 → `nekkora_ops_mfa` + `op_*` por herança; 10 → Platform; 3 → CA; 2 → intake; `ops_bootstrap_operator` só o dono). PUBLIC: não | conforme (37) |
| Internas de `ops` | 28 | `nekkora_ops_mfa_owner` | idem | INVOKER; ACL e efetivo só do dono | conforme (28) |
| `ops.prevent_*` | 11 | **migrador** | **pendente (D-B5-1)** | INVOKER, `search_path` fixo; ACL `{M=X/M}`; efetivo só `M`; 14 gatilhos | não decidido |
| `sslinfo` (extensão + membros) | 1 + 10 | **migrador** | **pendente (D-B5-1)** | membros em linguagem C; ACL `{M=X/M,O=X/M}`; efetivo `M`, `O` | não decidido |
| `public.prevent_update/delete/truncate` (001–015) | 3 | migrador | migrador (001–015) | ACL nula (PUBLIC). Em `ops` são usadas por 7 gatilhos de UPDATE, 21 de DELETE e 21 de TRUNCATE (no banco todo: 17/50/50). O EXECUTE de função de gatilho não é conferido no disparo | fora do escopo da 016 |

**Divergências:** 0. **Não decididos:** 22 (11 + 10 + 1).

### 2.3 B5.3: a falha explicada

#### 2.3.1 Código

Trecho de `016_recuperacao_emergencial_mfa.sql`:

```sql
-- §18 (3054-3063): só fora de ssl%/prevent_% troca o dono; em todas tira PUBLIC
WHERE (n.nspname = 'ops' AND p.proname NOT LIKE 'ssl%' AND p.proname NOT LIKE 'prevent\_%') OR (… 'ops\_%')
    → ALTER FUNCTION … OWNER TO nekkora_ops_mfa_owner; REVOKE ALL … FROM PUBLIC
WHERE n.nspname = 'ops' AND p.proname LIKE 'prevent\_%' → só REVOKE ALL … FROM PUBLIC
-- §19, V09 (3163-3167):
WHERE n.nspname = 'ops' AND NOT r.rolsuper AND r.rolname <> 'nekkora_ops_mfa_owner'
  AND has_function_privilege(r.oid, p.oid, 'EXECUTE');
```

O dono de uma função tem EXECUTE pela ACL (`{migrador_nekkora=X/migrador_nekkora}`).

#### 2.3.2 Por que passa na janela e falha depois

Consultas READ ONLY `sql/05_b53_explicacao.sql`, código 0.

| Medida | Resultado |
|---|---|
| (a) predicado original com o estado real (migrador `rolsuper=false`) | **21** violações, todas de `migrador_nekkora` |
| (b) mesmo predicado **simulando** a janela (migrador tratado como superusuário no predicado; nada concedido) | **0** |
| (c) origem do EXECUTE nas 21 | violador = **dono do objeto** em 21/21; via ACL do dono, sem PUBLIC, sem participação; 10 com `ext=sslinfo`, 11 sem extensão |
| (d) violações por papel **diferente** do dono | **0** |
| (e) serviços, operadores, `nekkora_ops_mfa`, `backup_nekkora` e PUBLIC com EXECUTE em função de `ops` | **0** |

Durante a J2 (23:03:54Z–23:03:58Z) a §19 rodou dentro da transação da 016 com o migrador superusuário. Ele era excluído por `NOT r.rolsuper`, e o bloco passou: a 016 aplicou e o Flyway validou. No `testar-016` (23:06Z), a cópia da §19 rodou com o migrador já 0|0|0 e falhou no V09.

Reprodução hoje (`sql/04_invariantes_016_originais.sql`, extraído da cópia do ambiente como o `h016.sh` faz): código 3, `CONTEXT … line 74 at RAISE`. O conjunto de 21 é **idêntico** ao do P4 (`B5/04b_conjunto_violacoes.txt`).

#### 2.3.3 Objetos e papéis da divergência

- **Funções:** as 10 da `sslinfo` (`ssl_cipher()`, `ssl_client_cert_present()`, `ssl_client_dn()`, `ssl_client_dn_field(text)`, `ssl_client_serial()`, `ssl_extension_info()`, `ssl_is_used()`, `ssl_issuer_dn()`, `ssl_issuer_field(text)`, `ssl_version()`) e as 11 `prevent_*` (`certificate`, `contact`, `entry`, `key_state`, `link`, `notice`, `notice_token`, `operator`, `permission`, `proof`, `request`, todas `_change()`).
- **Papel:** só `migrador_nekkora`.

#### 2.3.4 Verificações da §19 não alcançadas no P4

A §19 tem 20 `RAISE EXCEPTION`, numerados V01–V20 em `sql/06_invariantes_016_sem_parada.sql`. No P4 passaram V01–V08 e o V09 falhou. **Não rodaram após a retirada:**

| # | Verificação |
|---|---|
| V10 | públicas: SECURITY DEFINER, dono operacional, `search_path` |
| V11 | guarda de isolamento como primeira instrução |
| V12 | 9 políticas `ops_function_context` com `roles = {nekkora_ops_mfa_owner}` (o B5 do `h016` só contou pelo nome) |
| V13 | RLS forçada nas 9 tabelas |
| V14 | dono operacional sem privilégio além da matriz |
| V15 | 6 restrições de `mfa_events` |
| V16 | `reset_admin_exige_motivo` NOT VALID |
| V17 | Platform sem INSERT/UPDATE em `ops_request_id` e com INSERT em `reason_code` |
| V18 | FKs I-3/I-4 |
| V19 | `sslinfo` em `ops` |
| V20 | SECURITY DEFINER com `search_path` e sem EXECUTE público |

**[OBS] Diagnóstico complementar:** variante com cada `RAISE EXCEPTION` trocado por `RAISE WARNING Vnn`, em READ ONLY. Só o V09 viola e o bloco chega ao fim (`B5/06_*.txt`). Essa variante **não substitui** a execução oficial. **[INF]** Nenhum de V10–V20 depende do atributo SUPERUSER do migrador.

#### 2.3.5 Distinções

| Afirmação | Situação |
|---|---|
| Retirada dos atributos elevados | **comprovada** (0|0|0 + ACL de parâmetro 0, no P4 e hoje) |
| Ausência de concessões indevidas a serviços, operadores e PUBLIC nas funções de `ops` | **comprovada** em leitura ((d) e (e) acima) |
| Propriedade que permanece com o migrador | **observada**: banco, schema, 2 extensões, 67 tabelas, 88 funções (§1.4). Para 22 objetos de `ops` a propriedade final não está decidida (D-B5-1) |
| Comprovação completa dos invariantes após a implantação | **não feita**. O P4 parou no V09; o diagnóstico sem parada é complementar. Exige a execução oficial com o invariante corrigido |

### 2.4 B5.4: alternativas

**Alternativa A: manter a propriedade do código e corrigir o invariante** [PROP]

Patch: `B5/proposta_A/016_secao19_proposta_A.patch`, gerado por `gerar.py` a partir de `v09_modelo.sql`. Substitui só as 5 linhas do V09 por um bloco aninhado:
- **(a) Âncora:** dono da implantação = dono do banco (`datdba`), que `IMPLANTACAO.md:12` diz ser o migrador. A âncora é **validada antes do uso**: não pode ser papel operacional, de serviço, dono de funções, `backup_nekkora`, `op_*` nem nula.
- **(b) Donos fora de funções:** schema `ops`, extensão `sslinfo` (dono e schema), membros da `sslinfo` só em `ops`, todas as relações de `ops`. Todos = âncora.
- **(c) Funções de `ops` por categoria explícita, com ACL exata (concessionário, privilégio, concedente, grant option):**
  - membros de `sslinfo` → âncora + EXECUTE de `nekkora_ops_mfa_owner`;
  - as 11 `prevent_*` **listadas por nome**, retorno `trigger` e INVOKER → âncora, ACL só do dono;
  - demais → `nekkora_ops_mfa_owner`, ACL só do dono;
  - qualquer outra forma (prevent não listado, `ssl_*` sem vínculo com a extensão, SECURITY DEFINER em `ops`, membro de outra extensão) → categoria não prevista;
  - contagem de `prevent_*` = 11.
- **(d) EXECUTE efetivo** (com herança) de todo papel não superusuário. Aceita só o dono **já conferido em (c)** e, nos membros da `sslinfo`, o dono operacional. Membros do migrador ou do dono operacional reprovam: hoje o V02 só cobre papéis operacionais.

Nada é aceito "por ser o dono": o dono só é aceito depois de comparado com o esperado da categoria.

Resultados:

| Teste | Resultado |
|---|---|
| §19 completa com a proposta, no estado real pós-retirada, READ ONLY (`sql/07_*`) | **código 0**: chega ao fim |
| Testes sintéticos (`sql/08_*`, mesmo texto do predicado (c) e da âncora (a), conferido por SHA-256 `50e7b5d9…`/`35e0d89f…`), READ ONLY | **27/27 OK**: 18 cenários e 9 âncoras. Cobrem dono incorreto em gatilho, interna e `sslinfo`; EXECUTE explícito a serviço, operador, grupo e PUBLIC; ACL nula; permissão legítima retirada; grant option; concedente errado; função inesperada; gatilho ausente; SECURITY DEFINER; outra extensão |
| Controle positivo (predicado com a comparação de ACL desligada, `sql/08b_*`) | **reprova 8** (S04–S11), código 3 |

Ainda não testado [NT]:
- a parte (d) e a parte (b) com contraprovas que mudam o catálogo;
- a migration aplicada.

**Alternativa B: transferir a propriedade final** [análise]

| Aspecto | Avaliação |
|---|---|
| Objetos a transferir | 11 `prevent_*` → `nekkora_ops_mfa_owner` (ou um papel novo de "guarda"). `sslinfo`: **não há `ALTER EXTENSION … OWNER` no PostgreSQL 16** **[INF]**. Trocar o dono só das 10 funções‑membro diverge da extensão e não sobrevive a dump/restore: o `pg_dump` recria a extensão e não emite o dono dos membros, o que afetaria o B11. Schema e tabelas não podem ir para o dono operacional: o dono de tabela tem `DELETE/TRUNCATE/TRIGGER` implícitos, o que viola o V06 e a matriz §2 |
| Gatilhos | O EXECUTE da função de gatilho não é conferido no disparo; o funcionamento não muda. Mas o **escritor** das tabelas passaria a ser dono da **guarda** de imutabilidade. Poderia `ALTER FUNCTION` (SECURITY DEFINER, `search_path`) se algum dia uma função DEFINER executasse DDL: perde a separação "quem escreve ≠ quem guarda" |
| Privilégios para executar | `ALTER … OWNER TO nekkora_ops_mfa_owner` exige superusuário ou participação no papel de destino. O V02 proíbe participação, então **só dentro da janela de elevação** (revisão da 016 ou 017 elevada) |
| Manutenção e migrations futuras | Mudar uma `prevent_*` passaria a exigir elevação: o migrador não seria dono. Hoje basta ser dono (`IMPLANTACAO.md:12`) |
| Efeito nos invariantes | O V09 ainda falharia pela `sslinfo` (dono = migrador). B sozinho não resolve: precisaria da mesma lógica de A para a extensão |

**Recomendação: Alternativa A**, com duas emendas de texto (decisão D-B5-1):
- matriz-016 §1: "internas `ops.*`, **exceto** as funções de gatilho `prevent_*` e os membros da `sslinfo`, que ficam com o dono da implantação";
- matriz-016 §2: "EXECUTE só para o dono operacional, **além do EXECUTE implícito do dono da extensão (o dono da implantação)**".

Atualizar ainda as contagens de `IMPLANTACAO.md:12`: hoje 46 tabelas e 67 funções; com a 016, 67 tabelas e 88 funções, mais schema `ops` e `sslinfo`.

Vantagens de A:
- não muda donos (nada a migrar nem a elevar além da própria 016);
- preserva a separação escritor/guarda e o padrão de 001–015 (`public.prevent_*` do migrador);
- o invariante fica independente da janela;
- detecta mais do que o original: dono errado, ACL extra, ACL faltante, grant option, membros do dono.

Impacto: só a §19 muda, logo há novo hash da 016 (§2.5).

**Questões abertas:**
- **Q1:** em produção o papel de implantação é o dono do banco? Se não for, a âncora `datdba` reprova a migration (falha fechada) e é preciso decidir outra âncora explícita.
- **Q2:** fixar a contagem de membros da `sslinfo` (10 na 1.2)?
- **Q3 (O-B5-3):** o V04 (USAGE em `ops`) só olha papéis `nekkora\_%` e o V05 usa uma lista fixa. Por isso `backup_nekkora` (`pg_read_all_data`, legítimo) e qualquer papel novo fora do prefixo passam sem ser vistos. Generalizar para "todo papel não superusuário fora dos esperados", com `backup_nekkora` explícito? Fora do escopo de B5, registrado.
- **Q4:** incluir no texto do contrato o privilégio padrão `{migrador=X/migrador}` como pressuposto?

### 2.5 B5.5: versionamento

**Recomendação: nova revisão (5) da migration candidata, para um novo ensaio. Não uma 017.**

- **[OBS]/[INF] Onde a revisão 4 (`25bef818`) foi aplicada:**
  - só no `ensaio-016a` (descartável, aguardando P5);
  - nas bases efêmeras das suítes (`db/tests/mfa-emergencia-016`, `OpsBanco`);
  - segundo os relatórios de implementação e do plano, base principal e `hml03` estão em 001–015;
  - `nk016-smoke` (container parado de 2026-09-27, fora do alvo) pode conter uma 016 de revisão desconhecida. Não foi conferido; é item de limpeza, não de rastreabilidade de produção.
- A 016 é **CANDIDATA**, não habilitada em produção. Não há base persistente que precise de migration de correção.
- Uma 017 deixaria permanentemente na 016 uma §19 falsa depois de qualquer retirada. O B5 do `h016.sh` reexecuta a §19 **da 016** e continuaria falhando. A 017 também exigiria nova elevação.
- **Rastreabilidade:**
  - o cabeçalho da 016 ganha a nota "Revisão 5 (P4-R1)", que a implementação deve incluir; o patch proposto altera só a §19;
  - o hash `25bef818` fica registrado como revisão 4 nos relatórios e no pacote P4, que é preservado;
  - a linha de `elevacao.txt` da revisão 5 terá **novo hash e novo id de ambiente** (ex.: `ensaio-016b`), com sua própria autorização e plano;
  - **o `ensaio-016a` e seu histórico Flyway não recebem a revisão 5**. O checksum divergiria e o `validate` falharia; não haverá `repair`. Ele segue para o P5.

### 2.6 B5.6: plano de testes da correção

Toda contraprova que altera dono ou ACL roda **só** em base descartável de laboratório (suíte ou `ensaio-016b`), nunca no `ensaio-016a`.

| # | Caso | Como | Esperado |
|---|---|---|---|
| T1 | Estado pós-retirada | Base com o migrador como dono do banco: 016 rev 5 aplicada elevada, `NOSUPERUSER` e §19 de novo (forma "homologação" nova no `OpsBanco`/`db/tests`: hoje todas aplicam como superusuário dono do banco, o que explica por que nunca pegaram B5) | §19 até o fim, código 0 |
| T2 | Dono incorreto | `ALTER FUNCTION ops.prevent_operator_change() OWNER TO nekkora_ops_mfa_owner`; `ALTER FUNCTION ops.alvo_inelegivel(uuid,uuid) OWNER TO migrador`; `ALTER SCHEMA ops OWNER`; `ALTER TABLE ops.ops_operators OWNER`; `ALTER DATABASE … OWNER TO nekkora_platform` (âncora) | reprova em (a), (b) ou (c) com o objeto citado |
| T3 | EXECUTE indevido | `GRANT EXECUTE` em `prevent_*`, interna e `ssl_*` a `nekkora_platform`, `op_hml_ver`, `nekkora_ops_mfa`, `PUBLIC`; `GRANT migrador_nekkora TO op_x` (herança); `GRANT … WITH GRANT OPTION` | reprova em (c) ou (d) |
| T4 | Permissões legítimas | `REVOKE EXECUTE` da `sslinfo` do dono operacional | reprova (ACL faltante). Sem a mudança: V08 inalterado (matriz das 37) |
| T5 | Extensão e gatilhos funcionando | Fluxos que usam `ops.ssl_*` (B6, B8a) e UPDATE/DELETE/TRUNCATE barrados pelas `prevent_*` via funções DEFINER (suítes de imutabilidade da 016) | inalterados |
| T6 | Bloco completo | `testar-016` no novo ensaio: B5 executa a §19 inteira após a retirada | `invariantes_016=OK` |
| T7 | Fluxos afetados | Alternativa A não muda donos nem ACL; revalidar B6, B7, B8a, B8b, B10 e B11 (dump) no novo ensaio | iguais ao P4 |
| T8 | Sintéticos | `08_testes_sinteticos_A.sql` contra o modelo da versão implementada (hash do predicado) e o controle `08b` | 27/27; controle reprova |

## 3. Frente B12: completude do inventário

### 3.1 B12.1: o erro

| Pergunta | Resposta |
|---|---|
| Comando e template | `docker image inspect "$i" --format '{{index .Config.Labels "nekkora.homologacao"}}\|{{.Id}}\|{{join .RepoTags ","}}'`, `h016.sh:291–294` (seção imagens de `fotografia()`). A posição `:1:15` da mensagem é o `.Config.Labels` desse template |
| Mecanismo | **[OBS]** `B12/reproducao_template.txt`, Docker 29.7.2, API 1.55. No objeto tipado o campo é `ID`. `.Id` falha e o CLI reexecuta o template sobre o **JSON bruto**. No bruto, imagem sem rótulos **não tem** a chave `Config.Labels`, e `index` sobre chave ausente dá "map has no entry for key". O comando imprime uma linha vazia e sai com código 1. Sozinho, `{{.Id}}` funciona (o bruto tem `Id`), e com `{{.ID}}` o mesmo template funciona |
| Tipo e ID | **Imagens**, não containers. Pela evidência histórica (P10 23:11Z × fotografia, mesmo conjunto) e pela posição das linhas vazias na ordenação: `51ab5e33…` `eclipse-temurin:21-jre-alpine`, `639ab7ce…` `postgres:17.11-bookworm`, `721873c3…` `postgres:16-alpine`, `858f009f…` `redis:7-alpine`. As 4 têm `Config` sem `Labels` no JSON bruto (reconferido hoje) |
| Código de saída e tratamento | 1 por imagem; a saída de erro foi para o terminal (`testar.txt`, 4 + 4 linhas). O `while … done \| grep -v \| cut` descarta o código. O `set -uo pipefail` sem `-e`, e `fotografia > arquivo` sem conferência, deixam passar |
| Registro não produzido | `<rótulo>\|<Id>\|<tags>` dessas 4 imagens; em seu lugar, linha vazia, 4 em cada fotografia |
| Por que deu B12 OK | O `diff` compara duas fotografias **igualmente incompletas**: as mesmas 4 linhas vazias nas mesmas posições. Uma troca de ID de uma dessas imagens no mesmo intervalo de ordenação, ou uma mudança de tag, seria invisível (teste T09 da proposta) |
| Hipótese O2 do P4 | **refutada**: não envolve container nem remoção durante a inspeção |

Na própria P4-R1, o primeiro inventário (`inventario_docker.sh` v1, com `{{.Id}}` + `{{json .Config.Labels}}`) caiu no **mesmo** mecanismo nas mesmas 4 imagens. Como a v1 registra `rc=` por objeto, a falha ficou **visível** (`A_baseline/inventario_pre_start.txt`). A v2 usa `{{.ID}}`: 124 leituras, 0 falhas.

### 3.2 B12.2: reconciliação por classe

`B12/reconciliacao.txt`, gerada por `B12/reconciliar.py`. A classe vem da seção do inventário, nunca do formato hexadecimal.

| Classe | Antes P1b 22:56Z | Depois P10 23:11Z | Novos esperados (ensaio) | Protegidos idênticos (ID + atributos) | Fotografia `h016` (23:06/23:08Z) | Hoje (14:18Z) × P10 |
|---|---|---|---|---|---|---|
| Containers (ID completo) | 9 | 14 | 5 (gateway, portal, platform, postgres, redis) | 9/9 | 9 + 9, completa | mesmos 14 IDs. Os 3 `unless-stopped` com novo `StartedAt` (14:15:49Z); os 5 do ensaio `exited` (VM); `hml03` e `nk016-smoke` iguais |
| Redes (ID completo) | 8 | 10 | 2 (`_borda` 10.252.81, `_interna` 10.252.80) | 8/8 | 8 + 8, completa | `bridge` recriada (`1a675586` → `18faaaf6`, daemon 14:15:49Z); demais iguais |
| Volumes (nome) | 77 | 81 | 4 (`_caixa`, `_operadores`, `_pgdata` e o anônimo `4d6c8c64…`) | 77/77 | 78 + 78, completa, mas **contou o anônimo do ensaio como "fora do ambiente"** (sem rótulo) | mesmos 81 |
| Imagens (ID) | 16 | 19 | 3 (`platform`/`portal`/`operacao:327c4d4`) | 16/16 | **12 registros + 4 linhas vazias** em cada uma: **incompleta** | mesmos 19 IDs |

- **Diferenças sem explicação de P1b para P10:** 0. P1a (19:55Z) e P1b têm o mesmo conjunto nas 4 classes.
- **Volume anônimo do Redis** (`B12/volume_anonimo_redis.txt`), sem remoção:
  - criado às 2026-09-29T22:58:07Z, no mesmo segundo do container `ensaio-016a-redis-1` (criado às 22:58:06.98Z);
  - montado **só** por ele, em `/data`, que vem do `VOLUME ["/data"]` da imagem `redis:7-alpine`;
  - rótulo só `com.docker.volume.anonymous`.

  É do ambiente e deve sair no P5.
- **Limitação:** o comando que gerou os inventários P1/P10 não ficou nas evidências; há só a saída. Ela é completa: imagens sem linhas vazias, 19 IDs com tags.

**Classificação do B12 do P4** (o resultado original `B12|OK` fica mantido no `resumo.txt` do P4):
- **medição do `h016` (B12 como executado):** **PARCIAL**. Containers, redes e volumes completos (com uma classificação errada do volume anônimo, sem efeito na comparação); **4 das 16 imagens protegidas sem registro** nos dois lados;
- **preservação no P4 (conclusão revisada):** **COMPROVADA**, pela evidência histórica independente P1b × P10. Completa nas 4 classes, cobre a janela do `testar-016`, com 0 diferença sem explicação.

### 3.3 B12.3: tratamento proposto [PROP]

**Arquivos novos** (patch `B12/proposta/inventario_b12_novos.patch`; fontes em `B12/proposta/ops/homologacao/testes/`):
- `inventario_b12.py`:
  - `coletar`: listagem e `inspect` por ID, **sem `--format`**, com código e erro de cada comando. O bruto é **reduzido** aos campos usados (nunca `Env`/`Cmd`), e o rótulo do container é lido já na listagem.
  - `comparar`: devolve 0 OK, 1 FALHA ou 3 INCONCLUSIVO.
- `test_inventario_b12.py`: 17 testes com Docker falso.

**Patch do `h016.sh`** (`B12/proposta/h016_b12.patch`; `git apply --check` OK no original; nada aplicado):
- `fotografia()` passa a chamar o coletor;
- o B12 mapeia o código para OK, FALHA ou **INCONCLUSIVO**;
- o `testar-016` passa a sair com 1 também em INCONCLUSIVO (antes só `|FALHA|`).

Regras implementadas e testadas:

| Situação | Tratamento | Teste |
|---|---|---|
| rótulos presentes | lidos | T01 |
| rótulos ausentes (`Config` sem `Labels`) ou nulos | `{}`; ausência legítima, objeto completo | T01, T02 |
| falha de inspeção (o erro **exato** do P4), igual antes e depois | INCONCLUSIVO (nunca OK) | T03 |
| código ≠ 0 com JSON válido na saída | INCONCLUSIVO | T03b |
| saída vazia, truncada, `[]` ou 2 objetos | INCONCLUSIVO | T04 |
| listado e não inspecionado | INCONCLUSIVO | T04b |
| some entre listagem e inspeção: erro "no such …" **e** ausente em nova listagem | DESAPARECIDO. Do ambiente (rótulo da listagem; ex. `--rm`) → OK; protegido → FALHA | T05, T05b |
| erro "no such …" mas ainda listado | INCONCLUSIVO | T05c |
| diferença real em protegido (reinício, tag, volume removido, rede nova, anônimo novo sem dono) | FALHA | T06 |
| mudança só em objetos do ambiente | OK | T06b |
| listagem falha (daemon) | INCONCLUSIVO | T07 |
| volume anônimo montado por container do ambiente | do ambiente | T08 |
| **mascaramento do P4**: imagem protegida sem rótulo trocada no mesmo lugar da ordenação | legado: "idêntico"; proposta: FALHA | T09 |
| bruto com código por comando; bruto sem `Env` | — | T10, T12 |
| CLI 0/1/3 e bruto ilegível | — | T11 |

Resultados:
- **Testes locais:** 17/17 OK (`B12/testes_locais.txt`, código 0).
- **Controle de mutação:** tratamento do código de saída desligado → 3 falhas, código 1 (`B12/controle_mutacao.txt`).
- **Coleta real, só leitura** (`B12/coleta_real/`): duas coletas e comparação, código 0. 124 objetos, 0 leituras com falha; as 4 imagens sem `Config.Labels` lidas por completo; ambiente = 5 containers, 2 redes, 4 volumes e 3 imagens, igual à reconciliação histórica; bruto sem `Env` e sem `PASSWORD`.
- **Efeito no `validar-config`:** os dois `.py` novos em `testes/` somam 2 verificações de sintaxe, de 109 para **111**/0 esperado **[INF]**.

## 4. Desvios e efeitos colaterais desta missão

1. **Docker Desktop iniciado** (14:15:37Z), com autorização. Os 3 Postgres `unless-stopped` subiram sozinhos e o daemon recriou a rede `bridge`. Mesmo efeito da O3 do P4.
2. **`docker start` do Postgres do `ensaio-016a`** (14:21:02Z), com autorização, só esse container. Recuperação de queda (WAL → checkpoint), sem mudança lógica: histórico 001–016, papéis, 0|0|0 e conjunto de violações idêntico ao P4. **Fica rodando** ao final, como autorizado. Os outros 4 containers do ensaio continuam `exited`.
3. Uma consulta da baseline falhou por erro de SQL (`ORDER BY` posicional; código 3, saída mantida em `00_baseline_tentativa1_rc3.txt`), corrigida e rodada de novo.
4. A captura de `docker logs` para arquivo devolveu segmentos antigos neste Docker Desktop. O trecho da recuperação foi **transcrito** da saída observada e está marcado como tal.
5. O primeiro inventário desta missão (v1) teve o mesmo erro de template em 4 imagens. Ficou registrado com `rc=1` e foi corrigido na v2.
6. **Primeira tentativa de pacote descartada** (`homologacao-016-p4r1-20261001T144714Z`). A conferência de "valores reais" tomou como segredo toda linha com 12 ou mais caracteres de todos os arquivos de `~/.nekkora/homologacao/ensaio-016a`, inclusive `.env` não secretos e a cópia do SQL de invariantes em `teste016/`. Isso deu 28 falsos positivos (nomes de papéis e trechos de SQL), e o controle contou errado. Esse pacote, criado e removido nesta missão minutos depois, não foi entregue. O gerador foi corrigido para só valores secretos e o controle mede apenas os valores plantados. O pacote entregue é o seguinte.

Nenhuma janela, J3, recriação, `repair`, alteração de dono/ACL/função/tabela/schema/extensão/dados, fluxo de MFA, fixture, commit, stage ou push. Todas as consultas SQL rodaram em `BEGIN TRANSACTION READ ONLY` (`transaction_read_only = on` registrado) pelo `admin_cluster`, no caminho do runner (`leitura.sh` do P4).

## 5. Observado, inferido, proposto e não testado

| Item | Classe |
|---|---|
| Causa do B5; 21 objetos; 0 concessão indevida; V10–V20 passam no diagnóstico | [OBS] |
| Proposta A passa no estado real e nos sintéticos | [OBS] da execução READ ONLY da **proposta** ([PROP]) |
| Nenhum de V10–V20 depende do SUPERUSER do migrador; inexistência de `ALTER EXTENSION OWNER`; efeito de dump/restore em donos de membros de extensão | [INF] (conferir T1/T2 em laboratório) |
| Parte (d) e (b) da proposta contra mudanças reais de catálogo; 016 rev 5 aplicada; T1–T7 | [NT] |
| Mecanismo e IDs do B12; reconciliação | [OBS] (IDs: evidência histórica P10 + reconferência hoje) |
| `inventario_b12.py` no `testar-016` real; contagem 111 do `validar-config` | [NT] / [INF] |

## 6. Plano da próxima missão (P4-R2: implementação e validação)

**0. Decisões (responsável)**

| Decisão | Opção recomendada |
|---|---|
| D-B5-1 | propriedade final de `prevent_*` e `sslinfo` = dono da implantação, com as emendas da matriz §1/§2 |
| D-B5-2 | âncora = `datdba` (Q1) |
| D-B5-3 | revisão 5 da candidata, novo ensaio |
| D-B12 | adotar `inventario_b12.py` e INCONCLUSIVO reprovando |
| — | autorizar ou não o P5 do `ensaio-016a` (independe da P4-R2) |

**1. Repositório, sem ambiente**
- Aplicar a §19 proposta na 016 com a nota de revisão 5 no cabeçalho, e registrar o novo hash.
- Emendar a matriz-016 e `IMPLANTACAO.md:12`.
- Aplicar o patch do B12.
- Criar a forma de teste pós-retirada (T1) em `db/tests/mfa-emergencia-016` e/ou `OpsBanco`.
- Rodar:
  - as suítes da 016 (instalação limpa e base atualizada);
  - `nekkora-ops`;
  - os testes locais do B12;
  - os sintéticos (T8);
  - `validar-config` (111/0 esperado).
- Contraprovas T2–T4 em base descartável da suíte.

**2. Laboratório, com nova autorização**
- Clone novo a partir do commit da revisão 5.
- Linha de `elevacao.txt` com o novo hash e `ensaio-016b`.
- Ciclo do P4: criar, uma janela da 016, concluir, H02, `preparar-016` e `testar-016` com B8b.
- **Critério:** B5 OK com a §19 inteira após a retirada; B12 OK pelo inventário novo; B6–B11 como no P4.

**3. Saída:** pacote com manifesto, varredura e controle positivo.

## 7. Dependência do ambiente preservado e descarte

**As conclusões desta missão não dependem mais do `ensaio-016a`:**
- catálogo, ACL, propriedade e violações estão nas saídas READ ONLY;
- o inventário, nas evidências do P4 e desta missão.

A P4-R2 usa base descartável e novo ensaio. Ele só seria útil para reconsultas de catálogo enquanto este pacote está em revisão.

**Quando o descarte (P5) for autorizado**, a ordem é: ambiente e volumes identificados primeiro, clone por último.
1. Containers `ensaio-016a` (5; o Postgres está rodando).
2. Volumes `nekkora-homologacao-ensaio-016a_{pgdata,operadores,caixa}` e o anônimo `4d6c8c642c40…6386`.
3. Redes `c8e4ecf64d1a` (`_borda`) e `f783b7290794` (`_interna`).
4. Imagens `nekkora-homologacao-{platform,portal,operacao}:327c4d4`.
5. Segredos em `~/.nekkora/homologacao/ensaio-016a`.
6. **Por último**, o clone `lab-p4-20260929T192708Z`.

Nada além disso: principal, `portal-dev`, `portal-013`, `hml03`, `nk016-smoke` e as imagens base continuam protegidos.

## 8. Entregáveis

| # | Entregável | Onde |
|---|---|---|
| 1 | Relatório P4-R1 | este arquivo |
| 2 | Matriz de propriedade e permissões | `docs/relatorios/2026-10-01-mfa-emergencia-p4r1-matriz-propriedade.md` (gerador `B5/gerar_matriz.py`) |
| 3 | Consultas e saídas com código de saída | `sql/*.sql` (executor `sql/ro.sh`); saídas `A_baseline/`, `B5/` (cada uma com `## codigo_de_saida=`) |
| 4 | Alternativas B5 e recomendação | §2.4; `B5/proposta_A/` (modelo, gerador, patch, saída do gerador) |
| 5 | Versionamento | §2.5 |
| 6 | Patch B12 e testes locais | `B12/proposta/` (patches, fontes, gerador do patch); `B12/testes_locais.txt`, `B12/controle_mutacao.txt`, `B12/coleta_real/` |
| 7 | Plano da próxima missão | §6 |
| 8 | ZIP com manifesto, varredura e controle positivo | `../relatorios/homologacao-016-p4r1-<instante>.zip` (`gerador-do-pacote.sh` no pacote) |
