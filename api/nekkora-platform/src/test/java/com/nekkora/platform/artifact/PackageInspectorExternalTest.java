package com.nekkora.platform.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pacote Python EXTERNO (AGENT-PYTHON-001, regra nekkora-agent/external-python-fingerprint/1). O vetor sintetico e o MESMO
 * conteudo do vetor da regra /1 (main.py com CRLF e um binario) com o descritor externo; a impressao foi calculada pela
 * implementacao Python independente do gerador (tools/external-package/nekkora_empacotar.py, Nekkora-Agent) e e conferida
 * tambem pelo Agent. Nada e executado na inspecao.
 */
class PackageInspectorExternalTest {

    static final String MAIN = "sha256:dbccf933401a15cfe345e90891e20b5a05a495f552e4577b80c052276604e24f";
    static final String BIN = "sha256:d2ad9277baaee14856d20ec2b21f87a0cb8a7f86c6ef090fd5a082b1e85135ac";
    static final String DESCRIPTOR_HASH = "sha256:87499b11c2791c5f27a812004e3a2518db60e72cd07424e40d7ed3db9004f5e9";
    static final String FINGERPRINT = "e71ca1e0aedd2842d09809d5634134f3f23fb4d2ca0c5d90c370bb15474595f9";

    static String descriptor(String entry, String deps, String files) {
        return "{\"schema\": \"nekkora-agent/external-python-package\", \"version\": \"1.0.0\", \"name\": \"vetor\", \"packageVersion\": \"1.0.0\", "
                + "\"entry\": \"" + entry + "\", \"python\": \">=3.10,<3.14\", \"dependencies\": " + deps + ", \"files\": " + files + "}\r\n";
    }

    static final String FILES = "{\"main.py\": \"" + MAIN + "\", \"util/x.bin\": \"" + BIN + "\"}";

    static Map<String, byte[]> vector() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("nekkora-external.json", descriptor("main.py", "[]", FILES).getBytes(StandardCharsets.UTF_8));
        m.put("main.py", "print('ola')\r\n".getBytes(StandardCharsets.UTF_8));
        m.put("util/x.bin", new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        return m;
    }

    @Test
    void vetorSinteticoReproduzidoComRegraPropriaETipoExterno() throws Exception {
        Map<String, byte[]> v = vector();
        assertThat(PackageInspector.fileHash(v.get("nekkora-external.json"))).isEqualTo(DESCRIPTOR_HASH);
        PackageInspector.Inspection in = PackageInspector.inspect(PackageInspectorTest.zip(v));
        assertThat(in.packageKind()).isEqualTo(2);
        assertThat(in.rule()).isEqualTo("nekkora-agent/external-python-fingerprint/1");
        assertThat(in.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(in.entry()).isEqualTo("main.py");
        assertThat(in.pythonRequirement()).isEqualTo(">=3.10,<3.14");
        assertThat(in.dependencies()).isEmpty();
        assertThat(in.framework()).isNull();
        assertThat(in.executionMode()).isNull();
        // a impressao NAO e o sha256 dos bytes do ZIP, e outro ZIP do mesmo conteudo muda o sha256 e mantem a impressao
        assertThat(in.fingerprint()).isNotEqualTo(in.sha256());
        Map<String, byte[]> withExtra = vector();
        withExtra.put("LEIA-ME.txt", "fora da lista".getBytes(StandardCharsets.UTF_8));
        PackageInspector.Inspection other = PackageInspector.inspect(PackageInspectorTest.zip(withExtra));
        assertThat(other.sha256()).isNotEqualTo(in.sha256());
        assertThat(other.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(other.ignoredFiles()).isEqualTo(1);
        // a MESMA lista sob a regra /1 da outra impressao: um pacote nao passa por outro tipo
        assertThat(PackageInspector.fingerprint(v.get("nekkora-external.json"), Map.of("main.py", MAIN, "util/x.bin", BIN))).isNotEqualTo(FINGERPRINT);
    }

    @Test
    void dependenciasComVersaoExataSaoGuardadas() throws Exception {
        Map<String, byte[]> v = vector();
        v.put("nekkora-external.json", descriptor("main.py", "[\"requests==2.32.3\", \"openpyxl[lxml]==3.1.5\"]", FILES).getBytes(StandardCharsets.UTF_8));
        assertThat(PackageInspector.inspect(PackageInspectorTest.zip(v)).dependencies()).containsExactly("requests==2.32.3", "openpyxl[lxml]==3.1.5");
    }

    @Test
    void recusasSemExecutarNada() throws Exception {
        assertRejected(with("main.py", "print('alterado')\r\n".getBytes(StandardCharsets.UTF_8)), "hash_divergente");
        assertRejected(withDescriptor(descriptor("../fora.py", "[]", FILES)), "entrada_fora_da_raiz");
        assertRejected(withDescriptor(descriptor("C:/x.py", "[]", FILES)), "entrada_fora_da_raiz");
        assertRejected(withDescriptor(descriptor("outro.py", "[]", FILES)), "entrada_ausente");
        assertRejected(withDescriptor(descriptor("main.py", "[\"requests>=2\"]", FILES)), "dependencia_sem_versao_exata");
        assertRejected(withDescriptor(descriptor("main.py", "[\"requests\"]", FILES)), "dependencia_sem_versao_exata");
        assertRejected(withDescriptor(descriptor("main.py", "[\"a==1.0\", \"A==2.0\"]", FILES)), "dependencia_repetida");
        assertRejected(withDescriptor(descriptor("main.py", "[]", "{\"main.py\": \"" + MAIN + "\", \"../x.bin\": \"" + BIN + "\"}")), "caminho_inseguro");
        Map<String, byte[]> venv = withDescriptor(descriptor("main.py", "[]", "{\"main.py\": \"" + MAIN + "\", \"venv/pyvenv.cfg\": \"" + BIN + "\"}"));
        venv.put("venv/pyvenv.cfg", new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        assertRejected(venv, "arquivo_proibido");
        Map<String, byte[]> env = withDescriptor(descriptor("main.py", "[]", "{\"main.py\": \"" + MAIN + "\", \"conf/.env\": \"" + BIN + "\"}"));
        env.put("conf/.env", new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        assertRejected(env, "arquivo_proibido");
        Map<String, byte[]> both = vector();
        both.put("manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertRejected(both, "descritor_ambiguo");
        assertRejected(withDescriptor("{\"schema\": \"x\", \"schema\": \"y\"}"), "manifesto_malformado");
        assertRejected(withDescriptor(descriptor("main.py", "[]", FILES).replace(">=3.10,<3.14", "qualquer")), "python_invalido");
    }

    static Map<String, byte[]> with(String name, byte[] data) {
        Map<String, byte[]> v = vector();
        v.put(name, data);
        return v;
    }

    static Map<String, byte[]> withDescriptor(String text) {
        return with("nekkora-external.json", text.getBytes(StandardCharsets.UTF_8));
    }

    static void assertRejected(Map<String, byte[]> files, String reason) {
        assertThatThrownBy(() -> PackageInspector.inspect(PackageInspectorTest.zip(files))).isInstanceOf(PackageInspector.Rejected.class)
                .satisfies(e -> assertThat(((PackageInspector.Rejected) e).reason()).isEqualTo(reason));
    }
}
