package com.history.backend.oauth.controller;

import com.history.backend.oauth.dto.ConsentDecisionRequest;
import com.history.backend.oauth.dto.ConsentDecisionResponse;
import com.history.backend.oauth.dto.ConsentPreviewResponse;
import com.history.backend.oauth.security.ConsentTicketCookies;
import com.history.backend.oauth.service.ConsentDecision;
import com.history.backend.oauth.service.OAuthConsentService;
import com.history.backend.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/oauth/consent")
public class OAuthConsentController {

    private final OAuthConsentService oAuthConsentService;

    // 디코딩된 파라미터가 아니라 getQueryString() 원문을 넘긴다 — 티켓이 원문 해시에 묶이기 때문이다.
    @GetMapping("/preview")
    public ConsentPreviewResponse preview(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            HttpServletRequest request
    ) {
        return oAuthConsentService.preview(authenticatedUser.id(), request.getQueryString());
    }

    // 티켓은 본문에 싣지 않고 httpOnly 쿠키로만 내려 JS가 읽지 못하게 한다.
    @PostMapping
    public ResponseEntity<ConsentDecisionResponse> decide(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody ConsentDecisionRequest body,
            HttpServletRequest request
    ) {
        ConsentDecision decision = oAuthConsentService.decide(authenticatedUser.id(), body.query(), body.approved());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (decision.ticket() != null) {
            response.header(HttpHeaders.SET_COOKIE,
                    ConsentTicketCookies.issue(decision.ticket(), request.isSecure()).toString());
        }
        return response.body(new ConsentDecisionResponse(decision.redirectTo()));
    }
}
