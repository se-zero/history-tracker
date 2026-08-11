package com.history.backend.clickup.dto;

import java.util.List;

// GET /team/{id}/space 응답 형태 — {"spaces": [{id,name}, ...]}
public record ClickUpSpaceListResponse(
        List<Item> spaces
) {

    public record Item(String id, String name) {
    }
}
