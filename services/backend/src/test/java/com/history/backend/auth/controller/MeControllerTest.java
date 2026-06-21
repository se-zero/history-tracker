package com.history.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.history.backend.auth.dto.UserResponse;
import com.history.backend.auth.service.UserService;
import com.history.backend.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MeController: 현재 사용자 정보 조회·탈퇴")
class MeControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");

    @Mock
    private UserService userService;

    @Test
    @DisplayName("현재 사용자 정보 반환")
    void meReturnsCurrentUser() {
        UserResponse response = new UserResponse(USER_ID, "github", "12345", "octocat@example.com", "Octocat", null);
        when(userService.getCurrentUser(USER_ID)).thenReturn(response);

        UserResponse result = new MeController(userService).me(new AuthenticatedUser(USER_ID));

        assertThat(result).isEqualTo(response);
        verify(userService).getCurrentUser(USER_ID);
    }

    @Test
    @DisplayName("현재 사용자 탈퇴 처리")
    void deleteMeDeactivatesCurrentUser() {
        new MeController(userService).deleteMe(new AuthenticatedUser(USER_ID));

        verify(userService).deactivateUser(USER_ID);
    }
}
