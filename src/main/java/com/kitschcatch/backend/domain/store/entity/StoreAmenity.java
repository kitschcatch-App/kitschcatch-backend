// 검수한 매장별 편의시설 제공 여부를 읽는다.
package com.kitschcatch.backend.domain.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_amenities", uniqueConstraints =
    @UniqueConstraint(name = "uk_store_amenities_code", columnNames = {"store_id", "code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreAmenity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private StoreAmenityCode code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StoreAmenityStatus status;

    @Column(length = 300)
    private String note;
}
