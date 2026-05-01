package com.history.pipeline_worker.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FileCheckpointManager: checkpoint.json 저장·로드·원자적 쓰기·캐시 갱신 검증.
 * @TempDir로 실제 파일 IO를 격리 — Spring 컨텍스트 불필요.
 */
class FileCheckpointManagerTest {

    // Instant 직렬화를 위해 JavaTimeModule 등록
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // ─── load() ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("파일 없을 때 load() → 빈 CheckpointData 반환 (전체 수집 모드)")
    void load_fileNotExists_returnsEmptyCheckpointData(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("checkpoint.json");
        FileCheckpointManager manager = new FileCheckpointManager(nonExistent.toString(), objectMapper);

        CheckpointData data = manager.getCached();

        // 최초 실행: 모든 타임스탬프 null
        assertThat(data.github.commitsScannedAt).isNull();
        assertThat(data.github.pullRequestsScannedAt).isNull();
        assertThat(data.github.issuesScannedAt).isNull();
        assertThat(data.slack.lastScannedAt).isNull();
        assertThat(data.jira.lastScannedAt).isNull();
    }

    @Test
    @DisplayName("손상된 JSON 파일 → load() 예외 없이 빈 CheckpointData 반환")
    void load_corruptedFile_returnsEmptyCheckpointData(@TempDir Path tempDir) throws IOException {
        Path corrupt = tempDir.resolve("checkpoint.json");
        Files.writeString(corrupt, "{ this is not valid json }");

        FileCheckpointManager manager = new FileCheckpointManager(corrupt.toString(), objectMapper);

        // 파싱 실패 → 전체 수집 모드로 폴백
        assertThat(manager.getCached().github.commitsScannedAt).isNull();
    }

    // ─── 저장 후 재로드 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateGitHubCommits 호출 후 파일에서 재로드 → 동일 타임스탬프")
    void updateGitHubCommits_savedAndReloaded_sameTimestamp(@TempDir Path tempDir) {
        Path checkpointFile = tempDir.resolve("checkpoint.json");
        FileCheckpointManager manager = new FileCheckpointManager(checkpointFile.toString(), objectMapper);

        Instant now = Instant.parse("2024-04-25T10:00:00Z");
        manager.updateGitHubCommits(now);

        // 새 인스턴스로 재로드
        FileCheckpointManager reloaded = new FileCheckpointManager(checkpointFile.toString(), objectMapper);
        assertThat(reloaded.getCached().github.commitsScannedAt).isEqualTo(now);
    }

    @Test
    @DisplayName("updateGitHubPullRequests 저장·재로드 검증")
    void updateGitHubPullRequests_savedAndReloaded(@TempDir Path tempDir) {
        Path checkpointFile = tempDir.resolve("checkpoint.json");
        FileCheckpointManager manager = new FileCheckpointManager(checkpointFile.toString(), objectMapper);

        Instant now = Instant.parse("2024-04-25T11:00:00Z");
        manager.updateGitHubPullRequests(now);

        FileCheckpointManager reloaded = new FileCheckpointManager(checkpointFile.toString(), objectMapper);
        assertThat(reloaded.getCached().github.pullRequestsScannedAt).isEqualTo(now);
    }

    @Test
    @DisplayName("updateSlack 저장·재로드 검증")
    void updateSlack_savedAndReloaded(@TempDir Path tempDir) {
        Path checkpointFile = tempDir.resolve("checkpoint.json");
        FileCheckpointManager manager = new FileCheckpointManager(checkpointFile.toString(), objectMapper);

        Instant now = Instant.parse("2024-04-25T12:00:00Z");
        manager.updateSlack(now);

        FileCheckpointManager reloaded = new FileCheckpointManager(checkpointFile.toString(), objectMapper);
        assertThat(reloaded.getCached().slack.lastScannedAt).isEqualTo(now);
    }

    @Test
    @DisplayName("updateJira 저장·재로드 검증")
    void updateJira_savedAndReloaded(@TempDir Path tempDir) {
        Path checkpointFile = tempDir.resolve("checkpoint.json");
        FileCheckpointManager manager = new FileCheckpointManager(checkpointFile.toString(), objectMapper);

        Instant now = Instant.parse("2024-04-25T13:00:00Z");
        manager.updateJira(now);

        FileCheckpointManager reloaded = new FileCheckpointManager(checkpointFile.toString(), objectMapper);
        assertThat(reloaded.getCached().jira.lastScannedAt).isEqualTo(now);
    }

    // ─── 캐시 갱신 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateGitHubCommits 호출 즉시 getCached()에 반영")
    void updateGitHubCommits_cacheUpdatedImmediately(@TempDir Path tempDir) {
        Path checkpointFile = tempDir.resolve("checkpoint.json");
        FileCheckpointManager manager = new FileCheckpointManager(checkpointFile.toString(), objectMapper);

        Instant now = Instant.parse("2024-04-25T14:00:00Z");
        manager.updateGitHubCommits(now);

        // 파일 재로드 없이 캐시에서 바로 확인
        assertThat(manager.getCached().github.commitsScannedAt).isEqualTo(now);
    }

    @Test
    @DisplayName("여러 타입 독립 업데이트 — 다른 타입에 영향 없음")
    void updateMultipleTypes_independentFields(@TempDir Path tempDir) {
        Path checkpointFile = tempDir.resolve("checkpoint.json");
        FileCheckpointManager manager = new FileCheckpointManager(checkpointFile.toString(), objectMapper);

        Instant commitsAt = Instant.parse("2024-04-01T00:00:00Z");
        Instant prsAt    = Instant.parse("2024-04-02T00:00:00Z");
        Instant issuesAt = Instant.parse("2024-04-03T00:00:00Z");

        manager.updateGitHubCommits(commitsAt);
        manager.updateGitHubPullRequests(prsAt);
        manager.updateGitHubIssues(issuesAt);

        CheckpointData data = manager.getCached();
        assertThat(data.github.commitsScannedAt).isEqualTo(commitsAt);
        assertThat(data.github.pullRequestsScannedAt).isEqualTo(prsAt);
        assertThat(data.github.issuesScannedAt).isEqualTo(issuesAt);
        // Slack / Jira 영향 없음
        assertThat(data.slack.lastScannedAt).isNull();
        assertThat(data.jira.lastScannedAt).isNull();
    }

    // ─── 원자적 쓰기 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("저장 후 .tmp 임시 파일이 남지 않아야 함 (ATOMIC_MOVE 확인)")
    void save_atomicMove_noTmpFileRemains(@TempDir Path tempDir) {
        Path checkpointFile = tempDir.resolve("checkpoint.json");
        FileCheckpointManager manager = new FileCheckpointManager(checkpointFile.toString(), objectMapper);

        manager.updateGitHubCommits(Instant.now());

        Path tmpFile = tempDir.resolve("checkpoint.json.tmp");
        assertThat(tmpFile).doesNotExist();
        assertThat(checkpointFile).exists();
    }

    @Test
    @DisplayName("체크포인트 저장 실패 시 예외 전파")
    void save_failure_throwsException(@TempDir Path tempDir) throws IOException {
        Path notDirectory = tempDir.resolve("not-a-dir");
        Files.writeString(notDirectory, "file");
        Path checkpointFile = notDirectory.resolve("checkpoint.json");
        FileCheckpointManager manager = new FileCheckpointManager(checkpointFile.toString(), objectMapper);

        assertThatThrownBy(() -> manager.updateJira(Instant.parse("2024-04-25T15:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("체크포인트 저장 실패");
    }

    @Test
    @DisplayName("체크포인트 저장 실패 시 캐시를 이전 값으로 롤백")
    void save_failure_rollsBackCachedValue(@TempDir Path tempDir) throws IOException {
        Instant previous = Instant.parse("2024-04-25T15:00:00Z");
        Path notDirectory = tempDir.resolve("not-a-dir");
        Files.writeString(notDirectory, "file");
        FileCheckpointManager failingManager = new FileCheckpointManager(
                notDirectory.resolve("checkpoint.json").toString(),
                objectMapper
        );
        failingManager.getCached().jira.lastScannedAt = previous;

        assertThatThrownBy(() -> failingManager.updateJira(Instant.parse("2024-04-25T16:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(failingManager.getCached().jira.lastScannedAt).isEqualTo(previous);
    }

    @Test
    @DisplayName("기존 .tmp 파일이 있어도 다음 저장 성공 시 제거")
    void save_existingTmpFile_overwrittenAndRemoved(@TempDir Path tempDir) throws IOException {
        Path checkpointFile = tempDir.resolve("checkpoint.json");
        Path tmpFile = tempDir.resolve("checkpoint.json.tmp");
        Files.writeString(tmpFile, "stale");

        FileCheckpointManager manager = new FileCheckpointManager(checkpointFile.toString(), objectMapper);
        manager.updateGitHubCommits(Instant.parse("2024-04-25T17:00:00Z"));

        assertThat(tmpFile).doesNotExist();
        assertThat(checkpointFile).exists();
    }
}
