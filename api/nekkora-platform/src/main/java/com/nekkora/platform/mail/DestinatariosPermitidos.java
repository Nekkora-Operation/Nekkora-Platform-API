package com.nekkora.platform.mail;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lista fechada de destinatarios do transporte real num ambiente de homologacao ({@code ensaio-*}): so enderecos da lista
 * chegam ao transporte. Cada item e um endereco EXATO ou {@code local+*@dominio}: o proprio {@code local@dominio} e as suas
 * variacoes com marcador ({@code local+marcador@dominio}, marcador de letras, digitos, ponto, hifen ou sublinhado), que
 * chegam a MESMA caixa controlada e permitem uma identidade nova por execucao. Sem curinga de dominio nem de parte local.
 * Qualquer outro e RECUSADO sem conexao de rede (nada sai), com o evento
 * {@code mail.destinatario_fora_da_lista} no log (so a correlacao: o endereco nunca vai para o log). Protege contra
 * mensagens antigas da fila e contas de teste com enderecos que nao sao controlados.
 */
public final class DestinatariosPermitidos implements MailTransport {

    private static final Logger LOG = LoggerFactory.getLogger(DestinatariosPermitidos.class);

    private static final Pattern EXATO = Pattern.compile("^[^@\\s*+,]+(\\+[A-Za-z0-9._-]{1,64})?@[^@\\s*+,]+\\.[^@\\s*+,]+$");
    private static final Pattern COM_MARCADOR = Pattern.compile("^([^@\\s*+,]+)\\+\\*@([^@\\s*+,]+\\.[^@\\s*+,]+)$");

    private final MailTransport transporte;
    private final Set<String> permitidos;

    public DestinatariosPermitidos(MailTransport transporte, Set<String> permitidos) {
        if (permitidos.isEmpty()) {
            throw new IllegalStateException("lista de destinatarios permitidos vazia");
        }
        this.transporte = transporte;
        this.permitidos = permitidos.stream().map(DestinatariosPermitidos::normalizar).collect(Collectors.toUnmodifiableSet());
    }

    /** Lista separada por virgulas; cada item, um endereco exato ou {@code local+*@dominio}. */
    public static Set<String> ler(String lista) {
        if (lista == null || lista.isBlank()) {
            return Set.of();
        }
        Set<String> s = Stream.of(lista.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toUnmodifiableSet());
        for (String e : s) {
            if (!EXATO.matcher(e).matches() && !COM_MARCADOR.matcher(e).matches()) {
                throw new IllegalStateException("nekkora.mail.destinatarios-permitidos: item que nao e um endereco exato nem local+*@dominio");
            }
        }
        return s;
    }

    public int tamanho() {
        return permitidos.size();
    }

    /** O endereco esta na lista (exato, sem diferenca de caixa) ou e uma variacao com marcador de um item {@code local+*@dominio}. */
    public static boolean permitido(Set<String> lista, String endereco) {
        if (endereco == null) {
            return false;
        }
        String e = normalizar(endereco);
        if (!EXATO.matcher(e).matches()) {
            return false;
        }
        for (String item : lista) {
            String i = normalizar(item);
            if (i.equals(e)) {
                return true;
            }
            Matcher m = COM_MARCADOR.matcher(i);
            if (m.matches()) {
                String local = m.group(1);
                String dominio = m.group(2);
                if (e.equals(local + "@" + dominio) || (e.startsWith(local + "+") && e.endsWith("@" + dominio)
                        && e.length() > local.length() + 2 + dominio.length())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Delivery deliver(Message m) {
        if (!permitido(permitidos, m.recipient())) {
            LOG.warn("evento=mail.destinatario_fora_da_lista desfecho=RECUSADO correlacao={}", m.correlation());
            return new Delivery(Result.RECUSADO, null, null);
        }
        return transporte.deliver(m);
    }

    private static String normalizar(String e) {
        return e.trim().toLowerCase(Locale.ROOT);
    }
}
