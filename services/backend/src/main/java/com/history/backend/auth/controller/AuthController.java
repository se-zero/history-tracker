package com.history.backend.auth.controller;

import java.net.URI;

import com.history.backend.auth.dto.GitHubCallbackRequest;
import com.history.backend.auth.dto.TokenResponse;
import com.history.backend.auth.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/github/callback")
    public ResponseEntity<TokenResponse> handleGitHubCallback(
            @RequestParam @NotBlank String code,
            @RequestParam(required = false) String state,
            @RequestParam(name = "installation_id", required = false) Long installationId
    ) {
        return ResponseEntity.ok(authService.loginWithGitHub(new GitHubCallbackRequest(code, state, installationId)));
    }
}
