package com.history.backend.billing.controller;

import com.history.backend.billing.dto.BillingPortalResponse;
import com.history.backend.billing.dto.BillingSummaryResponse;
import com.history.backend.billing.dto.CheckoutAvailabilityResponse;
import com.history.backend.billing.dto.CheckoutSessionResponse;
import com.history.backend.billing.dto.OpenBillingPortalRequest;
import com.history.backend.billing.service.BillingAccountService;
import com.history.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class BillingController {

    private final BillingAccountService billingAccountService;

    @GetMapping("/billing/availability")
    public CheckoutAvailabilityResponse availability() {
        return new CheckoutAvailabilityResponse(billingAccountService.checkoutAvailable());
    }

    @GetMapping("/me/billing")
    public BillingSummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return billingAccountService.summary(authenticatedUser.id());
    }

    @PostMapping("/me/billing/checkout")
    public CheckoutSessionResponse checkout(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return billingAccountService.startCheckout(authenticatedUser.id());
    }

    @PostMapping("/me/billing/portal")
    public BillingPortalResponse portal(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody OpenBillingPortalRequest request
    ) {
        return billingAccountService.openPortal(authenticatedUser.id(), request.target());
    }
}
