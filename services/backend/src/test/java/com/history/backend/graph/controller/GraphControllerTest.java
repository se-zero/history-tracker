package com.history.backend.graph.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.ForbiddenException;
import com.history.backend.graph.dto.GraphNodeResponse;
import com.history.backend.graph.dto.GraphResponse;
import com.history.backend.graph.service.GraphService;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
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
    void propagatesForbiddenWhenNotOwner() throws Exception {
        when(graphService.getProjectGraph(USER_ID, PROJECT_ID, null, null))
                .thenThrow(new ForbiddenException("Project access denied."));

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", PROJECT_ID))
                .andExpect(status().isUnauthorized());
    }
}
