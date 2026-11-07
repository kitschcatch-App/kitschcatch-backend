// 사용자가 직접 설정한 방향성 차단 관계를 저장한다.
package com.kitschcatch.backend.domain.block.entity;

import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "user_blocks", uniqueConstraints = @UniqueConstraint(
    name = "uk_user_blocks_direction", columnNames = {"blocker_id", "blocked_id"}),
    indexes = {
        @Index(name = "ix_user_blocks_blocker_created_id", columnList = "blocker_id, created_at DESC, id DESC"),
        @Index(name = "ix_user_blocks_blocked", columnList = "blocked_id")
    })
@Check(constraints = "blocker_id <> blocked_id", name = "ck_user_blocks_not_self")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocker_id", nullable = false)
    private User blocker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_id", nullable = false)
    private User blocked;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public UserBlock(User blocker, User blocked) {
        this.blocker = blocker;
        this.blocked = blocked;
        this.createdAt = Instant.now();
    }
}
