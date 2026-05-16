package com.history.backend.auth.controller;

import com.history.backend.auth.dto.UserResponse;
import com.history.backend.auth.service.UserService;
import com.history.backend.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService userService;

    @GetMapping
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return userService.getCurrentUser(authenticatedUser.id());
    }
}
