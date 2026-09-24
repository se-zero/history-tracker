package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.PlanExpiryProperties;
import com.history.backend.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanExpiryService: 만료된 PAID 플랜 배치 강등")
class PlanExpiryServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");
    private static final UUID FIRST_USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID SECOND_USER_ID = UUID.fromString("801db2d0-f3dd-4dfc-ae2a-8ea12678ba59");
    private static final UUID THIRD_USER_ID = UUID.fromString("42d24fb1-13e7-4f81-8409-dc1c82bc5ec3");

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlanService planService;

    @Test
    @DisplayName("후보 3건 모두 강등 처리하고 성공 건수를 반환한다")
    void downgradeExpiredPlansDowngradesAllCandidates() {
        PlanExpiryService service = service(100);
        when(userRepository.findExpiredPaidUserIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(FIRST_USER_ID, SECOND_USER_ID, THIRD_USER_ID));

        int downgradedCount = service.downgradeExpiredPlans(NOW);

        assertThat(downgradedCount).isEqualTo(3);
        verify(planService).downgradeToFree(FIRST_USER_ID);
        verify(planService).downgradeToFree(SECOND_USER_ID);
        verify(planService).downgradeToFree(THIRD_USER_ID);
    }

    @Test
    @DisplayName("후보가 없으면 PlanService와 상호작용하지 않고 0을 반환한다")
    void downgradeExpiredPlansReturnsZeroWhenNoCandidates() {
        PlanExpiryService service = service(100);
        when(userRepository.findExpiredPaidUserIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        int downgradedCount = service.downgradeExpiredPlans(NOW);

        assertThat(downgradedCount).isZero();
        verifyNoInteractions(planService);
    }

    @Test
    @DisplayName("가운데 후보가 RuntimeException을 던져도 나머지 후보는 계속 처리되고 성공 건수만 반환한다")
    void downgradeExpiredPlansContinuesAfterMiddleCandidateFails() {
        PlanExpiryService service = service(100);
        when(userRepository.findExpiredPaidUserIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(FIRST_USER_ID, SECOND_USER_ID, THIRD_USER_ID));
        // 첫 건·마지막 건이 아니라 가운데 건을 실패시킨다 — 실패 후 루프가 멈추는 결함은
        // 첫/마지막 건 실패로는 잡히지 않고, 세 번째 건까지 호출됐는지를 봐야 드러난다.
        // 세 건을 모두 명시적으로 스텁한다. 하나만 doThrow로 스텁하면 나머지 인자로 호출될 때
        // Mockito가 strict stub 불일치(PotentialStubbingProblem)를 던진다(UserPurgeServiceTest와 같은 이유).
        doNothing().when(planService).downgradeToFree(FIRST_USER_ID);
        doThrow(new RuntimeException("boom")).when(planService).downgradeToFree(SECOND_USER_ID);
        doNothing().when(planService).downgradeToFree(THIRD_USER_ID);

        int downgradedCount = service.downgradeExpiredPlans(NOW);

        assertThat(downgradedCount).isEqualTo(2);
        InOrder inOrder = inOrder(planService);
        inOrder.verify(planService).downgradeToFree(FIRST_USER_ID);
        inOrder.verify(planService).downgradeToFree(SECOND_USER_ID);
        inOrder.verify(planService).downgradeToFree(THIRD_USER_ID);
    }

    @Test
    @DisplayName("넘긴 시각과 batchSize를 그대로 페이지 0 조회에 전달한다")
    void downgradeExpiredPlansPassesGivenTimeAndPageZeroWithBatchSizeToRepository() {
        PlanExpiryService service = service(50);
        when(userRepository.findExpiredPaidUserIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.downgradeExpiredPlans(NOW);

        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findExpiredPaidUserIds(nowCaptor.capture(), pageableCaptor.capture());
        assertThat(nowCaptor.getValue()).isEqualTo(NOW);
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("인자 없는 downgradeExpiredPlans()는 현재 시각으로 리포지토리를 호출한다")
    void downgradeExpiredPlansNoArgDelegatesToInstantOverload() {
        PlanExpiryService service = service(100);
        when(userRepository.findExpiredPaidUserIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        int downgradedCount = service.downgradeExpiredPlans();

        assertThat(downgradedCount).isZero();
        verify(userRepository).findExpiredPaidUserIds(any(Instant.class), any(Pageable.class));
    }

    // ── 가드: @Transactional 부재 확인 ──
    // PlanExpiryService에 @Transactional이 붙으면(클래스든 메서드든) downgradeToFree(REQUIRED)가
    // 바깥 트랜잭션에 합류한다. 그러면 한 건이 예외를 던지는 순간 트랜잭션 전체가 rollback-only로
    // 표시되고, 루프에서 그 예외를 catch해도 소용없이 끝에 UnexpectedRollbackException으로 이미
    // 성공한 강등까지 전부 롤백된다. 위의 "가운데 실패" Mockito 테스트는 트랜잭션 없는 환경이라
    // 이 결함을 잡지 못하므로, 어노테이션 부재 자체를 리플렉션으로 확정하는 이 가드가 따로 필요하다.
    @Test
    @DisplayName("클래스·메서드 어디에도 @Transactional이 붙어 있지 않다")
    void classAndMethodsHaveNoTransactionalAnnotation() throws NoSuchMethodException {
        assertThat(hasAnyTransactionalAnnotation(PlanExpiryService.class)).isFalse();

        Method downgradeExpiredPlansNoArg = PlanExpiryService.class.getMethod("downgradeExpiredPlans");
        Method downgradeExpiredPlansWithInstant =
                PlanExpiryService.class.getMethod("downgradeExpiredPlans", Instant.class);
        assertThat(hasAnyTransactionalAnnotation(downgradeExpiredPlansNoArg)).isFalse();
        assertThat(hasAnyTransactionalAnnotation(downgradeExpiredPlansWithInstant)).isFalse();
    }

    private boolean hasAnyTransactionalAnnotation(AnnotatedElement element) {
        return element.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)
                || element.isAnnotationPresent(jakarta.transaction.Transactional.class);
    }

    private PlanExpiryService service(int batchSize) {
        return new PlanExpiryService(
                userRepository,
                planService,
                new PlanExpiryProperties(true, "0 0 4 * * *", batchSize)
        );
    }
}
