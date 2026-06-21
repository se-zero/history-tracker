package com.history.pipeline_worker.controller;

import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.dto.CollectTriggerRequest;
import com.history.pipeline_worker.trigger.CollectionTriggerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequiredArgsConstructor
public class CollectionTriggerController {

    private final CollectionTriggerService collectionTriggerService;

    @PostMapping("/api/v1/collect/{provider}")
    public ResponseEntity<Map<String, String>> triggerCollection(
            @PathVariable String provider,
            @RequestBody @Valid CollectTriggerRequest request
    ) {
        CollectionProvider collectionProvider;
        try {
            collectionProvider = CollectionProvider.fromPath(provider);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "bad_request",
                    "message", "unsupported collection provider"
            ));
        }

        CollectionTriggerService.TriggerResult result = collectionTriggerService.trigger(
                collectionProvider,
                request.projectUuid()
        );
        HttpStatus status = switch (result.status()) {
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(Map.of(
                "status", result.status().name().toLowerCase(),
                "message", result.message()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequest() {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "bad_request",
                "message", "invalid request"
        ));
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<Map<String, String>> handleRejectedExecution() {
        return ResponseEntity.internalServerError().body(Map.of(
                "status", "internal_server_error",
                "message", "collection queue rejected"
        ));
    }
}
