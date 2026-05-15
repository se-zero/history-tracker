package com.history.backend.common.error;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<FieldErrorDetail> fields
) {

    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(Instant.now(), status, error, message, List.of());
    }

    public static ErrorResponse of(int status, String error, String message, List<FieldErrorDetail> fields) {
        return new ErrorResponse(Instant.now(), status, error, message, fields);
    }
}
