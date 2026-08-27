package com.history.backend.auth.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        // 이번 실행(cron 1회차) 동안 자원 정리에 실패한 id를 누적해 다음 조회에서 배제한다.
        // 조회는 항상 page 0만 보므로, 배제하지 않으면 선두 batch 전원이 실패했을 때 그 뒤
        // 후보는 조회조차 되지 않아 영원히 파기되지 않는다.
        Set<UUID> excludedIds = new HashSet<>();
        while (true) {
            List<UUID> candidateIds = fetchCandidateIds(cutoff, excludedIds);
            if (candidateIds.isEmpty()) {
                break;
            }
            // 조회 결과가 이미 전부 이번 실행에서 실패로 확인된 id뿐이면 더 진행할 수 없다는 뜻이다.
            // 정상적인 DB 조회는 excludedIds를 반영해 이런 결과를 주지 않지만, 방어적으로 무한
            // 루프를 막는다.
            if (excludedIds.containsAll(candidateIds)) {
                break;
            }
            purgedCount += purgeBatch(candidateIds, excludedIds);
        }
        return purgedCount;
    }

    private List<UUID> fetchCandidateIds(Instant cutoff, Set<UUID> excludedIds) {
        // 대량 조회로 인한 장기 트랜잭션을 피하기 위해 batch 단위로 트랜잭션 분리
        List<UUID> candidateIds = transactionTemplate.execute(status -> userRepository.findPurgeCandidateIds(
                cutoff,
                excludedIds,
                PageRequest.of(0, properties.batchSize())
        ));
        return candidateIds == null ? List.of() : candidateIds;
    }

    private int purgeBatch(List<UUID> candidateIds, Set<UUID> excludedIds) {
        // Neo4j 그래프 삭제·provider 권한 폐기는 외부 HTTP 호출이라 트랜잭션 밖에서 수행한다 —
        // 커넥션을 오래 점유하면 안 된다는 원칙은 ProjectService.deleteProject와 동일하다.
        List<UUID> purgedIds = new ArrayList<>();
        for (UUID userId : candidateIds) {
            try {
                projectService.releaseExternalResources(userId);
                purgedIds.add(userId);
            } catch (RuntimeException exception) {
                // 실패한 사용자는 이번 회차 삭제 대상에서 제외하고, 이번 실행의 다음 조회에서도
                // 배제한다 — 다음 cron 실행에서는 다시 후보로 잡혀 재시도된다.
                excludedIds.add(userId);
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
