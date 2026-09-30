package com.history.backend.oauth.dto;

import java.util.List;

public record ConsentPreviewResponse(
        String clientName,
        String clientUri,
        String redirectUri,
        String redirectHost,
        boolean loopback,
        List<String> scopes
) {
}
