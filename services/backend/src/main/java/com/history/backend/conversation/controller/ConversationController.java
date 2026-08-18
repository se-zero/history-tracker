package com.history.backend.conversation.controller;

import java.util.UUID;

import com.history.backend.conversation.dto.ConversationDetailResponse;
import com.history.backend.conversation.dto.ConversationPageResponse;
import com.history.backend.conversation.dto.ConversationResponse;
import com.history.backend.conversation.dto.ConversationSearchResponse;
import com.history.backend.conversation.dto.CreateConversationRequest;
import com.history.backend.conversation.dto.CreateMessageRequest;
import com.history.backend.conversation.dto.MessageExchangeResponse;
import com.history.backend.conversation.dto.MessagePageResponse;
import com.history.backend.conversation.dto.UpdateConversationRequest;
import com.history.backend.conversation.service.ConversationService;
import com.history.backend.conversation.service.MessageService;
import com.history.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageService messageService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDetailResponse createConversation(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return ConversationDetailResponse.from(conversationService.createConversation(
                authenticatedUser.id(),
                projectId,
                request.message()
        ));
    }

    @GetMapping
    public ConversationPageResponse listConversations(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String cursor
    ) {
        return ConversationPageResponse.from(
                conversationService.findConversationsPage(authenticatedUser.id(), projectId, cursor)
        );
    }

    // 대화 검색 — 제목·메시지 본문 부분 일치 대화 (리터럴 "search"가 "/{conversationId}"보다 우선 매칭된다)
    @GetMapping("/search")
    public ConversationSearchResponse searchConversations(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestParam String q
    ) {
        return ConversationSearchResponse.from(
                conversationService.searchConversations(authenticatedUser.id(), projectId, q)
        );
    }

    @GetMapping("/{conversationId}")
    public ConversationDetailResponse getConversation(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId
    ) {
        return ConversationDetailResponse.from(
                conversationService.getConversationDetail(authenticatedUser.id(), projectId, conversationId)
        );
    }

    @PatchMapping("/{conversationId}")
    public ConversationResponse updateConversation(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody UpdateConversationRequest request
    ) {
        return ConversationResponse.from(conversationService.updateTitle(
                authenticatedUser.id(),
                projectId,
                conversationId,
                request.title()
        ));
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId
    ) {
        conversationService.deleteConversation(authenticatedUser.id(), projectId, conversationId);
    }

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageExchangeResponse createMessage(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody CreateMessageRequest request
    ) {
        return MessageExchangeResponse.from(messageService.addMessage(
                authenticatedUser.id(),
                projectId,
                conversationId,
                request.content(),
                request.focusEvidence()
        ));
    }

    @GetMapping("/{conversationId}/messages")
    public MessagePageResponse listMessages(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) String before
    ) {
        return MessagePageResponse.from(
                messageService.findMessagesPage(authenticatedUser.id(), projectId, conversationId, before)
        );
    }
}
