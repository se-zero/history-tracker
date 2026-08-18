package com.history.backend.integration.dto;

import com.history.backend.integration.domain.SelectionStep;

// 사용자가 골라야 하는 단계 하나 — 프론트는 이 선언을 그대로 렌더링한다(단계 수·이름을 하드코딩하지 않는다).
public record SelectionStepResponse(String key, String title, boolean optional) {

    public static SelectionStepResponse from(SelectionStep step) {
        return new SelectionStepResponse(step.key(), step.title(), step.optional());
    }
}
