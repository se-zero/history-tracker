package com.history.pipeline_worker.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CollectTriggerRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validProjectId_passesValidationAndConvertsToUuid() {
        CollectTriggerRequest request = new CollectTriggerRequest("11111111-1111-1111-1111-111111111111");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.projectUuid())
                .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void invalidProjectId_failsValidation() {
        CollectTriggerRequest request = new CollectTriggerRequest("not-a-uuid");

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("projectId"));
    }
}
