// 선택된 상품 필터만 SQL에 추가하고 작성자를 함께 조회하며 DB에서 페이지를 제한한다.
package com.kitschcatch.backend.domain.post.repository;

import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.service.PostSearchQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Repository;

@Repository
public class PostSearchRepository {
    private final EntityManager entityManager;

    public PostSearchRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    public Page<Post> search(PostSearchQuery query) {
        var builder = entityManager.getCriteriaBuilder();
        var select = builder.createQuery(Post.class);
        var post = select.from(Post.class);
        post.fetch("user", JoinType.INNER);
        select.where(predicates(builder, post, query));
        var created = builder.desc(post.get("createdAt"));
        var id = builder.desc(post.get("id"));
        select.orderBy(switch (query.sort()) {
            case LATEST -> List.of(created, id);
            case PRICE_ASC -> List.of(builder.asc(post.get("price")), created, id);
            case PRICE_DESC -> List.of(builder.desc(post.get("price")), created, id);
        });
        var content = entityManager.createQuery(select)
            .setFirstResult(Math.toIntExact(query.pageable().getOffset()))
            .setMaxResults(query.size()).getResultList();

        var count = builder.createQuery(Long.class);
        var countPost = count.from(Post.class);
        count.select(builder.count(countPost)).where(predicates(builder, countPost, query));
        return new PageImpl<>(content, query.pageable(), entityManager.createQuery(count).getSingleResult());
    }

    private Predicate[] predicates(CriteriaBuilder builder, Root<Post> post, PostSearchQuery query) {
        var predicates = new ArrayList<Predicate>();
        predicates.add(builder.isNull(post.get("deletedAt")));
        if (query.status() != null) predicates.add(builder.equal(post.get("productStatus"), query.status()));
        if (query.category() != null) predicates.add(builder.equal(post.get("productCategory"), query.category()));
        if (query.condition() != null) predicates.add(builder.equal(post.get("productCondition"), query.condition()));
        if (query.minPrice() != null) predicates.add(builder.ge(post.get("price"), query.minPrice()));
        if (query.maxPrice() != null) predicates.add(builder.le(post.get("price"), query.maxPrice()));
        if (query.keyword() != null) {
            String pattern = "%" + query.keyword().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            predicates.add(builder.or(builder.like(builder.lower(post.get("title")), pattern, '!'),
                builder.like(builder.lower(post.get("description")), pattern, '!')));
        }
        return predicates.toArray(Predicate[]::new);
    }
}
