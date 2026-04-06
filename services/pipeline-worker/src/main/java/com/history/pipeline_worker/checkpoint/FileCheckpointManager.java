package com.history.pipeline_worker.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

@Slf4j
@Service
public class FileCheckpointManager {

    private final Path checkpointPath;
    private final ObjectMapper objectMapper;
    private CheckpointData cached;
    private final Object lock = new Object();

    public FileCheckpointManager(
            @Value("${app.checkpoint.path:./checkpoint.json}") String path,
            ObjectMapper objectMapper) {
        this.checkpointPath = Path.of(path);
        this.objectMapper = objectMapper;
        this.cached = load();
    }

    public CheckpointData load() {
        if (!Files.exists(checkpointPath)) {
            log.info("체크포인트 파일 없음 ({}), 전체 수집 시작", checkpointPath);
            return new CheckpointData();
        }
        try {
            CheckpointData data = objectMapper.readValue(checkpointPath.toFile(), CheckpointData.class);
            log.info("체크포인트 로드 완료: {}", checkpointPath);
            return data;
        } catch (IOException e) {
            log.warn("체크포인트 파일 읽기 실패, 전체 수집 시작: {}", e.getMessage());
            return new CheckpointData();
        }
    }

    public void updateGitHubCommits(Instant scannedAt) {
        synchronized (lock) {
            cached.github.commitsScannedAt = scannedAt;
            save(cached);
        }
    }

    public void updateGitHubPullRequests(Instant scannedAt) {
        synchronized (lock) {
            cached.github.pullRequestsScannedAt = scannedAt;
            save(cached);
        }
    }

    public void updateGitHubIssues(Instant scannedAt) {
        synchronized (lock) {
            cached.github.issuesScannedAt = scannedAt;
            save(cached);
        }
    }

    public void updateSlack(Instant scannedAt) {
        synchronized (lock) {
            cached.slack.lastScannedAt = scannedAt;
            save(cached);
        }
    }

    public void updateJira(Instant scannedAt) {
        synchronized (lock) {
            cached.jira.lastScannedAt = scannedAt;
            save(cached);
        }
    }

    public CheckpointData getCached() {
        return cached;
    }

    private void save(CheckpointData data) {
        try {
            Path tmp = checkpointPath.resolveSibling(checkpointPath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            Files.move(tmp, checkpointPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("체크포인트 저장 실패: {}", e.getMessage());
        }
    }
}
