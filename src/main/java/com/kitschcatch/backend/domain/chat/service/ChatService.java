package com.kitschcatch.backend.domain.chat.service;

import com.kitschcatch.backend.domain.chat.dto.ChatRoomDetailResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomListResponse;
import com.kitschcatch.backend.domain.chat.dto.ChatRoomResponse;
import com.kitschcatch.backend.domain.chat.dto.CreateChatRoomRequest;
import com.kitschcatch.backend.domain.chat.entity.ChatRoom;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import com.kitschcatch.backend.domain.chat.repository.ChatMessageRepository;
import org.springframework.transaction.annotation.Isolation;
import java.util.Map;
import java.util.stream.Collectors;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.post.service.PostImageStorage;
import com.kitschcatch.backend.domain.post.entity.PostImage;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import com.kitschcatch.backend.domain.block.service.UserInteractionPolicy;
import com.kitschcatch.backend.domain.block.service.UserInteractionPolicy.Status;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final PostImageStorage postImageStorage;
    private final ChatContactPolicy contactPolicy;
    private final UserInteractionPolicy interactions;

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
        contactPolicy.requireWritable(userId, seller.getId());

        // 채팅방 생성
        ChatRoom chatRoom = findOrCreateChatRoom(post, buyer, seller);

        return ChatRoomResponse.from(chatRoom);
    }

    private ChatRoom findOrCreateChatRoom(Post post, User buyer, User seller) {
        return chatRoomRepository
                .findByPostIdAndBuyerIdAndSellerId(post.getId(), buyer.getId(), seller.getId())
                .orElseGet(() -> chatRoomRepository.saveAndFlush(ChatRoom.create(post, buyer, seller)));
    }

    /**
     * 현재 로그인 사용자가 참여 중인 채팅방 목록을 조회한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ChatRoomListResponse> getMyChatRooms(Long userId) {
        return getMyChatRooms(userId, new ChatRoomListQuery(null, null));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ChatRoomListResponse> getMyChatRooms(Long userId, ChatRoomListQuery query) {
        if (!userRepository.existsById(userId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
        var rooms = chatRoomRepository.findMyChatRooms(userId,
                query.role() == null ? null : query.role().name(), query.postId());
        if (rooms.isEmpty()) return List.of();
        Map<Long, ChatMessageRepository.UnreadCount> stats = chatMessageRepository.countUnreadByRoomIds(
                rooms.stream().map(ChatRoom::getId).toList(), userId).stream()
            .collect(Collectors.toMap(ChatMessageRepository.UnreadCount::getChatRoomId, row -> row));
        return rooms.stream()
                .map(room -> {
                    var row = stats.get(room.getId());
                    if (row == null) return ChatRoomListResponse.from(room, userId, 0, Status.ALLOWED);
                    var status = new Status(row.getBlockedByMe(), !row.getBlockedByMe() && !row.getBlockedByOpponent());
                    return ChatRoomListResponse.from(room, userId, row.getUnreadCount(), status);
                })
                .toList();
    }


    /**
     * 특정 채팅방에서 게시글과 관련된 상세 정보를 조회한다.
     */
    @Transactional(readOnly = true)
    public ChatRoomDetailResponse getChatRoom(Long userId, Long chatRoomId) {

        ChatRoom chatRoom = chatRoomRepository.findChatRoomDetailById(chatRoomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));


        validateChatRoomParticipant(chatRoom, userId);

        String postThumbnailImageUrl = getPostThumbnailImageUrl(chatRoom);

        var status = interactions.status(userId, ChatContactPolicy.opponentId(chatRoom, userId));
        var opponent = chatRoom.getBuyer().getId().equals(userId) ? chatRoom.getSeller() : chatRoom.getBuyer();
        return ChatRoomDetailResponse.from(chatRoom, postThumbnailImageUrl, status, opponent.isActive());
    }

    /**
     * 현재 로그인 사용자가 채팅방 참여자인지 검증한다.
     */
    private void validateChatRoomParticipant(ChatRoom chatRoom, Long userId) {
        boolean isBuyer = chatRoom.getBuyer().getId().equals(userId);
        boolean isSeller = chatRoom.getSeller().getId().equals(userId);

        if (!isBuyer && !isSeller) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
        }
    }

    /**
     * 판매자가 자기 자신의 게시글에 문의하는 것을 방지한다.
     */
    private void validateNotSelfChat(User buyer, User seller) {
        if (buyer.getId().equals(seller.getId())) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_SELF_NOT_ALLOWED);
        }
    }


    /**
     * 판매글의 대표 이미지 URL을 반환한다.
     */
    private String getPostThumbnailImageUrl(ChatRoom chatRoom) {
        List<PostImage> images = chatRoom.getPost().getImages();

        if (images == null || images.isEmpty()) {
            return null;
        }

        return postImageStorage.imageUrl(images.get(0).getObjectKey());
    }
}

