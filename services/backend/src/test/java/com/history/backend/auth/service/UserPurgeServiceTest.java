package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.UserPurgeProperties;
import com.history.backend.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("만료 사용자 배치 단위로 전체 삭제")
    void purgeExpiredUsersDeletesAllCandidatesInBatches() {
        UserPurgeService service = userPurgeService(2);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(userRepository.findPurgeCandidateIds(eq(NOW.minus(Duration.ofDays(30))), any(Pageable.class)))
                .thenReturn(List.of(FIRST_USER_ID, SECOND_USER_ID))
                .thenReturn(List.of(THIRD_USER_ID));

        int purgedCount = service.purgeExpiredUsers(NOW);

        assertThat(purgedCount).isEqualTo(3);
        verify(userRepository).deleteAllByIdInBatch(List.of(FIRST_USER_ID, SECOND_USER_ID));
        verify(userRepository).deleteAllByIdInBatch(List.of(THIRD_USER_ID));
    }

    @Test
    @DisplayName("배치 크기를 페이지 크기로 사용")
    void purgeExpiredUsersUsesBatchSizeAsPageSize() {
        UserPurgeService service = userPurgeService(100);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(userRepository.findPurgeCandidateIds(any(), any(Pageable.class))).thenReturn(List.of());

        service.purgeExpiredUsers(NOW);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findPurgeCandidateIds(eq(NOW.minus(Duration.ofDays(30))), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    private UserPurgeService userPurgeService(int batchSize) {
        return new UserPurgeService(
                userRepository,
                new UserPurgeProperties(true, Duration.ofDays(30), "0 0 3 * * *", batchSize),
                transactionTemplate
        );
    }
}
