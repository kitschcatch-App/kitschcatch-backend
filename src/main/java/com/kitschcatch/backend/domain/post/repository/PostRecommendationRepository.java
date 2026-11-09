// 추천 기준을 일괄 조회하고 후보의 자격과 추천 순서를 DB에서 적용한다.
package com.kitschcatch.backend.domain.post.repository;

import com.kitschcatch.backend.domain.block.entity.UserBlock;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Collection;
import java.util.List;
import java.util.SequencedMap;
import org.springframework.stereotype.Repository;

@Repository
public class PostRecommendationRepository {
    private final EntityManager entityManager;

    public PostRecommendationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<ReferencePost> findReferences(long viewerId, Collection<Long> recentIds) {
        var builder = entityManager.getCriteriaBuilder();
        var query = builder.createQuery(ReferencePost.class);
        var post = query.from(Post.class);
        query.select(builder.construct(ReferencePost.class, post.get("id"), post.get("productCategory"), post.get("price")))
            .where(eligible(builder, query, post, viewerId), post.get("id").in(recentIds));
        return entityManager.createQuery(query).getResultList();
    }

    public List<Post> findRecommendations(long viewerId, Collection<Long> recentIds,
                                          SequencedMap<ProductCategory, Long> referencePrices, int limit) {
        var builder = entityManager.getCriteriaBuilder();
        var query = builder.createQuery(Post.class);
        var post = query.from(Post.class);
        post.fetch("user", JoinType.INNER);
        var category = post.<ProductCategory>get("productCategory");
        var price = post.<Long>get("price");
        var categoryRank = builder.<ProductCategory, Integer>selectCase(category);
        var referencePrice = builder.<ProductCategory, Long>selectCase(category);
        int rank = 0;
        for (var reference : referencePrices.entrySet()) {
            categoryRank.when(reference.getKey(), builder.literal(rank++));
            referencePrice.when(reference.getKey(), builder.literal(reference.getValue()));
        }
        var priceDifference = builder.abs(builder.diff(price, referencePrice.otherwise(price)));
        query.select(post).where(eligible(builder, query, post, viewerId),
            builder.equal(post.get("productStatus"), ProductStatus.ON_SALE),
            category.in(referencePrices.keySet()), builder.not(post.get("id").in(recentIds)))
            .orderBy(builder.asc(categoryRank.otherwise(builder.literal(rank))), builder.asc(priceDifference),
                builder.desc(post.get("createdAt")), builder.desc(post.get("id")));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }

    private Predicate eligible(CriteriaBuilder builder, CriteriaQuery<?> query, Root<Post> post, long viewerId) {
        var seller = post.get("user");
        var sellerId = seller.<Long>get("id");
        var block = query.subquery(Integer.class);
        var relation = block.from(UserBlock.class);
        var blocker = relation.get("blocker").get("id");
        var blocked = relation.get("blocked").get("id");
        block.select(builder.literal(1)).where(builder.or(
            builder.and(builder.equal(blocker, viewerId), builder.equal(blocked, sellerId)),
            builder.and(builder.equal(blocker, sellerId), builder.equal(blocked, viewerId))));
        return builder.and(builder.isNull(post.get("deletedAt")), builder.isNull(seller.get("withdrawnAt")),
            builder.notEqual(sellerId, viewerId), builder.not(builder.exists(block)));
    }

    public record ReferencePost(long id, ProductCategory category, long price) {}
}
