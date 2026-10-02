-- Estado (SOMENTE LEITURA) dos campos que as fixtures declaradas podem alterar (desenho P1a, sec. 5). Usado:
--   - ANTES do BEGIN de cada fixture (\ir deste arquivo com prefixo ANTES), registrando na evidencia o valor de partida;
--   - pela reconciliacao (homologacao.sh fixture <id> conferir <execucao>), com prefixo ESTADO.
-- Saida: uma linha por campo "<prefixo>|<tabela>|<id>|<campo>|<epoch em microssegundos ou NULL>", ordenada. So ids
-- sinteticos e instantes: nada sensivel.
-- -v: prefixo, tipo (contatos|solicitacao) e contatos (uuids por virgula) ou solicitacao (uuid).
SELECT :'tipo' = 'contatos' AS fx_eh_contatos \gset
\if :fx_eh_contatos
SELECT :'prefixo' || '|contato|' || c.contact_id || '|' || x.campo || '|' || coalesce((extract(epoch FROM x.valor) * 1000000)::bigint::text, 'NULL')
FROM ops.org_authorized_contacts c
CROSS JOIN LATERAL (VALUES ('channel_set_at', c.channel_set_at), ('created_at', c.created_at)) AS x(campo, valor)
WHERE c.contact_id = ANY (string_to_array(:'contatos', ',')::uuid[])
ORDER BY 1;
\else
SELECT linha FROM (
    SELECT :'prefixo' || '|req|' || r.request_id || '|' || x.campo || '|' || coalesce((extract(epoch FROM x.valor) * 1000000)::bigint::text, 'NULL') AS linha
    FROM ops.ops_mfa_recovery_requests r
    CROSS JOIN LATERAL (VALUES ('opened_at', r.opened_at), ('t_total', r.t_total), ('verified_at', r.verified_at),
                               ('preapproved_at', r.preapproved_at), ('notice_confirmed_at', r.notice_confirmed_at),
                               ('final_approved_at', r.final_approved_at), ('executed_at', r.executed_at),
                               ('closed_at', r.closed_at)) AS x(campo, valor)
    WHERE r.request_id = :'solicitacao'::uuid
    UNION ALL
    SELECT :'prefixo' || '|not|' || n.notice_id || '|' || x.campo || '|' || coalesce((extract(epoch FROM x.valor) * 1000000)::bigint::text, 'NULL')
    FROM ops.ops_mfa_notices n
    CROSS JOIN LATERAL (VALUES ('created_at', n.created_at), ('issued_at', n.issued_at), ('state_at', n.state_at),
                               ('claimed_at', n.claimed_at), ('accepted_at', n.accepted_at)) AS x(campo, valor)
    WHERE n.request_id = :'solicitacao'::uuid
    UNION ALL
    SELECT :'prefixo' || '|tok|' || k.token_id || '|' || x.campo || '|' || coalesce((extract(epoch FROM x.valor) * 1000000)::bigint::text, 'NULL')
    FROM ops.ops_mfa_notice_tokens k
    CROSS JOIN LATERAL (VALUES ('issued_at', k.issued_at), ('valid_until', k.valid_until), ('used_at', k.used_at),
                               ('invalidated_at', k.invalidated_at)) AS x(campo, valor)
    WHERE k.request_id = :'solicitacao'::uuid
    UNION ALL
    SELECT :'prefixo' || '|apr|' || a.request_id || '/' || a.notice_id || '|' || x.campo || '|'
           || coalesce((extract(epoch FROM x.valor) * 1000000)::bigint::text, 'NULL')
    FROM ops.ops_mfa_approval_attempts a
    CROSS JOIN LATERAL (VALUES ('accepted_at', a.accepted_at), ('approved_at', a.approved_at)) AS x(campo, valor)
    WHERE a.request_id = :'solicitacao'::uuid
) q ORDER BY 1;
\endif
