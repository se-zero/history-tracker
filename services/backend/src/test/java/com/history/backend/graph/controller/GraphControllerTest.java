package com.history.backend.graph.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.ForbiddenException;
import com.history.backend.graph.dto.GraphBuildResponse;
import com.history.backend.graph.dto.GraphBuildStatusResponse;
import com.history.backend.graph.dto.GraphNodeResponse;
import com.history.backend.graph.dto.GraphResponse;
import com.history.backend.graph.service.GraphService;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GraphController: 그래프 HTTP API")
class GraphControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GraphService graphService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUpAuthentication() {
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
    }

    @Test
    @DisplayName("필터 파라미터와 함께 프로젝트 그래프 반환")
    void returnsProjectGraphWithForwardedFilters() throws Exception {
        GraphResponse graph = new GraphResponse(
                List.of(new GraphNodeResponse("n1", "commit", "feat: x", "abc1234", "github", "body")),
                List.of(List.of("n1", "n2"))
        );
        when(graphService.getProjectGraph(USER_ID, PROJECT_ID, 50, "commit,pr")).thenReturn(graph);

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .param("limit", "50")
                        .param("types", "commit,pr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].id").value("n1"))
                .andExpect(jsonPath("$.nodes[0].type").value("commit"))
                .andExpect(jsonPath("$.edges[0][0]").value("n1"))
                .andExpect(jsonPath("$.edges[0][1]").value("n2"));

        verify(graphService).getProjectGraph(USER_ID, PROJECT_ID, 50, "commit,pr");
    }

    @Test
    @DisplayName("선택 파라미터 미전달 시 null 기본값 사용")
    void defaultsOptionalParamsToNull() throws Exception {
        when(graphService.getProjectGraph(eq(USER_ID), eq(PROJECT_ID), isNull(), isNull()))
                .thenReturn(GraphResponse.empty());

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.edges").isArray());

        verify(graphService).getProjectGraph(USER_ID, PROJECT_ID, null, null);
    }

    @Test
    @DisplayName("소유자가 아닌 경우 403 Forbidden 전파")
    void propagatesForbiddenWhenNotOwner() throws Exception {
        when(graphService.getProjectGraph(USER_ID, PROJECT_ID, null, null))
                .thenThrow(new ForbiddenException("Project access denied."));

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 요청 거부 → 401 Unauthorized")
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", PROJECT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("빌드 트리거 → 202 Accepted, 상태 본문 반환")
    void triggersBuildReturns202() throws Exception {
        GraphBuildStatusResponse buildStatus =
                new GraphBuildStatusResponse("running", true, "2026-06-24T00:00:00+00:00", null, null);
        when(graphService.buildProjectGraph(USER_ID, PROJECT_ID, true)).thenReturn(buildStatus);

        mockMvc.perform(post("/api/v1/projects/{projectId}/graph/build", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .param("verify", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("running"))
                .andExpect(jsonPath("$.verify").value(true))
                .andExpect(jsonPath("$.started_at").value("2026-06-24T00:00:00+00:00"));

        verify(graphService).buildProjectGraph(USER_ID, PROJECT_ID, true);
    }

    @Test
    @DisplayName("빌드 트리거 — verify 미전달 시 기본 false")
    void triggersBuildDefaultsVerifyFalse() throws Exception {
        when(graphService.buildProjectGraph(USER_ID, PROJECT_ID, false))
                .thenReturn(new GraphBuildStatusResponse("running", false, "2026-06-24T00:00:00+00:00", null, null));

        mockMvc.perform(post("/api/v1/projects/{projectId}/graph/build", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isAccepted());

        verify(graphService).buildProjectGraph(USER_ID, PROJECT_ID, false);
    }

    @Test
    @DisplayName("빌드 상태 조회 → 200, succeeded면 result 카운트 포함")
    void returnsBuildStatus() throws Exception {
        GraphBuildStatusResponse buildStatus = new GraphBuildStatusResponse(
                "succeeded", false, "2026-06-24T00:00:00+00:00",
                new GraphBuildResponse(1, 2, 3, 4, 5), null);
        when(graphService.getBuildStatus(USER_ID, PROJECT_ID)).thenReturn(buildStatus);

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph/build/status", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("succeeded"))
                .andExpect(jsonPath("$.result.triggered_by").value(2))
                .andExpect(jsonPath("$.result.thread_propagated").value(5));

        verify(graphService).getBuildStatus(USER_ID, PROJECT_ID);
    }

    @Test
    @DisplayName("빌드 트리거 — 소유자가 아니면 403 Forbidden 전파")
    void propagatesForbiddenOnBuild() throws Exception {
        when(graphService.buildProjectGraph(USER_ID, PROJECT_ID, false))
                .thenThrow(new ForbiddenException("Project access denied."));

        mockMvc.perform(post("/api/v1/projects/{projectId}/graph/build", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("빌드 상태 조회 — 미인증 요청 거부 → 401 Unauthorized")
    void rejectsUnauthenticatedBuildStatus() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/graph/build/status", PROJECT_ID))
                .andExpect(status().isUnauthorized());
    }
}
