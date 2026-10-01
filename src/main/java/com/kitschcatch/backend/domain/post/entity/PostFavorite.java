// 사용자가 관심 상품으로 저장한 관계와 최초 등록 시각을 보관한다.
package com.kitschcatch.backend.domain.post.entity;

import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "post_favorites",
    uniqueConstraints = @UniqueConstraint(name = "uk_post_favorites_user_post", columnNames = {"user_id", "post_id"}),
    indexes = {
        @Index(name = "ix_post_favorites_user_created_id", columnList = "user_id,created_at DESC,id DESC"),
        @Index(name = "ix_post_favorites_post", columnList = "post_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostFavorite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_post_favorites_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, foreignKey = @ForeignKey(name = "fk_post_favorites_post"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Post post;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PostFavorite(User user, Post post) {
        this.user = user;
        this.post = post;
    }
}
