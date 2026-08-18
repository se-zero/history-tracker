package com.history.backend.conversation.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;

// (timestamp, id) 키셋 페이지네이션 커서 — 정렬키와 PK tiebreak를 불투명 문자열로 인코딩
public record Cursor(Instant timestamp, UUID id) {

    public String encode() {
        String raw = timestamp.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator < 0) {
                throw new BadRequestException("Invalid cursor.");
            }
            return new Cursor(
                    Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1))
            );
        } catch (BadRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BadRequestException("Invalid cursor.");
        }
    }
}
