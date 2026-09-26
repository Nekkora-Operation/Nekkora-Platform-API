package com.nekkora.core.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP do cliente (contrato 8.6, §2.1 item 5): vem EXCLUSIVAMENTE do proxy confiavel. {@code X-Forwarded-For}
 * so e aceito quando a conexao TCP chega de um endereco da lista de proxies; o proxy SOBRESCREVE o cabecalho
 * com o endereco de quem se conectou a ele, entao ha um unico valor. Qualquer outra situacao usa o endereco
 * TCP e ignora o cabecalho. O resolvedor de cabecalhos encaminhados do Spring fica desligado
 * ({@code server.forward-headers-strategy=none}).
 */
public final class ClientIpResolver {

    private final List<Cidr> proxies;

    public ClientIpResolver(List<String> trustedProxies) {
        this.proxies = trustedProxies.stream().map(Cidr::parse).toList();
    }

    public String resolver(HttpServletRequest req) {
        String tcp = req.getRemoteAddr();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff == null || !trusted(tcp)) {
            return tcp;
        }
        String value = xff.trim();
        if (value.isEmpty() || value.contains(",") || !ipLiteral(value)) {
            return tcp;
        }
        return value;
    }

    private boolean trusted(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            return proxies.stream().anyMatch(c -> c.includes(a));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean ipLiteral(String s) {
        // so literais IPv4/IPv6 (sem resolucao de nome)
        return s.matches("^[0-9.]{7,15}$") || s.matches("^[0-9A-Fa-f:.]{2,45}$");
    }

    /** IP truncado para log (/24 IPv4, /48 IPv6). */
    public static String truncated(String ip) {
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            int keep = b.length == 4 ? 3 : 6;
            for (int i = keep; i < b.length; i++) {
                b[i] = 0;
            }
            return InetAddress.getByAddress(b).getHostAddress() + (b.length == 4 ? "/24" : "/48");
        } catch (UnknownHostException e) {
            return "invalido";
        }
    }

    record Cidr(byte[] network, int prefixLength) {
        static Cidr parse(String s) {
            try {
                String[] p = s.trim().split("/");
                byte[] network = InetAddress.getByName(p[0]).getAddress();
                int prefixLength = p.length > 1 ? Integer.parseInt(p[1]) : network.length * 8;
                return new Cidr(network, prefixLength);
            } catch (UnknownHostException | RuntimeException e) {
                throw new IllegalArgumentException("proxy confiavel invalido na configuracao");
            }
        }

        boolean includes(InetAddress a) {
            byte[] b = a.getAddress();
            if (b.length != network.length) {
                return false;
            }
            int bits = prefixLength;
            for (int i = 0; i < b.length && bits > 0; i++, bits -= 8) {
                int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                if ((b[i] & mask) != (network[i] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
