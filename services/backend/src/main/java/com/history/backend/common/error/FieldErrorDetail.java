package com.history.backend.common.error;

public record FieldErrorDetail(
        String field,
        String message
) {
}
