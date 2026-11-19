package com.history.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ALB 건강 검사는 토큰을 붙일 수 없어 헬스 경로만 인증 없이 열려 있어야 한다.
// 노출·상세 설정은 Boot 기본값에 기대므로 테스트 yaml에 management 설정을 두지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Actuator 헬스 경로: 인증 없는 접근 범위")
class ActuatorHealthAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("readiness는 JWT 없이 200 UP이고 하위 지표를 싣지 않는다")
    void readinessIsPublicAndHidesComponents() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("liveness는 JWT 없이 200 UP이고 하위 지표를 싣지 않는다")
    void livenessIsPublicAndHidesComponents() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("전체 health는 JWT 없이 200 UP이고 DB 같은 하위 지표를 싣지 않는다")
    void healthIsPublicAndHidesComponents() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("health 밖의 actuator 경로는 JWT 없이 401 표준 오류 응답이다")
    void rejectUnauthenticatedRequestToNonHealthActuatorEndpoint() throws Exception {
        // 허용을 /actuator/**로 넓히면 인증을 지나 404가 되어 이 테스트가 깨진다
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }
}
