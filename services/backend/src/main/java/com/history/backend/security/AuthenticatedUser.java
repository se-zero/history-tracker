package com.history.backend.security;

import java.util.UUID;

public record AuthenticatedUser(
        UUID id
) {
}
