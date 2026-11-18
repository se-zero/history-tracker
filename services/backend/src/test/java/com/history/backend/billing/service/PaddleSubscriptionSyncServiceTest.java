package com.history.backend.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.history.backend.billing.PaddleProperties;
import com.history.backend.billing.repository.BillingSubscriptionRepository;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyOutcome;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplyResult;
import com.history.backend.billing.service.PaddleSubscriptionApplier.ApplySource;
import com.history.backend.billing.service.PaddleSubscriptionSyncService.SyncSummary;
import com.history.backend.common.error.BadGatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * PaddleSubscriptionSyncService: Paddle과 구독 캐시를 맞춘다 (1시간 동기화 + 결제 직전 재확인).
 * 이 서비스는 반영 규칙을 갖지 않는다 — 무엇을 조회해 applier(API 출처)에 넘기고 결과를 세는지만 검증한다.
 * 반영·순서·UNCHANGED 판정은 PaddleSubscriptionApplierTest의 몫이라 applier는 mock이다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("PaddleSubscriptionSyncService: Paddle 구독 동기화·재확인")
class PaddleSubscriptionSyncServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PRO_PRICE_ID = "pri_pro";
    private static final Instant UPDATED_AT = Instant.parse("2026-11-10T03:00:00Z");
    private static final Instant PERIOD_ENDS_AT = Instant.parse("2026-12-10T00:00:00Z");
    private static final Instant RENEWED_PERIOD_ENDS_AT = Instant.parse("2027-01-10T00:00:00Z");

    @Mock
    private PaddleApiClient paddleApiClient;

    @Mock
    private PaddleSubscriptionApplier applier;

    @Mock
    private BillingSubscriptionRepository billingSubscriptionRepository;

    // ── 1단계: price_id로 살아 있는 구독 전체를 받아 반영 ──

    @Test
    @DisplayName("Paddle 목록에 있고 캐시에 없던 구독은 API 출처로 반영한다 (생성 알림 유실 복구)")
    void syncAllAppliesSubscriptionThatIsMissingFromCache() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState created = state("sub_created", "active", PERIOD_ENDS_AT);
        cachedIds();
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of(created));
        stubApply(created, ApplyOutcome.APPLIED);

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 1, 0, 0, 0, 0, 0, 0);
        // 순서 기준은 구독 엔티티의 updated_at이라 봉투 시각 같은 대체 기준(두 번째 인자)은 넘기지 않는다
        verify(applier).apply(created, null, ApplySource.API);
        verifyNoLookupByIds();
    }

    @Test
    @DisplayName("갱신 알림을 잃어 캐시의 기간 끝이 옛 값이어도, Paddle 목록의 새 기간 끝 상태를 그대로 applier에 넘긴다 (갱신 유실 복구)")
    void syncAllHandsRenewedStateOfCachedSubscriptionToApplier() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState renewed = state("sub_renewed", "active", RENEWED_PERIOD_ENDS_AT);
        cachedIds("sub_renewed");
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of(renewed));
        stubApply(renewed, ApplyOutcome.APPLIED);

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 1, 0, 0, 0, 0, 0, 0);
        verify(applier).apply(renewed, null, ApplySource.API);
        // 이미 목록에서 받은 구독이라 다시 조회하지 않는다
        verifyNoLookupByIds();
    }

    @Test
    @DisplayName("applier가 UNCHANGED로 답한 구독은 unchanged로만 세고, 서비스가 캐시를 읽거나 쓰지 않는다")
    void syncAllCountsUnchangedWithoutTouchingTheCache() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState same = state("sub_same", "active", PERIOD_ENDS_AT);
        cachedIds("sub_same");
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of(same));
        stubApply(same, ApplyOutcome.UNCHANGED);

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 0, 1, 0, 0, 0, 0, 0);
        // 캐시 접근은 ID 조회 하나뿐 — 엔티티를 읽지 않고(ID만 조회), 쓰는 일은 applier가 판단한다
        verify(billingSubscriptionRepository).findSubscriptionIdsByStatusIn(any());
        verifyNoMoreInteractions(billingSubscriptionRepository);
    }

    @Test
    @DisplayName("한 건의 반영이 실패해도 다음 건을 계속 반영하고, 실패는 failed로 센다")
    void syncAllContinuesAfterOneApplyFailsAndCountsIt() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState first = state("sub_1", "active", PERIOD_ENDS_AT);
        PaddleSubscriptionState broken = state("sub_2", "active", PERIOD_ENDS_AT);
        PaddleSubscriptionState last = state("sub_3", "active", PERIOD_ENDS_AT);
        cachedIds();
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of(first, broken, last));
        stubApply(first, ApplyOutcome.APPLIED);
        // 첫 건·마지막 건이 아니라 가운데 건을 실패시킨다 — 실패 뒤 루프가 멈추는 결함은 마지막 건 호출 여부로 드러난다
        when(applier.apply(broken, null, ApplySource.API)).thenThrow(new IllegalStateException("boom"));
        stubApply(last, ApplyOutcome.APPLIED);

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 2, 0, 0, 0, 0, 1, 0);
        InOrder inOrder = inOrder(applier);
        inOrder.verify(applier).apply(first, null, ApplySource.API);
        inOrder.verify(applier).apply(broken, null, ApplySource.API);
        inOrder.verify(applier).apply(last, null, ApplySource.API);
    }

    // ── 2단계: 캐시에는 살아 있는데 1단계에 없는 구독을 ID로 조회 ──

    @Test
    @DisplayName("캐시는 active인데 Paddle 목록에 없는(해지된) 구독은 ID로 조회해 받은 canceled 상태를 반영한다 (해지 알림 유실 복구)")
    void syncAllAppliesCanceledStateOfCachedSubscriptionMissingFromLiveList() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState canceled = state("sub_lost_cancel", "canceled", null);
        cachedIds("sub_lost_cancel");
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of());
        when(paddleApiClient.listSubscriptionsByIds(sameIds("sub_lost_cancel"))).thenReturn(List.of(canceled));
        stubApply(canceled, ApplyOutcome.APPLIED);

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 1, 0, 0, 0, 0, 0, 0);
        verify(applier).apply(canceled, null, ApplySource.API);
    }

    @Test
    @DisplayName("ID 조회는 캐시 ID 중 1단계 목록에 없는 것만 한다 (목록에 있던 ID와 캐시에 없는 ID는 제외)")
    void syncAllLooksUpOnlyCachedIdsThatAreMissingFromLiveList() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState listedA = state("sub_a", "active", PERIOD_ENDS_AT);
        PaddleSubscriptionState listedC = state("sub_c", "active", PERIOD_ENDS_AT);
        PaddleSubscriptionState listedNew = state("sub_new", "active", PERIOD_ENDS_AT);
        PaddleSubscriptionState lookedUp = state("sub_b", "canceled", null);
        cachedIds("sub_a", "sub_b", "sub_c");
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of(listedA, listedC, listedNew));
        when(paddleApiClient.listSubscriptionsByIds(sameIds("sub_b"))).thenReturn(List.of(lookedUp));
        stubApply(listedA, ApplyOutcome.UNCHANGED);
        stubApply(listedC, ApplyOutcome.UNCHANGED);
        stubApply(listedNew, ApplyOutcome.APPLIED);
        stubApply(lookedUp, ApplyOutcome.APPLIED);

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 2, 2, 0, 0, 0, 0, 0);
        verify(paddleApiClient).listSubscriptionsByIds(sameIds("sub_b"));
    }

    @Test
    @DisplayName("캐시의 모든 ID가 1단계 목록에 있으면 ID 조회를 하지 않는다")
    void syncAllDoesNotLookUpIdsAlreadyCoveredByLiveList() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState listedA = state("sub_a", "active", PERIOD_ENDS_AT);
        PaddleSubscriptionState listedB = state("sub_b", "trialing", PERIOD_ENDS_AT);
        cachedIds("sub_a", "sub_b");
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of(listedA, listedB));
        stubApply(listedA, ApplyOutcome.UNCHANGED);
        stubApply(listedB, ApplyOutcome.UNCHANGED);

        service.syncAll();

        verifyNoLookupByIds();
    }

    @Test
    @DisplayName("ID로 조회한 구독 중 한 건의 반영이 실패해도 나머지를 계속 반영하고 failed로 센다")
    void syncAllContinuesAfterOneLookedUpApplyFails() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState broken = state("sub_b1", "canceled", null);
        PaddleSubscriptionState ok = state("sub_b2", "canceled", null);
        cachedIds("sub_b1", "sub_b2");
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of());
        when(paddleApiClient.listSubscriptionsByIds(sameIds("sub_b1", "sub_b2"))).thenReturn(List.of(broken, ok));
        when(applier.apply(broken, null, ApplySource.API)).thenThrow(new IllegalStateException("boom"));
        stubApply(ok, ApplyOutcome.APPLIED);

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 1, 0, 0, 0, 0, 1, 0);
        verify(applier).apply(ok, null, ApplySource.API);
    }

    @Test
    @DisplayName("Paddle이 돌려주지 않은 ID는 notFound로 세고, 캐시는 그대로 둔다 (반영도 삭제도 하지 않는다)")
    void syncAllCountsIdsPaddleDidNotReturnAsNotFoundAndKeepsTheCache() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState returned = state("sub_returned", "canceled", null);
        cachedIds("sub_returned", "sub_gone");
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of());
        when(paddleApiClient.listSubscriptionsByIds(sameIds("sub_returned", "sub_gone")))
                .thenReturn(List.of(returned));
        stubApply(returned, ApplyOutcome.APPLIED);

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 1, 0, 0, 0, 0, 0, 1);
        verify(applier).apply(returned, null, ApplySource.API);
        verifyNoMoreInteractions(applier);
        verify(billingSubscriptionRepository).findSubscriptionIdsByStatusIn(any());
        verifyNoMoreInteractions(billingSubscriptionRepository);
    }

    // ── 집계: 결과 종류마다 따로 센다 ──

    @Test
    @DisplayName("applied·unchanged·stale·ignored·unmatched·failed·notFound를 서로 다른 건수로 따로 센다")
    void syncAllCountsEveryOutcomeSeparately() {
        PaddleSubscriptionSyncService service = service();
        List<PaddleSubscriptionState> listed = new ArrayList<>();
        listed.addAll(stubListed("applied", ApplyOutcome.APPLIED, 1));
        listed.addAll(stubListed("unchanged", ApplyOutcome.UNCHANGED, 2));
        listed.addAll(stubListed("stale", ApplyOutcome.STALE, 3));
        listed.addAll(stubListed("ignored", ApplyOutcome.IGNORED, 4));
        listed.addAll(stubListed("unmatched", ApplyOutcome.UNMATCHED, 5));
        listed.addAll(stubFailing("failed", 6));
        List<String> cached = new ArrayList<>(listed.stream().map(PaddleSubscriptionState::subscriptionId).toList());
        List<String> gone = IntStream.range(0, 7).mapToObj(i -> "gone_" + i).toList();
        cached.addAll(gone);
        cachedIds(cached.toArray(String[]::new));
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(listed);
        when(paddleApiClient.listSubscriptionsByIds(sameIds(gone.toArray(String[]::new)))).thenReturn(List.of());

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 1, 2, 3, 4, 5, 6, 7);
    }

    // ── 건너뜀: 키·가격이 비면 Paddle을 부르지 않는다 ──

    @ParameterizedTest(name = "{0}")
    @MethodSource("unconfiguredProperties")
    @DisplayName("API 키나 pro-price-id가 비어 있으면 Paddle도 applier도 부르지 않고 건수 0으로 끝낸다")
    void syncAllSkipsWhenApiKeyOrProPriceIdIsBlank(String name, PaddleProperties properties) {
        PaddleSubscriptionSyncService service = service(properties);

        SyncSummary summary = service.syncAll();

        assertSummary(summary, 0, 0, 0, 0, 0, 0, 0);
        verifyNoInteractions(paddleApiClient, applier);
    }

    static Stream<Arguments> unconfiguredProperties() {
        return Stream.of(
                Arguments.of("API 키가 null", properties(null, PRO_PRICE_ID)),
                Arguments.of("API 키가 공백", properties("  ", PRO_PRICE_ID)),
                Arguments.of("pro-price-id가 null", properties("pdl_key", null)),
                Arguments.of("pro-price-id가 공백", properties("pdl_key", " "))
        );
    }

    // ── 실패 전파: 목록 조회 자체가 실패하면 이번 실행은 끝내고 다음 시간에 다시 한다 ──

    @Test
    @DisplayName("살아 있는 구독 목록 조회가 실패하면 예외를 그대로 던지고 아무것도 반영하지 않는다")
    void syncAllPropagatesLiveListFailure() {
        PaddleSubscriptionSyncService service = service();
        BadGatewayException failure = new BadGatewayException("Paddle subscription list request failed.");
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenThrow(failure);

        assertThatThrownBy(service::syncAll).isSameAs(failure);
        verifyNoInteractions(applier);
        verifyNoLookupByIds();
    }

    @Test
    @DisplayName("ID 묶음 조회가 실패하면 예외를 그대로 던진다 (앞서 반영한 건은 각자 커밋돼 남는다)")
    void syncAllPropagatesLookupByIdsFailure() {
        PaddleSubscriptionSyncService service = service();
        BadGatewayException failure = new BadGatewayException("Paddle subscription list request failed.");
        PaddleSubscriptionState listed = state("sub_a", "active", PERIOD_ENDS_AT);
        cachedIds("sub_a", "sub_b");
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(List.of(listed));
        stubApply(listed, ApplyOutcome.APPLIED);
        when(paddleApiClient.listSubscriptionsByIds(sameIds("sub_b"))).thenThrow(failure);

        assertThatThrownBy(service::syncAll).isSameAs(failure);
        verify(applier).apply(listed, null, ApplySource.API);
    }

    // ── 로그: 요약 한 줄 ──

    @Test
    @DisplayName("UNMATCHED를 행마다 경고하지 않고, 반영 결과 요약을 info 한 줄로만 남긴다")
    void syncAllLogsOneInfoSummaryAndNoWarningForUnmatched(CapturedOutput output) {
        PaddleSubscriptionSyncService service = service();
        List<PaddleSubscriptionState> listed = stubListed("unmatched", ApplyOutcome.UNMATCHED, 3);
        cachedIds();
        when(paddleApiClient.listLiveSubscriptions(PRO_PRICE_ID)).thenReturn(listed);

        service.syncAll();

        List<String> syncLogLines = output.getAll().lines()
                .filter(line -> line.contains("PaddleSubscriptionSyncService"))
                .toList();
        assertThat(syncLogLines).hasSize(1);
        assertThat(syncLogLines.get(0)).contains("INFO");
        assertThat(output.getAll()).doesNotContain("WARN").doesNotContain("ERROR");
    }

    // ── 트랜잭션 가드 ──
    // 이 클래스에 @Transactional이 붙으면 건마다 쓰는 applier(REQUIRED)가 바깥 트랜잭션에 합류한다. 그러면 한 건이
    // 예외를 던지는 순간 트랜잭션 전체가 rollback-only가 돼, 위의 "한 건 실패해도 계속" 테스트(mock이라 트랜잭션이 없다)가
    // 잡지 못하는 방식으로 앞서 반영한 건까지 되돌아간다. 또 Paddle을 기다리는 동안 DB 커넥션을 쥐게 된다.
    @Test
    @DisplayName("클래스·syncAll·refresh 어디에도 @Transactional이 붙어 있지 않다")
    void classAndMethodsHaveNoTransactionalAnnotation() throws NoSuchMethodException {
        Method syncAll = PaddleSubscriptionSyncService.class.getMethod("syncAll");
        Method refresh = PaddleSubscriptionSyncService.class.getMethod("refresh", Collection.class);

        assertThat(hasAnyTransactionalAnnotation(PaddleSubscriptionSyncService.class)).isFalse();
        assertThat(hasAnyTransactionalAnnotation(syncAll)).isFalse();
        assertThat(hasAnyTransactionalAnnotation(refresh)).isFalse();
    }

    // ── refresh: 결제 직전 재확인 ──

    @Test
    @DisplayName("refresh는 받은 ID들을 Paddle에서 조회해 하나씩 API 출처로 반영하고, 목록 조회·캐시 조회는 하지 않는다")
    void refreshAppliesEachLookedUpState() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState first = state("sub_1", "canceled", null);
        PaddleSubscriptionState second = state("sub_2", "active", RENEWED_PERIOD_ENDS_AT);
        when(paddleApiClient.listSubscriptionsByIds(sameIds("sub_1", "sub_2"))).thenReturn(List.of(first, second));
        stubApply(first, ApplyOutcome.APPLIED);
        stubApply(second, ApplyOutcome.APPLIED);

        service.refresh(List.of("sub_1", "sub_2"));

        verify(applier).apply(first, null, ApplySource.API);
        verify(applier).apply(second, null, ApplySource.API);
        verify(paddleApiClient, never()).listLiveSubscriptions(any());
        verifyNoInteractions(billingSubscriptionRepository);
    }

    @Test
    @DisplayName("refresh는 반영 실패를 삼키지 않고 그대로 던진다")
    void refreshPropagatesApplyFailure() {
        PaddleSubscriptionSyncService service = service();
        PaddleSubscriptionState state = state("sub_1", "active", PERIOD_ENDS_AT);
        IllegalStateException failure = new IllegalStateException("boom");
        when(paddleApiClient.listSubscriptionsByIds(sameIds("sub_1"))).thenReturn(List.of(state));
        when(applier.apply(state, null, ApplySource.API)).thenThrow(failure);

        assertThatThrownBy(() -> service.refresh(List.of("sub_1"))).isSameAs(failure);
    }

    @Test
    @DisplayName("refresh는 Paddle 조회 실패를 삼키지 않고 그대로 던지며 아무것도 반영하지 않는다")
    void refreshPropagatesPaddleFailure() {
        PaddleSubscriptionSyncService service = service();
        BadGatewayException failure = new BadGatewayException("Paddle subscription list request failed.");
        when(paddleApiClient.listSubscriptionsByIds(sameIds("sub_1"))).thenThrow(failure);

        assertThatThrownBy(() -> service.refresh(List.of("sub_1"))).isSameAs(failure);
        verifyNoInteractions(applier);
    }

    @Test
    @DisplayName("refresh가 조회에 넘기는 ID는 받은 ID 그대로다")
    void refreshPassesGivenIdsToLookup() {
        PaddleSubscriptionSyncService service = service();
        when(paddleApiClient.listSubscriptionsByIds(sameIds("sub_1", "sub_2"))).thenReturn(List.of());

        service.refresh(List.of("sub_1", "sub_2"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(paddleApiClient).listSubscriptionsByIds(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder("sub_1", "sub_2");
    }

    // ── 헬퍼 ──

    private PaddleSubscriptionSyncService service() {
        return service(properties("pdl_key", PRO_PRICE_ID));
    }

    private PaddleSubscriptionSyncService service(PaddleProperties properties) {
        return new PaddleSubscriptionSyncService(paddleApiClient, applier, billingSubscriptionRepository, properties);
    }

    private static PaddleProperties properties(String apiKey, String proPriceId) {
        return new PaddleProperties("whsec", Duration.ofSeconds(5), proPriceId, apiKey, "sandbox", "test_token");
    }

    // 캐시에서 종료되지 않은 상태(active·trialing·past_due·paused)인 구독 ID만 조회한다 — 엔티티를 읽지 않는다
    private void cachedIds(String... ids) {
        when(billingSubscriptionRepository.findSubscriptionIdsByStatusIn(argThat(this::isNonTerminalStatuses)))
                .thenReturn(List.of(ids));
    }

    private boolean isNonTerminalStatuses(Collection<String> statuses) {
        return statuses != null
                && statuses.size() == 4
                && new HashSet<>(statuses).equals(Set.of("active", "trialing", "past_due", "paused"));
    }

    // 순서와 무관하게 ID 구성이 같은 컬렉션과 매칭
    private Collection<String> sameIds(String... expected) {
        return argThat(actual -> actual != null
                && actual.size() == expected.length
                && new HashSet<>(actual).equals(Set.of(expected)));
    }

    // ID 조회가 (빈 목록이 아닌 인자로) 일어나지 않았음을 확정 — 빈 목록 호출은 클라이언트가 Paddle 없이 끝내므로 허용한다
    private void verifyNoLookupByIds() {
        verify(paddleApiClient, never()).listSubscriptionsByIds(argThat(ids -> ids != null && !ids.isEmpty()));
    }

    private void stubApply(PaddleSubscriptionState state, ApplyOutcome outcome) {
        when(applier.apply(state, null, ApplySource.API))
                .thenReturn(new ApplyResult(outcome, state.subscriptionId(), USER_ID));
    }

    // 같은 결과를 내는 목록 구독 count건을 만들고 applier 응답을 스텁한다
    private List<PaddleSubscriptionState> stubListed(String prefix, ApplyOutcome outcome, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> {
                    PaddleSubscriptionState state = state(prefix + "_" + i, "active", PERIOD_ENDS_AT);
                    stubApply(state, outcome);
                    return state;
                })
                .toList();
    }

    private List<PaddleSubscriptionState> stubFailing(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> {
                    PaddleSubscriptionState state = state(prefix + "_" + i, "active", PERIOD_ENDS_AT);
                    when(applier.apply(state, null, ApplySource.API)).thenThrow(new IllegalStateException("boom"));
                    return state;
                })
                .toList();
    }

    private void assertSummary(
            SyncSummary summary,
            int applied,
            int unchanged,
            int stale,
            int ignored,
            int unmatched,
            int failed,
            int notFound
    ) {
        assertThat(summary.applied()).as("applied").isEqualTo(applied);
        assertThat(summary.unchanged()).as("unchanged").isEqualTo(unchanged);
        assertThat(summary.stale()).as("stale").isEqualTo(stale);
        assertThat(summary.ignored()).as("ignored").isEqualTo(ignored);
        assertThat(summary.unmatched()).as("unmatched").isEqualTo(unmatched);
        assertThat(summary.failed()).as("failed").isEqualTo(failed);
        assertThat(summary.notFound()).as("notFound").isEqualTo(notFound);
    }

    private boolean hasAnyTransactionalAnnotation(AnnotatedElement element) {
        return element.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)
                || element.isAnnotationPresent(jakarta.transaction.Transactional.class);
    }

    private PaddleSubscriptionState state(String subscriptionId, String status, Instant periodEndsAt) {
        return new PaddleSubscriptionState(
                subscriptionId, status, "ctm_1", List.of(PRO_PRICE_ID), USER_ID.toString(),
                null, null, null, periodEndsAt, periodEndsAt, UPDATED_AT);
    }
}
