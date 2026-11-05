// 매장 리뷰의 작성자, 별점, 공개 상태와 수정 버전을 저장한다.
package com.kitschcatch.backend.domain.store.entity;

import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private User author;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant deletedAt;
    private Instant hiddenAt;

    @Version
    @Column(nullable = false)
    private long version;

    public StoreReview(Store store, User author, int rating, String content, Instant now) {
        this.store = store;
        this.author = author;
        this.createdAt = now;
        update(rating, content, now);
    }

    public void update(int rating, String content, Instant now) {
        this.rating = rating;
        this.content = content;
        this.updatedAt = now;
    }

    public void delete(Instant now) {
        this.deletedAt = now;
    }
}
