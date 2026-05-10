package com.history.backend.service;

import com.history.backend.dto.QueryRequest;
import com.history.backend.dto.QueryResponse;
import org.springframework.stereotype.Service;

@Service
public class QueryService {

    public QueryResponse ask(String projectId, QueryRequest request) {
        // TODO: call ai-engine GraphRAG API.
        return new QueryResponse("");
    }
}
