package com.kitschcatch.backend.domain.chat.repository;

import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	@EntityGraph(attributePaths = {"chatRoom", "sender"})
	List<ChatMessage> findByChatRoomIdOrderByCreatedAtAsc(Long chatRoomId);

	// 안읽은 메세지 목록 조회
	@Query("""
		select m.id
		from ChatMessage m
		where m.chatRoom.id = :chatRoomId
		  and m.sender.id <> :readerId
		  and m.isRead = false
		order by m.createdAt asc
	""")
	List<Long> findUnreadMessageIds(@Param("chatRoomId") Long chatRoomId, @Param("readerId") Long readerId);

	// 메세지 읽음 처리
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update ChatMessage m
		set m.isRead = true
		where m.id in :messageIds
	""")
	int markAsReadByIds(@Param("messageIds") List<Long> messageIds);
}