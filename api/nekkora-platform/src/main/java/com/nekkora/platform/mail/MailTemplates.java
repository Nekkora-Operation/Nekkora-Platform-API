package com.nekkora.platform.mail;

import java.util.UUID;

/**
 * Assunto e corpo (texto simples, pt-BR) de cada {@link MailSender.Kind} (desenho de 2026-10-11, §4). Regras:
 * <ul>
 * <li>todo link aponta para a ORIGEM PUBLICA configurada; nada vem de cabecalho da requisicao;</li>
 * <li>token SEMPRE no fragmento ({@code #token=}), nunca na query: nao vai para log de servidor nem para Referer. Abrir o
 * link nao executa nada: a pagina le o fragmento e envia o token por POST;</li>
 * <li>sem segredo alem do token do proprio ato, sem codigo TOTP, sem dado de outra organizacao.</li>
 * </ul>
 * Os caminhos das paginas sao contrato com a interface web (fora desta missao): {@code /cadastro/confirmar},
 * {@code /recuperar-senha}, {@code /o/{org}/recuperar-senha}, {@code /o/{org}/mfa-emergencia/confirmar} e
 * {@code /o/{org}/mfa-emergencia/contestar}.
 */
public final class MailTemplates {

    public record Rendered(String subject, String body) {
        @Override
        public String toString() {
            return "Rendered[subject]";
        }
    }

    private static final String RODAPE = "\n\nEsta mensagem foi enviada automaticamente pela Nekkora. Nao responda: este endereco nao recebe mensagens.";
    private static final String NAO_FOI_VOCE = "\n\nSe nao foi voce, entre na sua conta e troque a senha e o fator de autenticacao, ou procure o suporte da Nekkora.";

    private final String origin;

    public MailTemplates(String publicOrigin) {
        this.origin = publicOrigin.replaceAll("/+$", "");
    }

    /** Mensagem de um token so (ou nenhum). {@code organization}: so nas rotas por organizacao. */
    public Rendered render(MailSender.Kind kind, UUID organization, String token) {
        return switch (kind) {
            case IDENTITY_SIGNUP_CONFIRMATION -> new Rendered("Confirme seu cadastro na Nekkora",
                    "Para concluir o cadastro, abra o link abaixo e escolha a sua senha:\n\n" + link("/cadastro/confirmar", token)
                            + "\n\nO link vale por 24 horas e so pode ser usado uma vez. Se voce nao pediu este cadastro, ignore esta mensagem." + RODAPE);
            case IDENTITY_SIGNUP_ALREADY_REGISTERED -> new Rendered("Pedido de cadastro na Nekkora",
                    "Recebemos um pedido de cadastro para este e-mail, que ja tem uma conta na Nekkora. Nenhuma conta nova foi criada."
                            + "\n\nSe foi voce, entre normalmente. Se esqueceu a senha, use a recuperacao de senha." + RODAPE);
            case PASSWORD_RECOVERY -> new Rendered("Recuperacao de senha da Nekkora",
                    "Para escolher uma senha nova, abra o link abaixo:\n\n"
                            + link(organization == null ? "/recuperar-senha" : "/o/" + organization + "/recuperar-senha", token)
                            + "\n\nO link vale por 30 minutos e so pode ser usado uma vez. O fator de autenticacao continua o mesmo."
                            + "\n\nSe voce nao pediu, ignore esta mensagem: a senha atual continua valendo." + RODAPE);
            case NOTICE_PASSWORD_CHANGED -> notice("A senha da sua conta foi alterada",
                    "A senha da sua conta na Nekkora foi alterada. As outras sessoes abertas foram encerradas.");
            case NOTICE_RECOVERY_CODE_USED -> notice("Um codigo de recuperacao foi usado",
                    "Um codigo de recuperacao da sua conta na Nekkora foi usado para entrar. As outras sessoes abertas foram encerradas.");
            case NOTICE_MFA_REPLACED -> notice("O fator de autenticacao da sua conta foi trocado",
                    "O fator de autenticacao da sua conta na Nekkora foi trocado. Os codigos de recuperacao anteriores deixaram de valer"
                            + " e as outras sessoes abertas foram encerradas.");
            case NOTICE_RECOVERY_CODES_REGENERATED -> notice("Novos codigos de recuperacao",
                    "Um novo conjunto de codigos de recuperacao foi gerado para a sua conta na Nekkora. Os codigos anteriores deixaram de valer.");
            case NOTICE_IDENTITY_LINKED -> notice("Conta vinculada a sua identidade Nekkora",
                    "Uma conta de organizacao com este e-mail passou a entrar pela sua identidade Nekkora. A senha e o fator antigos"
                            + " dessa conta deixaram de valer.");
            case NOTICE_MFA_RESET -> notice("O fator de autenticacao da sua conta foi redefinido",
                    "Um administrador da sua organizacao redefiniu o fator de autenticacao da sua conta. Na proxima entrada, cadastre um fator novo.");
            case NOTICE_MFA_RESET_ADMIN_TARGET -> notice("Redefinicao de fator de outro administrador",
                    "O fator de autenticacao de outro administrador da sua organizacao foi redefinido.");
            case NOTICE_MFA_EMERGENCY_EXECUTED -> notice("Recuperacao emergencial de acesso executada",
                    "A recuperacao emergencial de acesso foi executada na sua organizacao. Os fatores de autenticacao afetados foram redefinidos.");
            case NOTICE_MFA_EMERGENCY_HOLDER, NOTICE_MFA_EMERGENCY_ADMIN ->
                    throw new IllegalArgumentException("aviso de recuperacao emergencial usa renderEmergency");
        };
    }

    /** Aviso da recuperacao emergencial (016): ate dois links, confirmar e contestar, cada um com o seu token. */
    public Rendered renderEmergency(MailSender.Kind kind, UUID organization, String confirmToken, String contestToken) {
        StringBuilder b = new StringBuilder("Foi pedida a recuperacao emergencial de acesso de uma conta da sua organizacao na Nekkora.");
        if (confirmToken != null) {
            b.append("\n\nSe foi voce quem pediu, confirme:\n").append(link("/o/" + organization + "/mfa-emergencia/confirmar", confirmToken));
        }
        if (contestToken != null) {
            b.append("\n\nSe voce nao reconhece o pedido, conteste:\n").append(link("/o/" + organization + "/mfa-emergencia/contestar", contestToken));
        }
        b.append("\n\nAbrir o link nao confirma nem contesta: a pagina pede a sua acao.").append(RODAPE);
        return new Rendered(kind == MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER ? "Pedido de recuperacao emergencial da sua conta"
                : "Pedido de recuperacao emergencial na sua organizacao", b.toString());
    }

    private Rendered notice(String subject, String text) {
        return new Rendered(subject, text + NAO_FOI_VOCE + RODAPE);
    }

    private String link(String path, String token) {
        if (token == null) {
            throw new IllegalArgumentException("mensagem com link exige token");
        }
        return origin + path + "#token=" + token;
    }
}
