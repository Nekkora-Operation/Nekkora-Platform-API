package com.nekkora.platform.identity;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.TokenSessao;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.Totp;
import com.nekkora.platform.identity.IdentitySessionResolver.IdentityPrincipal;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mail.MailIntents;
import com.nekkora.platform.mail.MailSender;

/**
 * Vinculacao de conta antiga a identidade (contrato P3; DC-17.11 e DC-17.12), em duas etapas, SO pela propria pessoa: a
 * conta e procurada pelo e-mail da identidade e nenhuma rota recebe outra conta, outro e-mail ou outra pessoa. Coincidencia
 * de e-mail nunca vincula sozinha: a etapa 1 prova a conta antiga (senha + segundo fator local) e a etapa 2 prova a
 * identidade (step-up de ate 5 min) e consome o desafio que liga as duas.
 *
 * <p><b>Etapa 1</b>, {@code POST /api/v1/links/start}: leitura somente leitura da conta e do hash vigente; Argon2 FORA da
 * transacao, contra o hash ou contra o hash ficticio (mesmo custo); depois uma transacao READ COMMITTED com os dois
 * contextos, na ordem global da 017: identidade e sessao de identidade {@code FOR UPDATE}, reconferidas -> pela porta,
 * organizacao {@code FOR SHARE} -> conta {@code FOR UPDATE} (credencial IGUAL a conferida, conta elegivel e nao vinculada,
 * mesmo e-mail) -> fator local: TOTP com anti-replay ou codigo de recuperacao consumido -> revogacao do desafio vivo da
 * identidade e o desafio novo, amarrado ao par exato -> no fim, a sessao de identidade e a janela do passo reconferidas
 * (fora: rollback integral). Qualquer falha: 401 unico, contado em R13, sem distinguir conta inexistente, senha errada,
 * codigo errado, conta inelegivel ou ja vinculada.
 *
 * <p><b>Etapa 2</b>, {@code POST /api/v1/links/confirm}: uma transacao que chama so {@code identity_link_account}. A
 * funcao reconfere tudo sob os seus locks e e de uso unico; {@code false}: 403 generico. Nos dois casos o cookie do
 * desafio expira. Depois do commit, o aviso ao endereco (o mesmo dos dois lados).
 */
public final class AccountLinkService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountLinkService.class);

    private final IdentityTransactions tx;
    private final IdentityAuthRepository auth;
    private final IdentityLinkRepository links;
    private final LegacyAccountPort accounts;
    private final PasswordHasher hasher;
    private final IdentityHmac hmac;
    private final Limits limits;
    private final MailIntents mail;

    public AccountLinkService(IdentityTransactions tx, IdentityAuthRepository auth, IdentityLinkRepository links, LegacyAccountPort accounts,
                              PasswordHasher hasher, IdentityHmac hmac, Limits limits, MailIntents mail) {
        this.tx = tx;
        this.auth = auth;
        this.links = links;
        this.accounts = accounts;
        this.hasher = hasher;
        this.hmac = hmac;
        this.limits = limits;
        this.mail = mail;
    }

    /** Desfecho da transacao da etapa 1: regra da recusa (nula = concluida) e se um codigo local foi usado. */
    private record Outcome(String rule, boolean usedRecoveryCode) {
        static Outcome refused(String rule) {
            return new Outcome(rule, false);
        }
    }

    /** Reconferencia final reprovada depois das escritas: desfaz a transacao inteira (consumo do fator, desafios). */
    private static final class ProofRollback extends RuntimeException {
        final String rule;

        ProofRollback(String rule) {
            super(rule, null, false, false);
            this.rule = rule;
        }
    }

    /**
     * Etapa 1. Devolve o token do desafio (so para o cookie). Sem sessao de identidade valida: 401; restrita: 403; corpo
     * invalido: 400 (no controller). Exatamente um de {@code code} e {@code recoveryCode}, conferido no controller.
     */
    public String start(IdentityPrincipal who, String sessionToken, UUID organizationId, char[] password, String code, String recoveryCode, String ip) {
        limits.identityRoutes(ip);
        if (who == null || !Tokens.isValidFormat(sessionToken)) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        if (who.restricted()) {
            throw ApiException.forbidden("sessao_restrita");
        }
        Optional<String> email = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_ONLY,
                (id, j) -> id.referencia().filter(who.sessionId()::equals).isPresent() ? auth.email(j, id.identityId()).orElse(null) : null);
        if (email.isEmpty()) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        String normalized = IdentityHmac.normalize(email.get());
        String identity = hmac.global(email.get());
        String target = hmac.fromTime(organizationId, email.get());
        limits.linkBeforeProof(identity, target);

        // leitura sem lock e Argon2 fora de transacao, com o mesmo custo quando nao ha conta ou credencial
        Optional<LegacyAccountPort.Candidate> candidate = tx.inIdentityAndOrganization(new TokenSessao(sessionToken), organizationId, Mode.READ_ONLY,
                (id, j) -> accounts.find(j, organizationId, normalized).orElse(null));
        boolean passwordOk;
        if (password == null || bytesUtf8(password) > PasswordHasher.MAX_BYTES_LOGIN) {
            passwordOk = false;
        } else if (candidate.isEmpty() || candidate.get().passwordHash() == null) {
            hasher.verifyDummy(password);
            passwordOk = false;
        } else {
            passwordOk = hasher.verify(password, candidate.get().passwordHash());
        }
        if (!passwordOk) {
            throw refused(identity, target, ip, "credencial");
        }
        LegacyAccountPort.Candidate c = candidate.get();
        String token = Tokens.newToken();
        UUID correlation = correlation();
        Outcome r;
        try {
            r = tx.inIdentityAndOrganization(new TokenSessao(sessionToken), organizationId, Mode.READ_WRITE, (id, j) -> {
                if (!id.identityId().equals(who.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()
                        || !auth.lockIdentity(j, id.identityId())) {
                    return Outcome.refused("sessao");
                }
                Optional<IdentityAuthRepository.LockedSession> s = auth.lockSession(j, who.sessionId());
                if (s.isEmpty() || !s.get().active() || s.get().restricted() || !auth.identityActive(j, id.identityId())) {
                    return Outcome.refused("sessao");
                }
                LegacyAccountPort.Proof p = accounts.prove(j, organizationId, c.userId(), normalized, c.passwordHash(), code, recoveryCode, correlation);
                if (!p.ok()) {
                    return Outcome.refused(p.wrongCode() ? "codigo" : "conta");
                }
                links.revokeLive(j, id.identityId());
                links.create(j, id.identityId(), c.userId(), Tokens.sha256Hex(token), c.passwordHash());
                // sessao e passo conferidos antes das esperas: reconferidos agora, antes do commit
                if (!auth.sessionStillValid(j, who.sessionId())) {
                    throw new ProofRollback("sessao_vencida");
                }
                if (p.totpStep() != null && !stillAccepted(p.totpStep())) {
                    throw new ProofRollback("passo_vencido");
                }
                if (p.usedRecoveryCode()) {
                    // aviso NA transacao do consumo do codigo local; chave = o desafio de vinculacao emitido
                    mail.record(j, new MailIntents.Intent(MailSender.Kind.NOTICE_RECOVERY_CODE_USED, organizationId, email.get(), null,
                            "vinculacao:" + Tokens.sha256Hex(token)));
                }
                return new Outcome(null, p.usedRecoveryCode());
            }).orElse(Outcome.refused("sessao"));
        } catch (ProofRollback e) {
            r = Outcome.refused(e.rule);
        } catch (DataAccessException e) {
            // a trigger da 017 recusou o desafio (conta vinculada ou e-mail divergente no intervalo): 23514, nada gravado.
            // CHECK violada (23514 com restricao) nao e essa recusa: propaga
            if (!DbErrors.raisedRefusal(e)) {
                throw e;
            }
            r = Outcome.refused("conta");
        }
        if (r.rule() != null) {
            throw refused(identity, target, ip, r.rule());
        }
        LOG.info("evento=identidade.vinculacao_desafio identidade={} ip={} correlacao={}", identity, ClientIpResolver.truncated(ip), correlation);
        return token;
    }

    /**
     * Etapa 2. {@code true}: vinculada. Sem sessao de identidade valida: 401; restrita: 403; recusa da funcao (sem step-up,
     * desafio ausente, vencido, de outra identidade ou ja usado, credencial trocada, conta inelegivel ou ja vinculada):
     * 403 generico.
     */
    public void confirm(IdentityPrincipal who, String sessionToken, String linkToken, UUID organizationId, String ip) {
        limits.identityRoutes(ip);
        if (who == null || !Tokens.isValidFormat(sessionToken)) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        if (who.restricted()) {
            throw ApiException.forbidden("sessao_restrita");
        }
        Optional<String> email = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_ONLY,
                (id, j) -> id.referencia().filter(who.sessionId()::equals).isPresent() ? auth.email(j, id.identityId()).orElse(null) : null);
        if (email.isEmpty()) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        String identity = hmac.global(email.get());
        boolean linked = Tokens.isValidFormat(linkToken)
                && tx.preAuthentication(Mode.READ_WRITE, j -> {
                    if (!links.link(j, sessionToken, linkToken, organizationId)) {
                        return false;
                    }
                    // aviso NA transacao da vinculacao; chave = o desafio de vinculacao consumido (uso unico)
                    mail.record(j, new MailIntents.Intent(MailSender.Kind.NOTICE_IDENTITY_LINKED, organizationId, email.get(), null,
                            "vinculada:" + Tokens.sha256Hex(linkToken)));
                    return true;
                });
        if (!linked) {
            LOG.info("evento=identidade.vinculacao_negada identidade={} ip={}", identity, ClientIpResolver.truncated(ip));
            throw ApiException.forbidden("vinculacao");
        }
        LOG.info("evento=identidade.vinculada org={} identidade={} ip={} correlacao={}", organizationId, identity, ClientIpResolver.truncated(ip),
                RequestContext.correlation());
    }

    private ApiException refused(String identity, String target, String ip, String rule) {
        limits.linkFailure(identity, target);
        // a regra so vai ao log; a resposta e uma so para qualquer causa
        LOG.info("evento=identidade.vinculacao_prova_negada identidade={} ip={} regra={}", identity, ClientIpResolver.truncated(ip), rule);
        return ApiException.unauthenticated("vinculacao");
    }

    /** Mesma tolerancia de {@code Totp.verify}, no instante medido AGORA. */
    private static boolean stillAccepted(long step) {
        long now = Totp.step(Instant.now());
        return step >= now - 1 && step <= now + 1;
    }

    private static int bytesUtf8(char[] c) {
        return StandardCharsets.UTF_8.encode(CharBuffer.wrap(c)).remaining();
    }

    private static UUID correlation() {
        try {
            return UUID.fromString(RequestContext.correlation());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID();
        }
    }
}
