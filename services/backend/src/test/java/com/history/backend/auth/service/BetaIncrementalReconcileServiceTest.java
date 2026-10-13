package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("BetaIncrementalReconcileService: FREE 사용자 연동의 증분 수집 플래그 정리")
class BetaIncrementalReconcileServiceTest {

    private static final UUID FIRST_USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID SECOND_USER_ID = UUID.fromString("801db2d0-f3dd-4dfc-ae2a-8ea12678ba59");
    private static final UUID THIRD_USER_ID = UUID.fromString("42d24fb1-13e7-4f81-8409-dc1c82bc5ec3");

    @Mock
    private PlanService planService;

    @Test
    @DisplayName("가운데 후보가 RuntimeException을 던져도 나머지 후보는 계속 처리되고 true 반환 건수만 센다")
    void reconcileContinuesAfterMiddleCandidateFails() {
        BetaIncrementalReconcileService service = new BetaIncrementalReconcileService(planService);
        when(planService.findIncrementalReconcileCandidates())
                .thenReturn(List.of(FIRST_USER_ID, SECOND_USER_ID, THIRD_USER_ID));
        // 첫/마지막 건이 아니라 가운데 건을 실패시킨다 — 루프가 첫 예외에서 멈추는 결함은 세 번째 호출 여부로 드러난다.
        when(planService.reconcileFreeIncremental(FIRST_USER_ID)).thenReturn(true);
        when(planService.reconcileFreeIncremental(SECOND_USER_ID)).thenThrow(new RuntimeException("boom"));
        when(planService.reconcileFreeIncremental(THIRD_USER_ID)).thenReturn(true);

        int reconciledCount = service.reconcile();

        assertThat(reconciledCount).isEqualTo(2);
        InOrder inOrder = inOrder(planService);
        inOrder.verify(planService).reconcileFreeIncremental(FIRST_USER_ID);
        inOrder.verify(planService).reconcileFreeIncremental(SECOND_USER_ID);
        inOrder.verify(planService).reconcileFreeIncremental(THIRD_USER_ID);
    }

    @Test
    @DisplayName("재확인에서 건너뛴 건(false 반환)은 성공 건수로 세지 않는다")
    void reconcileDoesNotCountCandidateSkippedByRecheck() {
        BetaIncrementalReconcileService service = new BetaIncrementalReconcileService(planService);
        when(planService.findIncrementalReconcileCandidates())
                .thenReturn(List.of(FIRST_USER_ID, SECOND_USER_ID, THIRD_USER_ID));
        when(planService.reconcileFreeIncremental(FIRST_USER_ID)).thenReturn(true);
        when(planService.reconcileFreeIncremental(SECOND_USER_ID)).thenReturn(false);
        when(planService.reconcileFreeIncremental(THIRD_USER_ID)).thenReturn(false);

        int reconciledCount = service.reconcile();

        assertThat(reconciledCount).isEqualTo(1);
    }

    @Test
    @DisplayName("후보가 없으면 정리를 호출하지 않고 0을 반환한다")
    void reconcileReturnsZeroWhenNoCandidates() {
        BetaIncrementalReconcileService service = new BetaIncrementalReconcileService(planService);
        when(planService.findIncrementalReconcileCandidates()).thenReturn(List.of());

        int reconciledCount = service.reconcile();

        assertThat(reconciledCount).isZero();
        verify(planService).findIncrementalReconcileCandidates();
        verifyNoMoreInteractions(planService);
    }

    // ── 가드: @Transactional 부재 확인 ──
    // PlanExpiryService와 같은 이유 — 붙으면 PlanService.reconcileFreeIncremental(REQUIRED)이 바깥 트랜잭션에
    // 합류해 한 건 실패가 전체를 rollback-only로 만들고, 이미 정리된 사용자까지 전부 되돌아간다.
    // Mockito 테스트는 트랜잭션이 없는 환경이라 이를 못 잡으므로 리플렉션으로 부재를 확정한다.
    @Test
    @DisplayName("클래스·메서드 어디에도 @Transactional이 붙어 있지 않다")
    void classAndMethodsHaveNoTransactionalAnnotation() throws NoSuchMethodException {
        assertThat(hasAnyTransactionalAnnotation(BetaIncrementalReconcileService.class)).isFalse();

        Method reconcile = BetaIncrementalReconcileService.class.getMethod("reconcile");
        assertThat(hasAnyTransactionalAnnotation(reconcile)).isFalse();
    }

    private boolean hasAnyTransactionalAnnotation(AnnotatedElement element) {
        return element.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)
                || element.isAnnotationPresent(jakarta.transaction.Transactional.class);
    }
}
