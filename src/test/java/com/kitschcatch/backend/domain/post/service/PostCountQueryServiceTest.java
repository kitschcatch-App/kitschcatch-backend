// 상품 집계의 빈 입력·중복 ID·0 기본값과 조회 실패 전파를 검증한다.
package com.kitschcatch.backend.domain.post.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kitschcatch.backend.domain.post.repository.PostFavoriteRepository;
import com.kitschcatch.backend.domain.chat.repository.ChatRoomRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostCountQueryServiceTest {
    final PostFavoriteRepository favorites = mock(PostFavoriteRepository.class);
    final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    final PostCountQueryService service = new PostCountQueryService(favorites, rooms);

    @Test void emptyInputSkipsBothQueries() {
        assertThat(service.findByPostIds(List.of())).isEmpty();
        verifyNoInteractions(favorites, rooms);
    }

    @Test void combinesIndependentCountsAndDeduplicatesIds() {
        var favorite = mock(PostFavoriteRepository.PostCount.class);
        when(favorite.getPostId()).thenReturn(1L);
        when(favorite.getTotal()).thenReturn(3L);
        var room = mock(ChatRoomRepository.PostCount.class);
        when(room.getPostId()).thenReturn(2L);
        when(room.getTotal()).thenReturn(2L);
        when(favorites.countByPostIds(List.of(1L, 2L, 3L))).thenReturn(List.of(favorite));
        when(rooms.countByPostIds(List.of(1L, 2L, 3L))).thenReturn(List.of(room));
        assertThat(service.findByPostIds(List.of(1L, 2L, 1L, 3L)))
            .containsEntry(1L, new PostCounts(3, 0)).containsEntry(2L, new PostCounts(0, 2))
            .containsEntry(3L, PostCounts.ZERO);
        verify(favorites).countByPostIds(List.of(1L, 2L, 3L));
        verify(rooms).countByPostIds(List.of(1L, 2L, 3L));
    }

    @Test void queryFailureIsNotReportedAsZero() {
        when(favorites.countByPostIds(List.of(1L))).thenThrow(new IllegalStateException("DB failure"));
        assertThatThrownBy(() -> service.findByPostIds(List.of(1L))).isInstanceOf(IllegalStateException.class);
    }
}
