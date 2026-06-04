package com.history.backend.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class UserPurgePropertiesTest {

    @Test
    void rejectsInvalidBatchSize() {
        assertThatThrownBy(() -> new UserPurgeProperties(true, Duration.ofDays(30), "0 0 3 * * *", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user-lifecycle.purge.batch-size must be positive.");
    }

    @Test
    void rejectsInvalidGracePeriod() {
        assertThatThrownBy(() -> new UserPurgeProperties(true, Duration.ZERO, "0 0 3 * * *", 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user-lifecycle.purge.grace-period must be positive.");
    }
}
