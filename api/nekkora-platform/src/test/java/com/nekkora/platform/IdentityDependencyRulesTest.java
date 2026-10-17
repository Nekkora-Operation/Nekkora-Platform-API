package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Regras de dependencia da identidade (levantamento de modularidade, §4.1 e §4.2), verificadas sobre os FONTES de
 * {@code src/main/java}: imports e nomes qualificados no corpo. Sem biblioteca nova (ArchUnit fica para quando houver
 * necessidade que um teste de imports nao cubra).
 * <ol>
 * <li>R1: {@code platform.identity} nao referencia {@code platform.auth}, exceto {@code platform.auth.crypto};</li>
 * <li>R2: {@code platform.auth} fora de {@code crypto} nao referencia {@code platform.identity};</li>
 * <li>R3: {@code platform.auth.crypto} nao referencia nada de {@code platform} fora dele;</li>
 * <li>R4: {@code platform.identity} nao usa {@code OrgTransactions}: a porta de emissao ({@code OrgSessionIssuer}) e uma
 * interface da identidade, implementada do lado da organizacao ({@code session.LinkedSessionIssuer}) e ligada em
 * {@code config};</li>
 * <li>R5: {@code platform.identity} nao referencia {@code config} nem {@code web} (sem ciclos novos);</li>
 * <li>R6: servicos de fluxo da identidade ({@code *Service}) nao referenciam outro servico de fluxo;</li>
 * <li>R7: controllers que usam a identidade nao usam {@code JdbcClient};</li>
 * <li>R8: {@code platform.identity} nao referencia codigo de organizacao ({@code session}, {@code account}, {@code mfa},
 * {@code admin}, {@code access}, {@code emergency}): o unico caminho ate a organizacao e a porta (§4.2);</li>
 * <li>R9: esse codigo de organizacao nao referencia {@code platform.identity}: a ligacao da porta fica em {@code config}.</li>
 * </ol>
 * {@link #oVerificadorDetectaCadaRegra()} prova, com fontes sinteticos, que cada regra reprova o que deve.
 */
class IdentityDependencyRulesTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final String BASE = "com/nekkora/platform/";
    private static final Pattern REF = Pattern.compile("com\\.nekkora\\.platform\\.([a-z]+(?:\\.[a-z]+)*)");
    /** Arquivos da identidade permitidos a usar OrgTransactions (R4). Vazio: a porta e implementada fora da identidade. */
    private static final List<String> PORTAS_ORG = List.of();
    /** Pacotes de codigo de organizacao (R8 e R9). */
    private static final List<String> ORGANIZACAO = List.of("session", "account", "mfa", "admin", "access", "emergency");

    @Test
    void regrasDeDependenciaDaIdentidade() throws IOException {
        Map<String, String> fontes = new java.util.TreeMap<>();
        try (Stream<Path> s = Files.walk(MAIN.resolve(BASE))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                fontes.put(MAIN.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        assertThat(fontes.keySet()).as("o pacote da identidade existe e foi lido").anyMatch(k -> k.startsWith(BASE + "identity/"));
        assertThat(violacoes(fontes)).as("regras de dependencia da identidade").isEmpty();
    }

    @Test
    void oVerificadorDetectaCadaRegra() {
        String id = BASE + "identity/";
        Map<String, String> casos = Map.of(
                "R1", id + "X.java|import com.nekkora.platform.auth.AuthService;",
                "R2", BASE + "auth/X.java|import com.nekkora.platform.identity.SignupService;",
                "R3", BASE + "auth/crypto/X.java|import com.nekkora.platform.limits.Limits;",
                "R4", id + "X.java|import com.nekkora.core.db.OrgTransactions;",
                "R5", id + "X.java|class X { com.nekkora.platform.config.PlatformProperties p; }",
                "R6", id + "LoginService.java|class LoginService { SignupService s; }",
                "R7", BASE + "web/X.java|import com.nekkora.platform.identity.SignupService; import org.springframework.jdbc.core.simple.JdbcClient;",
                "R8", id + "X.java|import com.nekkora.platform.session.SessionRepository;",
                "R9", BASE + "session/X.java|import com.nekkora.platform.identity.OrgSessionIssuer;");
        for (Map.Entry<String, String> c : casos.entrySet()) {
            String[] partes = c.getValue().split("\\|", 2);
            Map<String, String> fontes = new java.util.HashMap<>(Map.of(partes[0], partes[1]));
            fontes.put(id + "SignupService.java", "class SignupService {}");
            assertThat(violacoes(fontes)).as("regra " + c.getKey()).anyMatch(v -> v.startsWith(c.getKey() + ":"));
        }
        assertThat(violacoes(Map.of(id + "Y.java", "import com.nekkora.platform.auth.crypto.IdentityHmac; import com.nekkora.platform.limits.Limits;")))
                .as("controle positivo: auth.crypto e limits sao permitidos a identidade").isEmpty();
    }

    static List<String> violacoes(Map<String, String> fontes) {
        List<String> v = new ArrayList<>();
        List<String> servicos = fontes.keySet().stream().filter(k -> k.startsWith(BASE + "identity/") && k.endsWith("Service.java"))
                .map(k -> k.substring(k.lastIndexOf('/') + 1, k.length() - ".java".length())).toList();
        for (Map.Entry<String, String> f : fontes.entrySet()) {
            String arquivo = f.getKey();
            String codigo = semComentarios(f.getValue());
            List<String> refs = new ArrayList<>();
            Matcher m = REF.matcher(codigo);
            while (m.find()) {
                refs.add(m.group(1));
            }
            boolean identity = arquivo.startsWith(BASE + "identity/");
            boolean crypto = arquivo.startsWith(BASE + "auth/crypto/");
            boolean auth = arquivo.startsWith(BASE + "auth/") && !crypto;
            boolean organizacao = ORGANIZACAO.stream().anyMatch(o -> arquivo.startsWith(BASE + o + "/"));
            for (String r : refs) {
                if (identity && (r.equals("auth") || r.startsWith("auth.")) && !(r.equals("auth.crypto") || r.startsWith("auth.crypto."))) {
                    v.add("R1: " + arquivo + " -> platform." + r);
                }
                if (auth && (r.equals("identity") || r.startsWith("identity."))) {
                    v.add("R2: " + arquivo + " -> platform." + r);
                }
                if (crypto && !(r.equals("auth.crypto") || r.startsWith("auth.crypto."))) {
                    v.add("R3: " + arquivo + " -> platform." + r);
                }
                if (identity && (r.startsWith("config") || r.startsWith("web"))) {
                    v.add("R5: " + arquivo + " -> platform." + r);
                }
                if (identity && ORGANIZACAO.stream().anyMatch(o -> r.equals(o) || r.startsWith(o + "."))) {
                    v.add("R8: " + arquivo + " -> platform." + r);
                }
                if (organizacao && (r.equals("identity") || r.startsWith("identity."))) {
                    v.add("R9: " + arquivo + " -> platform." + r);
                }
            }
            if (identity && codigo.contains("OrgTransactions") && PORTAS_ORG.stream().noneMatch(arquivo::endsWith)) {
                v.add("R4: " + arquivo + " usa OrgTransactions");
            }
            if (identity && arquivo.endsWith("Service.java")) {
                String proprio = arquivo.substring(arquivo.lastIndexOf('/') + 1, arquivo.length() - ".java".length());
                for (String outro : servicos) {
                    if (!outro.equals(proprio) && Pattern.compile("\\b" + outro + "\\b").matcher(codigo).find()) {
                        v.add("R6: " + arquivo + " -> " + outro);
                    }
                }
            }
            if (arquivo.startsWith(BASE + "web/") && refs.stream().anyMatch(r -> r.startsWith("identity")) && codigo.contains("JdbcClient")) {
                v.add("R7: " + arquivo + " usa JdbcClient");
            }
        }
        return v;
    }

    /** Remove comentarios de bloco e de linha (javadoc cita pacotes proibidos de proposito, como aqui). */
    private static String semComentarios(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
