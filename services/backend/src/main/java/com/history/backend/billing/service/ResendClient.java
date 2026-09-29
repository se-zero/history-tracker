package com.history.backend.billing.service;

import java.util.List;
import java.util.Map;

import com.history.backend.billing.ResendProperties;
import com.history.backend.common.error.BadGatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// 안내 메일 발송. 받는 주소와 본문은 로그에 남기지 않는다.
@Slf4j
@Component
public class ResendClient {

    private final ResendProperties properties;
    private final RestClient restClient;

    public ResendClient(
            ResendProperties properties,
            @Qualifier("resendRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public void send(OutboundEmail email) {
        if (!properties.isConfigured()) {
            throw new BadGatewayException("Resend API key is not configured.");
        }
        try {
            restClient.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", properties.from(),
                            "to", List.of(email.to()),
                            "reply_to", properties.replyTo(),
                            "subject", email.subject(),
                            "text", email.text()
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            log.warn("Resend email request failed. status={}", exception.getStatusCode().value());
            throw new BadGatewayException("Resend email request failed.");
        } catch (RestClientException exception) {
            log.warn("Resend email request failed. errorType={}", exception.getClass().getSimpleName());
            throw new BadGatewayException("Resend email request failed.");
        }
    }
}
