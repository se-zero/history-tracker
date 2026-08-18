package com.history.backend.clickup.dto;

import java.util.List;

// GET /folder/{id}/list, GET /space/{id}/list(folderless) 공통 응답 형태 — {"lists": [{id,name}, ...]}
public record ClickUpListsResponse(
        List<Item> lists
) {

    public record Item(String id, String name) {
    }
}
