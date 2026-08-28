package com.history.backend.conversation.service;

import static org.mockito.Mockito.mock;

import com.history.backend.config.TransactionConfig;
import com.history.backend.conversation.ChatMemoryProperties;
import com.history.backend.auth.service.PlanService;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.conversation.repository.MessageRepository;
import com.history.backend.project.service.ProjectService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

@TestConfiguration
@Import(TransactionConfig.class)
class MessageServiceTransactionTestConfig {

    @Bean
    MessageService messageService(
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            TransactionTemplate transactionTemplate
    ) {
        ChatMemoryProperties chatMemoryProperties = new ChatMemoryProperties(32000, 8000, 3, 3);
        return new MessageService(
                messageRepository,
                conversationRepository,
                mock(ProjectService.class),
                mock(PlanService.class),
                mock(AiEngineQueryClient.class),
                chatMemoryProperties,
                new SummaryBackoffTracker(chatMemoryProperties),
                new SyncTaskExecutor(),
                transactionTemplate
        );
    }
}
