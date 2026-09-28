package com.history.backend.oauth.service;

import java.util.Set;

public record CimdClientMetadata(String clientId, String clientName, String clientUri, Set<String> redirectUris) {
}
