package com.history.pipeline_worker.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizeFetchRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validRequest_passesValidationAndConvertsToRawFetchRequest() {
        NormalizeFetchRequest request = new NormalizeFetchRequest(
                "11111111-1111-1111-1111-111111111111",
                "Bearer token",
                "owner/repo",
                Map.of("branch", "main")
        );

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.toRawFetchRequest())
                .isEqualTo(new RawFetchRequest("Bearer token", "owner/repo", Map.of("branch", "main")));
    }

    @Test
    void invalidProjectId_failsValidation() {
        NormalizeFetchRequest request = new NormalizeFetchRequest(
                "not-a-uuid",
                "Bearer token",
                "owner/repo",
                Map.of()
        );

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("projectId"));
    }
}
