// 구매 확정 주문의 당사자와 상대방 평가를 변경 없이 보존한다.
package com.kitschcatch.backend.domain.review.entity;

import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "transaction_reviews",
    uniqueConstraints = @UniqueConstraint(name = "uk_reviews_order_author", columnNames = {"order_id", "author_id"}),
    indexes = @Index(name = "ix_reviews_recipient_created_id", columnList = "recipient_id,created_at DESC,id DESC"))
@Check(constraints = "rating between 1 and 5 and trim(content) <> '' and author_id <> recipient_id "
    + "and ((author_id = buyer_id and recipient_id = seller_id) or (author_id = seller_id and recipient_id = buyer_id))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_reviews_order"))
    private PurchaseOrder order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_reviews_author"))
    private User author;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_reviews_recipient"))
    private User recipient;
    @Column(name = "buyer_id", nullable = false, updatable = false)
    private Long buyerId;
    @Column(name = "seller_id", nullable = false, updatable = false)
    private Long sellerId;
    @Column(nullable = false, updatable = false)
    private Integer rating;
    @Column(nullable = false, updatable = false, length = 1000)
    private String content;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public TransactionReview(PurchaseOrder order, User author, User recipient, int rating, String content) {
        this.order = order;
        this.author = author;
        this.recipient = recipient;
        this.buyerId = order.getUser().getId();
        this.sellerId = order.getSellerId();
        this.rating = rating;
        this.content = content;
    }
}
