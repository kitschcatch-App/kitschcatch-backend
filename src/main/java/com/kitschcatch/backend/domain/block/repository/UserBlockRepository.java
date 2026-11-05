// 차단 목록과 양방향 접촉 제한 판정에 필요한 데이터를 조회한다.
package com.kitschcatch.backend.domain.block.repository;

import com.kitschcatch.backend.domain.block.entity.UserBlock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {
    boolean existsByBlockerIdAndBlockedId(long blockerId, long blockedId);

    @Query("""
        select case when count(b) > 0 then true else false end from UserBlock b
        where (b.blocker.id = :first and b.blocked.id = :second)
           or (b.blocker.id = :second and b.blocked.id = :first)
        """)
    boolean existsBetween(@Param("first") long first, @Param("second") long second);

    @Query("""
        select b.blocker.id as blockerId, b.blocked.id as blockedId from UserBlock b
        where (b.blocker.id = :actor and b.blocked.id in :others)
           or (b.blocked.id = :actor and b.blocker.id in :others)
        """)
    List<Direction> findRelations(@Param("actor") long actor, @Param("others") Collection<Long> others);

    @Modifying
    @Query("delete from UserBlock b where b.blocker.id = :actor and b.blocked.id = :target")
    int deleteDirection(@Param("actor") long actor, @Param("target") long target);

    @Query(value = """
        select u.id as userId, case when u.profileRegisteredAt is null then '사용자' else u.nickname end as nickname,
               u.profileImageKey as profileImageKey, b.createdAt as blockedAt
        from UserBlock b join b.blocked u where b.blocker.id = :actor
        order by b.createdAt desc, b.id desc
        """, countQuery = "select count(b) from UserBlock b where b.blocker.id = :actor")
    Page<BlockRow> findBlockedUsers(@Param("actor") long actor, Pageable pageable);

    interface Direction {
        long getBlockerId();
        long getBlockedId();
    }

    interface BlockRow {
        long getUserId();
        String getNickname();
        String getProfileImageKey();
        Instant getBlockedAt();
    }
}
