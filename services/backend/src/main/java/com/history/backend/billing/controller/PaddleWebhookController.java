package com.history.backend.billing.controller;

import com.history.backend.billing.service.PaddleWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/billing/webhook")
public class PaddleWebhookController {

    private final PaddleWebhookService paddleWebhookService;

    @PostMapping("/paddle")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "Paddle-Signature", required = false) String signature,
            @RequestBody String body
    ) {
        paddleWebhookService.handle(signature, body);
        return ResponseEntity.ok().build();
    }
}
