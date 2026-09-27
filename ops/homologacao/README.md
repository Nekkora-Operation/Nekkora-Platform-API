# Homologação interna da API v1

Ambiente **interno** da Nekkora para exercitar os fluxos aprovados com dados sintéticos e papéis reais. Não é o ambiente oferecido a clientes, não é produção e não substitui os gates de produção. Relatório: `docs/relatorios/2026-09-26-validacao-integracao-homologacao-api.md`. Identidade de implantação: [IMPLANTACAO.md](IMPLANTACAO.md).

## Comandos

```sh
ops/homologacao/homologacao.sh criar [id]      # recursos, segredos, bootstrap, implantação 001-015, serviços
ops/homologacao/homologacao.sh testar <id>     # H01-H09 e R015 (recusa ambiente BLOQUEADO)
ops/homologacao/homologacao.sh estado <id>     # LIBERADO só com migrador recuperado E instalação completa (001–015, validate)
ops/homologacao/homologacao.sh recuperar <id>  # recupera o MIGRADOR (interrupção abrupta, falha na revogação); mesma trava no banco
ops/homologacao/homologacao.sh implantar <id>  # aplica as versões pendentes; código 0 só com o ambiente LIBERADO ao fim
ops/homologacao/homologacao.sh ensaio-interrupcao <id>   # controles da elevação; SÓ em ambiente descartável (id ensaio-*)
HOMOLOG_CONFIRMA_DESTRUIR=<id> ops/homologacao/homologacao.sh destruir <id>
ops/homologacao/mapa_privilegios.sh            # mede o que a baseline exige da identidade de implantação
```

**Requisitos:** Docker, Git Bash/Linux/macOS, Java 21 (build da API e hash das senhas sintéticas), Python 3 e OpenSSL.

**Variáveis opcionais:** `HOMOLOG_HOST`, `HOMOLOG_PORTA`, `HOMOLOG_BIND` e `HOMOLOG_SEGREDOS`. Os nomes e o formato estão em `exemplo.env`, sem valores.

## O que o `criar` faz

1. **Protege o que já existe**, antes de criar qualquer recurso: recusa um id já usado (evidências, segredos, rótulos ou projeto), sub-rede ou porta ocupada e a porta da stack principal. Grava o inventário anterior e o estado da stack principal.
2. **Segredos fora do Git** (`HOMOLOG_SEGREDOS`, padrão `$HOME/.nekkora/homologacao/<id>`):
   - senhas por identidade;
   - chaves de MFA/HMAC exclusivas do ambiente;
   - ACL do Redis com hash das senhas;
   - CA própria e certificados do gateway, do PostgreSQL e do Redis (30 dias).
3. **Imagens** da Platform e do Portal a partir do commit integrado. O runner recusa fontes principais da API com alteração não commitada.
4. **PostgreSQL 16:** `initdb` com SCRAM (sem `trust`), TLS obrigatório e `pg_hba` em `postgres/pg_hba.conf` (cada identidade só do próprio IP). **Redis:** só TLS, usuário `default` desligado e ACL por serviço (comandos e prefixos de chave mínimos).
5. **Bootstrap** (`sql/bootstrap.sql`) pelo administrador do cluster, no socket local: cria `migrador_nekkora` (dona do banco) e `backup_nekkora`, com senhas enviadas como verificador SCRAM.
6. **Implantação** (`implantacao/implantar.sh`): Flyway pela identidade dedicada, uma versão por vez. A elevação temporária só acontece nas versões de `implantacao/elevacao.txt`, com o sha256 autorizado, sob a trava no banco (exclusão compartilhada por implantação e recuperação, durante toda a execução; as ações privilegiadas rodam na própria sessão da trava e cada conexão do Flyway confere o lock, de modo que a perda da trava impede novas ações), com revogação, encerramento de sessões e conferência a cada janela (controles em [IMPLANTACAO.md](IMPLANTACAO.md), §3). Os serviços só sobem com o ambiente LIBERADO.
7. **Senhas dos papéis de serviço**, também como verificador SCRAM. Em seguida sobem Platform, Portal e gateway.

## Rede

| Rede | Tipo | Quem está nela | Acesso |
|---|---|---|---|
| `interna` | `internal: true`, `10.252.10.0/24` | PostgreSQL `.10`, Redis `.11`, Platform `.20`, Portal `.21`, gateway `.5`, jobs efêmeros (Flyway `.30`, ferramentas `.31`, backup `.32`) | sem saída e sem porta publicada |
| `borda` | normal, `10.252.11.0/24` | só o gateway | publicado em `HOMOLOG_BIND:HOMOLOG_PORTA` |

**Acesso da equipe:**
- `HOMOLOG_BIND` = interface da rede interna da empresa;
- o nome `HOMOLOG_HOST` resolvendo para essa máquina;
- a CA `tls/ca.crt` (pública) instalada como confiável.

A chave da CA (`tls/ca.key`) **nunca** sai do diretório de segredos.

## Segredos: origem, responsáveis, substituição

- **Origem:** gerados por `lib/segredos.py` (`secrets`/`/dev/urandom`) e por `openssl`, a cada `criar`. Nada é copiado da base principal nem de outro ambiente.
- **Responsáveis:** o operador da homologação (quem executou o `criar`) e o responsável técnico da API.
- **Substituição:** não há rotação automática. Os procedimentos são:
  - **ambiente inteiro:** `destruir` e `criar` com outro id;
  - **uma senha de serviço:** gerar outra no diretório de segredos, aplicar o verificador SCRAM (`sql/senhas_servicos.sql`), atualizar o `.env` do serviço e reiniciar só o serviço;
  - **certificados:** vencem em 30 dias, e a renovação é recriar o ambiente.
- **Descarte:** o `destruir` não apaga o diretório de segredos. Apague-o manualmente depois de confirmar que o ambiente não será mais usado.
- **Limite conhecido:** os `.env` dos serviços viram variáveis de ambiente dos containers, visíveis para quem tem acesso ao Docker do host. Em produção, use um cofre de segredos.

## Falha de implantação e recuperação

O runner para na primeira falha: sem nova tentativa, sem `repair`, sem `baseline` e sem `clean`.
- **Estado conferido limpo:** o runner libera a trava.
- **Falha na revogação ou na conferência, ou encerramento abrupto** (SIGKILL, queda do host), que o runner não consegue tratar: a trava e a elevação podem ficar. O ambiente fica **BLOQUEADO**: `estado`, `testar` e novas implantações recusam.

Para liberar: `homologacao.sh recuperar <id>`, que obtém a trava no banco (senão recusa sem alterar nada), revoga, encerra as sessões, confere `0|0|0` e só então remove a trava local. Isso recupera o **migrador**. Se a instalação ficou incompleta, o ambiente **continua BLOQUEADO** até `homologacao.sh implantar <id>` concluir as versões pendentes. Procedimento completo em [IMPLANTACAO.md](IMPLANTACAO.md), §5.

## Recriar

`destruir` e depois `criar`, que reproduz o ambiente do zero, com novos segredos. Os dados são sintéticos (fixture da H05), e nada precisa ser preservado.

O `destruir` remove **só** o que tem o rótulo `nekkora.homologacao=<id>`: containers, volumes, redes e as imagens construídas. Nunca remove por prefixo, nunca usa `prune` e nunca toca a stack principal nem as stacks de teste.

## Testes (H01–H09, R015)

| Teste | O que prova |
|---|---|
| H01 | Identificação (commit, imagens, digests, limites), isolamento e preservação dos recursos preexistentes |
| H02 | Implantação 001–015 com as janelas de elevação; a reexecução só valida histórico e checksums |
| H03 | Catálogo igual à instalação de referência (com o instalador normalizado); invariantes 012–015 reexecutados; atributos e memberships; login real dos serviços por TLS |
| H04 | TLS do gateway; recusas do PostgreSQL (texto claro, CA errada, nome errado, senha errada, papel fora do próprio IP); rede sem saída; ACL e TLS do Redis |
| H05 | Fluxo integrado pelo gateway: MFA, Portal com o cookie da Platform, isolamento de organização, cliente e concessão, logout, revoke-all, CSRF e Origin |
| H06 | Contexto por requisição e `DISCARD ALL` no pool real, medidos pelo log de instruções ligado só durante o teste, sem parâmetros |
| H07 | Redis indisponível; R7 esgotada com logout e revoke-all funcionando; logout idempotente; revoke-all sem sessão → 401 |
| H08 | Marcadores secretos ausentes dos logs de todos os serviços e das evidências; detector com controle positivo |
| H09 | Recusa de id em uso; checksum divergente; falha simulada com e sem elevação (sem nova tentativa, elevação revogada, histórico intacto) |
| I1–I7, I9, I10 (`ensaio-interrupcao <id> ["casos"]`) | Só em ambiente descartável: sha256 não autorizado; implantação simultânea (mesmo host e "outro host"); `kill -9` do runner e queda do Flyway na janela; banco indisponível na revogação; bloqueio da liberação e recuperação; corrida na entrada antes da elevação (I6, 5 rodadas); recuperação durante implantação ativa (I7); perda só da sessão da trava, em três pontos (I9a–c); código de saída do `implantar` com a conferência final recusada (I10) |
| I8 (`testes/instalacao_incompleta.sh ensaio-<nome>`) | Só em ambiente descartável, criado e destruído pelo próprio teste: criação interrompida na 011; `recuperar` devolve o migrador, mas o ambiente continua BLOQUEADO pela instalação incompleta; consulta falha bloqueia; `implantar` conclui → LIBERADO |
| R015 | Backup lógico pela identidade de backup, restauração completa noutro cluster, catálogo e dados idênticos |
