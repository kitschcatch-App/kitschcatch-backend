// 매장 소개 사진의 객체 키와 표시 순서를 읽는다.
package com.kitschcatch.backend.domain.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_images", uniqueConstraints = {
    @UniqueConstraint(name = "uk_store_images_key", columnNames = "object_key"),
    @UniqueConstraint(name = "uk_store_images_sort", columnNames = {"store_id", "sort_order"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreImage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 180)
    private String objectKey;

    @Column(nullable = false)
    private int sortOrder;

    @Column(length = 200)
    private String caption;

    @Column(length = 200)
    private String credit;

    @Column(length = 1000)
    private String sourceUrl;
}
