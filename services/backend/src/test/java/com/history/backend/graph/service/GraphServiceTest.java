package com.history.backend.graph.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.ForbiddenException;
import com.history.backend.graph.dto.GraphResponse;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GraphServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Mock
    private ProjectService projectService;

    @Mock
    private AiEngineGraphClient aiEngineGraphClient;

    @InjectMocks
    private GraphService graphService;

    @Test
    void fetchesGraphAfterOwnershipCheck() {
        GraphResponse expected = new GraphResponse(List.of(), List.of());
        when(aiEngineGraphClient.fetchOverview(PROJECT_ID, 50, "commit")).thenReturn(expected);

        GraphResponse result = graphService.getProjectGraph(USER_ID, PROJECT_ID, 50, "commit");

        assertThat(result).isSameAs(expected);
        // 소유권 검증이 ai-engine 호출보다 먼저 일어나야 한다
        InOrder inOrder = inOrder(projectService, aiEngineGraphClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineGraphClient).fetchOverview(PROJECT_ID, 50, "commit");
    }

    @Test
    void doesNotCallAiEngineWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        assertThatThrownBy(() -> graphService.getProjectGraph(USER_ID, PROJECT_ID, null, null))
                .isInstanceOf(ForbiddenException.class);

        // 인가 실패 시 ai-engine에 절대 요청이 가면 안 된다 (데이터 누출 차단)
        verifyNoInteractions(aiEngineGraphClient);
    }
}
