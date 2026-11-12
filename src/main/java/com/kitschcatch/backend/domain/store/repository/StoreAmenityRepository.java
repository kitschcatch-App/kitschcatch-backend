// 매장에 등록된 편의시설 확인 결과를 조회한다.
package com.kitschcatch.backend.domain.store.repository;

import com.kitschcatch.backend.domain.store.entity.StoreAmenity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreAmenityRepository extends JpaRepository<StoreAmenity, Long> {
    List<StoreAmenity> findByStoreId(long storeId);
}
