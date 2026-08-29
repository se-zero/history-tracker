package com.history.backend.common.error;

public class PlanLimitExceededException extends RuntimeException {

    public PlanLimitExceededException(String message) {
        super(message);
    }
}
