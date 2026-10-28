package com.nekkora.agentgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * Vetor verificavel da regra {@code nekkora-agent/package-fingerprint/1} (contrato, sec. 6.4). A API nao recebe o pacote,
 * entao nao calcula a impressao em producao: so guarda e compara. Esta implementacao de REFERENCIA existe para que os dois
 * lados confiram o mesmo vetor. O valor esperado foi obtido da funcao do proprio Agent ({@code ExecutionPackage.fingerprint},
 * Nekkora-Agent 932e61c) e de uma reimplementacao independente; as tres dao o mesmo resultado.
 */
class PackageFingerprintVectorTest {

    static final String RULE = "nekkora-agent/package-fingerprint/1";

    /** sha256 de um arquivo: texto UTF-8 valido com CRLF trocado por LF; o resto, os bytes como estao. */
    static String fileHash(byte[] data) throws Exception {
        byte[] bytes = data;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data)).toString();
            bytes = text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException binary) {
            // binario: os bytes como estao
        }
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Texto canonico: regra, tipo, descritor com o hash dele, cada arquivo listado em ordem de caminho (bytes UTF-8). */
    static String canonical(String kind, String descriptor, byte[] descriptorBytes, Map<String, String> files) throws Exception {
        StringBuilder b = new StringBuilder(RULE).append('\n').append(kind).append('\n');
        b.append(descriptor).append('\t').append(fileHash(descriptorBytes)).append('\n');
        new TreeMap<>(files).forEach((k, v) -> b.append(k).append('\t').append(v).append('\n'));
        return b.toString();
    }

    static String fingerprint(String canonical) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    static final byte[] MAIN = "print('ola')\r\n".getBytes(StandardCharsets.UTF_8);
    static final byte[] DADOS = {(byte) 0xff, (byte) 0xfe, 0x00, 0x01};
    static final String MAIN_SHA = "sha256:dbccf933401a15cfe345e90891e20b5a05a495f552e4577b80c052276604e24f";
    static final String DADOS_SHA = "sha256:d2ad9277baaee14856d20ec2b21f87a0cb8a7f86c6ef090fd5a082b1e85135ac";
    static final byte[] MANIFEST = ("{\"schema\": \"nekkora-studio/codegen-manifest\", \"version\": \"1.4.0\", \"files\": {\"main.py\": \"" + MAIN_SHA
            + "\", \"dados.bin\": \"" + DADOS_SHA + "\"}}\r\n").getBytes(StandardCharsets.UTF_8);

    @Test
    void vetorDaRegraComCrlfEBinario() throws Exception {
        assertEquals(MAIN_SHA, fileHash(MAIN));
        assertEquals(DADOS_SHA, fileHash(DADOS));
        assertEquals("sha256:779965c156b7c0f0921285933008850b19d592ce990ba7dffb42b8dd22eae868", fileHash(MANIFEST));
        String c = canonical("STUDIO_PYTHON", "manifest.json", MANIFEST, Map.of("main.py", MAIN_SHA, "dados.bin", DADOS_SHA));
        assertEquals("""
                nekkora-agent/package-fingerprint/1
                STUDIO_PYTHON
                manifest.json\tsha256:779965c156b7c0f0921285933008850b19d592ce990ba7dffb42b8dd22eae868
                dados.bin\tsha256:d2ad9277baaee14856d20ec2b21f87a0cb8a7f86c6ef090fd5a082b1e85135ac
                main.py\tsha256:dbccf933401a15cfe345e90891e20b5a05a495f552e4577b80c052276604e24f
                """, c);
        assertEquals("cada6a642bc7001a314b513a9dd56a312b4ff7612149c0ee29d523e641dff828", fingerprint(c));
    }

    @Test
    void crlfNoTextoNaoMudaAImpressaoMasUmByteMudaEOsBytesDoArquivoSaoOutraMedida() throws Exception {
        byte[] lf = "print('ola')\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(fileHash(MAIN), fileHash(lf));
        assertNotEquals(fileHash(MAIN), fileHash("print('olá')\n".getBytes(StandardCharsets.UTF_8)));
        // o sha256 dos bytes do manifesto (o que um artefato distribuido mediria) nao e a impressao do pacote
        String bytesSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MANIFEST));
        assertNotEquals(bytesSha, "779965c156b7c0f0921285933008850b19d592ce990ba7dffb42b8dd22eae868");
        assertNotEquals(bytesSha, "cada6a642bc7001a314b513a9dd56a312b4ff7612149c0ee29d523e641dff828");
    }
}
