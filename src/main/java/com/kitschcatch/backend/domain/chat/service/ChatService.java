package com.kitschcatch.backend.domain.chat.service;


import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    // 채팅방 생성
    @Transactional
    public ChatRoomResponse createChatRoom(Long userId, CreateChatRoomRequest request) {

        // 판매 게시글 조회
        Post post = postRepository.findByIdAndDeletedAtIsNull(request.postId())
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        // 구매자 조회
        User buyer = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));

        // 판매자 조회
        User seller = post.getUser();

        // 판매자가 자기 자신의 게시글에 문의하는 경우 방지
        validateNotSelfChat(buyer, seller);

        // 채팅방 생성
        ChatRoom chatRoom = chatRoomRepository
                .findByPostIdAndBuyerIdAndSellerId(post.getId(), buyer.getId(), seller.getId())
                .orElseGet(() -> chatRoomRepository.save(ChatRoom.create(post, buyer, seller)));

        return ChatRoomResponse.from(chatRoom);
    }

    private void validateNotSelfChat(User buyer, User seller) {
        if (buyer.getId().equals(seller.getId())) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_SELF_NOT_ALLOWED);
        }
    }


}


