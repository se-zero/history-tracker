package com.history.backend.auth.controller;

import java.util.List;

import com.history.backend.auth.dto.UpgradePlanRequest;
import com.history.backend.auth.dto.UserResponse;
import com.history.backend.auth.service.AccountWithdrawalService;
import com.history.backend.auth.service.PlanService;
import com.history.backend.auth.service.UserService;
import com.history.backend.billing.service.BillingAccountService;
import com.history.backend.oauth.dto.OAuthGrantResponse;
import com.history.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final AccountWithdrawalService accountWithdrawalService;
    private final BillingAccountService billingAccountService;

    @GetMapping
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return userService.getCurrentUser(authenticatedUser.id());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        accountWithdrawalService.withdraw(authenticatedUser.id());
    }

    @PostMapping("/plan/downgrade")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void downgradePlan(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        billingAccountService.downgradeCodePlan(authenticatedUser.id());
    }

    @PostMapping("/consent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordConsent(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        userService.recordConsent(authenticatedUser.id());
    }

    @GetMapping("/oauth-grants")
    public List<OAuthGrantResponse> oAuthGrants(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return userService.listOAuthGrants(authenticatedUser.id());
    }

    @DeleteMapping("/oauth-grants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOAuthGrant(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable String id
    ) {
        userService.revokeOAuthGrant(authenticatedUser.id(), id);
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
