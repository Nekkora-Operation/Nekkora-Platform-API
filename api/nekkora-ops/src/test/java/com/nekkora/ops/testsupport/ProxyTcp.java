package com.nekkora.ops.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Proxy TCP de teste entre a CLI e o PostgreSQL (o TLS passa inteiro; o proxy nao le nada). Simula as perdas do
 * contrato 7: {@link #engolirRespostas()} descarta o que o servidor envia (o COMMIT chega e e confirmado, a resposta
 * nao volta); {@link #cortarCliente()} fecha so o lado do cliente e MANTEM o do servidor aberto (a sessao do servidor
 * continua viva, em transacao, segurando os locks); {@link #recusarNovas()} derruba o acesso ao banco.
 */
public final class ProxyTcp implements AutoCloseable {

    private final String destino;
    private final int portaDestino;
    private final ServerSocket servidor;
    private final List<Socket[]> pares = new CopyOnWriteArrayList<>();
    private volatile boolean engolir;
    private volatile boolean clienteCortado;

    public ProxyTcp(String destino, int portaDestino) throws IOException {
        this.destino = destino;
        this.portaDestino = portaDestino;
        this.servidor = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::aceitar, "proxy-aceite");
        t.setDaemon(true);
        t.start();
    }

    public int porta() {
        return servidor.getLocalPort();
    }

    public String host() {
        return "127.0.0.1";
    }

    public void engolirRespostas() {
        engolir = true;
    }

    public void cortarCliente() {
        clienteCortado = true;
        for (Socket[] p : pares) {
            fechar(p[0]);
        }
    }

    public void recusarNovas() {
        fechar(servidor);
    }

    @Override
    public void close() {
        fechar(servidor);
        for (Socket[] p : pares) {
            fechar(p[0]);
            fechar(p[1]);
        }
    }

    private void aceitar() {
        while (!servidor.isClosed()) {
            try {
                Socket cliente = servidor.accept();
                Socket banco = new Socket(destino, portaDestino);
                pares.add(new Socket[] {cliente, banco});
                bombear(cliente.getInputStream(), banco.getOutputStream(), false, cliente, banco);
                bombear(banco.getInputStream(), cliente.getOutputStream(), true, cliente, banco);
            } catch (IOException e) {
                return;
            }
        }
    }

    private void bombear(InputStream in, OutputStream out, boolean doServidor, Socket cliente, Socket banco) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[16384];
            try {
                int n;
                while ((n = in.read(b)) >= 0) {
                    if (doServidor && engolir) {
                        continue;
                    }
                    out.write(b, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                // fim de um dos lados
            } finally {
                fechar(cliente);
                if (!clienteCortado) {
                    fechar(banco);
                }
            }
        }, "proxy-bomba");
        t.setDaemon(true);
        t.start();
    }

    private static void fechar(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            // ja fechado
        }
    }

    /** Conexao que executa {@code antes} imediatamente antes do COMMIT (o gancho da perda simulada). */
    public static Connection comGanchoNoCommit(Connection real, Runnable antes) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (px, m, args) -> {
            if ("commit".equals(m.getName())) {
                antes.run();
            }
            try {
                return m.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
