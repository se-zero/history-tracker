package com.history.backend.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("WorkspacePathNormalizer: 작업 폴더 절대 경로 정규화")
class WorkspacePathNormalizerTest {

    @Test
    @DisplayName("POSIX 절대 경로 허용")
    void normalizeAcceptsPosixAbsolutePath() {
        assertThat(WorkspacePathNormalizer.normalize("/home/me/repo")).isEqualTo("/home/me/repo");
    }

    @Test
    @DisplayName("드라이브 문자 + 역슬래시 경로 허용")
    void normalizeAcceptsWindowsDrivePathWithBackslash() {
        assertThat(WorkspacePathNormalizer.normalize("C:\\git\\repo")).isEqualTo("C:\\git\\repo");
    }

    @Test
    @DisplayName("드라이브 문자 + 슬래시 경로 허용")
    void normalizeAcceptsWindowsDrivePathWithSlash() {
        assertThat(WorkspacePathNormalizer.normalize("C:/git/repo")).isEqualTo("C:/git/repo");
    }

    @Test
    @DisplayName("UNC 경로 허용")
    void normalizeAcceptsUncPath() {
        assertThat(WorkspacePathNormalizer.normalize("\\\\server\\share\\repo")).isEqualTo("\\\\server\\share\\repo");
    }

    @Test
    @DisplayName("끝의 구분자 제거")
    void normalizeStripsTrailingSeparator() {
        assertThat(WorkspacePathNormalizer.normalize("/home/me/repo/")).isEqualTo("/home/me/repo");
        assertThat(WorkspacePathNormalizer.normalize("C:\\git\\repo\\")).isEqualTo("C:\\git\\repo");
        assertThat(WorkspacePathNormalizer.normalize("\\\\server\\share\\")).isEqualTo("\\\\server\\share");
    }

    @Test
    @DisplayName("루트 자체는 그대로 둔다")
    void normalizeKeepsRootPaths() {
        assertThat(WorkspacePathNormalizer.normalize("/")).isEqualTo("/");
        assertThat(WorkspacePathNormalizer.normalize("C:\\")).isEqualTo("C:\\");
        assertThat(WorkspacePathNormalizer.normalize("C:/")).isEqualTo("C:/");
    }

    @Test
    @DisplayName("앞뒤 공백 제거")
    void normalizeTrimsSurroundingWhitespace() {
        assertThat(WorkspacePathNormalizer.normalize("  /home/me/repo  ")).isEqualTo("/home/me/repo");
        assertThat(WorkspacePathNormalizer.normalize("\tC:\\git\\repo\\ \n")).isEqualTo("C:\\git\\repo");
    }

    @Test
    @DisplayName("대소문자와 구분자 종류는 바꾸지 않는다")
    void normalizePreservesCaseAndSeparatorKind() {
        String backslash = WorkspacePathNormalizer.normalize("C:\\Git\\Repo");
        String slash = WorkspacePathNormalizer.normalize("c:/git/repo");

        assertThat(backslash).isEqualTo("C:\\Git\\Repo");
        assertThat(slash).isEqualTo("c:/git/repo");
        assertThat(backslash).isNotEqualTo(slash);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("null·빈 값·공백만은 거부")
    void normalizeRejectsBlank(String raw) {
        assertThatThrownBy(() -> WorkspacePathNormalizer.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"repo", "./repo", "~/repo", "C:repo"})
    @DisplayName("상대 경로는 거부")
    void normalizeRejectsRelativePath(String raw) {
        assertThatThrownBy(() -> WorkspacePathNormalizer.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정규화 뒤 정확히 1024바이트(UTF-8)는 통과")
    void normalizeAcceptsExactlyMaxLength() {
        String path = "/" + "a".repeat(1023);

        assertThat(WorkspacePathNormalizer.normalize(path)).isEqualTo(path).hasSize(1024);
    }

    @Test
    @DisplayName("정규화 뒤 1025바이트는 거부")
    void normalizeRejectsOverMaxLength() {
        String path = "/" + "a".repeat(1024);

        assertThatThrownBy(() -> WorkspacePathNormalizer.normalize(path))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("길이는 정규화 뒤 기준 — 공백·끝 구분자를 떼면 1024바이트가 되는 입력은 통과")
    void normalizeMeasuresLengthAfterNormalization() {
        String path = "/" + "a".repeat(1023);

        assertThat(WorkspacePathNormalizer.normalize(" " + path + "/ ")).isEqualTo(path);
    }

    // 상한은 글자 수가 아니라 UTF-8 바이트다 — PK 인덱스 한도가 바이트 기준이라, 한글(3바이트)은 글자 수로 세면 넘친다.
    @Test
    @DisplayName("한글 경로: 341자(1024바이트)는 통과")
    void normalizeAcceptsMultibytePathWithinByteLimit() {
        String path = "/" + "가".repeat(341);

        assertThat(WorkspacePathNormalizer.normalize(path)).isEqualTo(path);
    }

    @Test
    @DisplayName("한글 경로: 글자 수는 1024자 미만이어도 1024바이트를 넘으면 거부")
    void normalizeRejectsMultibytePathOverByteLimit() {
        String path = "/" + "가".repeat(342);

        assertThat(path.length()).isLessThan(1024);
        assertThatThrownBy(() -> WorkspacePathNormalizer.normalize(path))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
