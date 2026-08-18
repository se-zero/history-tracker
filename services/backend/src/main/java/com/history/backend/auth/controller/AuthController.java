package com.history.backend.auth.controller;

import java.net.URI;

import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.auth.dto.RefreshTokenRequest;
import com.history.backend.auth.dto.TokenResponse;
import com.history.backend.auth.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            @RequestParam(required = false) String state
    ) {
        return ResponseEntity.ok(authService.loginWithGitHub(new GitHubCallbackRequest(code, state)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody @Valid RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody @Valid RefreshTokenRequest request) {
        authService.logout(request);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
