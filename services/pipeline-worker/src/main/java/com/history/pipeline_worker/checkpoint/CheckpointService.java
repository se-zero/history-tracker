package com.history.pipeline_worker.checkpoint;

import com.history.pipeline_worker.collection.CollectionProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * (project, provider, cursor_key) 커서 저장소 경계.
 *
 * <p>cursor_key는 provider가 소유한다 — 어떤 키를 몇 개 쓰는지는 수집 구조에 따라 다르므로
 * (GitHub은 타입별 3개, Jira·Slack은 1개) 이 클래스는 키를 해석하지 않는다.</p>
 */
@Service
public class CheckpointService {

    private final CheckpointRepository repository;

    public CheckpointService(CheckpointRepository repository) {
        this.repository = repository;
    }

    public Map<String, Instant> loadCursors(String projectId, CollectionProvider provider) {
        Map<String, Instant> cursors = new HashMap<>();
        for (CheckpointRepository.CheckpointRow row :
                repository.findAllByProjectIdAndProvider(UUID.fromString(projectId), provider.value())) {
            cursors.put(row.cursorKey(), row.cursorValue());
        }
        return cursors;
    }

    public void updateCursor(String projectId, CollectionProvider provider, String cursorKey, Instant cursorValue) {
        if (cursorValue != null) {
            repository.upsertCursor(UUID.fromString(projectId), provider.value(), cursorKey, cursorValue);
        }
    }

    // provider가 자기 키의 생애를 관리하기 위한 삭제(예: Slack이 삭제된 채널의 고아 커서 정리) — cursor_key 해석은 여전히 provider 소유다.
    public void deleteCursor(String projectId, CollectionProvider provider, String cursorKey) {
        repository.deleteCursor(UUID.fromString(projectId), provider.value(), cursorKey);
    }
}
