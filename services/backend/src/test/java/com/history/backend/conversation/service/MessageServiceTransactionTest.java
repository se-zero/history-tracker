package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.history.backend.conversation.domain.Conversation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@Import(MessageServiceTransactionTestConfig.class)
class MessageServiceTransactionTest {

    private static final java.util.UUID CONVERSATION_ID = java.util.UUID.fromString(
            "7dbd88a3-807d-4b6d-8fef-462de48f6c6c"
    );

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MessageServiceTransactionTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private MessageService messageService;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void appendUserMessageRequiresExistingTransaction() {
        assertThatThrownBy(() -> messageService.appendUserMessageInCurrentTransaction(
                new Conversation(null, null, null),
                "Question"
        )).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findMessagesInCurrentTransactionRequiresExistingTransaction() {
        assertThatThrownBy(() -> messageService.findMessagesInCurrentTransaction(CONVERSATION_ID))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
