package com.nekkora.core.db;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unico caminho de acesso as tabelas de identidade da 017 (contrato da integracao Java, §1; RLS por identidade, R2).
 *
 * <p>Cada chamada abre UMA transacao READ COMMITTED, REQUIRES_NEW, e executa, nesta ordem (§1.1):
 * <ol>
 *   <li><b>guarda</b>: {@code nekkora.identity_id} e {@code nekkora.organization_id} precisam estar vazios; senao a
 *       transacao aborta ({@link ContextoVazado}) - pega um {@code SET} de sessao esquecido numa conexao do pool;</li>
 *   <li><b>resolucao</b> da identidade pela {@link Chave} exata, por funcao de consulta da 017, DENTRO desta
 *       transacao (ou UUID novo, no cadastro);</li>
 *   <li><b>contexto</b>: {@code SELECT set_config('nekkora.identity_id', ?, true)} - valor por bind, efeito so na
 *       transacao (limpo no COMMIT/ROLLBACK; o {@link ConnectionResetDataSource} ainda faz DISCARD ALL na devolucao);</li>
 *   <li>o <b>trabalho</b>, com o {@link JdbcClient} da mesma conexao.</li>
 * </ol>
 *
 * <p>O {@code identity_id} posto no contexto vem SO da resolucao (ou do UUID gerado no cadastro), nunca de parametro
 * da requisicao: esta classe nao aceita UUID solto. A {@link IdentidadeResolvida} vale so dentro da transacao que a
 * produziu, e so na thread dessa transacao enquanto ela esta ATIVA: em outra thread, numa transacao interna (que
 * suspende a de origem) ou depois do COMMIT/ROLLBACK, falha ({@link IllegalStateException}). Uma transacao nova
 * resolve de novo pela chave.
 *
 * <p>A RLS limita o acesso ao contexto; ela nao prova, sozinha, que a aplicacao autenticou aquela pessoa (§13.3).
 * Nada de @Async/CompletableFuture/reativo dentro do trabalho: fora da thread nao ha transacao nem contexto (a RLS
 * devolve zero linhas - falha fechada).
 */
public final class IdentityTransactions {

    public enum Mode { READ_ONLY, READ_WRITE }

    /** Chave exata de resolucao da identidade. Cada uma corresponde a uma funcao de consulta da 017. */
    public sealed interface Chave permits TokenSessao, TokenDesafio, TokenEmail, EmailCadastro, NovaIdentidade {
    }

    /** Token bruto do cookie {@code __Host-nekkora_id}: {@code identity_session_lookup} (so sessao ativa e no prazo). */
    public record TokenSessao(String token) implements Chave {
        public TokenSessao { Objects.requireNonNull(token, "token"); }
    }

    /** Token bruto do cookie de desafio: {@code identity_challenge_lookup} (so desafio vivo e no prazo). */
    public record TokenDesafio(String token) implements Chave {
        public TokenDesafio { Objects.requireNonNull(token, "token"); }
    }

    /** Token bruto de confirmacao de cadastro: {@code identity_email_token_lookup} (so token vivo e no prazo). */
    public record TokenEmail(String token) implements Chave {
        public TokenEmail { Objects.requireNonNull(token, "token"); }
    }

    /** E-mail normalizado do cadastro: {@code identity_signup_state} (so a identidade daquele e-mail exato). */
    public record EmailCadastro(String emailNormalizado) implements Chave {
        public EmailCadastro { Objects.requireNonNull(emailNormalizado, "emailNormalizado"); }
    }

    /** Cadastro de e-mail livre: a aplicacao gera o identity_id e o define como contexto ANTES do INSERT. */
    public record NovaIdentidade() implements Chave {
    }

    /** Contexto ja definido na conexao ao abrir a transacao (vazamento): a transacao aborta sem executar nada. */
    public static final class ContextoVazado extends IllegalStateException {
        ContextoVazado() {
            super("contexto de identidade ou de organizacao ja definido ao abrir a transacao");
        }
    }

    /**
     * Identidade resolvida nesta transacao. Construida so aqui e vinculada a thread e a transacao de origem: cada acesso
     * exige (1) a thread que a resolveu, (2) a transacao de origem ativa - nao suspensa por uma transacao interna
     * (REQUIRES_NEW/NOT_SUPPORTED) nem encerrada - e (3) o mesmo recurso de conexao ligado a thread. Suspensao,
     * retomada e encerramento chegam pelo {@link TransactionSynchronization} registrado na transacao de origem.
     * {@code referencia}: sessao, desafio ou token que a resolveu (nulo no cadastro).
     */
    public static final class IdentidadeResolvida {
        private enum Estado { ATIVA, SUSPENSA, ENCERRADA }

        private final UUID identityId;
        private final UUID referencia;
        private final Short status;
        private final Thread dona;
        private final Object fonte;
        private final Object recurso;
        private volatile Estado estado = Estado.ATIVA;

        private IdentidadeResolvida(UUID identityId, UUID referencia, Short status, Object fonte) {
            this.identityId = identityId;
            this.referencia = referencia;
            this.status = status;
            this.dona = Thread.currentThread();
            this.fonte = fonte;
            this.recurso = fonte == null ? null : TransactionSynchronizationManager.getResource(fonte);
            if (!TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("identidade resolvida exige transacao com sincronizacao ativa");
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void suspend() {
                    if (estado == Estado.ATIVA) {
                        estado = Estado.SUSPENSA;
                    }
                }

                @Override
                public void resume() {
                    if (estado == Estado.SUSPENSA) {
                        estado = Estado.ATIVA;
                    }
                }

                @Override
                public void afterCompletion(int situacao) {
                    estado = Estado.ENCERRADA;
                }
            });
        }

        public UUID identityId() {
            exigirValida();
            return identityId;
        }

        public Optional<UUID> referencia() {
            exigirValida();
            return Optional.ofNullable(referencia);
        }

        /** So para {@link EmailCadastro}: o status da identidade (1=PENDING, 2=ACTIVE, ...). */
        public Optional<Short> status() {
            exigirValida();
            return Optional.ofNullable(status);
        }

        private void exigirValida() {
            if (Thread.currentThread() != dona) {
                throw new IllegalStateException("identidade resolvida usada fora da thread da transacao que a resolveu");
            }
            switch (estado) {
                case SUSPENSA -> throw new IllegalStateException("identidade resolvida usada com a transacao de origem suspensa");
                case ENCERRADA -> throw new IllegalStateException("identidade resolvida usada fora da transacao que a resolveu");
                case ATIVA -> { }
            }
            if (fonte != null && TransactionSynchronizationManager.getResource(fonte) != recurso) {
                throw new IllegalStateException("identidade resolvida usada com outra conexao ligada a thread");
            }
        }

        private void encerrar() {
            estado = Estado.ENCERRADA;
        }
    }

    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate readWriteTx;
    private final JdbcClient jdbc;
    /** Chave do recurso de conexao da transacao (o DataSource do gerenciador), para conferir a conexao ligada a thread. */
    private final Object fonte;

    public IdentityTransactions(PlatformTransactionManager tm, JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.fonte = tm instanceof DataSourceTransactionManager d ? d.getDataSource() : null;
        this.readOnlyTx = template(tm, true);
        this.readWriteTx = template(tm, false);
    }

    private static TransactionTemplate template(PlatformTransactionManager tm, boolean readOnly) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setReadOnly(readOnly);
        return t;
    }

    /**
     * Guarda -> resolucao pela chave -> contexto de identidade -> trabalho. Chave que nao resolve (token invalido,
     * expirado, revogado; e-mail sem identidade): {@link Optional#empty()}, sem contexto e sem executar o trabalho.
     */
    public <T> Optional<T> inIdentity(Chave chave, Mode mode, BiFunction<IdentidadeResolvida, JdbcClient, T> work) {
        return executar(chave, null, mode, work);
    }

    /**
     * Fluxos com os DOIS contextos: a emissao da sessao de organizacao vinculada, para que a sessao (RLS de organizacao)
     * e o evento da identidade (RLS de identidade) sejam confirmados no mesmo commit; e a vinculacao, etapa 1 (a trigger
     * do desafio de vinculacao trava a conta sob a RLS de organizacao). A organizacao e a do alvo; a identidade, a
     * resolvida pela chave. READ COMMITTED, como as demais transacoes desta classe.
     *
     * <p>A resolucao pela chave NAO trava nada. O trabalho segue a ordem global de locks da 017 (identidade -> sessao de
     * identidade -> organizacao -> conta): trava identidade e sessao de identidade ANTES de qualquer linha da organizacao,
     * inclusive as pedidas implicitamente pelas chaves estrangeiras de quem grava evento ou sessao vinculada.
     */
    public <T> Optional<T> inIdentityAndOrganization(Chave chave, UUID organization, Mode mode,
                                                     BiFunction<IdentidadeResolvida, JdbcClient, T> work) {
        Objects.requireNonNull(organization, "organizacao");
        return executar(chave, organization, mode, work);
    }

    /**
     * Transacao SEM contexto, com a guarda: so para as funcoes anteriores a autenticacao que recebem chave exata e
     * nao precisam de contexto (identity_resolve_login, identity_login_complete, identity_link_account ...).
     */
    public <T> T preAuthentication(Mode mode, Function<JdbcClient, T> work) {
        TransactionTemplate t = mode == Mode.READ_ONLY ? readOnlyTx : readWriteTx;
        return t.execute(status -> {
            guarda();
            return work.apply(jdbc);
        });
    }

    private <T> Optional<T> executar(Chave chave, UUID organization, Mode mode, BiFunction<IdentidadeResolvida, JdbcClient, T> work) {
        Objects.requireNonNull(chave, "chave");
        TransactionTemplate t = mode == Mode.READ_ONLY ? readOnlyTx : readWriteTx;
        IdentidadeResolvida[] resolvida = new IdentidadeResolvida[1];
        try {
            return t.execute(status -> {
                guarda();
                Optional<IdentidadeResolvida> r = resolver(chave);
                if (r.isEmpty()) {
                    return Optional.<T>empty();
                }
                resolvida[0] = r.get();
                jdbc.sql("SELECT set_config('nekkora.identity_id', ?, true)").param(r.get().identityId.toString()).query(String.class).single();
                if (organization != null) {
                    jdbc.sql("SELECT set_config('nekkora.organization_id', ?, true)").param(organization.toString()).query(String.class).single();
                }
                return Optional.ofNullable(work.apply(r.get(), jdbc));
            });
        } finally {
            if (resolvida[0] != null) {
                resolvida[0].encerrar();
            }
        }
    }

    private void guarda() {
        String vazado = jdbc.sql("SELECT coalesce(current_setting('nekkora.identity_id', true), '') || coalesce(current_setting('nekkora.organization_id', true), '')")
                .query(String.class).single();
        if (!vazado.isEmpty()) {
            throw new ContextoVazado();
        }
    }

    private record Linha(UUID identityId, UUID referencia, Short status) {
    }

    /** Le pela chave e so entao constroi a {@link IdentidadeResolvida}, na thread e na transacao corrente. */
    private Optional<IdentidadeResolvida> resolver(Chave chave) {
        Optional<Linha> l = switch (chave) {
            case TokenSessao(String token) -> par("SELECT identity_id, identity_session_id FROM public.identity_session_lookup(?)", token);
            case TokenDesafio(String token) -> par("SELECT identity_id, challenge_id FROM public.identity_challenge_lookup(?)", token);
            case TokenEmail(String token) -> par("SELECT identity_id, token_id FROM public.identity_email_token_lookup(?)", token);
            case EmailCadastro(String email) -> jdbc.sql("SELECT identity_id, status FROM public.identity_signup_state(?)").param(email)
                    .query((rs, n) -> new Linha(rs.getObject(1, UUID.class), null, rs.getShort(2))).optional();
            case NovaIdentidade n -> Optional.of(new Linha(UUID.randomUUID(), null, null));
        };
        return l.map(x -> new IdentidadeResolvida(x.identityId(), x.referencia(), x.status(), fonte));
    }

    private Optional<Linha> par(String sql, String chave) {
        return jdbc.sql(sql).param(chave).query((rs, n) -> new Linha(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), null)).optional();
    }
}
