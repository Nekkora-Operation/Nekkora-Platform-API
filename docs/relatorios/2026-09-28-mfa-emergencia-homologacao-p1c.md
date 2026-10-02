# Homologação controlada da 016 — P1c: testes da infraestrutura em recursos descartáveis

**Data:** 2026-09-28/29 (UTC 22:39 → 03:35). **Base:** HEAD `dd74a8f` (sem commit da missão) + pacote P1b aprovado
`homologacao-016-p1b-20260928T222151Z.zip` (SHA-256 `af5c124f…d65c7f`). **Natureza:** código, evidências e relatório
**para revisão**, sem commit/push.

**Revisão documental (2026-09-29, depois da revisão do pacote `homologacao-016-p1c-20260929T033334Z.zip`, SHA-256
`2555170b…3b882e`, preservado):** corrigida a descrição das repetições da sonda `ca-estranha` (D2 e §4.3: comprovado,
ainda não observado e estresse, separados) e D1/D2/D6 consolidados no desenho v2.3. Sem mudança de código, testes ou
evidências de execução; regressão não repetida (mesmos arquivos de código). Aprovação de P1c **não** é homologação completa da 016 nem liberação de produção.

O que este relatório **não** afirma: execução integrada do `h016.sh` no ambiente completo (ensaio, P4); restrição por IP
(P4/B6); B9 real; varredura de tentativas paradas (B10 continua parcial); restauração em segundo cluster (B11 parcial);
P3 (elevação da 016, `elevacao.txt` sem mudança); remoção de `nk016-smoke`.

## 1. Resultado por requisito

Legenda: **COMPROVADO** (execução real nesta missão, evidência indicada), **FALHOU**, **BLOQUEADO**, **FORA DO ESCOPO**.
Evidências: caminhos relativos a `evidencia/` do pacote. Os arquivos por grupo da versão final (`fixture/`, `runner/`,
`pghba/`, `ferramenta/`, `cliente/`) estão em `regressao/evidencias-testes/`; os da imagem (`operacao/`) em
`testes-tentativas/t16/` (execução completa) e `testes-tentativas/t17/` (estresse); a caixa Linux em `caixa/`. `tNN` =
tentativa (todas preservadas em `tentativas/` e `testes-tentativas/`).

| # | Requisito | Resultado | Evidência (final) |
|---|---|---|---|
| **1** | **Configuração e transporte** | | |
| 1.1 | Matriz de configuração (válida habilita; inconsistente impede a partida; padrão = local) | COMPROVADO | `CaixaHomologacaoConfigIT` (6 testes) na regressão final; t01 |
| 1.2 | Despachante real com ACEITO/ENFILEIRADO/RECUSADO/INCERTO/EXCECAO, estado e categoria simulados gravados; `LocalMailSender.simulated()` falso | COMPROVADO | `CaixaHomologacaoMailSenderIT` (regressão final) |
| 1.3 | Colisão sem sobrescrita, repetição por attempt_id, mensagens sem attempt_id, gravação completa, sem arquivo final parcial | COMPROVADO | idem; e `caixa/caixa_linux.txt` (JVM Linux, volume) |
| 1.4 | Concorrência, consumo único de `proximo`, trava ocupada externamente, recuperação explícita sem expiração | COMPROVADO | idem (N threads, dois transportes + escritor externo); `caixa/caixa_linux.txt` (runner real: trava órfã, `liberar-trava` com confirmação) |
| 1.5 | Caixa no volume Linux: dono/permissões, ferramenta restrita sem rede, rejeição de índice fora do formato | COMPROVADO (após correção D6) | `caixa/caixa_linux.txt` 36/36 (tentativas 1–3 em `caixa-tentativa*`) |
| 1.6 | Segredos fora de Git/relatório/evidência; só o índice sanitizado exportado | COMPROVADO | `caixa/caixa_indice.jsonl` (sanitizado); varredura + controle positivo do pacote; testes conferem ausência de tokens/senhas nas saídas |
| **2** | **Fixtures SQL e reconciliação** | | |
| 2.1 | Scripts reais com `psql -v ON_ERROR_STOP=1`, BEGIN/COMMIT interno, sem `--single-transaction` | COMPROVADO | `FixtureHomologacaoIT`, `FixtureRunnerIT` (runner real) |
| 2.2 | Deslocamentos exatos, contagens, campos não autorizados, demais linhas, fotografia; `captured_at` não deslocado | COMPROVADO | `fixture/contatos_aplicada.txt`, `fixture/solicitacao_aplicada.txt` (µs, quatro tabelas + fotografia) |
| 2.3 | Recusas: org não sintética, servidor divergente, contagem divergente, linha extra, solicitação existente (contatos) | COMPROVADO | `fixture/recusas.txt` (contagem e linha extra com o script REAL e sessão concorrente) |
| 2.4 | Contraprova obrigatória (injeção explícita em cópia de teste, lida, recusada, rollback integral; sem gatilho) | COMPROVADO | `fixture/contraprova_copias.txt`, `fixture/recusas.txt` (casos `contraprova_*`) |
| 2.5 | `session_replication_role` = origin: sessão original × conexão nova | COMPROVADO | `fixture/session_replication_role.txt` (§4.2) |
| 2.6 | APLICADA, NAO_APLICADA, APLICADA_COM_FALHA_POS_COMMIT, INDETERMINADA (observadas × sintéticas) | COMPROVADO | `fixture/classificacao_observada.txt`, `fixture/classificacao_sintetica.txt`, `runner/execucoes.txt` |
| 2.7 | Reconciliação: original, aplicado, divergência, sem estado de partida; falha de leitura não conclui | COMPROVADO | `fixture/reconciliacao.txt`, `runner/execucoes.txt` (banco pausado: "nada concluido") |
| 2.8 | Registro preventivo para B9 antes da tentativa, inclusive em falha e indeterminada | COMPROVADO | `saidas-ambientes-teste/ensaio-p1c-rn-*/solicitacoes_com_fixture.txt` e `.sonda` |
| **3** | **Autenticação, TLS e identidades** | | |
| 3.1 | pg_hba/pg_ident gerados pelas ferramentas reais | COMPROVADO | `pghba/configuracao.txt` (sha256, trocas /32, `pg_hba_file_rules` sem erro) |
| 3.2 | Certificado válido aceito; senha e CN divergente 28xxx; CA estranha `unknown_ca` + `certificate verify failed` | COMPROVADO (após correções D1/D2) | `pghba/sondas.txt`, `operacao/execucoes.txt` (imagem, 10×), `operacao/estresse_ca_estranha.txt` (30×) |
| 3.3 | Alerta genérico, porta errada, servidor indisponível → FALHA_TECNICA; sem evidência do servidor reprova; real × simulado | COMPROVADO | `pghba/sondas.txt` (reais), `pghba/classificacao_simulada.txt` (simuladas), `pghba/avaliacao_b6.txt` |
| 3.4 | Disponibilidade antes e depois | COMPROVADO | `pghba/sondas.txt` (valida ×2, SELECT 1) |
| 3.5 | SCRAM `nekkora_ops_ca` / `nekkora_ops_intake`, cada um com a sua credencial, sem ampliar permissões | COMPROVADO | `pghba/scram.txt` |
| 3.6 | Versão da chave pela configuração; versão divergente recusada; sem leitura de tabela pelo serviço | COMPROVADO | `FerramentaHomologacaoIT`; `operacao/execucoes.txt` |
| 3.7 | Restrição por IP com regras originais (.33/.31) | FORA DO ESCOPO (P4) | declarado; a mensagem real da regra explícita foi obtida com outro usuário/banco (§4.3) |
| **4** | **Ferramentas operacionais e imagem** | | |
| 4.1 | Imagem `operacao` de teste sem segredos; CA/IdP simulados só no uso previsto | COMPROVADO (após D3/D4) | `imagens/03_conteudo_operacao.txt` |
| 4.2 | credencial/operador/contato/entrada/sonda pelo caminho implementado; credencial registrada, ≤ 15 min, aceita em ato real, vencida recusada | COMPROVADO (após D5) | `ferramenta/*`, `operacao/*` (inclui vencimento em TEMPO REAL, `operacao/vencimento_real.txt`) |
| 4.3 | Componentes/cenários novos do cliente executáveis isoladamente | COMPROVADO (isolado) | `cliente/cenarios_h016.txt`; `h016.sh avaliar-b6` em `pghba/avaliacao_b6.txt` |
| 4.4 | `h016.sh` integrado no ambiente completo | FORA DO ESCOPO (ensaio) | não declarado concluído |
| **5** | **Regressão e preservação** | | |
| 5.1 | Regressão pertinente de nekkora-ops e nekkora-platform com os testes novos | COMPROVADO (§6) | `regressao/regressao_maven.txt` |
| 5.2 | Validação estática afetada + sobreposição de redes | COMPROVADO | `estatica/validar_config.txt`, `estatica/controle_negativo_secao9.txt` |
| 5.3 | Inventário antes/depois por ID, recursos próprios e destino | COMPROVADO (§7) | `inventario/` |

Nenhum requisito obrigatório ficou BLOQUEADO.

## 2. Baseline e preservação do trabalho existente

- `baseline/git_inicial.txt`: branch `feat/mfa-emergencia`, HEAD = main = origin/main = `dd74a8f`; status completo.
- `baseline/comparacao_p1b.txt`: ZIP P1b com o SHA-256 aprovado; manifesto 53/53; os **34** arquivos de `repo/` idênticos
  byte a byte à árvore de trabalho no início; nenhum removido. Migration 016 `25bef818…fbe61` (sem diff contra HEAD).
- Arquivos de outras missões (saídas de `db/tests`, `ops/mfa-emergencia/saidas`, `api/e2e/email/node_modules`) não
  foram tocados. HEAD não mudou durante a missão (nenhum commit, stage ou push).

## 3. Defeitos encontrados na P1b e correções (dentro do contrato)

Cada um foi observado em execução; a correção foi repetida e há trava estática contra regressão (§5).

| id | Defeito (observado) | Consequência sem correção | Correção |
|---|---|---|---|
| D1 | `sonda ca-estranha` com o gerenciador de chaves **padrão do pgjdbc**: o certificado de CA estranha **não é apresentado** (o emissor não está na lista de CAs aceitas enviada pelo servidor). O servidor vê conexão sem certificado e recusa pelo pg_hba: `RECUSADA_AUTENTICACAO:28000`, "connection requires a valid client certificate", sem "certificate verify failed" (t07; teste `semAFabricaDaSondaOPgjdbcNaoApresentaOCertificadoDeCaEstranha` documenta) | B6 falharia sempre na P4 (ou, pior, a verificação de CA do servidor nunca seria exercitada) | `FerramentaHomologacao.CertificadoForcado` (fábrica TLS só da sonda): apresenta o certificado estranho; servidor verificado pela raiz, nome conferido pelo pgjdbc (verify-full) |
| D2 | Dentro do container Linux, a recusa chega como `FALHA_TECNICA:080xx:SocketException`: o servidor fecha com dados do cliente não lidos (RST) e o cliente, ainda escrevendo, perde o alerta (t11/t12, também com TLS 1.2; no host Windows o proxy do Docker Desktop mascarava) | B6 intermitente na P4 | A fábrica termina o handshake e **lê antes** de o pgjdbc escrever (2 s); só em falha de transporte (`FALHA_TECNICA:08…`) a sonda repete (até 5) e informa `tentativas=N`. Classificação inalterada: reset nunca é recusa. **Comprovado:** repetição até o limite mantendo `FALHA_TECNICA` quando a indisponibilidade persiste (execuções reais com porta errada e servidor pausado: `tentativas=5`, `pghba/sondas.txt`); falha TLS conclusiva não repete (`handshake_failure` e falha local: `tentativas=1`). **Ainda não observado:** recuperação de uma falha transitória para `unknown_ca` numa tentativa posterior. **Estresse da imagem (t17):** 30/30 recusas esperadas na 1ª tentativa; com a leitura prévia, 42/43 no 1º intento no total (a única perda, t14, foi antes da repetição existir) |
| D3 | `Dockerfile.operacao`: `COPY .../Pki$Ca.class` — o Dockerfile expande `$Ca` como variável vazia; `Pki$Ca` **fora da imagem** (histórico mostra `Pki.class` duas vezes) | `sonda ca-estranha` (usa `Pki.ca`) com `NoClassDefFoundError` na P4 | COPY por glob `IdpSimulado*.class Pki*.class` |
| D4 | `Dockerfile.operacao` copiava o **diretório** `homologacao/` inteiro (recursivo) | Código de teste do P1c (`homologacao/p1c`) entraria na imagem | COPY só `FerramentaHomologacao*.class`; testes do P1c no subpacote `p1c` |
| D5 | `sql/fixture_016.sql`: INSERT direto do primeiro ORG_ADMIN sem responsável → recusado pela trigger da 012 ("bootstrap sem responsavel so e permitido via provision_first_org_admin (current_user=admin_cluster)") (t11) | `h016.sh preparar` falharia na P4 | Primeiro ORG_ADMIN por `provision_first_org_admin` (SECURITY DEFINER, caminho autorizado); o segundo continua atribuído pelo primeiro |
| D6 | Caixa no volume Linux: `indice.jsonl` com **644** (FileChannel CREATE sem permissão); o `controle.conf` também nasceria 644 (regravação por `Files.write`; ferramenta com umask 022) | Desvio do desenho §4.2 ("arquivos 600"; mitigado pelo diretório 700) | Transporte cria o índice com 600 e restringe o controle regravado; `caixa controle` do runner com `umask 077`. Conferido: índice, controle gravado pela ferramenta e controle regravado pelo transporte com 600 |

Ajustes sem mudança de comportamento: B6 do `h016.sh` teve a avaliação extraída para `avalia_b6` (mesmos critérios; a
saída do `.31` passa a ir inteira para o arquivo) e exposta em `h016.sh avaliar-b6 <arquivo>` para ser testada com
saídas reais. `FerramentaHomologacao.tentar`/`Conexao` passaram a públicos (testes no subpacote). Suporte de teste:
`OpsBanco` ganhou forma parametrizável (superusuário, `cluster_name`, pg_hba/pg_ident, rede, IP fixo, rótulos); a forma
padrão é a de antes (mesmo pg_hba, mesmo `cluster_name`, mesmos nomes no certificado).

**Consolidado no desenho v2.3** (depois da revisão do P1c): D1/D2 na §6 (sonda `ca-estranha`: apresenta o certificado,
lê antes de escrever, repete só em falha de transporte com `tentativas=N`) e D6 na §4.2 (índice e controle `600`, `umask
077` da ferramenta); D3–D5 e a extração do `avalia_b6` registrados no histórico (§13). Nenhuma mudança de contrato,
privilégio ou escopo.

## 4. Detalhes por grupo

### 4.1 Configuração e transporte
- **Matriz (`CaixaHomologacaoConfigIT`)**: a Platform REAL sobe (ou não) com as mesmas variáveis do `compose.caixa.yml`.
  Padrão → `LocalMailSender`, sem agendador; `local` explícito continua local; válida → caixa, agendador ligado, log
  `evento=mail.transporte transporte=homologacao ambiente=… simulado=sim envio_externo=nenhum`; não sobe com ambiente
  vazio/`hml03`/`producao`/`ensaio-`/maiúsculas/35 caracteres/prefixo errado, sem caixa, caixa inexistente ou arquivo,
  transporte `smtp`/`Homologacao`/`homologacao2`/`real`.
- **Despachante (`CaixaHomologacaoMailSenderIT`)**: base 001–016 exclusiva; bean escolhido pelo PlatformConfig;
  `dispatchPending()` → `ops_notice_delivery_result`. Por desfecho: estado/categoria em `ops_mfa_notices` e o histórico
  `ops_mfa_notice_results` (reivindicação `2:-:APLICADO` + `4:ACEITE_SIMULADO`, `3:FILA_SIMULADA`, `5:RECUSA_SIMULADA`,
  `6:INCERTO_SIMULADO` ×2), log `simulado=sim`. Colisão: arquivo pré-existente intacto, `repeticao-1`, alerta. 16 threads ×
  12 mensagens de 256 KiB com leitor contínuo: nenhum arquivo final parcial visto, nomes distintos, `.tmp` vazio. Uma regra
  `proximo` × 24 entregas concorrentes: consumida uma vez. Dois transportes + escritor externo com o protocolo da
  ferramenta: nenhuma regra perdida ou duplicada. Trava ocupada: espera de 5 s, INCERTO_SIMULADO, nenhuma mensagem,
  regra preservada; sem expiração; liberação explícita → volta a consumir. Índice pela sanitização real, sem token/endereço;
  linha com campo extra → índice inteiro recusado.
- **Volume Linux (`testes/p1c_caixa.sh`)**: compose REAL (homolog + 016 + caixa) com id de teste `ensaio-p1c-cx-*`
  (só o volume rotulado e containers `--rm`; segredos/config fictícios fora do repositório), `caixa-init` como no `criar`
  (`nekkora 700`), transporte na JVM Linux da imagem Platform de teste, runner REAL para `listar`, `ler` (conteúdo para
  arquivo temporário fora da evidência), `controle`, `liberar-trava`, `exportar-indice`, e `destruir` pelo rótulo.
  Ferramenta: só `lo`, raiz somente leitura, volume gravável, usuário nekkora. Tentativas 1–3 falharam por defeitos do
  **script de teste** (aspas no `ambiente.env`; `MSYS_NO_PATHCONV` exportado vazando para o python do runner) e pela D6;
  o volume da tentativa 1 ficou para trás e foi removido manualmente (§7).

### 4.2 Fixtures
- Base na forma da homologação (`cluster_name=nekkora-homologacao`, `admin_cluster`, TLS), rede própria `10.252.40.0/24`
  (sobreposição conferida por `lib/redes.py` contra todas as redes, só leitura), servidor `.10`, cliente psql `.31`; 001–016
  pelo mecanismo da suíte (initdb). Preparação pelos caminhos reais da suíte (declarada).
- **Aplicada**: contatos −721 h e solicitação −25 h exatos em µs; nulos continuam nulos; campos não autorizados e demais
  linhas idênticos; eventos de contato inalterados; fotografia inteira idêntica; as contagens `linhas=apr:… not:… req:1
  tok:…` do script conferem com a contagem independente do teste.
- **Recusas** (código 3, `ERROR:`, sem marca, nada alterado, reconciliação NAO_APLICADA): org não sintética, servidor
  divergente, ambiente não descartável, contato de outra org, lista repetida, solicitação existente, horas 0/73, solicitação
  de outra org; **contagem divergente** e **linha extra** com o script REAL e uma sessão concorrente (linha segura por
  FOR UPDATE; remoção/alteração confirmada enquanto a fixture espera).
- **Contraprova** (cópias `teste_injecao_*.sql`, bloco inserido imediatamente antes de "-- (4) verificacoes ANTES do
  COMMIT", sha256 do original registrado): contatos — campo não autorizado e linha extra; solicitação — campo não
  autorizado (`notice_seq`), fotografia (`captured_at`), linha extra e deslocamento diferente. Em todas: `INJECAO_LIDA|…`,
  `AUTORIZADO_LIDO|…` (deslocamento autorizado já aplicado na mesma transação), `NA_TRANSACAO|session_replication_role=replica
  xid=…`, recusa específica, código 3 e fotografia inteira igual à anterior (inclusive campos autorizados). Sem gatilho.
- **session_replication_role**: caminho APLICADA — a sessão ORIGINAL imprime `origin` depois do COMMIT (saída do script);
  caminhos de recusa — a sessão original termina no erro (psql para), logo só há verificação em **conexão nova**
  (`SHOW` = origin e nenhum `session_replication_role` em `pg_db_role_setting`).
- **Classificação observada** (falhas controladas no banco): NAO_APLICADA (recusas); INDETERMINADA sem commit (backend
  encerrado no UPDATE) → reconcilia NAO_APLICADA; APLICADA_COM_FALHA_POS_COMMIT (ACCESS EXCLUSIVE na fila; backend encerrado
  na conferência pós-COMMIT) → reconcilia APLICADA; INDETERMINADA **com** commit (réplica síncrona inexistente exigida; COMMIT
  gravado localmente e backend encerrado na espera; servidor: "already committed locally") → reconcilia APLICADA. Sintéticas
  (texto montado, rotuladas): código 0 sem marca, código 3 sem ERROR, marca sem `verificacoes=OK`, marca + erro pós-COMMIT,
  sem linha de código.
- **Runner real (`FixtureRunnerIT`)**: `homologacao.sh fixture` sem alteração; único desvio, um shim de `docker` que leva
  `compose run … ferramentas psql` ao cliente do teste. Observados: APLICADA; NAO_APLICADA ("transacao desfeita, nada foi
  alterado"); banco pausado → INDETERMINADA ("a RECONCILIAR", nunca "nada foi alterado") → `conferir` SEM_ESTADO_DE_PARTIDA
  (código 1); falha pós-COMMIT → APLICADA_COM_FALHA_POS_COMMIT sem "rollback/desfeita/nada foi alterado" → `conferir`
  APLICADA; `conferir` com banco pausado → "nada concluido", nada anexado. B9: a linha `tentativa` existe **no instante em
  que o psql é chamado** (`.sonda`) em todos os casos, inclusive falha e indeterminada; contatos não tocam o registro.

### 4.3 Autenticação e TLS (`PgHbaHomologacaoIT`)
- pg_hba e pg_ident gerados por `redes.py gerar` (INTERNA=10.252.44). **Limite:** `/32` → `0.0.0.0/0` só no teste
  (`pghba/configuracao.txt` lista cada troca): comprova **autenticação**, **não restrição por IP**.
- Conexões REAIS pela ferramenta: valida, senha (28000), CA estranha (`unknown_ca` + `certificate verify failed` no log entre
  marcas), CN diferente (28000), valida de novo, SELECT 1. Falhas técnicas REAIS: porta errada, servidor pausado,
  `handshake_failure` enviado pelo servidor (cliente só com ECDHE_RSA frente à chave EC), falha TLS local do cliente — todas
  `FALHA_TECNICA`, `conforme=nao`. Na sonda `ca-estranha`, porta errada e servidor pausado esgotaram a repetição
  (`tentativas=5`) e continuaram `FALHA_TECNICA`; as falhas TLS conclusivas pararam na primeira (`tentativas=1`). SIMULADAS (exceção montada): handshake_failure, decrypt_error, access_denied,
  bad_certificate, certificate_unknown, unknown_ca, 28P01, ConnectException.
- Avaliação REAL do B6 (`h016.sh avaliar-b6`): aprovado com as saídas reais; reprovado sem a evidência do servidor, com
  alerta genérico, com "no pg_hba.conf entry" no lugar da regra explícita, sem disponibilidade depois, com uma só `valida`.
  A linha do `.31` é **composta** com a mensagem real da regra explícita obtida com outro usuário/banco (a prova com `op_*`
  a partir do `.31` é da P4).
- SCRAM: senhas pelo `sql/senhas_servicos.sql` REAL com verificadores de `segredos.py scram`; cada serviço autentica pela
  própria linha do pg_hba (número da linha no log), a senha do outro serviço → 28P01; sem atributo privilegiado;
  participações em papéis inalteradas; sem EXECUTE em `ops_mfa_open` nem SELECT em tabelas da 016.

### 4.4 Ferramenta, imagem e cliente
- `FerramentaHomologacaoIT` (processo Java separado): `operador` (2 habilitações OK), `contato` (evento CONTRATACAO pelo
  CADASTRAR), `credencial` (PKCS#12, 900 s, registrada para o operador certo), ato REAL da `OpsCli` com a credencial (OK);
  vencida por fixture declarada do registro → `RECUSA motivo=CERTIFICADO_FORA_DA_VALIDADE`; `entrada` com versão 1 → OK,
  versão 2 → `ENTRADA_INVALIDA`; mecanismo 3 → OK; recusas de uso.
- **Imagem `operacao` (`OperacaoImagemVerificacao`, execução explícita, fora da regressão Maven)**: `docker run --rm
  --read-only --tmpfs /tmp`, IP `.33`, volume `operadores` rotulado preparado pelo comando exato do `operadores-init`
  (`nekkora 700`); `operadores_016.sql` e `fixture_016.sql` REAIS; credencial, 3 operadores (5 habilitações), contato,
  entrada (1 OK, 2 recusada), sondas (ca-estranha 10×), CLI como `homologacao.sh operacao cli` (senha lida dentro do
  container); `.p12`/`.senha` 600. **Vencimento em tempo real**: depois de `validade_ate`, a CLI responde
  `FALHA_CONFIRMADA motivo=CONEXAO_NAO_ESTABELECIDA` e o servidor registra "certificate has expired". Observação:
  `cli.log` (log local sanitizado da CLI) fica 644 no volume 700; o desenho só exige 600 para as credenciais.
- **Cliente (`ClienteH016IT`, isolado)**: `cliente.py` sem alteração contra a Platform REAL em HTTPS com o nome da origem
  e transporte de homologação: `h016_mfa` (2 fatores reais), `h016_confirmar` → 204 e o mesmo token de novo → 400 (uso
  único; a linha "FALHA … obtido=400" na evidência é essa contraprova), aprovação imediata `ESPERA_NAO_CUMPRIDA`, execução,
  `h016_novo_mfa` (exige e conclui novo cadastro). 61 valores secretos vistos pelo cliente, nenhum na saída registrada.
  Não é o `h016.sh` integrado.

## 5. Validação estática

`homologacao.sh validar-config` (sem criar recurso) na versão final: ver `estatica/validar_config.txt`. Seção 9 nova
(travas dos achados D3–D6, sonda e B6), com **controle negativo**: as quatro verificações novas acusam as versões da P1b
(`estatica/controle_negativo_secao9.txt`). Sobreposição de redes: cada rede de teste conferida por `lib/redes.py`
contra as sub-redes de TODAS as redes Docker (só `docker network ls/inspect`), nenhuma rede existente alterada.

## 6. Regressão

Comando (versão final dos arquivos, 2026-09-29 03:21→03:30Z): `cd api && ./mvnw -o -pl nekkora-ops,nekkora-platform -am clean test`
(`regressao/regressao_maven.txt`, por classe em `regressao/resumo.txt`). Código de saída 0.

| Módulo | Testes | Falhas | Erros | Ignorados |
|---|---|---|---|---|
| nekkora-core (entra por `-am`) | 5 | 0 | 0 | 0 |
| nekkora-ops | 155 | 0 | 0 | 0 |
| nekkora-platform | 84 | 0 | 0 | 0 |
| **Ops + Platform** | **239** | 0 | 0 | 0 |

Referência histórica Ops 126 + Platform 66 = 192. Diferença observada = exatamente os testes novos: Ops +29
(`FixtureHomologacaoIT` 15, `FixtureRunnerIT` 5, `PgHbaHomologacaoIT` 6, `FerramentaHomologacaoIT` 3); Platform +18
(`CaixaHomologacaoConfigIT` 6, `CaixaHomologacaoMailSenderIT` 11, `ClienteH016IT` 1). Portal não executado (fora do
pertinente). Nenhum teste ignorado. `OperacaoImagemVerificacao` **não** faz parte da regressão (depende da imagem de
teste): execução explícita t16 (6/6, com vencimento real) e estresse t17 (30 sondas).

Tentativas anteriores (falhas preservadas, causa e correção): t01 (2 erros de teste — expectativa da reivindicação e
marcador; 1 BindException transitória do Windows no apoio), t03/t04 (dados e CHECK da própria prova; espera SyncRep
presa pela remoção das temporárias na saída do backend — o teste passou a retirar a exigência de réplica depois do
encerramento), t07 (formato de asserção; **D1**), t09 (regra CANAL_RECENTE: contato precisava de canal elegível),
t11/t12/t14 (**D2**, **D5**, aspas no harness do Windows), `caixa-tentativa1..3` (script de teste e **D6**).

## 7. Docker: inventário e recursos próprios

- **Início do daemon (autorizado):** o Docker Desktop estava parado; ao iniciá-lo, os três containers `unless-stopped`
  (`nekkora-postgres`, `nekkora-portal-013-postgres`, `nekkora-portal-dev-postgres`) voltaram com os MESMOS IDs e novo
  `StartedAt` (22:39:09Z) e a rede predefinida `bridge` foi recriada com outro ID (`inventario/04_diff_p1b_depois.txt`
  contra o inventário final da P1b). Nada mais difere.
- **Antes × depois da missão:** `inventario/01_antes.txt` (22:39Z, logo após o início do daemon) e `02_depois.txt`
  **idênticos** (`03_diff_antes_depois.txt` vazio): mesmos IDs, nomes, estados, inícios, redes/sub-redes, volumes e imagens.
  `hml03` (parado), banco principal, `nk016-smoke` e o seu volume preservados; nenhuma limpeza global.
- **Recursos próprios:** Testcontainers (containers PostgreSQL/cliente/Ryuk, redes `10.252.40/42/44/46.0/24` e volumes
  anônimos, rótulos `org.testcontainers` e `nekkora.p1c`) removidos pelo próprio Testcontainers ao fim de cada JVM;
  volume `nekkora-p1c-operadores-*` removido pelo teste; projetos `ensaio-p1c-cx-*` removidos pelo `destruir` do runner
  (exceto o da tentativa 1, removido manualmente); imagens `nekkora-p1c-operacao:p1c` e `nekkora-p1c-platform:p1c` e a tag
  temporária `nekkora-homologacao-platform:p1c-*` removidas; dois containers de reprodução manual da espera SyncRep e um
  volume anônimo esquecido (removido). Registro: `inventario/recursos_proprios.txt`.
- **Não removido (declarado):** cache do BuildKit das construções de teste (não é recurso por ID; só sairia com limpeza
  global, vedada). Saídas dos ambientes de teste em `ops/homologacao/saidas/ensaio-p1c-*` (ignoradas pelo Git; copiadas
  para `evidencia/saidas-ambientes-teste/`), sem segredos (só caminhos temporários já apagados).

## 8. Arquivos, varredura e pacote

- Lista em `arquivos-alterados.txt` / `arquivos-novos.txt`; diff incremental contra `repo/` da P1b em
  `diff-incremental.patch` (`incremental-arquivos.txt`: M/A/R) e diff completo contra `dd74a8f` em `diff-dd74a8f.patch`.
  `OpsBanco.java` aparece como "A" no incremental: é arquivo RASTREADO alterado no P1c que a P1b não tocava (por isso não
  está no `repo/` da P1b); a forma padrão continua a de antes.
- Varredura (`ops/mfa-emergencia/varredura.py`) sobre `evidencia/`: sem achados (`varredura.txt`). Controle positivo
  SEPARADO da evidência real (`varredura_controle_positivo.txt`): 200 tokens no formato da v1 e 5 marcadores de
  chave/senha plantados num texto próprio, todos detectados; a evidência real antes do plantio, zero achados. **Gerador
  realmente usado:** Python, `secrets.token_bytes(32)` + base64url sem `=` (lista temporária apagada); a frase fixa
  "gerados por Tokens.newToken da v1" impressa pelo `varredura.py` não descreve esta execução.
- Manifesto SHA-256 conferido diretamente no ZIP extraído (resultado impresso pelo gerador).

## 9. Pendências preservadas

P3 separado (`elevacao.txt` sem a linha da 016). B9 real (sem fixture temporal na solicitação, setup novo) é mini missão
posterior. B10 parcial até a varredura de tentativas paradas ser comprovada; B11 parcial até restaurar em segundo
cluster. Integração completa (`h016.sh`, gateway, compose, runner `criar`) e restrição por IP no ensaio. Descarte de
`nk016-smoke` separado (container e volume preservados). Próxima frente do checklist de backend: inventário das rotas
da API — sem relação com liberação de produção.
