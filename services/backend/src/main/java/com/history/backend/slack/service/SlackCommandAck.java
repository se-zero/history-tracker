package com.history.backend.slack.service;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SlackCommandAck(
        @JsonProperty("response_type") String responseType,
        String text
) {
}
