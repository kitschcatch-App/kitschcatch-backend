// 매장 목록의 페이지 조회와 매장 상세 조회를 제공한다.
package com.kitschcatch.backend.domain.store.repository;

import com.kitschcatch.backend.domain.store.entity.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {
    Page<Store> findByRegion(String region, Pageable pageable);
}
