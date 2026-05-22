package com.history.backend.conversation.repository;

import java.util.List;
import java.util.UUID;

import com.history.backend.conversation.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findAllByConversation_IdOrderByCreatedAtAsc(UUID conversationId);
}
