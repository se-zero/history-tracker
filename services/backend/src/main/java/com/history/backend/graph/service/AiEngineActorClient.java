package com.history.backend.graph.service;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.graph.dto.ActorDecisionListResponse;
import com.history.backend.graph.dto.ActorDetailResponse;
import com.history.backend.graph.dto.ActorListResponse;
import com.history.backend.graph.dto.ActorMergeResponse;
import com.history.backend.graph.dto.ActorRenameResponse;
import com.history.backend.graph.dto.ActorSplitResponse;
import com.history.backend.graph.dto.ActorUnmergeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Actor 수동 병합·분리 프록시 — 그래프(Neo4j) 데이터의 단일 소유자인 ai-engine에 위임한다.
// 검증 실패(400/404)는 ai-engine의 detail 사유를 그대로 프론트에 전달한다 — 운영자가
// 실패 원인(예: "모든 alias를 분리할 수 없다")을 보고 조치할 수 있어야 하는 조작 API라서다.
@Slf4j
@Service
@RequiredArgsConstructor
public class AiEngineActorClient {

    private final RestClient aiEngineRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ActorListResponse fetchActors(UUID projectId) {
        try {
            ActorListResponse response = aiEngineRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/graph/actors")
                            .queryParam("project_id", projectId)
                            .build())
                    .retrieve()
                    .body(ActorListResponse.class);
            return response == null || response.actors() == null
                    ? ActorListResponse.empty() : response;
        } catch (RestClientException exception) {
            log.error("ai-engine actors request failed: projectId={}, {}",
                    projectId, exception.getMessage());
            throw new BadGatewayException("Failed to load project actors.");
        }
    }

    public ActorDetailResponse fetchActorDetail(UUID projectId, String actorUuid) {
        try {
            return aiEngineRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/graph/actors/{actorUuid}")
                            .queryParam("project_id", projectId)
                            .build(actorUuid))
                    .retrieve()
                    .body(ActorDetailResponse.class);
        } catch (RestClientException exception) {
            throw translate(exception, "Failed to load actor detail.", projectId);
        }
    }

    public ActorMergeResponse mergeActors(UUID projectId, String uuidA, String uuidB, String note) {
        try {
            return aiEngineRestClient.post()
                    .uri("/actors/merge")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiEngineMergeRequest(
                            projectId.toString(), uuidA, uuidB, note == null ? "" : note))
                    .retrieve()
                    .body(ActorMergeResponse.class);
        } catch (RestClientException exception) {
            throw translate(exception, "Failed to merge actors.", projectId);
        }
    }

    public ActorRenameResponse renameActor(UUID projectId, String actorUuid, String name) {
        try {
            return aiEngineRestClient.post()
                    .uri("/actors/rename")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiEngineRenameRequest(
                            projectId.toString(), actorUuid, name == null ? "" : name))
                    .retrieve()
                    .body(ActorRenameResponse.class);
        } catch (RestClientException exception) {
            throw translate(exception, "Failed to rename actor.", projectId);
        }
    }

    public ActorUnmergeResponse unmergeActors(UUID projectId, String decisionId) {
        try {
            return aiEngineRestClient.post()
                    .uri("/actors/unmerge")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiEngineUnmergeRequest(projectId.toString(), decisionId))
                    .retrieve()
                    .body(ActorUnmergeResponse.class);
        } catch (RestClientException exception) {
            throw translate(exception, "Failed to unmerge actors.", projectId);
        }
    }

    public ActorSplitResponse splitActor(UUID projectId, String actorUuid, List<String> sourceIds) {
        try {
            return aiEngineRestClient.post()
                    .uri("/actors/split")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiEngineSplitRequest(
                            projectId.toString(), actorUuid,
                            sourceIds == null ? List.of() : sourceIds))
                    .retrieve()
                    .body(ActorSplitResponse.class);
        } catch (RestClientException exception) {
            throw translate(exception, "Failed to split actor.", projectId);
        }
    }

    public ActorDecisionListResponse fetchDecisions(UUID projectId) {
        try {
            ActorDecisionListResponse response = aiEngineRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/actors/decisions")
                            .queryParam("project_id", projectId)
                            .build())
                    .retrieve()
                    .body(ActorDecisionListResponse.class);
            return response == null || response.decisions() == null
                    ? ActorDecisionListResponse.empty() : response;
        } catch (RestClientException exception) {
            log.error("ai-engine actor decisions request failed: projectId={}, {}",
                    projectId, exception.getMessage());
            throw new BadGatewayException("Failed to load actor decisions.");
        }
    }

    public void deleteDecision(UUID projectId, String decisionId) {
        try {
            aiEngineRestClient.delete()
                    .uri(uriBuilder -> uriBuilder.path("/actors/decisions/{decisionId}")
                            .queryParam("project_id", projectId)
                            .build(decisionId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw translate(exception, "Failed to revoke actor decision.", projectId);
        }
    }

    // ai-engine의 4xx는 조작 검증 실패 — detail 사유를 살려 같은 계열 상태로 변환하고,
    // 그 외(연결 실패·5xx)만 502로 뭉갠다.
    private RuntimeException translate(RestClientException exception, String fallback, UUID projectId) {
        log.error("ai-engine actor request failed: projectId={}, {}", projectId, exception.getMessage());
        if (exception instanceof RestClientResponseException response) {
            String detail = extractDetail(response, fallback);
            if (response.getStatusCode().value() == 404) {
                return new NotFoundException(detail);
            }
            if (response.getStatusCode().is4xxClientError()) {
                return new BadRequestException(detail);
            }
        }
        return new BadGatewayException(fallback);
    }

    private String extractDetail(RestClientResponseException response, String fallback) {
        try {
            JsonNode node = objectMapper.readTree(response.getResponseBodyAsString());
            JsonNode detail = node.get("detail");
            if (detail != null && detail.isTextual() && !detail.asText().isBlank()) {
                return detail.asText();
            }
        } catch (Exception ignored) {
            // 본문이 JSON이 아니면 fallback 메시지 사용
        }
        return fallback;
    }

    private record AiEngineMergeRequest(
            @JsonProperty("project_id") String projectId,
            @JsonProperty("uuid_a") String uuidA,
            @JsonProperty("uuid_b") String uuidB,
            String note
    ) {
    }

    private record AiEngineUnmergeRequest(
            @JsonProperty("project_id") String projectId,
            @JsonProperty("decision_id") String decisionId
    ) {
    }

    private record AiEngineRenameRequest(
            @JsonProperty("project_id") String projectId,
            @JsonProperty("actor_uuid") String actorUuid,
            String name
    ) {
    }

    private record AiEngineSplitRequest(
            @JsonProperty("project_id") String projectId,
            @JsonProperty("actor_uuid") String actorUuid,
            @JsonProperty("source_ids") List<String> sourceIds
    ) {
    }
}
