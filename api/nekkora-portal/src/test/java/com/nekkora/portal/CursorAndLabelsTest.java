package com.nekkora.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

class CursorAndLabelsTest {

    @Test
    void cursorRoundTripAndRejectsMalformed() {
        Cursor c = new Cursor(OffsetDateTime.parse("2026-09-26T12:34:56.123456Z"), UUID.randomUUID());
        assertThat(Cursor.decode(c.encode())).isEqualTo(c);
        assertThatThrownBy(() -> Cursor.decode("eyJjIjoiMjAyNi0wOS0yNiIsImkiOiJ4In0")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursor.decode("%%%")).isInstanceOf(ApiException.class);
    }

    @Test
    void allowlistedLabelsAndUnknownCodeIsInternalError() {
        assertThat(Labels.status(5)).isEqualTo("failed");
        assertThat(Labels.reason(6)).isEqualTo("queue_deadline_exceeded");
        assertThat(Labels.event(4)).isEqualTo("process_start_failed");
        assertThat(Labels.statusCodeOf("running")).isEqualTo((short) 3);
        assertThatThrownBy(() -> Labels.status(9)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.apiError()).isEqualTo(ApiError.INTERNAL_ERROR));
        assertThatThrownBy(() -> Labels.statusCodeOf("RUNNING")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.apiError()).isEqualTo(ApiError.INVALID_REQUEST));
    }
}
