package com.nekkora.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c (parte 2) - o EFETIVO de uma automacao: as dependencias do manifesto do pacote
 * publicado mais os acrescimos declarados na Platform. O manifesto nunca e enfraquecido: a mesma biblioteca declarada de
 * outro jeito e conflito, dito, e o que vale continua sendo o do manifesto.
 */
class RuntimeRequirementsEffectiveTest {

    @Test
    void manifestoPrimeiroEAcrescimosDepoisSemRepetir() {
        RuntimeRequirementsService.Effective e = RuntimeRequirementsService.effective(List.of("requests==2.32.3", "nekkora-rpa==0.3.0"),
                List.of("pandas[excel]==2.2.2", "Requests==2.32.3", "idna==3.7"));
        assertThat(e.dependencies()).containsExactly("requests==2.32.3", "nekkora-rpa==0.3.0", "pandas[excel]==2.2.2", "idna==3.7");
        assertThat(e.conflicts()).isEmpty();
        assertThat(e.resolved()).isTrue();
    }

    @Test
    void mesmaBibliotecaComVersaoOuExtrasDiferentesEConflitoEOManifestoPrevalece() {
        RuntimeRequirementsService.Effective e = RuntimeRequirementsService.effective(List.of("requests==2.32.3", "pandas==2.2.2", "my_pkg==1.0"),
                List.of("requests==2.31.0", "pandas[excel]==2.2.2", "My.Pkg==1.0", "idna==3.7"));
        assertThat(e.dependencies()).as("nada do manifesto e trocado").containsExactly("requests==2.32.3", "pandas==2.2.2", "my_pkg==1.0", "idna==3.7");
        assertThat(e.resolved()).isFalse();
        assertThat(e.conflicts()).containsExactly(new RuntimeRequirementsService.Conflict("requests", "requests==2.32.3", "requests==2.31.0"),
                new RuntimeRequirementsService.Conflict("pandas", "pandas==2.2.2", "pandas[excel]==2.2.2"));
    }

    @Test
    void semManifestoOuSemDeclaracao() {
        assertThat(RuntimeRequirementsService.effective(List.of(), List.of("idna==3.7")).dependencies()).containsExactly("idna==3.7");
        assertThat(RuntimeRequirementsService.effective(List.of("idna==3.7"), List.of()).dependencies()).containsExactly("idna==3.7");
        assertThat(RuntimeRequirementsService.effective(List.of(), List.of()).resolved()).isTrue();
    }
}
