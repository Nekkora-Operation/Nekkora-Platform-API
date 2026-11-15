# AGENT-CLIENT-NAVEGACAO-002 · T01 — organizações atuais da pessoa no aplicativo desktop

Contrato `desktop-navigation/2`. Sucede `docs/agent-client-navegacao-001/t01-contexto-da-pessoa.md` (contrato `/1`), que
continua valendo em tudo o que este documento não muda.

## 1. O problema, confirmado no código

No contrato `/1` a lista de organizações do aplicativo era o **retrato da aprovação do login**: organização em que a
pessoa entrou depois (convite aceito, organização criada) só aparecia no login seguinte. Repetir a mesma consulta não
resolvia. A causa não é cache, política de autorização nem consentimento:

| Hipótese | O que o código mostra |
|---|---|
| Cache | Não há. Cada requisição já reconferia cada organização no banco (`identity_org_session_allowed`). |
| Consentimento por organização | Não existe. O escopo `identity navigation` é um só, e o texto aprovado pela pessoa fala em "as organizações de que você participa", sem lista. |
| Política de autorização | Não muda: a regra de quem vê o quê é a mesma da Web (`WorkspaceAccess`, `ProductReads`). |
| **Retrato dos vínculos** | **É a causa.** A sessão do aplicativo guarda só a identidade e o **id** da sessão de identidade que a autorizou. As funções que resolvem "identidade → contas e organizações" (`identity_organizations`, `identity_org_session_target`, 017) exigem o **token** da sessão do navegador, que só está presente na aprovação e nunca é guardado. E a Platform não tem, por invariante da 017, privilégio algum em `identity_users`. Sem uma função nova, a única hora em que a lista podia ser resolvida era a aprovação. |

## 2. A solução mínima

Uma função nova no banco e o serviço passando a usá-la a cada requisição. Nada mais.

**Migration `039_organizacoes_atuais_da_sessao.sql`** cria
`public.identity_session_organizations(p_identity_session_id uuid, p_organization_id uuid, p_limit integer)`:

- dono `nekkora_identity_owner`, `SECURITY DEFINER`, `search_path` fixo, `EXECUTE` **só** para `nekkora_platform`;
- **entrada:** o id da sessão de identidade (não é segredo de portador), a organização (`NULL` = todas) e o limite (1 a 201);
- **saída:** `organization_id`, `user_id`, `name`, em ordem de nome e id. Nenhum papel, e-mail, token, hash ou fator;
- só devolve linhas se a requisição **já estiver no contexto da identidade dona da sessão** (`nekkora.identity_id`, que a
  aplicação define depois de autenticar o token do aplicativo) e a sessão for dela, ativa e não restrita: a mesma regra
  do ramo 2 de `identity_org_session_allowed` (029). A elegibilidade das contas é a de `identity_organizations` (017):
  conta interna ativa e verificada, organização ativa, vínculo ativo;
- sem contexto, com contexto de outra identidade, sessão inexistente, encerrada, vencida ou restrita: nenhuma linha;
- só leitura: nenhum lock, escrita ou evento.

Não há tabela, coluna, papel, política ou privilégio de tabela novo. Nenhuma função existente muda. A Platform continua
sem privilégio em `identity_users` (conferido nas invariantes da migration e na suíte).

**Serviço (`DesktopNavigationService`):**

- `GET /desktop/organizations`: chama a função (até 200) e, como antes, confere e lê cada organização no próprio
  contexto. A resposta ganha `"asOf": "request"`.
- Rotas com `{org}`: na mesma transação da conferência, a conta da pessoa na organização é resolvida pela função, com o
  filtro da organização; sem conta elegível, `404`. Depois, como antes: `identity_org_session_allowed`, vínculo ativo,
  `WorkspaceAccess` e `ProductReads`.
- O retrato da aprovação **não decide mais nada**. Ele continua gravado com o código e a sessão só para que uma instância
  da revisão anterior, durante a implantação ou num retorno, ainda leia uma sessão emitida por esta. Há teste com o
  retrato adulterado e com o retrato vazio.

O que **não** muda: o escopo e o texto do consentimento; a duração da sessão (15 minutos, sem renovação: ler a lista não
a estende); a regra de ativação (organização aguardando ativação aparece como na Web e nada é ativado); o teto de 200;
as rotas, os cursores e os erros; a sessão de escopo `identity`, que continua sem navegar.

## 3. Compatibilidade

| Combinação | Efeito |
|---|---|
| Client antigo × API nova | Funciona. O campo `asOf` é ignorado; a lista já vem atual a cada leitura. |
| Client novo × API antiga | A resposta não traz `asOf`. O Client diz que esta versão da Platform não atualiza a lista de organizações sem novo login e **não** mostra "Dados atualizados" para ela. |
| API nova × banco sem a 039 | Não suportado: as rotas de navegação respondem `500`. A migration vem antes da API, como sempre. |
| Sessões abertas na troca de revisão | Continuam válidas: o formato no Redis não mudou. |

## 4. Elevação

A 039 cria uma função de `nekkora_identity_owner`. O migrador é dono do banco, mas não é membro desse papel: sem a
janela de `SUPERUSER` a migration para em `must be able to SET ROLE "nekkora_identity_owner"` (suíte, cluster B). É o
mesmo caso da 019, 020, 023, 024 e 029.

- **Feito neste PR:** a 039 entra na lista padrão de `db/bootstrap/aplicar_como_migrador.sh`, usada só em bases
  descartáveis de teste, no desenvolvimento e nos laboratórios locais.
- **Não feito, e não autorizado pela missão:** incluir a 039 em `ops/homologacao/implantacao/elevacao.txt` (que exige
  medição, revisão e vínculo com um ensaio) e qualquer aplicação em homologação ou produção. Sem essa autorização a 039
  não é aplicada nesses ambientes e a API desta revisão não pode ser implantada neles.
- **Privilégio antes e depois:** `nekkora_platform` ganha `EXECUTE` em uma função. Nenhum outro papel ganha nada.
- **Retorno:** voltar a API para a revisão anterior e `DROP FUNCTION public.identity_session_organizations(uuid, uuid,
  integer)`. Nenhum dado depende da função. Ensaiado na suíte (cluster B, passo B2).

## 5. Testes

- `db/tests/organizacoes-da-sessao-039/run.sh`: instalação limpa 001–039; dono, `SECURITY DEFINER` e privilégios; sem
  contexto não devolve nada; limite inválido; a Platform não lê `identity_users`; o portal não executa a função; sem a
  janela a migration é recusada e nada muda; com a janela, o catálogo muda em **uma** linha; reaplicação recusada;
  retorno.
- `DesktopNavigationIT` (papel `nekkora_platform`, PostgreSQL e Redis reais): convite aceito e organização criada
  **depois** do login aparecem com o mesmo token; a ordem é a da Web; organização homônima de outra pessoa não vaza, nem
  pelo id; a leitura não estende a sessão; papel revogado, vínculo retirado, organização suspensa e logout valem na
  requisição seguinte; retrato adulterado não dá acesso.
- `DesktopLoginContractTest`: o esquema publicado tem `items` e `asOf`.
