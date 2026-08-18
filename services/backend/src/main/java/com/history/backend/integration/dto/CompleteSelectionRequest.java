package com.history.backend.integration.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

/**
 * 선택 확정 요청 — 단계 key → 고른 값.
 *
 * <p>필수 단계 누락 검증은 provider의 단계 선언을 아는 서비스가 한다(여기서는 형태만 본다).</p>
 */
public record CompleteSelectionRequest(
        @NotNull Map<String, SelectionValueRequest> selections
) {

    public record SelectionValueRequest(String value, String label) {
    }
}
