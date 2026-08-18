package com.history.backend.googlechat.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.googlechat.dto.GoogleChatSpaceListResponse;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.integration.service.GoogleChatTokenService;
import com.history.backend.integration.service.IntegrationSelectionFlow;
import com.history.backend.integration.service.SelectionOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Google Chat은 스페이스 1단 선택이다. 단계 키는 pipeline-worker가 수집할 때 읽는 external_ref 키와
// 같아야 하므로 여기서 정한다.
@Service
@RequiredArgsConstructor
public class GoogleChatSelectionFlow implements IntegrationSelectionFlow {

    public static final String SPACE_ID = "space_id";
    public static final String SPACE_NAME = "space_name";

    private final GoogleChatClient client;
    private final GoogleChatTokenService tokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.GOOGLE_CHAT;
    }

    @Override
    public List<SelectionStep> steps() {
        return List.of(SelectionStep.required(SPACE_ID, SPACE_NAME, "스페이스"));
    }

    @Override
    public List<SelectionOption> options(UUID projectId, String stepKey, Map<String, String> selected) {
        if (!SPACE_ID.equals(stepKey)) {
            throw new BadRequestException("Unknown Google Chat selection step: " + stepKey);
        }
        // 저장된 연동의 access token을 쓴다 — 필요하면 GoogleChatTokenService가 갱신한다
        String accessToken = tokenService.getAccessToken(projectId);
        return client.listSpaces(accessToken).stream()
                .map(space -> new SelectionOption(space.name(), displayLabel(space)))
                .toList();
    }

    // displayName이 비어 있는 스페이스도 있을 수 있어(이론상) 리소스 이름으로 폴백한다
    private static String displayLabel(GoogleChatSpaceListResponse.GoogleChatSpace space) {
        String displayName = space.displayName();
        return displayName == null || displayName.isBlank() ? space.name() : displayName;
    }
}
