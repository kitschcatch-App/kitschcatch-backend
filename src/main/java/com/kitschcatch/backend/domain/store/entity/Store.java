// 매장 지도에 표시할 위치와 연락처, 요일별 영업시간을 저장한다.
package com.kitschcatch.backend.domain.store.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stores", indexes = {
    @Index(name = "ix_stores_region_id", columnList = "region,id"),
    @Index(name = "ix_stores_latitude", columnList = "latitude")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 20)
    private String region;
    @Column(nullable = false, length = 500)
    private String address;
    @Column(nullable = false)
    private double latitude;
    @Column(nullable = false)
    private double longitude;
    @Column(length = 30)
    private String phone;

    @ElementCollection
    @CollectionTable(name = "store_business_hours", joinColumns = @JoinColumn(name = "store_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_store_business_hours_day", columnNames = {"store_id", "day_of_week"}))
    private List<StoreBusinessHours> businessHours = new ArrayList<>();

    @Builder
    private Store(String name, String region, String address, double latitude, double longitude,
                  String phone, List<StoreBusinessHours> businessHours) {
        this.name = name;
        this.region = region;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.phone = phone;
        if (businessHours != null) {
            this.businessHours.addAll(businessHours);
        }
    }
}
