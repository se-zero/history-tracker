package com.history.backend.conversation.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.service.ConversationService;
import com.history.backend.conversation.service.ConversationDetail;
import com.history.backend.conversation.service.ConversationStart;
import com.history.backend.conversation.service.MessageExchange;
import com.history.backend.conversation.service.MessageService;
import com.history.backend.project.domain.Project;
import com.history.backend.security.AuthenticatedUser;
import com.history.backend.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ConversationController: 대화 HTTP API")
class ConversationControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID CONVERSATION_ID = UUID.fromString("7dbd88a3-807d-4b6d-8fef-462de48f6c6c");
    private static final UUID USER_MESSAGE_ID = UUID.fromString("f34a3264-8406-477e-ad87-652d1b126164");
    private static final UUID ASSISTANT_MESSAGE_ID = UUID.fromString("92c04fd6-1dca-41b5-87ee-9dd75b734eca");
    private static final Instant CREATED_AT = Instant.parse("2026-05-23T01:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-05-23T01:01:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService conversationService;

    @MockitoBean
    private MessageService messageService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUpAuthentication() {
        when(jwtTokenService.validateAccessToken(anyString())).thenReturn(new AuthenticatedUser(USER_ID));
    }

    @Test
    @DisplayName("대화 생성 → 201 Created 및 초기 메시지 반환")
    void createConversationReturnsConversationAndInitialMessages() throws Exception {
        Conversation conversation = conversation("Why did auth change?");
        Message userMessage = userMessage(conversation, "Why did auth change?");
        Message assistantMessage = assistantMessage(conversation, "OAuth callback changed.", null);
        when(conversationService.createConversation(USER_ID, PROJECT_ID, "Why did auth change?"))
                .thenReturn(new ConversationStart(conversation, userMessage, assistantMessage));

        mockMvc.perform(post("/api/v1/projects/{projectId}/conversations", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Why did auth change?"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONVERSATION_ID.toString()))
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.title").value("Why did auth change?"))
                .andExpect(jsonPath("$.messages[0].id").value(USER_MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[0].content").value("Why did auth change?"))
                .andExpect(jsonPath("$.messages[1].id").value(ASSISTANT_MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[1].content").value("OAuth callback changed."));
    }

    @Test
    @DisplayName("대화 목록 조회 → 프로젝트 대화 반환")
    void listConversationsReturnsProjectConversations() throws Exception {
        when(conversationService.findConversations(USER_ID, PROJECT_ID))
                .thenReturn(List.of(conversation("Auth changes")));

        mockMvc.perform(get("/api/v1/projects/{projectId}/conversations", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CONVERSATION_ID.toString()))
                .andExpect(jsonPath("$[0].title").value("Auth changes"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-05-23T01:00:00Z"))
                .andExpect(jsonPath("$[0].updatedAt").value("2026-05-23T01:01:00Z"));
    }

    @Test
    @DisplayName("대화 단건 조회 → 대화와 메시지 반환")
    void getConversationReturnsConversationAndMessages() throws Exception {
        Conversation conversation = conversation("Auth changes");
        when(conversationService.getConversationDetail(USER_ID, PROJECT_ID, CONVERSATION_ID))
                .thenReturn(new ConversationDetail(
                        conversation,
                        List.of(
                        userMessage(conversation, "What changed?"),
                        assistantMessage(conversation, "OAuth callback changed.", Map.of("fallback", true))
                        )
                ));

        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/conversations/{conversationId}",
                        PROJECT_ID,
                        CONVERSATION_ID
                ).header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONVERSATION_ID.toString()))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[1].metadata.fallback").value(true));
    }

    @Test
    @DisplayName("대화 제목 수정 → 수정된 대화 반환")
    void updateConversationReturnsUpdatedConversation() throws Exception {
        when(conversationService.updateTitle(USER_ID, PROJECT_ID, CONVERSATION_ID, "New title"))
                .thenReturn(conversation("New title"));

        mockMvc.perform(patch(
                        "/api/v1/projects/{projectId}/conversations/{conversationId}",
                        PROJECT_ID,
                        CONVERSATION_ID
                ).header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"New title"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"));
    }

    @Test
    @DisplayName("대화 삭제 → 204 No Content 반환")
    void deleteConversationReturnsNoContent() throws Exception {
        mockMvc.perform(delete(
                        "/api/v1/projects/{projectId}/conversations/{conversationId}",
                        PROJECT_ID,
                        CONVERSATION_ID
                ).header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNoContent());

        verify(conversationService).deleteConversation(USER_ID, PROJECT_ID, CONVERSATION_ID);
    }

    @Test
    @DisplayName("메시지 생성 → 사용자·보조자 메시지 반환")
    void createMessageReturnsUserAndAssistantMessages() throws Exception {
        Conversation conversation = conversation("Auth changes");
        when(messageService.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "What changed?"))
                .thenReturn(new MessageExchange(
                        userMessage(conversation, "What changed?"),
                        assistantMessage(conversation, "OAuth callback changed.", null)
                ));

        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/conversations/{conversationId}/messages",
                        PROJECT_ID,
                        CONVERSATION_ID
                ).header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"What changed?"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userMessage.role").value("USER"))
                .andExpect(jsonPath("$.userMessage.content").value("What changed?"))
                .andExpect(jsonPath("$.assistantMessage.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.assistantMessage.content").value("OAuth callback changed."));
    }

    @Test
    @DisplayName("메시지 목록 조회 → 대화 메시지 반환")
    void listMessagesReturnsConversationMessages() throws Exception {
        Conversation conversation = conversation("Auth changes");
        when(messageService.findMessages(USER_ID, PROJECT_ID, CONVERSATION_ID))
                .thenReturn(List.of(userMessage(conversation, "What changed?")));

        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/conversations/{conversationId}/messages",
                        PROJECT_ID,
                        CONVERSATION_ID
                ).header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(USER_MESSAGE_ID.toString()))
                .andExpect(jsonPath("$[0].conversationId").value(CONVERSATION_ID.toString()));
    }

    @Test
    @DisplayName("빈 메시지로 대화 생성 → 400 Bad Request 반환")
    void createConversationRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/conversations", PROJECT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.fields[0].field").value("message"));
    }

    @Test
    @DisplayName("빈 내용으로 메시지 생성 → 400 Bad Request 반환")
    void createMessageRejectsBlankContent() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/conversations/{conversationId}/messages",
                        PROJECT_ID,
                        CONVERSATION_ID
                ).header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.fields[0].field").value("content"));
    }

    @Test
    @DisplayName("존재하지 않는 대화 조회 → 404 Not Found 반환")
    void getConversationReturnsNotFound() throws Exception {
        when(conversationService.getConversationDetail(USER_ID, PROJECT_ID, CONVERSATION_ID))
                .thenThrow(new NotFoundException("Conversation not found."));

        mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/conversations/{conversationId}",
                        PROJECT_ID,
                        CONVERSATION_ID
                ).header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Conversation not found."));
    }

    @Test
    @DisplayName("액세스 토큰 없으면 401 Unauthorized 반환")
    void rejectMissingAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/conversations", PROJECT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    private Conversation conversation(String title) {
        Conversation conversation = new Conversation(project(), user(), title);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        ReflectionTestUtils.setField(conversation, "createdAt", CREATED_AT);
        ReflectionTestUtils.setField(conversation, "updatedAt", UPDATED_AT);
        return conversation;
    }

    private Message userMessage(Conversation conversation, String content) {
        Message message = Message.user(conversation, content);
        ReflectionTestUtils.setField(message, "id", USER_MESSAGE_ID);
        ReflectionTestUtils.setField(message, "createdAt", CREATED_AT);
        return message;
    }

    private Message assistantMessage(Conversation conversation, String content, Map<String, Object> metadata) {
        Message message = Message.assistant(conversation, content, metadata);
        ReflectionTestUtils.setField(message, "id", ASSISTANT_MESSAGE_ID);
        ReflectionTestUtils.setField(message, "createdAt", UPDATED_AT);
        return message;
    }

    private Project project() {
        Project project = new Project(user(), "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }

    private User user() {
        User user = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }
}
