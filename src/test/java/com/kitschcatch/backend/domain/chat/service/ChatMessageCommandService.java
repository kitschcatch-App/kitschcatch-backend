package com.kitschcatch.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.chat.dto.ChatMessageResponse;
import com.kitschcatch.backend.domain.chat.entity.ChatMessage;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.entity.MessageType;
import com.kitschcatch.backend.domain.chat.repository.ChatMessageRepository;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageCommandServiceTest {

    private static final String CHAT_IMAGE_URL = "https://cdn.example.com/chats/100/1/chat-image.png";

    private ChatMessageRepository chatMessageRepository;
    private ChatRoomRepository chatRoomRepository;
    private UserRepository userRepository;
    private ChatMessageCommandService chatMessageCommandService;

    private User buyer;
    private User seller;
    private ChatRoom chatRoom;

    @BeforeEach
    void setUp() {
        chatMessageRepository = mock(ChatMessageRepository.class);
        chatRoomRepository = mock(ChatRoomRepository.class);
        userRepository = mock(UserRepository.class);

        chatMessageCommandService = new ChatMessageCommandService(
                chatRoomRepository,
                userRepository,
                chatMessageRepository
        );

        buyer = user(1L, "buyer", "buyer@example.com", "buyer-provider");
        seller = user(2L, "seller", "seller@example.com", "seller-provider");
        chatRoom = chatRoom(100L, buyer, seller);
    }

    private User user(Long id, String nickname, String email, String providerUserId) {
        User user = User.builder()
                .nickname(nickname)
                .email(email)
                .authProvider(AuthProvider.KAKAO)
                .providerUserId(providerUserId)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private ChatRoom chatRoom(Long id, User buyer, User seller) {
        Post post = Post.builder()
                .user(seller)
                .title("키링 판매")
                .description("미개봉 상품입니다.")
                .price(12000L)
                .productCategory(ProductCategory.GOODS)
                .productCondition(ProductCondition.NEW)
                .productStatus(ProductStatus.ON_SALE)
                .build();
        ReflectionTestUtils.setField(post, "id", 10L);

        ChatRoom chatRoom = ChatRoom.create(post, buyer, seller);
        ReflectionTestUtils.setField(chatRoom, "id", id);
        return chatRoom;
    }
}