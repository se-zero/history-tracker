package com.history.backend.auth.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.history.backend.auth.UserPurgeProperties;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// grace period 경과 탈퇴 사용자 hard delete
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPurgeService {

    private final UserRepository userRepository;
    private final ProjectService projectService;
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
            // 종료 조건은 "이번 회차 파기 0명"이다. 자원 정리에 실패한 사용자는 삭제되지 않아 다음
            // 조회에서도 계속 후보로 잡히므로, 옛 조건(파기 수 == batchSize)을 쓰면 실패한 사용자만
            // 남았을 때 무한 루프가 된다. 진행이 없으면 멈추고 다음 cron 실행에서 재시도한다.
        } while (batchCount > 0);
        return purgedCount;
    }

    private int purgeBatch(Instant cutoff) {
        // 대량 조회로 인한 장기 트랜잭션을 피하기 위해 batch 단위로 트랜잭션 분리
        List<UUID> candidateIds = transactionTemplate.execute(status -> userRepository.findPurgeCandidateIds(
                cutoff,
                PageRequest.of(0, properties.batchSize())
        ));
        if (candidateIds == null || candidateIds.isEmpty()) {
            return 0;
        }

        // Neo4j 그래프 삭제·provider 권한 폐기는 외부 HTTP 호출이라 트랜잭션 밖에서 수행한다 —
        // 커넥션을 오래 점유하면 안 된다는 원칙은 ProjectService.deleteProject와 동일하다.
        List<UUID> purgedIds = new ArrayList<>();
        for (UUID userId : candidateIds) {
            try {
                projectService.releaseExternalResources(userId);
                purgedIds.add(userId);
            } catch (RuntimeException exception) {
                // 실패한 사용자는 이번 회차 삭제 대상에서 제외 — 다음 cron 실행에서 재시도된다.
                log.warn("Failed to release external resources for user. userId={}, error={}",
                        userId, exception.getMessage());
            }
        }

        if (purgedIds.isEmpty()) {
            return 0;
        }

        transactionTemplate.execute(status -> {
            userRepository.deleteAllByIdInBatch(purgedIds);
            return null;
        });
        return purgedIds.size();
    }
}
