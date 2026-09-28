package com.history.backend.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Resend 발신 설정. api-key가 비면 안내 메일을 보내지 않는다 — 결제·해지 자체는 메일이 없어도 동작한다.
@ConfigurationProperties(prefix = "resend")
public record ResendProperties(
        String apiKey,
        String from,
        String replyTo
) {
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
