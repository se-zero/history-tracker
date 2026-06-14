package com.history.pipeline_worker.controller;

import com.history.pipeline_worker.trigger.CollectionProvider;
import com.history.pipeline_worker.trigger.CollectionTriggerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CollectionTriggerControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private CollectionTriggerService collectionTriggerService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        collectionTriggerService = mock(CollectionTriggerService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CollectionTriggerController(collectionTriggerService))
                .setValidator(validator)
                .build();
    }

    @Test
    void triggerCollection_accepted_returns202() throws Exception {
        when(collectionTriggerService.trigger(CollectionProvider.GITHUB, PROJECT_ID))
                .thenReturn(new CollectionTriggerService.TriggerResult(
                        CollectionTriggerService.TriggerStatus.ACCEPTED,
                        "collection queued"
                ));

        mockMvc.perform(post("/api/v1/collect/github")
                        .contentType("application/json")
                        .content("{\"projectId\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.message").value("collection queued"));
    }

    @Test
    void triggerCollection_missingIntegration_returns404() throws Exception {
        when(collectionTriggerService.trigger(CollectionProvider.JIRA, PROJECT_ID))
                .thenReturn(new CollectionTriggerService.TriggerResult(
                        CollectionTriggerService.TriggerStatus.NOT_FOUND,
                        "no project integration found"
                ));

        mockMvc.perform(post("/api/v1/collect/jira")
                        .contentType("application/json")
                        .content("{\"projectId\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("not_found"));
    }

    @Test
    void triggerCollection_invalidProjectId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/collect/slack")
                .contentType("application/json")
                        .content("{\"projectId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("bad_request"))
                .andExpect(jsonPath("$.message").value("invalid request"));

        verifyNoInteractions(collectionTriggerService);
    }

    @Test
    void triggerCollection_unsupportedProvider_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/collect/GITHUB")
                .contentType("application/json")
                        .content("{\"projectId\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("bad_request"))
                .andExpect(jsonPath("$.message").value("unsupported collection provider"));

        verifyNoInteractions(collectionTriggerService);
    }

    @Test
    void triggerCollection_executorRejection_returns500() throws Exception {
        when(collectionTriggerService.trigger(CollectionProvider.GITHUB, PROJECT_ID))
                .thenThrow(new RejectedExecutionException("queue full"));

        mockMvc.perform(post("/api/v1/collect/github")
                        .contentType("application/json")
                        .content("{\"projectId\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("internal_server_error"))
                .andExpect(jsonPath("$.message").value("collection queue rejected"));
    }
}
