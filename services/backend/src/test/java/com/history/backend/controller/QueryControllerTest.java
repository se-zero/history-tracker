package com.history.backend.controller;

import com.history.backend.dto.QueryResponse;
import com.history.backend.security.JwtAuthenticationException;
import com.history.backend.security.JwtTokenService;
import com.history.backend.service.QueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QueryController.class)
class QueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryService queryService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @Test
    @WithMockUser
    void askAcceptsQuestion() throws Exception {
        when(queryService.ask(any(), any())).thenReturn(new QueryResponse(""));

        mockMvc.perform(post("/api/v1/projects/history-tracker/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"Why did this file change?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"answer":""}
                        """));
    }

    @Test
    @WithMockUser
    void askRejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/v1/projects/history-tracker/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askRejectsInvalidAccessTokenWithErrorResponse() throws Exception {
        when(jwtTokenService.validateAccessToken(anyString()))
                .thenThrow(new JwtAuthenticationException("Invalid JWT signature."));

        mockMvc.perform(post("/api/v1/projects/history-tracker/query")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"Why did this file change?"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid access token."));
    }
}
