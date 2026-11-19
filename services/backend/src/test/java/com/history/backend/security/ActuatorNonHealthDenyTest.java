package com.history.backend.security;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 나중에 노출 목록을 넓혀도 health 밖의 actuator 경로는 로그인 사용자에게도 막혀 있어야 한다(방어 심층화).
// 노출을 넓힌 상태를 만들려고 이 클래스만 속성을 직접 준다 — 테스트 yaml에는 management 설정을 두지 않는다.
@SpringBootTest(properties = "management.endpoints.web.exposure.include=health,env")
@AutoConfigureMockMvc
@DisplayName("Actuator 헬스 밖 경로: 노출을 넓혀도 로그인 사용자에게 막힘")
class ActuatorNonHealthDenyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    @DisplayName("노출된 env도 유효한 로그인 토큰으로 403")
    void denyAuthenticatedUserOnExposedNonHealthEndpoint() throws Exception {
        String accessToken = jwtTokenService.issueAccessToken(UUID.randomUUID());

        mockMvc.perform(get("/actuator/env")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("노출된 env는 무인증이면 401 표준 오류 응답")
    void rejectUnauthenticatedRequestToExposedNonHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    @Test
    @DisplayName("env를 막아도 readiness는 무인증 200 UP")
    void keepReadinessPublicWhenNonHealthEndpointsAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
