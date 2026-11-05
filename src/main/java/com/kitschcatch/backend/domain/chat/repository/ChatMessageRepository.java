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

    // 채팅방별 미읽음 수와 두 방향의 차단 상태를 한 번에 조회한다.
    @Query("""
        select r.id as chatRoomId, count(m.id) as unreadCount,
            case when exists (select 1 from UserBlock b where b.blocker.id = :userId
                and b.blocked.id in (r.buyer.id, r.seller.id)) then true else false end as blockedByMe,
            case when exists (select 1 from UserBlock b where b.blocked.id = :userId
                and b.blocker.id in (r.buyer.id, r.seller.id)) then true else false end as blockedByOpponent
        from ChatRoom r left join ChatMessage m
            on m.chatRoom.id = r.id and m.sender.id <> :userId and m.isRead = false
        where r.id in :roomIds
        group by r.id, r.buyer.id, r.seller.id
        """)
    List<UnreadCount> countUnreadByRoomIds(@Param("roomIds") List<Long> roomIds,
        @Param("userId") Long userId);

    interface UnreadCount {
        Long getChatRoomId();
        long getUnreadCount();
        boolean getBlockedByMe();
        boolean getBlockedByOpponent();
    }

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