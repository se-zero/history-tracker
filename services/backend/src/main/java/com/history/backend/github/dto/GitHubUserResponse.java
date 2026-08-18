package com.history.backend.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubUserResponse(
        Long id,
        String login,
        String name,
        String email,
        @JsonProperty("avatar_url")
        String avatarUrl
) {

    public String displayName() {
        return name != null && !name.isBlank() ? name : login;
    }

    public String emailOrFallback() {
        return email != null && !email.isBlank() ? email : id + "+" + login + "@users.noreply.github.com";
    }
}
