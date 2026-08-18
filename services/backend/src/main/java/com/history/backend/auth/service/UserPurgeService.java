package com.history.backend.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.UserPurgeProperties;
import com.history.backend.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// grace period 경과 탈퇴 사용자 hard delete
@Service
@RequiredArgsConstructor
public class UserPurgeService {

    private final UserRepository userRepository;
    private final UserPurgeProperties properties;
    private final TransactionTemplate transactionTemplate;

    public int purgeExpiredUsers() {
        return purgeExpiredUsers(Instant.now());
    }

    public int purgeExpiredUsers(Instant now) {
        Instant cutoff = now.minus(properties.gracePeriod());
        int purgedCount = 0;
        int batchCount;
        do {
            batchCount = purgeBatch(cutoff);
            purgedCount += batchCount;
        } while (batchCount == properties.batchSize());
        return purgedCount;
    }

    private int purgeBatch(Instant cutoff) {
        // 대량 삭제로 인한 장기 트랜잭션을 피하기 위해 batch 단위로 트랜잭션 분리
        return transactionTemplate.execute(status -> {
            List<UUID> userIds = userRepository.findPurgeCandidateIds(
                    cutoff,
                    PageRequest.of(0, properties.batchSize())
            );
            if (userIds.isEmpty()) {
                return 0;
            }
            userRepository.deleteAllByIdInBatch(userIds);
            return userIds.size();
        });
    }
}
