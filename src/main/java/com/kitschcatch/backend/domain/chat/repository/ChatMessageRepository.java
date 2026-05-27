package com.kitschcatch.backend.domain.chat.repository;

import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
	@EntityGraph(attributePaths = {"chatRoom", "sender"})
	List<ChatMessage> findByChatRoomIdOrderByCreatedAtAsc(Long chatRoomId);
}
