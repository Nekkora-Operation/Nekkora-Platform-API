package com.nekkora.platform.mail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * P1c: o transporte de homologacao REAL numa JVM Linux (imagem da Platform de teste, usuario nekkora), gravando no VOLUME
 * Docker da caixa, para conferir dono e permissoes no sistema de arquivos do ensaio (desenho P1a, sec. 4.2 e 4.3).
 * Executado por testes/p1c_caixa.sh com o PropertiesLauncher do jar da Platform e as classes de teste montadas.
 *
 * <pre>
 *   mensagens &lt;caixa&gt; &lt;ambiente&gt;   uma tentativa (attempt_id) + a mesma de novo (repeticao) + 16 sem attempt_id em paralelo
 *   aviso &lt;caixa&gt; &lt;ambiente&gt;       uma tentativa nova; imprime o desfecho (ou a falha)
 * </pre>
 * Saida: so contagens, desfechos e classes de erro - nunca token, endereco ou conteudo.
 */
public final class CaixaLinuxSonda {

    private CaixaLinuxSonda() {
    }

    public static void main(String[] a) throws Exception {
        CaixaHomologacaoMailSender t = new CaixaHomologacaoMailSender(Path.of(a[1]), a[2]);
        switch (a[0]) {
            case "mensagens" -> {
                UUID tentativa = UUID.randomUUID();
                MailSender.Outcome o1 = t.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER, UUID.randomUUID(), "titular@p1c.test",
                        "tk-confirmar-" + UUID.randomUUID(), "tk-contestar-" + UUID.randomUUID(), tentativa);
                MailSender.Outcome o2 = t.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER, UUID.randomUUID(), "titular@p1c.test",
                        "tk-confirmar-" + UUID.randomUUID(), "tk-contestar-" + UUID.randomUUID(), tentativa);
                ExecutorService ex = Executors.newFixedThreadPool(8);
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    int id = i;
                    fs.add(ex.submit(() -> t.send(MailSender.Kind.PASSWORD_RECOVERY, UUID.randomUUID(), "c" + id + "@p1c.test", "tk-" + UUID.randomUUID())));
                }
                for (Future<?> f : fs) {
                    f.get();
                }
                ex.shutdown();
                System.out.println("tentativa=" + tentativa + " desfechos=" + o1 + "," + o2 + " outras=16");
            }
            case "aviso" -> {
                try {
                    MailSender.Outcome o = t.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN, UUID.randomUUID(), "admin@p1c.test", null,
                            "tk-contestar-" + UUID.randomUUID(), UUID.randomUUID());
                    System.out.println("desfecho=" + o);
                } catch (RuntimeException e) {
                    System.out.println("falha=" + e.getClass().getSimpleName() + ":" + e.getMessage());
                    System.exit(2);
                }
            }
            default -> throw new IllegalArgumentException("uso: mensagens|aviso <caixa> <ambiente>");
        }
    }
}
