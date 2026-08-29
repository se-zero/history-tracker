package com.history.backend.slack.service;

// Slack Events API 응답 — url_verification만 challenge non-null, 나머지는 null
public record SlackEventAck(int status, String challenge) {
}
