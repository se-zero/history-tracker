package com.history.backend.integration.controller;

import com.history.backend.integration.dto.AtlassianConsentRequest;
import com.history.backend.integration.service.AtlassianAppTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InternalAtlassianConsentController {

    private final AtlassianAppTokenService atlassianAppTokenService;

    @PostMapping("/api/v1/internal/atlassian/consent")
    public ResponseEntity<Void> storeConsent(@RequestBody AtlassianConsentRequest request) {
        atlassianAppTokenService.storeConsent(request.code());
        return ResponseEntity.noContent().build();
    }
}
