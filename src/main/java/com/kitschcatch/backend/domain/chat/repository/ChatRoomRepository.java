package com.kitschcatch.backend.domain.chat.repository;

import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @EntityGraph(attributePaths = {"post", "buyer", "seller"})
    Optional<ChatRoom> findByPostIdAndBuyerIdAndSellerId(Long postId, Long buyerId, Long sellerId);
}