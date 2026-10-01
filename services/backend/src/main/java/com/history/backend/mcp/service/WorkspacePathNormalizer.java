package com.history.backend.mcp.service;

import java.nio.charset.StandardCharsets;

/**
 * MCP 클라이언트가 넘긴 작업 폴더 경로를 연결 키로 쓸 수 있게 다듬는다.
 * 대소문자·구분자 종류는 바꾸지 않는다 — 파일시스템마다 규칙이 달라 서버가 같은 경로라고 단정할 수 없다.
 */
public final class WorkspacePathNormalizer {

    // PK 인덱스(user_id, workspace_path)의 항목 크기 한도(Postgres btree 약 2700바이트)를 넘지 않도록 막는 상한.
    // 한도가 바이트 기준이라 글자 수가 아니라 UTF-8 바이트로 센다 — 한글은 글자당 3바이트다.
    private static final int MAX_BYTES = 1024;

    private WorkspacePathNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Workspace path must not be blank.");
        }
        String path = raw.strip();
        int rootLength = rootLength(path);
        int end = path.length();
        while (end > rootLength && isSeparator(path.charAt(end - 1))) {
            end--;
        }
        path = path.substring(0, end);
        if (path.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Workspace path is too long.");
        }
        return path;
    }

    // 절대 경로 4형식(POSIX, 드라이브+역슬래시, 드라이브+슬래시, UNC)만 허용하고 루트 길이를 돌려준다
    private static int rootLength(String path) {
        if (path.startsWith("\\\\")) {
            return 2;
        }
        if (path.charAt(0) == '/') {
            return 1;
        }
        if (path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
                && isSeparator(path.charAt(2))) {
            return 3;
        }
        throw new IllegalArgumentException("Workspace path must be absolute.");
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }
}
