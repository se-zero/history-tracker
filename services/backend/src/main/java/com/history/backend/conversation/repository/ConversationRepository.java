package com.history.backend.conversation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.conversation.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    List<Conversation> findAllByProject_IdOrderByUpdatedAtDesc(UUID projectId);

    Optional<Conversation> findByIdAndProject_Id(UUID id, UUID projectId);
}
