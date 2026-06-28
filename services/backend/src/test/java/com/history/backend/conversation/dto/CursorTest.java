package com.history.backend.conversation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Cursor: (timestamp, id) 키셋 커서 인코딩/디코딩")
class CursorTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-05-23T01:00:00.123456Z");
    private static final UUID ID = UUID.fromString("7dbd88a3-807d-4b6d-8fef-462de48f6c6c");

    @Test
    @DisplayName("인코딩 후 디코딩하면 원래 timestamp와 id 복원")
    void encodeThenDecodeRoundTrips() {
        String encoded = new Cursor(TIMESTAMP, ID).encode();

        Cursor decoded = Cursor.decode(encoded);

        assertThat(decoded.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(decoded.id()).isEqualTo(ID);
    }

    @Test
    @DisplayName("나노초 정밀도 timestamp도 복원")
    void preservesNanoPrecision() {
        Instant withNanos = Instant.parse("2026-05-23T01:00:00.123456789Z");

        Cursor decoded = Cursor.decode(new Cursor(withNanos, ID).encode());

        assertThat(decoded.timestamp()).isEqualTo(withNanos);
    }

    @Test
    @DisplayName("base64로 디코딩되지 않는 커서는 BadRequestException")
    void decodeRejectsNonBase64Cursor() {
        assertThatThrownBy(() -> Cursor.decode("@@@not-base64@@@"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("구분자가 없는 커서는 BadRequestException")
    void decodeRejectsCursorWithoutSeparator() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("missing-separator".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> Cursor.decode(encoded))
                .isInstanceOf(BadRequestException.class);
    }
}
