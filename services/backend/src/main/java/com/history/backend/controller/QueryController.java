package com.history.backend.controller;

import com.history.backend.dto.QueryRequest;
import com.history.backend.dto.QueryResponse;
import com.history.backend.service.QueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/query")
public class QueryController {

    private final QueryService queryService;

    @PostMapping
    public ResponseEntity<QueryResponse> ask(
            @PathVariable String projectId,
            @RequestBody @Valid QueryRequest request
    ) {
        return ResponseEntity.ok(queryService.ask(projectId, request));

    }
}
