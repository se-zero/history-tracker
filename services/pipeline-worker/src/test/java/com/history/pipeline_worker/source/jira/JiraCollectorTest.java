package com.history.pipeline_worker.source.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JiraCollectorTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROJECT_UUID = UUID.fromString(PROJECT_ID);
    private static final byte[] JIRA_TOKEN = new byte[] {2};
    private static final String GATEWAY_BASE_URL = "https://api.atlassian.com/ex/jira";
    private static final String CREDENTIAL_JSON =
            "{\"access_token\":\"jira-access-token\",\"refresh_token\":\"jira-refresh-token\"}";

    @Mock
    private JiraRawService rawService;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private CheckpointService checkpointService;
    @Mock
    private CredentialCryptoService credentialCryptoService;

    private JiraCollector collector;

    @BeforeEach
    void setUp() {
        collector = new JiraCollector(
                rawService,
                new JiraNormalizer(new RefsExtractor()),
                eventPublisher,
                checkpointService,
                credentialCryptoService,
                new ObjectMapper(),
                GATEWAY_BASE_URL
        );
    }

    @Test
    @DisplayName("페이지 발행 후 그 페이지의 최대 occurredAt으로 커서를 갱신한다")
    void collect_advancesCursorPerPublishedPage() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "PLAT", Map.of());
        JiraRawService.JiraFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.JIRA)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null)).thenReturn(context);
        when(rawService.fetchSearchPage(context, null, 1)).thenReturn(new JiraRawService.JiraSearchPage(
                Map.of("issues", List.of(buildIssue("PLAT-1", "2024-05-01T00:00:00.000+0000"))), null, false));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isEqualTo(1);
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.JIRA,
                JiraCollector.UPDATED_CURSOR, Instant.parse("2024-05-01T00:00:00Z"));
    }

    @Test
    @DisplayName("max pages 도달 시 그 페이지는 발행하지 않고 중단한다")
    void collect_limitReached_stopsWithoutPublishing() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "PLAT", Map.of());
        JiraRawService.JiraFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.JIRA)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, null)).thenReturn(context);
        when(rawService.fetchSearchPage(context, null, 1))
                .thenReturn(new JiraRawService.JiraSearchPage(Map.of("issues", List.of()), null, true));

        assertThat(collector.collect(PROJECT_ID, request)).isZero();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void resolveFetchRequest_buildsBearerRequestWithCloudIdGatewayUrl() {
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn(CREDENTIAL_JSON);

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(
                jiraRow(Map.of("project_key", "PLAT", "cloud_id", "CLOUD123")));

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer jira-access-token");
            assertThat(request.projectKey()).isEqualTo("PLAT");
            assertThat(request.options()).containsEntry("baseUrl", GATEWAY_BASE_URL + "/CLOUD123");
        });
    }

    @Test
    void resolveFetchRequest_brokenCredentialJson_throwsIllegalStateException() {
        // OAuth 전환 후 credential은 JSON이라, 깨진 JSON도 IllegalStateException으로 감싸야
        // 호출부의 안전망(IllegalArgumentException|IllegalStateException만 잡음)에 걸려
        // webhook 수집 전체가 실패하지 않는다.
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn("not-valid-json");

        assertThatThrownBy(() -> collector.resolveFetchRequest(
                jiraRow(Map.of("project_key", "PLAT", "cloud_id", "CLOUD123"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to parse Jira credential JSON.");
    }

    @Test
    void requiredCredentialString_throwsMessageDistinctFromExternalRefFailures() {
        // access_token은 external_ref가 아니라 암호화된 credential JSON 안에 있으므로,
        // requiredString과 같은 "Missing external_ref value: ..." 메시지를 재사용하면 엉뚱한 곳을 가리켜
        // 디버깅을 오도한다.
        assertThatThrownBy(() -> collector.requiredCredentialString(Map.of(), "access_token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Jira credential field: access_token");
    }

    private ProjectIntegrationRepository.IntegrationRow jiraRow(Map<String, Object> externalRef) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "jira", externalRef, JIRA_TOKEN, null, null);
    }

    private JiraRawService.JiraFetchContext fetchContext() {
        return new JiraRawService.JiraFetchContext(WebClient.builder().build(), "Bearer token", "PLAT", null);
    }

    private Map<String, Object> buildIssue(String key, String updated) {
        return Map.of(
                "key", key,
                "fields", Map.of(
                        "summary", "요약",
                        "description", "본문",
                        "updated", updated,
                        "created", updated,
                        "status", Map.of("name", "진행 중")
                )
        );
    }
}
