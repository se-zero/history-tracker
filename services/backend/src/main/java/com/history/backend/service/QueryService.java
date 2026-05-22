package com.history.backend.service;

import com.history.backend.dto.QueryRequest;
import com.history.backend.dto.QueryResponse;
import com.history.backend.conversation.service.AiEngineQueryClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueryService {

    private final AiEngineQueryClient aiEngineQueryClient;

    public QueryResponse ask(String projectId, QueryRequest request) {
        return new QueryResponse(aiEngineQueryClient.ask(request.question()).answer());
    }
}

