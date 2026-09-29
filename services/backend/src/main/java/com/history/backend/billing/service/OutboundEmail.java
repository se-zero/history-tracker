package com.history.backend.billing.service;

public record OutboundEmail(String to, String subject, String text) {
}
