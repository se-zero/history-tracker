package com.history.backend.oauth.repository;

import java.time.Instant;

public record OAuthGrantRow(String registeredClientId, Instant grantedAt, Instant lastUsedAt) {
}
