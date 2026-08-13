package com.history.backend.clickup.dto;

import java.util.List;

// GET /space/{id}/folder 응답 형태 — {"folders": [{id,name}, ...]}
public record ClickUpFolderListResponse(
        List<Item> folders
) {

    public record Item(String id, String name) {
    }
}
