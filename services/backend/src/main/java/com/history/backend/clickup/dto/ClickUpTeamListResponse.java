package com.history.backend.clickup.dto;

import java.util.List;

// GET /team 응답 형태 — {"teams": [{id,name}, ...]}
public record ClickUpTeamListResponse(
        List<Item> teams
) {

    public record Item(String id, String name) {
    }
}
