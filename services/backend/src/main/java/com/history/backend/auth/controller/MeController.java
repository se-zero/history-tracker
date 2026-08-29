package com.history.backend.auth.controller;

import com.history.backend.auth.dto.UpgradePlanRequest;
import com.history.backend.auth.dto.UserResponse;
import com.history.backend.auth.service.PlanService;
import com.history.backend.auth.service.UserService;
import com.history.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService userService;
    private final PlanService planService;

    @GetMapping
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return userService.getCurrentUser(authenticatedUser.id());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        userService.deactivateUser(authenticatedUser.id());
    }

    @PostMapping("/consent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordConsent(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        userService.recordConsent(authenticatedUser.id());
    }

    @PostMapping("/plan/upgrade")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upgradePlan(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody UpgradePlanRequest request
    ) {
        planService.upgradeToPaid(authenticatedUser.id(), request.code());
    }
}
