package com.kitschcatch.backend.domain.chat.repository;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @EntityGraph(attributePaths = {"post", "buyer", "seller"})
    Optional<ChatRoom> findByPostIdAndBuyerIdAndSellerId(Long postId, Long buyerId, Long sellerId);

	// 메시지 전송과 조회 전에 참여자 정보를 함께 읽어 권한을 검사한다.
	@EntityGraph(attributePaths = {"post", "buyer", "seller"})
	Optional<ChatRoom> findChatRoomById(Long id);

	// WebSocket SUBSCRIBE 단계에서 채팅방 참여자 여부를 빠르게 확인한다.
	@Query("""
	select case when count(cr) > 0 then true else false end
	from ChatRoom cr
	where cr.id = :chatRoomId
	  and (cr.buyer.id = :userId or cr.seller.id = :userId)
	""")
	boolean existsParticipant(@Param("chatRoomId") Long chatRoomId, @Param("userId") Long userId);

}
