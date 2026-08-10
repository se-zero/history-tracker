package com.history.backend.googlechat.dto;

import java.util.List;

// spaces.list 응답. spaceType 필터는 요청 쪽(GoogleChatClient)에서 spaceType = "SPACE"로 걸어
// DM·그룹챗을 미리 제외하므로, 여기 담기는 항목은 이름 있는 스페이스뿐이다.
public record GoogleChatSpaceListResponse(
        List<GoogleChatSpace> spaces,
        String nextPageToken
) {

    public record GoogleChatSpace(
            // "spaces/{id}" 리소스 이름 원문 — 수집(GET /v1/{space_id}/messages)이 이 값을 그대로 쓴다
            String name,
            String displayName
    ) {
    }
}
