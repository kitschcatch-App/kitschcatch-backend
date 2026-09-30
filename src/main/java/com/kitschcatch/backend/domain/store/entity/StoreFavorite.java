// 사용자가 관심 매장으로 저장한 관계와 최초 등록 시각을 보관한다.
package com.kitschcatch.backend.domain.store.entity;

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
@Table(name = "store_favorites",
    uniqueConstraints = @UniqueConstraint(name = "uk_store_favorites_user_store", columnNames = {"user_id", "store_id"}),
    indexes = {
        @Index(name = "ix_store_favorites_user_created_id", columnList = "user_id,created_at DESC,id DESC"),
        @Index(name = "ix_store_favorites_store", columnList = "store_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreFavorite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_store_favorites_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, foreignKey = @ForeignKey(name = "fk_store_favorites_store"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Store store;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public StoreFavorite(User user, Store store) {
        this.user = user;
        this.store = store;
    }
}
