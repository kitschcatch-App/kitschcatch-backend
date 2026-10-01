// 사용자 사이의 방향성 팔로우 관계와 최초 등록 시각을 보관한다.
package com.kitschcatch.backend.domain.follow.entity;

import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "user_follows",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_follows_direction", columnNames = {"follower_id", "following_id"}),
    indexes = {
        @Index(name = "ix_user_follows_follower_created_id", columnList = "follower_id,created_at DESC,id DESC"),
        @Index(name = "ix_user_follows_following_created_id", columnList = "following_id,created_at DESC,id DESC")
    })
@Check(name = "ck_user_follows_not_self", constraints = "follower_id <> following_id")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserFollow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "follower_id", nullable = false, foreignKey = @ForeignKey(name = "fk_user_follows_follower"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User follower;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "following_id", nullable = false, foreignKey = @ForeignKey(name = "fk_user_follows_following"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User following;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public UserFollow(User follower, User following) {
        this.follower = follower;
        this.following = following;
    }
}
