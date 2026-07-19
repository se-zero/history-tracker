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
import com.history.backend.graph.dto.ActorListResponse;
import com.history.backend.graph.dto.ActorMergeRequest;
import com.history.backend.graph.dto.ActorMergeResponse;
import com.history.backend.graph.dto.ActorRenameRequest;
import com.history.backend.graph.dto.ActorRenameResponse;
import com.history.backend.graph.dto.ActorSplitRequest;
import com.history.backend.graph.dto.ActorUnmergeRequest;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActorService: Actor 수동 병합·분리 프록시")
class ActorServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Mock
    private ProjectService projectService;

    @Mock
    private AiEngineActorClient aiEngineActorClient;

    @InjectMocks
    private ActorService actorService;

    @Test
    @DisplayName("소유권 확인 후 액터 목록 조회")
    void fetchesActorsAfterOwnershipCheck() {
        ActorListResponse expected = ActorListResponse.empty();
        when(aiEngineActorClient.fetchActors(PROJECT_ID)).thenReturn(expected);

        ActorListResponse result = actorService.getActors(USER_ID, PROJECT_ID);

        assertThat(result).isSameAs(expected);
        // 소유권 검증이 ai-engine 호출보다 먼저 일어나야 한다
        InOrder inOrder = inOrder(projectService, aiEngineActorClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineActorClient).fetchActors(PROJECT_ID);
    }

    @Test
    @DisplayName("소유권 확인 후 병합 위임 (합친 뒤 이름 전달)")
    void mergesActorsAfterOwnershipCheck() {
        ActorMergeResponse expected = new ActorMergeResponse("d1", "t", "s", 3, List.of());
        when(aiEngineActorClient.mergeActors(PROJECT_ID, "s", "t", "서준수", "메모")).thenReturn(expected);

        ActorMergeResponse result = actorService.mergeActors(
                USER_ID, PROJECT_ID, new ActorMergeRequest("s", "t", "서준수", "메모"));

        assertThat(result).isSameAs(expected);
        InOrder inOrder = inOrder(projectService, aiEngineActorClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineActorClient).mergeActors(PROJECT_ID, "s", "t", "서준수", "메모");
    }

    @Test
    @DisplayName("소유권 확인 후 이름 변경 위임")
    void renamesActorAfterOwnershipCheck() {
        ActorRenameResponse expected = new ActorRenameResponse("a", "정세영", "정세영");
        when(aiEngineActorClient.renameActor(PROJECT_ID, "a", "정세영")).thenReturn(expected);

        ActorRenameResponse result = actorService.renameActor(
                USER_ID, PROJECT_ID, new ActorRenameRequest("a", "정세영"));

        assertThat(result).isSameAs(expected);
        InOrder inOrder = inOrder(projectService, aiEngineActorClient);
        inOrder.verify(projectService).getProject(USER_ID, PROJECT_ID);
        inOrder.verify(aiEngineActorClient).renameActor(PROJECT_ID, "a", "정세영");
    }

    @Test
    @DisplayName("소유권 검증 실패 시 병합·이름 변경·복원·분리·철회 모두 ai-engine 호출 차단")
    void doesNotCallAiEngineWhenOwnershipCheckFails() {
        doThrow(new ForbiddenException("Project access denied."))
                .when(projectService).getProject(USER_ID, PROJECT_ID);

        assertThatThrownBy(() -> actorService.mergeActors(
                USER_ID, PROJECT_ID, new ActorMergeRequest("s", "t", "서준수", "")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> actorService.unmergeActors(
                USER_ID, PROJECT_ID, new ActorUnmergeRequest("d1")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> actorService.renameActor(
                USER_ID, PROJECT_ID, new ActorRenameRequest("a", "정세영")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> actorService.splitActor(
                USER_ID, PROJECT_ID, new ActorSplitRequest("a", List.of("GITHUB:x"), "")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> actorService.revokeDecision(USER_ID, PROJECT_ID, "d1"))
                .isInstanceOf(ForbiddenException.class);

        // 인가 실패 시 ai-engine에 절대 요청이 가면 안 된다 (수동 조작은 그래프를 변형한다)
        verifyNoInteractions(aiEngineActorClient);
    }
}
