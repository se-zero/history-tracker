package com.history.pipeline_worker.collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CollectionRunContext: 지금 스레드의 수집 실행이 어느 연동(integrations.id) 소속인지 담는 ThreadLocal 범위.
 * ProjectIntegrationRepository는 Mock으로 대체하고, 연동 행이 남아 있는지(existsById)만 바꿔가며 ensureActive 분기를 검증한다.
 */
class CollectionRunContextTest {

    private static final String INTEGRATION_ID = "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1";
    private static final String OTHER_INTEGRATION_ID = "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2";

    private final ProjectIntegrationRepository repository = mock(ProjectIntegrationRepository.class);
    private final CollectionRunContext runContext = new CollectionRunContext(repository);

    @Test
    @DisplayName("범위가 없으면 ensureActive는 DB를 부르지 않고 통과한다")
    void ensureActive_withoutScope_doesNotTouchRepository() {
        assertThatCode(runContext::ensureActive).doesNotThrowAnyException();

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("범위가 열려 있고 연동 행이 남아 있으면 ensureActive는 통과한다")
    void ensureActive_scopeWithExistingRow_passes() {
        when(repository.existsById(UUID.fromString(INTEGRATION_ID))).thenReturn(true);

        try (CollectionRunContext.Scope scope = runContext.open(INTEGRATION_ID)) {
            assertThatCode(runContext::ensureActive).doesNotThrowAnyException();
        }

        verify(repository).existsById(UUID.fromString(INTEGRATION_ID));
    }

    @Test
    @DisplayName("범위가 열려 있는데 연동 행이 없으면 IntegrationDisconnectedException")
    void ensureActive_scopeWithMissingRow_throwsIntegrationDisconnected() {
        when(repository.existsById(UUID.fromString(INTEGRATION_ID))).thenReturn(false);

        try (CollectionRunContext.Scope scope = runContext.open(INTEGRATION_ID)) {
            assertThatThrownBy(runContext::ensureActive)
                    .isInstanceOf(IntegrationDisconnectedException.class);
        }
    }

    // 풀 스레드는 재사용된다 — close 뒤에도 범위가 남으면 다음 작업이 엉뚱한 연동의 해제 여부로 막힌다.
    @Test
    @DisplayName("close하면 현재 연동 id가 비고, 이후 ensureActive는 해제된 연동이어도 막지 않는다")
    void close_clearsCurrentIntegrationId() {
        when(repository.existsById(UUID.fromString(INTEGRATION_ID))).thenReturn(false);

        try (CollectionRunContext.Scope scope = runContext.open(INTEGRATION_ID)) {
            assertThat(runContext.currentIntegrationId()).contains(INTEGRATION_ID);
        }

        assertThat(runContext.currentIntegrationId()).isEmpty();
        assertThatCode(runContext::ensureActive).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("integrationId가 null이면 범위를 열지 않는다 (지금 동작 유지)")
    void open_nullIntegrationId_opensNoScope() {
        try (CollectionRunContext.Scope scope = runContext.open(null)) {
            assertThat(runContext.currentIntegrationId()).isEmpty();
            assertThatCode(runContext::ensureActive).doesNotThrowAnyException();
        }

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("중첩 open 뒤 안쪽을 close하면 바깥 범위로 복원된다")
    void close_nestedScope_restoresOuterScope() {
        try (CollectionRunContext.Scope outer = runContext.open(INTEGRATION_ID)) {
            try (CollectionRunContext.Scope inner = runContext.open(OTHER_INTEGRATION_ID)) {
                assertThat(runContext.currentIntegrationId()).contains(OTHER_INTEGRATION_ID);
            }

            assertThat(runContext.currentIntegrationId()).contains(INTEGRATION_ID);
        }

        assertThat(runContext.currentIntegrationId()).isEmpty();
    }

    // 초기 수집 풀과 webhook 풀이 동시에 돈다 — 한 스레드의 범위가 다른 스레드의 실행에 새면 안 된다.
    @Test
    @DisplayName("범위는 연 스레드에서만 보인다")
    void currentIntegrationId_isIsolatedPerThread() throws Exception {
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try (CollectionRunContext.Scope scope = runContext.open(INTEGRATION_ID)) {
            Optional<String> seenByOtherThread = otherThread.submit(runContext::currentIntegrationId)
                    .get(5, TimeUnit.SECONDS);

            assertThat(seenByOtherThread).isEmpty();
            assertThat(runContext.currentIntegrationId()).contains(INTEGRATION_ID);
        } finally {
            otherThread.shutdownNow();
        }
    }
}
