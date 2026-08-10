package com.history.backend.asana.dto;

import java.util.List;

// GET /workspaces, GET /projects 공통 응답 형태 — {"data": [{gid,name}, ...]}
public record AsanaListResponse(
        List<Item> data
) {

    public record Item(String gid, String name) {
    }
}
