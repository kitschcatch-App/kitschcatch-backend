// 방향별 팔로우 조회·중복 확인·삭제를 처리하고 목록은 공개 필드로 투영한다.
package com.kitschcatch.backend.domain.follow.repository;

import com.kitschcatch.backend.domain.follow.dto.FollowUserRow;
import com.kitschcatch.backend.domain.follow.entity.UserFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {
    boolean existsByFollowerIdAndFollowingId(Long followerId, Long followingId);

    @Modifying(flushAutomatically = true)
    @Query("delete from UserFollow f where f.follower.id = :followerId and f.following.id = :followingId")
    int deleteFollow(@Param("followerId") Long followerId, @Param("followingId") Long followingId);

    @Query(value = """
        select new com.kitschcatch.backend.domain.follow.dto.FollowUserRow(u.id, u.nickname, u.profileImageKey)
        from UserFollow f join f.follower u where f.following.id = :userId
        order by f.createdAt desc, f.id desc
        """, countQuery = "select count(f) from UserFollow f where f.following.id = :userId")
    Page<FollowUserRow> findFollowers(@Param("userId") Long userId, Pageable pageable);

    @Query(value = """
        select new com.kitschcatch.backend.domain.follow.dto.FollowUserRow(u.id, u.nickname, u.profileImageKey)
        from UserFollow f join f.following u where f.follower.id = :userId
        order by f.createdAt desc, f.id desc
        """, countQuery = "select count(f) from UserFollow f where f.follower.id = :userId")
    Page<FollowUserRow> findFollowings(@Param("userId") Long userId, Pageable pageable);
}
