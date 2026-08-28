package com.history.backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("GlobalExceptionHandler: 예외 → HTTP 상태 매핑")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("PlanLimitExceededException → 403 Forbidden, 예외 메시지 포함")
    void handlePlanLimitExceededReturnsForbiddenWithMessage() {
        PlanLimitExceededException exception = new PlanLimitExceededException("Free plan query limit exceeded.");

        ResponseEntity<ErrorResponse> response = handler.handlePlanLimitExceeded(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Free plan query limit exceeded.");
    }
}
