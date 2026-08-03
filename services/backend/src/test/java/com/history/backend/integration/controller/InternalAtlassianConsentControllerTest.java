package com.history.backend.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.history.backend.integration.dto.AtlassianConsentRequest;
import com.history.backend.integration.service.AtlassianAppTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("InternalAtlassianConsentController: 내부 Atlassian 앱 동의 API")
class InternalAtlassianConsentControllerTest {

    @Test
    @DisplayName("유효한 동의 code 요청 → service에 위임하고 204 No Content 반환")
    void storeConsentReturnsNoContent() {
        AtlassianAppTokenService atlassianAppTokenService = mock(AtlassianAppTokenService.class);
        InternalAtlassianConsentController controller = new InternalAtlassianConsentController(atlassianAppTokenService);

        ResponseEntity<Void> response = controller.storeConsent(new AtlassianConsentRequest("consent-code"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(atlassianAppTokenService).storeConsent("consent-code");
    }
}
