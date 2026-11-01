package com.history.pipeline_worker.messaging;

import com.history.pipeline_worker.collection.CollectionRunContext;
import com.history.pipeline_worker.collection.IntegrationDisconnectedException;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.config.RabbitMqConfig;
import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.outbox.OutboxRepository;
import com.history.pipeline_worker.outbox.OutboxRepository.OutboxInsert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * EventPublisher: NormalizedEvent → 아웃박스 행 적재(한 publishAll = 한 트랜잭션).
 * 브로커 발행·라우팅 키·confirm 검증은 OutboxRelay로 옮겨갔으므로 여기서 다루지 않는다.
 * CollectionRunContext는 실제 객체 사용 — ThreadLocal 범위와 ensureActive가 "해제된 연동의 발행 차단"의 본질이라서.
 * 직렬화는 실제 변환기(RabbitMqConfig.messageConverter) — 와이어 포맷이 기존과 같다는 것이 이 클래스의 계약이라서.
 * 트랜잭션은 실제 TransactionTemplate + Mock PlatformTransactionManager로 경계(begin → insert → commit/rollback)를 본다.
 */
@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    @Mock
    private OutboxRepository outbox;

    @Mock
    private ProjectIntegrationRepository integrationRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TransactionStatus transactionStatus;
    private CollectionRunContext runContext;
    private EventPublisher publisher;

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INTEGRATION_ID = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    private static final Instant OCCURRED_AT = Instant.parse("2026-05-18T01:00:00Z");

    @BeforeEach
    void setUp() {
        transactionStatus = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        runContext = new CollectionRunContext(integrationRepository);
        publisher = new EventPublisher(outbox, runContext, new RabbitMqConfig().messageConverter(),
                new TransactionTemplate(transactionManager));
    }

    // ─── 빈 / null 입력 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("null 이벤트 목록 → 0 반환, 아웃박스·트랜잭션 미호출")
    void publishAll_nullList_returnsZeroAndNoInteraction() {
        int count = publisher.publishAll(null);

        assertThat(count).isZero();
        verifyNoInteractions(outbox, transactionManager);
    }

    @Test
    @DisplayName("빈 이벤트 목록 → 0 반환, 아웃박스·트랜잭션 미호출")
    void publishAll_emptyList_returnsZeroAndNoInteraction() {
        int count = publisher.publishAll(List.of());

        assertThat(count).isZero();
        verifyNoInteractions(outbox, transactionManager);
    }

    // ─── 적재 내용 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("payload는 기존 변환기의 JSON이고 source 컬럼은 event.source()")
    void publishAll_storesConverterJsonPayloadAndSource() {
        NormalizedEvent event = buildEvent("GITHUB");

        publisher.publishAll(List.of(event));

        OutboxInsert row = captureRows().get(0);
        assertThat(row.projectId()).isEqualTo(PROJECT_ID);
        assertThat(row.source()).isEqualTo(event.source());
        assertThat(row.payload())
                .contains("\"source\":\"GITHUB\"")
                .contains("\"occurredAt\":\"2026-05-18T01:00:00Z\"")
                .contains("\"projectId\":\"" + PROJECT_ID + "\"");
    }

    @Test
    @DisplayName("이벤트 3건 → insertAll 1회에 3행, 3 반환")
    void publishAll_multipleEvents_insertsOnceAndReturnsCount() {
        List<NormalizedEvent> events = List.of(buildEvent("GITHUB"), buildEvent("JIRA"), buildEvent("SLACK"));

        int count = publisher.publishAll(events);

        assertThat(count).isEqualTo(3);
        verify(outbox, times(1)).insertAll(anyList());
        assertThat(captureRows()).extracting(OutboxInsert::source).containsExactly("GITHUB", "JIRA", "SLACK");
    }

    @Test
    @DisplayName("source가 공백이면 source 컬럼은 빈 문자열")
    void publishAll_blankSource_storesEmptySource() {
        publisher.publishAll(List.of(buildEvent("  ")));

        assertThat(captureRows().get(0).source()).isEmpty();
    }

    @Test
    @DisplayName("source가 null이면 source 컬럼은 빈 문자열")
    void publishAll_nullSource_storesEmptySource() {
        publisher.publishAll(List.of(buildEvent(null)));

        assertThat(captureRows().get(0).source()).isEmpty();
    }

    // ─── 트랜잭션 경계 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("insertAll은 트랜잭션 안에서 호출된다 (getTransaction → insertAll → commit)")
    void publishAll_insertsInsideTransaction() {
        publisher.publishAll(List.of(buildEvent("GITHUB")));

        InOrder order = inOrder(transactionManager, outbox);
        order.verify(transactionManager).getTransaction(any());
        order.verify(outbox).insertAll(anyList());
        order.verify(transactionManager).commit(transactionStatus);
    }

    @Test
    @DisplayName("insertAll이 DataAccessException → 그대로 전파하고 롤백 (커서 전진 차단)")
    void publishAll_insertFails_propagatesAndRollsBack() {
        DataAccessException failure = new DataAccessResourceFailureException("db down");
        doThrow(failure).when(outbox).insertAll(anyList());

        assertThatThrownBy(() -> publisher.publishAll(List.of(buildEvent("GITHUB"))))
                .isSameAs(failure);

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    // ─── 연동 해제 → 적재 차단 / 연동 id 태깅 ──────────────────────────────────────

    // 연동을 해제해도 이미 도는 수집이 이벤트를 끝까지 쏟아내던 결함 — 적재 전에 막아야 한 건도 안 들어간다.
    @Test
    @DisplayName("범위가 열린 연동 행이 없으면 예외를 던지고 한 건도 적재하지 않는다")
    void publishAll_integrationDisconnected_throwsAndInsertsNothing() {
        when(integrationRepository.existsById(INTEGRATION_ID)).thenReturn(false);
        List<NormalizedEvent> events = List.of(buildEvent("GITHUB"), buildEvent("JIRA"));

        try (CollectionRunContext.Scope scope = runContext.open(INTEGRATION_ID.toString())) {
            assertThatThrownBy(() -> publisher.publishAll(events))
                    .isInstanceOf(IntegrationDisconnectedException.class);
        }

        verifyNoInteractions(outbox, transactionManager);
    }

    @Test
    @DisplayName("범위가 열려 있으면 적재하는 모든 행에 그 integrationId를 붙인다 (payload JSON 포함)")
    void publishAll_scopeOpen_tagsEveryRowWithIntegrationId() {
        when(integrationRepository.existsById(INTEGRATION_ID)).thenReturn(true);
        List<NormalizedEvent> events = List.of(buildEvent("GITHUB"), buildEvent("JIRA"));

        int count;
        try (CollectionRunContext.Scope scope = runContext.open(INTEGRATION_ID.toString())) {
            count = publisher.publishAll(events);
        }

        assertThat(count).isEqualTo(2);
        List<OutboxInsert> rows = captureRows();
        assertThat(rows).extracting(OutboxInsert::integrationId).containsOnly(INTEGRATION_ID);
        assertThat(rows).extracting(OutboxInsert::payload)
                .allSatisfy(payload -> assertThat(payload).contains("\"integrationId\":\"" + INTEGRATION_ID + "\""));
    }

    @Test
    @DisplayName("범위가 없으면 DB를 확인하지 않고 integrationId 없이 적재한다")
    void publishAll_withoutScope_insertsUntagged() {
        publisher.publishAll(List.of(buildEvent("GITHUB")));

        OutboxInsert row = captureRows().get(0);
        assertThat(row.integrationId()).isNull();
        assertThat(row.payload()).doesNotContain(INTEGRATION_ID.toString());
        verifyNoInteractions(integrationRepository);
    }

    // ─── 헬퍼 메서드 ─────────────────────────────────────────────────────────────

    private List<OutboxInsert> captureRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutboxInsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(outbox).insertAll(captor.capture());
        return captor.getValue();
    }

    private NormalizedEvent buildEvent(String source) {
        return new NormalizedEvent(
                PROJECT_ID.toString(),
                "ChangeSet",
                source,
                OCCURRED_AT,
                new ActorDto("user-1", "Test User", "test@example.com"),
                Map.of("hash", "abc123"),
                Map.of()
        );
    }
}
