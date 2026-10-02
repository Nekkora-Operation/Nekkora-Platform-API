# Homologação controlada da 016 — desenho P1a (adaptações) e implementação P1b

**Data:** 2026-09-28. **Versão 2.3**: v2 (ajustes da revisão da v1 e implementação P1b), v2.1 (correções da revisão do código de P1b), v2.2 (B6: mensagem da regra explícita de recusa e causa TLS comprovada) e v2.3 (correções encontradas na execução do P1c, sem mudança de contrato: sonda da CA estranha e permissões da caixa) (§13). Validação estática em §12; testes em recursos descartáveis no relatório `2026-09-28-mfa-emergencia-homologacao-p1c.md`.
**Base:** plano `2026-09-28-mfa-emergencia-plano-homologacao-016.md` v2.1 e commit `dd74a8f` (`main`).
**Natureza:**
- desenho e código **para revisão**, sem commit;
- P1b entrega código e validação **estática**: nenhum container, rede, volume ou imagem foi criado para ela, e nenhum teste da API foi executado;
- as propriedades descritas aqui **não** estão comprovadas em execução. Isso depende de P1c (testes que criam recursos) e do ensaio (P4).

O que este documento **não** autoriza:
- aplicar a 016 em base persistente;
- mudar `hml03`, `nekkora-postgres` ou a base principal;
- retomar o e-mail real;
- mudar o frontend;
- executar P1c, P3 (elevação da 016) ou o descarte de `nk016-smoke`.

## 1. Princípios do desenho

1. **Nada de simulação ativo por padrão.**
   - O transporte de homologação só existe com duas propriedades explícitas.
   - IdP simulado e emissão de credenciais de homologação ficam no **escopo de teste** (`src/test`) e na imagem `operacao`, nunca no jar da Platform nem no código principal da CLI.
2. **Tudo simulado é identificado como simulado:** no banco (categoria da tentativa e do histórico), no log e no índice da caixa.
3. **Fixtures temporais só no ambiente descartável** (`ensaio-*`), só em dados sintéticos, com linhas e campos declarados, `SET LOCAL` e verificações **antes do `COMMIT`** que desfazem a transação se algo não bater.
4. **O runner continua conservador:**
   - recusa sub-rede sobreposta, porta ou id em uso;
   - elevação só pela lista revisada, com sha256;
   - nenhuma limpeza por prefixo.
   - **O padrão continua o desenho anterior** (`HOMOLOG_ATE=015`); a 016 só pedida explicitamente.
5. **Segredos só no diretório de segredos do ambiente ou em volumes Docker exclusivos do ensaio**, fora do Git e fora das evidências.

## 2. Arquivos afetados (implementados em P1b, salvo indicação)

| Arquivo | Mudança |
|---|---|
| `ops/homologacao/homologacao.sh` | Parâmetros `HOMOLOG_ATE` (padrão `015`), `HOMOLOG_REDE_INTERNA`/`BORDA` e `HOMOLOG_TRANSPORTE`. Recusas **antes de qualquer recurso** (§7). Sobreposição de CIDR contra **todas** as redes Docker (§3.1). Com a 016: cópia 001–016, CA operacional, `pg_hba`/`pg_ident`/`nginx.conf` gerados, imagem `operacao`, senhas de `nekkora_ops_ca`/`ops_intake`, volumes exclusivos conferidos (dono `nekkora`, `700`). Ações novas: `preparar-016`, `testar-016`, `operacao`, `caixa`, `fixture`, `validar-config`. Compose com sobreposições por ambiente (`HOMOLOG_COMPOSE_EXTRA`); ambientes antigos (ex.: `hml03`) seguem só com `compose.homolog.yml` |
| `ops/homologacao/compose.homolog.yml` | Sub-redes e IPs pelos prefixos, com padrão `10.252.10`/`10.252.11` (retrocompatível) |
| `ops/homologacao/compose.016.yml` (novo) | Sobreposição com a 016: PostgreSQL com `ssl_ca_file` (CA operacional) e `ident_file`, `pg_hba`/`pg_ident` gerados; gateway com `nginx.conf` gerado; job `operacao` (`.33`, perfil `operacao`, único com a chave da CA operacional); volume exclusivo `operadores` e `operadores-init` |
| `ops/homologacao/compose.caixa.yml` (novo) | Sobreposição do transporte: volume Docker **exclusivo** `caixa` (rotulado); Platform com as propriedades da §3.2; `caixa-init` (dono e permissão aplicados e conferidos no Linux do Docker) e `caixa-ferramenta` (sem rede, somente leitura exceto o volume) |
| `ops/homologacao/postgres/pg_hba.modelo`, `pg_ident.modelo`, `gateway/nginx.modelo` (novos) | Modelos. Os `pg_hba.conf` e `nginx.conf` originais **não mudam**: continuam os dos ambientes já criados |
| `ops/homologacao/lib/redes.py` (novo) | Sobreposição de CIDR (qualquer tamanho), validação de prefixo, geração dos modelos (recusa marcador sobrando ou chave sem uso) |
| `ops/homologacao/lib/indice_caixa.py` (novo) | Sanitização do índice da caixa antes da evidência (§4.2) |
| `ops/homologacao/lib/segredos.py` | Proxies pelo prefixo; com a 016 (só `ensaio-*`): senhas de `ops_ca`/`ops_intake` e `operacao.env` |
| `ops/homologacao/sql/senhas_servicos.sql` | Verificadores de `nekkora_ops_ca`/`ops_intake` com a 016 (`\if`); sem a 016, ignorado |
| `ops/homologacao/sql/operadores_016.sql` (novo) | Papéis `op_hml_cad/ver/apr/exe` (DBA, fora da migration) e `ops_bootstrap_operator` do CADASTRAR |
| `ops/homologacao/sql/fixture_016.sql` (novo) | Organização sintética própria da 016 (dois ORG_ADMIN e um membro) |
| `ops/homologacao/sql/fixture_016_contatos.sql`, `fixture_016_solicitacao.sql` (novos) | Fixtures declaradas (§5) |
| `ops/homologacao/testes/h016.sh` (novo) | `preparar` e `testar` (B5–B8a, B8b opcional, B10–B12) |
| `ops/homologacao/testes/cliente.py` | Cenários `h016_mfa`, `h016_confirmar`, `h016_novo_mfa` |
| `ops/homologacao/testes/executar.sh` | IPs pelo prefixo (padrão do desenho anterior) |
| `ops/homologacao/testes/validar_config.sh` (novo) | Validação estática (§12) |
| `ops/homologacao/mapa_privilegios.sh` | Papéis `nekkora_ops_*` na variante pré-criada (o laço já percorre todas as migrations) |
| `ops/homologacao/exemplo.env`, `README.md`, `IMPLANTACAO.md` | Variáveis, ações e a 016 na matriz de elevação |
| `ops/homologacao/implantacao/elevacao.txt` | **Sem mudança** (a linha da 016 é P3) |
| `api/nekkora-platform/.../mail/MailSender.java` | `default boolean simulated() { return false; }` |
| `api/nekkora-platform/.../mail/CaixaHomologacaoMailSender.java` (novo) | Transporte de homologação (§4) |
| `api/nekkora-platform/.../emergency/EmergencyNotices.java` | Categorias simuladas em `recordTransportResult`; `simulado=` no log da entrega |
| `api/nekkora-platform/.../config/PlatformConfig.java` | Seleção do `MailSender` (§4.1), sem mudar o `PlatformProperties` |
| `api/nekkora-platform/src/main/resources/application.yml` | `nekkora.environment`, `nekkora.mail.transport` (padrão `local`), `nekkora.mail.caixa-dir`, `nekkora.emergency-notices.enabled` (padrão `false`) |
| `api/nekkora-ops/src/test/.../homologacao/FerramentaHomologacao.java` (novo, escopo de **teste**) | `credencial`, `operador`, `contato`, `entrada`, `sonda` (§6) |
| `api/e2e/Dockerfile.operacao` (novo) | Imagem do job `operacao` |

**Sem mudança:** `db/migrations/*` (a 016 continua em `25bef818…`), `implantacao/implantar.sh` e `lib/estado.sh`.

## 3. Configurações

### 3.1 Variáveis do runner (sem valores secretos)

| Variável | Padrão | Uso |
|---|---|---|
| `HOMOLOG_ATE` | `015` | `016` só com id `ensaio-*` **e** a linha da 016 em `elevacao.txt` (P3) |
| `HOMOLOG_REDE_INTERNA` / `HOMOLOG_REDE_BORDA` | `10.252.10` / `10.252.11` | Prefixos /24. Com `015`, só o padrão (o desenho anterior usa os arquivos fixos) |
| `HOMOLOG_TRANSPORTE` | vazio | `homologacao` exige a 016 (logo, `ensaio-*`) |

- **Sobreposição de redes:** o `criar` lê as sub-redes de **todas** as redes Docker e recusa se a /24 interna ou a de borda se sobrepuser a qualquer uma, com `ipaddress.overlaps`:
  - rede igual, **maior** que contém a candidata ou **menor** contida nela;
  - também as duas candidatas entre si;
  - uma sub-rede ilegível recusa por segurança.
  - A regra anterior comparava só um prefixo textual.
- Para `ensaio-016a`, enquanto as redes da `hml03` existirem: `10.252.20` / `10.252.21`.

### 3.2 Propriedades da Platform

| Propriedade | Variável | Padrão | Com `compose.caixa.yml` |
|---|---|---|---|
| `nekkora.mail.transport` | `NEKKORA_MAIL_TRANSPORTE` | `local` | `homologacao` |
| `nekkora.mail.caixa-dir` | `NEKKORA_MAIL_CAIXA` | vazio | `/caixa` (volume) |
| `nekkora.environment` | `NEKKORA_AMBIENTE` | vazio | o id `ensaio-*` |
| `nekkora.emergency-notices.enabled` | `NEKKORA_AVISOS_EMERGENCIA` | `false` | `true` |

### 3.3 `pg_hba` e `pg_ident` gerados

Linhas acrescentadas antes dos `reject` finais (o resto é o `pg_hba` anterior, com o prefixo trocado; a validação estática confere a igualdade):
```
hostssl   nekkora    /^op_               <interna>.33/32     cert map=ops clientname=CN
hostssl   nekkora    nekkora_ops_ca      <interna>.33/32     scram-sha-256
hostssl   nekkora    nekkora_ops_intake  <interna>.33/32     scram-sha-256
```
- Não há regra de senha para `op_*`.
- `ssl_ca_file` é a CA **operacional** descartável; `pg_ident`: `ops "/^(op_[a-z0-9_]{2,40})$" \1`.
- **Limite declarado:** CA e entrada simuladas no mesmo job (`.33`), separadas só por senha, **sem isolamento por endereço entre elas**.

## 4. Transporte de homologação (`CaixaHomologacaoMailSender`)

### 4.1 Ativação

| `nekkora.mail.transport` | Resultado |
|---|---|
| vazio ou `local` | `LocalMailSender` (comportamento de hoje) |
| `homologacao`, com `nekkora.environment` casando com `^ensaio-[a-z0-9-]{1,34}$` e `caixa-dir` diretório gravável | Caixa. Log de partida `evento=mail.transporte transporte=homologacao ambiente=<id> simulado=sim envio_externo=nenhum` |
| `homologacao` sem ambiente `ensaio-*` ou sem caixa | **A Platform não sobe** (exceção na criação do bean); nunca cai silenciosamente no local |
| qualquer outro valor | **A Platform não sobe** |

### 4.2 Caixa: volume exclusivo, ferramenta restrita, índice sanitizado

- **Armazenamento (ajuste da revisão):** a caixa é um **volume Docker exclusivo** do ensaio (`compose.caixa.yml`, rotulado `nekkora.homologacao=<id>`), nunca uma pasta do perfil Windows.
  - O `caixa-init` aplica **e confere** no Linux do Docker o dono `nekkora` e o modo `700`; o `criar` falha se a saída não for exatamente `nekkora 700`.
  - O transporte grava os arquivos com `600` e os diretórios com `700`, **inclusive** o `indice.jsonl` (criado já com `600`) e o `controle.conf` regravado sob a trava (temporário restrito a `600` antes do `ATOMIC_MOVE`); a ferramenta grava o controle com `umask 077` (v2.3: no P1c o índice nascia `644` no volume Linux).
  - O volume some no `destruir`, pelo rótulo.
- **Acesso do operador** só pela ferramenta `homologacao.sh caixa <id> …`, que roda num container sem rede, somente leitura exceto o próprio volume:
  - `listar`;
  - `ler <arquivo>`: caminho validado por regex; a saída vai para o terminal ou arquivo do operador, nunca para a evidência;
  - `controle <regra>`: regra validada, sob a mesma trava do transporte;
  - `liberar-trava`: só com confirmação explícita;
  - `exportar-indice`.
- **Exportação:** só o `indice.jsonl`, passando por `lib/indice_caixa.py`. Ele reemite só se **todas** as linhas tiverem exatamente os campos esperados, cada um no formato esperado (UUID, hash hex de 64, tipo e desfecho de listas fechadas, nomes de arquivo por regex). Qualquer linha fora do formato recusa o índice inteiro.
- **Conteúdo das mensagens:** tipo, organização, destinatário, tokens (`token`, `token_confirmar`, `token_contestar`), `attempt_id`, instante, `simulado: true`. **Links não são montados** (correção da v1): nenhum remetente monta links na branch, e o cliente de teste usa o token.

### 4.3 Publicação sem sobrescrita e sem arquivo parcial (protocolo definido)

`CREATE_NEW` e renomeação são operações distintas. O protocolo usa uma ligação física, que publica atomicamente **e** falha se o nome existir:
1. **Temporário:** grava o conteúdo completo em `.tmp/<uuid>.part`, aberto com `CREATE_NEW` (nome único; nunca reaproveitado). A gravação **repete `FileChannel.write` até esgotar o buffer** (uma chamada pode gravar menos). Só então faz `force(true)` (fsync). O índice usa a mesma gravação completa.
2. **Publicação:** `Files.createLink(<nome final>, <temporário>)`. A ligação é atômica e lança `FileAlreadyExistsException` se o nome final já existir, **sem tocar** no existente. Quem lê o nome final vê sempre o arquivo inteiro.
3. Remove o temporário (um órfão em `.tmp` não afeta as mensagens publicadas).
- **Nomes:**
  - aviso: `avisos/<attempt_id>.json`;
  - se já existir (segunda entrega da mesma tentativa, que o protocolo proíbe): `avisos/<attempt_id>.repeticao-<n>.json`, com o primeiro `n` livre pelo mesmo protocolo, `repeticao=true` no índice e alerta no log;
  - demais mensagens (verificação de e-mail, redefinição de senha, avisos de reset): `outras/<yyyyMMddTHHmmssSSSZ>_<TIPO>_<uuid-aleatório>.json`, sempre com `createLink`.
- **Índice:** uma linha por mensagem, anexada (`APPEND` + fsync) sob o monitor da JVM. As linhas nunca se intercalam dentro da Platform.
- **Requisito:** sistema de arquivos com ligações físicas (o volume Linux do ensaio). Sem suporte, o envio falha; não há fallback que perca a garantia.

### 4.4 Controle dos desfechos e consumo único de `proximo`

- **Regras em `controle.conf`:**
  - `proximo <DESFECHO>`: consumida **uma vez**, a primeira da lista;
  - `destinatario <sha256 do e-mail normalizado> <DESFECHO>`;
  - `DESFECHO` ∈ {`ACEITO`, `ENFILEIRADO`, `RECUSADO`, `INCERTO`, `EXCECAO`};
  - sem regra: `ACEITO`.
- **Trava:** diretório `controle.trava`, cuja criação é atômica. É a mesma trava da ferramenta do runner (`mkdir` no mesmo volume).
  - O transporte a obtém dentro de um monitor da JVM, lê e consome a regra `proximo`, regrava o controle (temporário e `ATOMIC_MOVE`, substituindo **sob a trava**) e a libera.
  - Dois envios concorrentes, da mesma JVM ou de fora, nunca consomem a mesma regra.
  - A trava não é liberada por tempo. Uma trava órfã (queda) só sai com `caixa liberar-trava` e confirmação.
  - Até lá, o envio falha e é registrado como `INCERTO_SIMULADO`.
- **Desfechos:** o transporte devolve o `Outcome` correspondente (`EXCECAO` lança exceção, tratada como `INCERTO`), e o registro segue o caminho de produção (`recordTransportResult` → `ops_notice_delivery_result`).
- **Categorias** (aceite **sempre** identificado como simulado):

| Desfecho simulado | Estado | Categoria |
|---|---|---|
| `ACEITO` | `ACEITA` | `ACEITE_SIMULADO` |
| `ENFILEIRADO` | `ENFILEIRADA` | `FILA_SIMULADA` |
| `RECUSADO` | `FALHOU` | `RECUSA_SIMULADA` |
| `INCERTO`/`EXCECAO` | `DESCONHECIDO` | `INCERTO_SIMULADO` |

- **Fora do escopo:** resultado assíncrono posterior (sem entrada na aplicação hoje).

## 5. Fixtures declaradas (só `ensaio-*`, só dados sintéticos)

### 5.1 Controle de transação e verificações (ajustes da revisão)

- **Forma única:** `psql -v ON_ERROR_STOP=1` **sem** `--single-transaction`. O próprio arquivo abre e fecha a transação (`BEGIN … COMMIT`).
  - Qualquer erro antes do `COMMIT`, inclusive o `RAISE` de uma verificação, faz o `psql` parar ali. A conexão fecha sem `COMMIT` e o servidor desfaz tudo.
  - `--single-transaction` foi descartado: envolveria também as conferências posteriores ao `COMMIT`.
- **Execução:** pelo `admin_cluster`, a partir do job `ferramentas` (TLS `verify-full`), só por `homologacao.sh fixture <id> …`.
- **O runner recusa antes de conectar se:**
  - o id não é `ensaio-*`;
  - o ambiente não tem a 016;
  - o projeto não tem containers com o rótulo do ambiente;
  - a organização sintética ainda não foi criada.
- **Antes da alteração, dentro da transação:**
  - `cluster_name` e `current_database()` corretos;
  - `inet_server_addr()` igual ao PostgreSQL deste ambiente (`<interna>.10`);
  - organização com o id do registro do ambiente **e** o nome `Homologacao 016 sintetica <id>`;
  - linhas alvo da organização, sem repetição; para contatos, nenhuma solicitação existente.
- **Instantâneos antes:**
  - os campos autorizados das linhas alvo;
  - o `md5` do **restante** de cada linha alvo (JSON da linha sem os campos autorizados);
  - o `md5` de **todas as demais linhas** das tabelas envolvidas e, para a solicitação, da fotografia inteira (`ops_mfa_request_admins`).
- **Alteração:** `SET LOCAL session_replication_role = replica` e `GET DIAGNOSTICS` por comando, comparado com a contagem esperada.
- **Verificações que provocam rollback, todas antes do `COMMIT`:**
  1. campos autorizados = antes − deslocamento declarado (nulos continuam nulos);
  2. **campos não autorizados das próprias linhas alteradas** idênticos;
  3. demais linhas (e a fotografia) idênticas.
- **Depois do `COMMIT`, na mesma sessão,** só: `session_replication_role = origin` (o `SET LOCAL` não sobreviveu) e confirmação da persistência.
- **Estado de partida e marca:**
  - antes do `BEGIN`, o script imprime os campos alvo (`ANTES|tabela|id|campo|epoch_us`, `sql/fixture_016_estado.sql`, somente leitura);
  - logo depois do `COMMIT`, imprime `FIXTURE_COMMIT_CONCLUIDO`, que só é alcançado se o `COMMIT` retornou sucesso.
- **Classificação da execução** (`lib/fixture_conferencia.py classificar`, correção da revisão de P1b):

| Saída | Classe | O runner |
|---|---|---|
| código 0, marca e `verificacoes=OK` | `APLICADA` | Código 0 |
| marca presente, qualquer falha depois | `APLICADA_COM_FALHA_POS_COMMIT` | Não diz "nada alterado": exige reconciliação |
| sem marca, código 3 (erro do script com `ON_ERROR_STOP`) e `ERROR:` do servidor | `NAO_APLICADA` | "Transação desfeita, nada foi alterado". O `psql` parou antes do `COMMIT` |
| qualquer outra (código 2: conexão perdida, inclusive a resposta do `COMMIT`; job falhou; código 3 sem erro do servidor) | `INDETERMINADA` | Exige reconciliação |

- **Reconciliação:** `homologacao.sh fixture <id> conferir <execucao>` lê o estado atual com a mesma consulta (prefixo `ESTADO`) e compara com o bloco `ANTES` da execução:
  - igual → `NAO_APLICADA`;
  - igual a partida − deslocamento → `APLICADA`;
  - outra coisa → `DIVERGENTE`, com análise manual;
  - bloco sem `ANTES` → `SEM_ESTADO_DE_PARTIDA`, com análise manual.
- **Registro:** cada execução tem um id (`execucao=<uuid>`) no cabeçalho em `saidas/<id>/fixtures.txt`, com a saída e a classificação.
- **B9 (preventivo):** a solicitação entra em `solicitacoes_com_fixture.txt` **antes** de executar (`<uuid>|tentativa|<execucao>`), e a classificação e a reconciliação são anexadas depois. **Toda** solicitação listada fica imprópria para B9, qualquer que seja o resultado.

### 5.2 Contatos (B8a; B9 opção ii)

| Tabela | Linhas | Campos | Deslocamento |
|---|---|---|---|
| `ops.org_authorized_contacts` | `contact_id` da lista (organização sintética), nenhuma outra | `created_at`, `channel_set_at` | −721 h |

`org_authorized_contact_events` não muda (instante real, declarado).

### 5.3 Solicitação (só B8b)

| Tabela | Linhas | Campos |
|---|---|---|
| `ops.ops_mfa_recovery_requests` | a solicitação (1) | `opened_at`, `t_total`, `verified_at`, `preapproved_at`, `notice_confirmed_at`, `final_approved_at`, `executed_at`, `closed_at` |
| `ops.ops_mfa_notices` | da solicitação | `created_at`, `issued_at`, `state_at`, `claimed_at`, `accepted_at` |
| `ops.ops_mfa_notice_tokens` | da solicitação | `issued_at`, `valid_until`, `used_at`, `invalidated_at` |
| `ops.ops_mfa_approval_attempts` | da solicitação | `accepted_at`, `approved_at` |

- **Deslocamento:** de 1 a 72 h (padrão 24 h no runner; B8b usa 25 h).
- `ops_mfa_request_admins` (`captured_at`) **não** muda, e a verificação confere isso.

## 6. Identidades, operadores e credenciais

- **Serviços:** `nekkora_ops_ca` e `nekkora_ops_intake` recebem verificadores SCRAM depois da implantação.
- **Operadores:** papéis `op_hml_*` pelo DBA (`operadores_016.sql`), com bootstrap do CADASTRAR.
- **Correção da v1:** a CLI (`OpsCli`) **não tem** comandos de cadastro. O cadastro e a habilitação dos demais operadores e a inclusão de contatos são feitos pela `FerramentaHomologacao`, que chama as **mesmas funções** (`ops_operator_create`, `ops_operator_grant`, `ops_contact_add`) com o **certificado do CADASTRAR**: o caminho de autenticação e autorização real.
- **`FerramentaHomologacao`** (escopo de teste, imagem `operacao`, só `ensaio-*`):
  - `credencial <papel>`: `IdpSimulado` → `EmissorCertificados` com a CA operacional → registro por `nekkora_ops_ca`. PKCS#12 de 15 min e senha no volume `operadores` (`600`); nada impresso além do papel e da validade;
  - `operador`, `contato`;
  - `entrada` (por `nekkora_ops_intake`). **Correção:** a versão da chave vem da configuração controlada do ensaio (`HOMOLOG_OPS_INTAKE_VERSAO` em `operacao.env`, a versão 1 semeada pela 016). O serviço **não lê tabelas**; antes, a ferramenta consultava `ops.ops_intake_key_state` com esse papel e falharia. Uma versão divergente é recusada pela própria função (`ENTRADA_INVALIDA`), sem ampliar privilégio;
  - `sonda`: tentativas que o servidor **precisa** recusar, a partir do próprio job (`.33`, com as regras originais). Senha para `op_*`, certificado de CA estranha e CN diferente do papel; mais o controle `valida`.
  - **Correção:** a sonda classifica pela **causa** e só devolve código 0 com a causa esperada:

| Sonda | Causa esperada |
|---|---|
| `valida` | `CONECTOU` |
| `senha`, `cn-diferente` | `RECUSADA_AUTENTICACAO:28xxx` (o servidor respondeu e recusou) |
| `ca-estranha` | `RECUSADA_TLS:unknown_ca` **e** `certificate verify failed` no log do servidor no intervalo da sonda (v2.2). Alertas TLS genéricos (`handshake_failure`, `decrypt_error`, `access_denied`, `bad_certificate`, `certificate_unknown`) são `FALHA_TECNICA:TLS:<alerta>` e não provam a causa |

    Qualquer outra (conexão recusada, tempo esgotado, TLS sem alerta de certificado) é `FALHA_TECNICA` e nunca conta como bloqueio.

    **Sonda `ca-estranha` (v2.3, correções do P1c):**
    - **Apresenta o certificado estranho.** O gerenciador de chaves padrão do pgjdbc só envia o certificado de cliente se o emissor estiver na lista de CAs aceitas enviada pelo servidor; com CA estranha nada seria enviado e a recusa viria do pg_hba (`28000`, "connection requires a valid client certificate"), sem exercitar a verificação de CA do servidor. A sonda usa uma fábrica TLS própria (`CertificadoForcado`) que sempre apresenta o certificado; o servidor continua verificado pela raiz do ambiente e o nome pelo pgjdbc (`verify-full`).
    - **Lê antes de escrever.** O servidor recusa e fecha com dados do cliente não lidos (RST); se o cliente ainda estiver escrevendo, a escrita falha e o alerta se perde (observado dentro do container Linux; no host o proxy do Docker Desktop mascarava). A fábrica conclui o handshake e lê primeiro (2 s): o alerta já recebido aparece como `unknown_ca`.
    - **Repete só em falha de transporte.** `FALHA_TECNICA:08…` (reset, conexão recusada, tempo esgotado) repete até 5 vezes; qualquer resultado conclusivo (alerta TLS, recusa de autenticação, conexão) encerra. A saída informa `tentativas=N`. Se a indisponibilidade persistir, o resultado continua `FALHA_TECNICA` (comprovado no P1c com porta errada e servidor pausado: `tentativas=5`); reset nunca vira recusa. Recuperação de falha transitória para `unknown_ca` numa tentativa posterior ainda não foi observada (estresse da imagem: 30/30 na 1ª tentativa). B6 ainda confirma a disponibilidade antes e depois (`valida` duas vezes e `SELECT 1`). A recusa a partir do `.31` exige a mensagem da **regra explícita de recusa** do fim do `pg_hba` (`host all all 0.0.0.0/0 reject`), `pg_hba.conf rejects connection` (v2.2): a origem não casa nenhuma regra de aceite e cai nessa regra, e não em "sem linha".
- **CLI real:** `homologacao.sh operacao <id> cli …` com `HOMOLOG_OPERADOR=op_hml_*`. A senha do PKCS#12 é lida **dentro** do container, do volume exclusivo, e nunca passa pelo host.

## 7. Runner: fluxo

1. **`criar`**, com `HOMOLOG_ATE=016`, `HOMOLOG_REDE_*` e opcionalmente `HOMOLOG_TRANSPORTE=homologacao`. Antes de qualquer recurso, recusa:
   - prefixo inválido, transporte desconhecido ou `HOMOLOG_ATE` inválido;
   - 016 fora de `ensaio-*`;
   - 016 sem a linha de P3 em `elevacao.txt`;
   - 015 com sub-rede não padrão ou com transporte;
   - sobreposição de CIDR.
   - **Correção da v1:** sem a linha de P3, a recusa agora é **antes** de criar recursos. Antes, a 016 rodaria sem privilégio, falharia e poderia deixar histórico do Flyway com `success=false`, que exige intervenção manual.
2. **`preparar-016`:** organização sintética, operadores, credenciais, contatos e MFA dos dois ORG_ADMIN pelo gateway (login real).
3. **`testar`:** H01–H09 inalterados. **`testar-016`:** B5–B8a, B10–B12; B8b com `H016_B8B=1`. Cobertura por cenário (correção da revisão de P1b):
   - **B5:** contagens (37 funções, 21 tabelas, 9 políticas `ops_function_context`) e o **bloco de invariantes da 016 (§19)** rodado de novo no ambiente, da cópia imutável da migration, em `BEGIN/ROLLBACK`.
   - **B10 — PARCIAL:** `RECUSADO`, `ENFILEIRADO`, `INCERTO`, `EXCECAO` e `ACEITO` pelo caminho normal, com as categorias esperadas. O último volta a `ACEITO`, para a vigente de B8b. A **varredura** de tentativas paradas fica **pendente**: não há gatilho no ambiente (sem agendamento, sem rota, e a identidade da Platform só conecta do próprio container).
   - **B11 — PARCIAL:** dump com as tabelas de `ops`; restauração num segundo cluster pendente.
   - **B12:** inventário **por ID** de tudo o que não é do ambiente (containers, redes, volumes, imagens), antes e depois.
4. **`destruir`:** sem mudança. Os volumes `caixa` e `operadores` e a imagem `operacao` saem pelo rótulo; o diretório de segredos continua com descarte manual.

## 8. Comportamento esperado (resumo)

| Situação | Esperado |
|---|---|
| Platform sem propriedades | `LocalMailSender`, como hoje |
| `transport=homologacao` sem `ensaio-*` ou sem caixa gravável | A Platform não sobe |
| Aviso aceito | `avisos/<attempt_id>.json`, `ACEITA` + `ACEITE_SIMULADO`, log `simulado=sim` |
| Segunda entrega da mesma tentativa | Sem sobrescrita, `repeticao-<n>`, alerta |
| Mensagens concorrentes sem `attempt_id` | Nomes distintos (UUID por chamada), sem sobrescrita |
| Duas entregas concorrentes com uma regra `proximo` | Exatamente uma consome a regra |
| Fixture fora do declarado | Rollback antes do `COMMIT`; nada muda |
| `op_*` com senha, CA estranha ou CN diferente | Recusado (`sonda`, com as regras originais no ensaio) |
| `criar` com 016 sem P3 | Recusa antes de qualquer recurso |
| Implantação com P3 | Quatro janelas (011, 012, 013, 016), cada uma com revogação e `0\|0\|0` |

## 9. Testes previstos em P1c (criam recursos; Testcontainers; sem ambiente de homologação)

| Teste | Confere |
|---|---|
| `CaixaHomologacaoConfigIT` (Platform) | Tabela da §4.1 |
| `CaixaHomologacaoMailSenderIT` (Platform, banco 001–016) | Despachante real com categorias simuladas; `repeticao-<n>`; **colisão** de nome final (arquivo pré-existente não é tocado); **envios concorrentes** (N threads) sem sobrescrita nem arquivo parcial; **consumo único** de `proximo` com envios concorrentes e com a trava ocupada por fora; índice aceito pelo `indice_caixa.py` e varrido com controle positivo; `LocalMailSender.simulated() == false` |
| `FixtureHomologacaoIT` (`nekkora-ops`, banco 001–016 descartável) | Os dois scripts reais: só campos e linhas declarados; **rollback** quando um campo não autorizado muda (gatilho de teste), com linha extra, organização não sintética, servidor divergente, solicitação existente (contatos) e contagem divergente; `session_replication_role = origin` depois; fotografia intacta |
| `PgHbaHomologacaoIT` (`nekkora-ops`, container com o `pg_hba`/`pg_ident` **gerados**) | Autenticação: `op_*` só com certificado da CA operacional; senha e CN diferente recusados com `28xxx`; CA estranha com `RECUSADA_TLS:unknown_ca` **e** `certificate verify failed` no log do servidor; `ops_ca`/`ops_intake` com SCRAM. **Distinção de causa (v2.2):** alertas TLS genéricos e indisponibilidade (servidor parado durante a sonda, porta errada) classificados como `FALHA_TECNICA`, nunca como recusa; `unknown_ca` sem a evidência do servidor reprova o cenário. **Limite explícito:** no container de teste os IPs dos `/32` são trocados por `0.0.0.0/0`. Isso comprova a **autenticação**, **não a restrição por IP**. A restrição por IP é verificada no ensaio (P4, B6), com as regras originais: `sonda` a partir do `.33` e tentativa de `op_*` a partir do `.31` |
| `FerramentaHomologacaoIT` (`nekkora-ops`) | `credencial` (≤ 15 min, registrada, aceita num ato real; vencida recusada), `operador`, `contato`, `entrada`, `sonda` |
| Ensaio do runner em modo validação | Sobreposição contra as redes reais (só leitura do Docker) |
| Regressão pertinente | `nekkora-platform` e `nekkora-ops`. **Correção da contagem:** os 209 do baseline (`154847Z`) cobrem quatro módulos. Platform e Ops somavam **192** (Ops 126, Platform 66); core 5 e portal 12. O relatório de P1c informa os módulos efetivamente executados e a contagem observada |

## 10. Riscos e limites

- **CA e entrada no mesmo job:** sem isolamento por endereço entre elas (§3.3).
- **Autenticação e restrição por IP:** o teste de P1c comprova a autenticação; a restrição por IP só no ensaio (§9).
- **Contatos com fixture:** eventos de inclusão com o instante real (§5.2).
- **Imagem `operacao`** com classes de teste (IdP e CA simulados): só construída para `HOMOLOG_ATE=016`, que exige `ensaio-*`.
- **Trava da caixa sem expiração** (§4.4): uma queda no meio do consumo exige `liberar-trava` explícito.
- **Sem aceite assíncrono** e **sem agendamento da varredura** (limites do plano).
- **B11:** o `testar-016` confere o dump por `backup_nekkora` (tabelas de `ops`). A restauração num segundo cluster fica **pendente** para o ensaio.

## 11. Decisões já tomadas (revisão da v1)

- **Aceitos:** `MailSender.simulated()`, as categorias simuladas e a imagem `operacao` restrita à homologação.
- **Caixa:** volume exclusivo do ensaio, acesso por ferramenta restrita, exportação só do índice sanitizado.
- **Continuam separados:** P1c, P3 e o descarte de `nk016-smoke`.

## 12. P1b: validação estática (2026-09-28)

Nenhum container, rede, volume ou imagem foi criado. Contagens do Docker antes e depois idênticas (containers 9, redes 8, volumes 77). Nenhum teste da API foi executado.

| Verificação | Resultado |
|---|---|
| `./mvnw -o -q -pl nekkora-ops,nekkora-platform -am compile test-compile` | Saída 0 (compila; testes **não** executados) |
| `homologacao.sh validar-config` (`testes/validar_config.sh`) | **v2.2: 74 verificações OK, 0 falha** (mais 4 da seção 8: `pg_hba` gerado termina nas regras explícitas de recusa; B6 espera a mensagem delas; a CA estranha exige `unknown_ca` e a evidência do servidor; a ferramenta só produz `RECUSADA_TLS:unknown_ca`). **v2.1: 70 verificações OK, 0 falha** (as 52 da v2 e mais 18 da seção 7: classificação e reconciliação das fixtures com casos sintéticos, a posição do `ANTES` e da marca, a ausência de `--single-transaction` e o registro preventivo para B9). Na v2:<br>(1) `bash -n` em 8 scripts e sintaxe (`ast.parse`, sem gravar `__pycache__`) de 5 módulos Python;<br>(2) sobreposição de CIDR: igual, maior, menor, candidatas entre si, IPv6 e linha ilegível; validação de prefixo;<br>(3) configuração gerada: sem marcador; `pg_hba` = regras anteriores com o prefixo novo + linhas da 016, antes dos `reject`; `op_*` só por certificado do `.33`; `pg_ident`; `nginx`;<br>(4) `docker compose config` (só renderiza) das três combinações: sub-redes pelo prefixo, `operacao` em `.33` só no perfil, chave da CA operacional montada só nele, `ssl_ca_file`/`ident_file` só com a 016, transporte só com a sobreposição, caixa como volume rotulado (nunca bind), ferramenta sem rede e somente leitura;<br>(5) `elevacao.txt` sem mudança e 016 com o sha256 revisado;<br>(6) sete recusas do `criar` antes de qualquer recurso |
| `lib/segredos.py` em diretório temporário (apagado) | Com a 016: senhas `ops_ca`/`ops_intake` e `operacao.env` (só nomes conferidos); sem ela, como antes; id fora de `ensaio-*` recusado |

**Não validado em P1b** (depende de execução, P1c e P4):
- SQL das fixtures e dos operadores;
- transporte (publicação, concorrência, trava);
- ferramenta de homologação (emissão, sondas);
- imagem `operacao` (build);
- `h016.sh` e os cenários do `cliente.py`;
- `compose run` dos jobs;
- autenticação por certificado no ambiente.

## 13. Histórico

- **v1** (2026-09-28): desenho.
- **v2** (2026-09-28): ajustes da revisão da v1 e implementação P1b:
  - verificações das fixtures que provocam rollback antes do `COMMIT`, incluindo os campos não autorizados das próprias linhas; depois do `COMMIT`, só persistência e `origin` (§5.1);
  - forma única de transação: `BEGIN/COMMIT` no arquivo e `ON_ERROR_STOP`, sem `--single-transaction` (§5.1);
  - caixa como volume Docker exclusivo com dono e permissões conferidos no Linux, ferramenta restrita e só o índice sanitizado exportado (§4.2);
  - protocolo de publicação definido: temporário, fsync e `createLink` (§4.3); consumo único de `proximo` sob trava compartilhada (§4.4); testes de colisão e concorrência previstos (§9);
  - sobreposição de CIDR de qualquer tamanho (§3.1); limite do teste com `0.0.0.0/0` explícito e verificação com as regras originais no ensaio (§9);
  - contagem da regressão corrigida (§9);
  - **correções encontradas na implementação:**
    - a CLI não tem cadastro, que passa à ferramenta com o certificado do CADASTRAR (§6);
    - links não são montados (§4.2);
    - a recusa da 016 sem P3 foi para antes de criar recursos (§7);
    - o padrão continua `HOMOLOG_ATE=015`, para não quebrar o `criar` de hoje (§3.1);
    - `pg_hba.conf`/`nginx.conf` originais preservados para os ambientes existentes (§2);
    - B11 com restauração pendente (§10).
- **v2.1** (2026-09-28): correções da revisão do código de P1b:
  - fixture: classificação pela marca pós-`COMMIT` e pelo código do `psql`; falha pós-`COMMIT` ou resultado perdido exigem reconciliação (`conferir`), nunca "nada alterado"; registro preventivo da solicitação como imprópria para B9 antes de executar (§5.1);
  - `entrada`: versão da chave por configuração controlada, sem leitura de tabela pelo serviço (§6);
  - sondas: classificação pela causa, `FALHA_TECNICA` separada da recusa esperada, disponibilidade antes e depois (§6);
  - transporte: gravação até esgotar o buffer antes do fsync e da publicação (§4.3);
  - `h016.sh`: rótulos de cobertura alinhados ao que é verificado. B5 com invariantes e políticas; B10 e B11 parciais, com o restante pendente e explícito; B12 com inventário por ID (§7).
  - **Para P1c (registrado):**
    - a contraprova das fixtures injeta a alteração indevida por comando explícito numa cópia de teste do script, depois da alteração autorizada e antes das verificações, **na mesma transação** (com `session_replication_role = replica`, gatilhos não disparam);
    - comprova a injeção lendo o valor dentro da transação;
    - comprova o rollback integral pela linha igual ao instantâneo anterior (inclusive os campos autorizados) e por `session_replication_role = origin`.
    - A preservação usa o inventário por ID; B11 continua parcial até a restauração.
- **v2.2** (2026-09-28): segunda revisão do código de P1b:
  - B6 (`.31`) espera `pg_hba.conf rejects connection`, a mensagem da regra explícita de recusa que termina o `pg_hba` gerado. Antes esperava "no pg_hba.conf entry" e reprovaria justamente quando a proteção funciona. O comentário "não tem linha" foi corrigido;
  - `RECUSADA_TLS` só para `unknown_ca`; os demais alertas passam a `FALHA_TECNICA:TLS:<alerta>`. A sonda da CA estranha exige também `certificate verify failed` no log do servidor no intervalo da sonda. Testes de P1c com a distinção (§9).
- **v2.3** (2026-09-29): correções encontradas na execução do P1c (testes em recursos descartáveis), **sem mudança de contrato, privilégio ou escopo**:
  - D1/D2: sonda `ca-estranha` apresenta o certificado estranho, lê antes de escrever e repete só em falha de transporte, com `tentativas=N` na saída (§6);
  - D6: índice e controle da caixa com `600`; ferramenta com `umask 077` (§4.2);
  - também corrigidos na implementação (sem efeito no desenho): `Dockerfile.operacao` copia `Pki*.class` por glob (o literal `Pki$Ca.class` era expandido como variável) e só `FerramentaHomologacao*.class` da pasta de homologação; `sql/fixture_016.sql` cria o primeiro ORG_ADMIN por `provision_first_org_admin` (o INSERT direto era recusado pela trigger da 012); avaliação do B6 extraída para `avalia_b6` (`h016.sh avaliar-b6`), mesmos critérios.
