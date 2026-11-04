// 반환 대상 상품의 관심 관계와 문의방 수를 두 번의 일괄 조회로 조합한다.
package com.kitschcatch.backend.domain.post.service;

import com.kitschcatch.backend.domain.post.repository.PostFavoriteRepository;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class PostCountQueryService {
    private final PostFavoriteRepository favorites;
    private final ChatRoomRepository rooms;

    public PostCountQueryService(PostFavoriteRepository favorites, ChatRoomRepository rooms) {
        this.favorites = favorites;
        this.rooms = rooms;
    }

    public Map<Long, PostCounts> findByPostIds(List<Long> postIds) {
        var ids = postIds.stream().distinct().toList();
        if (ids.isEmpty()) return Map.of();
        var favoriteCounts = favorites.countByPostIds(ids).stream().collect(Collectors.toMap(
            PostFavoriteRepository.PostCount::getPostId, PostFavoriteRepository.PostCount::getTotal));
        var roomCounts = rooms.countByPostIds(ids).stream().collect(Collectors.toMap(
            ChatRoomRepository.PostCount::getPostId, ChatRoomRepository.PostCount::getTotal));
        return ids.stream().collect(Collectors.toMap(id -> id, id -> new PostCounts(
            favoriteCounts.getOrDefault(id, 0L), roomCounts.getOrDefault(id, 0L))));
    }
}
