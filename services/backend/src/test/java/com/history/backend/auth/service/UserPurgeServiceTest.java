package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.UserPurgeProperties;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserPurgeService: 만료 사용자 배치 퍼지")
class UserPurgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");
    private static final UUID FIRST_USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID SECOND_USER_ID = UUID.fromString("801db2d0-f3dd-4dfc-ae2a-8ea12678ba59");
    private static final UUID THIRD_USER_ID = UUID.fromString("42d24fb1-13e7-4f81-8409-dc1c82bc5ec3");

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("만료 사용자 배치 단위로 전체 삭제")
    void purgeExpiredUsersDeletesAllCandidatesInBatches() {
        UserPurgeService service = userPurgeService(2);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(userRepository.findPurgeCandidateIds(
                eq(NOW.minus(Duration.ofDays(30))), any(Collection.class), any(Pageable.class)))
                .thenReturn(List.of(FIRST_USER_ID, SECOND_USER_ID))
                .thenReturn(List.of(THIRD_USER_ID))
                // 새 종료 조건은 "이번 회차 파기 0명"으로만 멈춘다. 두 번째 회차도 1명을 파기하므로
                // 세 번째 회차에서 빈 목록을 받아야 루프가 끝난다(옛 조건은 batchSize 불일치로 멈췄다).
                .thenReturn(List.of());

        int purgedCount = service.purgeExpiredUsers(NOW);

        assertThat(purgedCount).isEqualTo(3);
        verify(userRepository).deleteAllByIdInBatch(List.of(FIRST_USER_ID, SECOND_USER_ID));
        verify(userRepository).deleteAllByIdInBatch(List.of(THIRD_USER_ID));
    }

    @Test
    @DisplayName("사용자별로 자원 정리(releaseExternalResources) 성공 후에만 배치 삭제")
    void purgeExpiredUsersReleasesExternalResourcesBeforeDeletingBatch() {
        UserPurgeService service = userPurgeService(2);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(userRepository.findPurgeCandidateIds(
                eq(NOW.minus(Duration.ofDays(30))), any(Collection.class), any(Pageable.class)))
                .thenReturn(List.of(FIRST_USER_ID, SECOND_USER_ID))
                .thenReturn(List.of());

        service.purgeExpiredUsers(NOW);

        InOrder inOrder = inOrder(projectService, userRepository);
        inOrder.verify(projectService).releaseExternalResources(FIRST_USER_ID);
        inOrder.verify(projectService).releaseExternalResources(SECOND_USER_ID);
        inOrder.verify(userRepository).deleteAllByIdInBatch(List.of(FIRST_USER_ID, SECOND_USER_ID));
    }

    @Test
    @DisplayName("자원 정리가 실패한 사용자는 삭제 대상에서 빠지고 나머지는 계속 파기")
    void purgeExpiredUsersSkipsUserWhenResourceReleaseFails() {
        UserPurgeService service = userPurgeService(3);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(userRepository.findPurgeCandidateIds(
                eq(NOW.minus(Duration.ofDays(30))), any(Collection.class), any(Pageable.class)))
                .thenReturn(List.of(FIRST_USER_ID, SECOND_USER_ID, THIRD_USER_ID))
                .thenReturn(List.of());
        // 세 사용자를 모두 명시적으로 스텁한다. 하나만 스텁하면 나머지 인자로 호출될 때 Mockito가
        // strict stub 불일치(PotentialStubbingProblem)를 던지는데, 그것이 RuntimeException이라
        // 사용자별 실패를 삼키는 프로덕션 catch에 걸려 "정리 실패"로 오인된다.
        doNothing().when(projectService).releaseExternalResources(FIRST_USER_ID);
        doThrow(new BadGatewayException("Failed to delete project graph."))
                .when(projectService).releaseExternalResources(SECOND_USER_ID);
        doNothing().when(projectService).releaseExternalResources(THIRD_USER_ID);

        int purgedCount = service.purgeExpiredUsers(NOW);

        assertThat(purgedCount).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> deletedIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(userRepository).deleteAllByIdInBatch(deletedIdsCaptor.capture());
        assertThat(deletedIdsCaptor.getValue()).containsExactly(FIRST_USER_ID, THIRD_USER_ID);
    }

    @Test
    @DisplayName("선두 후보가 자원 정리에 실패해도 실패 id를 다음 조회에서 배제해 그 뒤 후보를 계속 파기한다")
    void purgeExpiredUsersPurgesCandidateBehindPersistentlyFailingLeadingCandidate() {
        UserPurgeService service = userPurgeService(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(userRepository.findPurgeCandidateIds(
                eq(NOW.minus(Duration.ofDays(30))), any(Collection.class), any(Pageable.class)))
                .thenReturn(List.of(FIRST_USER_ID))
                .thenReturn(List.of(SECOND_USER_ID))
                .thenReturn(List.of());
        doThrow(new BadGatewayException("Failed to release."))
                .when(projectService).releaseExternalResources(FIRST_USER_ID);
        doNothing().when(projectService).releaseExternalResources(SECOND_USER_ID);

        int purgedCount = service.purgeExpiredUsers(NOW);

        // 지금 구현(항상 0페이지만 조회하고 batchCount==0이면 즉시 루프 종료)이면 선두 FIRST_USER_ID의
        // 실패로 루프가 멈춰 SECOND_USER_ID는 영원히 조회조차 되지 않는다 — 아래 assertion이 그 회귀를 잡는다
        assertThat(purgedCount).isEqualTo(1);
        verify(userRepository).deleteAllByIdInBatch(List.of(SECOND_USER_ID));
        verify(userRepository, never()).deleteAllByIdInBatch(List.of(FIRST_USER_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> excludedIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository, atLeast(1)).findPurgeCandidateIds(
                eq(NOW.minus(Duration.ofDays(30))), excludedIdsCaptor.capture(), any(Pageable.class));
        // 두 번째 이후 조회는 첫 회차에서 실패한 FIRST_USER_ID를 배제 목록에 담아야 한다
        assertThat(excludedIdsCaptor.getAllValues()).anyMatch(excluded -> excluded.contains(FIRST_USER_ID));
    }

    @Test
    @DisplayName("전원 자원 정리 실패 시 무한 루프 없이 종료(회귀)")
    @Timeout(5)
    void purgeExpiredUsersStopsWithoutInfiniteLoopWhenAllReleasesFail() {
        UserPurgeService service = userPurgeService(2);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        // 항상 같은 후보 목록을 반환 — 옛 종료 조건(delete 수 == batchSize)을 쓰면 실패한 사용자가
        // 계속 후보로 잡혀 무한 루프가 된다.
        when(userRepository.findPurgeCandidateIds(
                eq(NOW.minus(Duration.ofDays(30))), any(Collection.class), any(Pageable.class)))
                .thenReturn(List.of(FIRST_USER_ID, SECOND_USER_ID));
        doThrow(new BadGatewayException("Failed to delete project graph."))
                .when(projectService).releaseExternalResources(any(UUID.class));

        int purgedCount = service.purgeExpiredUsers(NOW);

        assertThat(purgedCount).isZero();
        verify(userRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    @DisplayName("배치 크기를 페이지 크기로 사용")
    void purgeExpiredUsersUsesBatchSizeAsPageSize() {
        UserPurgeService service = userPurgeService(100);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(userRepository.findPurgeCandidateIds(any(), any(Collection.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.purgeExpiredUsers(NOW);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findPurgeCandidateIds(
                eq(NOW.minus(Duration.ofDays(30))), any(Collection.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    private UserPurgeService userPurgeService(int batchSize) {
        return new UserPurgeService(
                userRepository,
                projectService,
                new UserPurgeProperties(true, Duration.ofDays(30), "0 0 3 * * *", batchSize),
                transactionTemplate
        );
    }
}
