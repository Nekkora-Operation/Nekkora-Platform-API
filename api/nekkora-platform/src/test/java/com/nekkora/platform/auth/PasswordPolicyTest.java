package com.nekkora.platform.auth;

import static com.nekkora.platform.auth.PasswordPolicy.Requirement.DIGIT;
import static com.nekkora.platform.auth.PasswordPolicy.Requirement.LENGTH;
import static com.nekkora.platform.auth.PasswordPolicy.Requirement.LOWERCASE;
import static com.nekkora.platform.auth.PasswordPolicy.Requirement.SYMBOL;
import static com.nekkora.platform.auth.PasswordPolicy.Requirement.UPPERCASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nekkora.core.web.ApiException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** PLATFORM-SENHA-001: cada requisito ausente, a senha valida, os limites e os casos Unicode. */
class PasswordPolicyTest {

    private static Set<PasswordPolicy.Requirement> missing(String s) {
        return PasswordPolicy.missing(s.toCharArray());
    }

    private static void refused(String s) {
        assertThatThrownBy(() -> PasswordPolicy.enforce(s.toCharArray()))
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining(s);
    }

    @Test
    void validPasswordMeetsEveryRequirement() {
        assertThat(missing("Nekkora#2026ok")).isEmpty();
        assertThatCode(() -> PasswordPolicy.enforce("Nekkora#2026ok".toCharArray())).doesNotThrowAnyException();
    }

    @Test
    void eachMissingRequirementIsReportedAlone() {
        assertThat(missing("Nk#2")).containsExactly(LENGTH);
        assertThat(missing("Nekkora#2026ok-mais-longa")).containsExactly(LENGTH);
        assertThat(missing("nekkora#2026ok")).containsExactly(UPPERCASE);
        assertThat(missing("NEKKORA#2026OK")).containsExactly(LOWERCASE);
        assertThat(missing("Nekkora#Teste!")).containsExactly(DIGIT);
        assertThat(missing("Nekkora2026ok7")).containsExactly(SYMBOL);
        for (String s : new String[] {"Nk#2", "Nekkora#2026ok-mais-longa", "nekkora#2026ok", "NEKKORA#2026OK", "Nekkora#Teste!", "Nekkora2026ok7"}) {
            refused(s);
        }
    }

    @Test
    void nothingMeetsNothing() {
        assertThat(PasswordPolicy.missing(null)).containsExactlyInAnyOrder(LENGTH, UPPERCASE, LOWERCASE, DIGIT, SYMBOL);
        assertThat(missing("")).containsExactlyInAnyOrder(LENGTH, UPPERCASE, LOWERCASE, DIGIT, SYMBOL);
        assertThatThrownBy(() -> PasswordPolicy.enforce(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void boundsAreInclusiveAndCountCodePoints() {
        assertThat(missing("Ab1!Ab1!Ab1")).containsExactly(LENGTH);        // 11
        assertThat(missing("Ab1!Ab1!Ab1!")).isEmpty();                     // 12
        assertThat(missing("Ab1!Ab1!Ab1!Ab1!Ab1!")).isEmpty();             // 20
        assertThat(missing("Ab1!Ab1!Ab1!Ab1!Ab1!A")).containsExactly(LENGTH); // 21
        assertThat(missing("Ab1!" + "😀".repeat(16))).isEmpty();           // 20 pontos de codigo, 36 unidades UTF-16
    }

    @Test
    void spacesAreKeptButAreNotSymbols() {
        assertThat(missing("Nekkora 2026 ok")).containsExactly(SYMBOL);
        assertThat(missing(" Nekkora#2026 ")).isEmpty();
        assertThat(missing("  Ab1!Ab1!Ab1!    ")).isEmpty(); // 18: os espacos das pontas contam (nada e aparado)
        assertThat(missing("Ab1!Ab1!Ab1!         ")).containsExactly(LENGTH); // 21 com os espacos: nao e cortada
    }

    @Test
    void unicodeCategoriesDecide() {
        assertThat(missing("Ação#2026ébom")).isEmpty();      // letras acentuadas valem
        assertThat(missing("ação#2026ébom")).containsExactly(UPPERCASE);
        assertThat(missing("Nekkora€2026ok")).isEmpty();     // simbolo de moeda
        assertThat(missing("Nekkora😀2026ok")).isEmpty();    // emoji e simbolo
        assertThat(missing("Nekkora_2026ok")).isEmpty();     // sublinhado e pontuacao
        assertThat(missing("密码密码Ab1!密码密码")).isEmpty(); // letras sem caixa nao contam, mas nao atrapalham
    }
}
