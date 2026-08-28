package com.history.backend.auth.controller;

import java.net.URI;

import com.history.backend.auth.RefreshTokenCookies;
import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.auth.dto.TokenResponse;
import com.history.backend.auth.service.AuthService;
import com.history.backend.auth.service.IssuedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    @GetMapping("/github/authorize")
    public ResponseEntity<Void> authorizeGitHub(@RequestParam(required = false) String state) {
        URI redirectUri = authService.buildGitHubAuthorizeUri(state);
        return ResponseEntity.status(302)
                .location(redirectUri)
                .build();
    }

    @GetMapping("/github/install")
    public ResponseEntity<Void> installGitHubApp(@RequestParam(required = false) String state) {
        URI redirectUri = authService.buildGitHubInstallUri(state);
        return ResponseEntity.status(302)
                .location(redirectUri)
                .build();
    }

    @GetMapping("/github/callback")
    public ResponseEntity<TokenResponse> handleGitHubCallback(
            @RequestParam @NotBlank String code,
            @RequestParam(required = false) String state,
            HttpServletRequest request
    ) {
        IssuedSession session = authService.loginWithGitHub(new GitHubCallbackRequest(code, state));
        return withRefreshCookie(session, request);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken,
            HttpServletRequest request
    ) {
        IssuedSession session = authService.refresh(refreshToken);
        return withRefreshCookie(session, request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken,
            HttpServletRequest request
    ) {
        authService.logout(refreshToken);
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, RefreshTokenCookies.clear(request.isSecure()).toString())
                .build();
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(IssuedSession session, HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        RefreshTokenCookies.issue(
                                session.refreshToken(),
                                request.isSecure(),
                                session.refreshTokenTtl()
                        ).toString()
                )
                .body(session.toResponse());
    }
}
