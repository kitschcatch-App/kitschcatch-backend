// 매장 소개 사진을 화면 표시 순서대로 조회한다.
package com.kitschcatch.backend.domain.store.repository;

import com.kitschcatch.backend.domain.store.entity.StoreImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreImageRepository extends JpaRepository<StoreImage, Long> {
    List<StoreImage> findByStoreIdOrderBySortOrder(long storeId);
}
