// 사용자 소유 배송지를 기본 여부와 최근 수정순으로 조회한다.
package com.kitschcatch.backend.domain.address.repository;
import com.kitschcatch.backend.domain.address.entity.ShippingAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ShippingAddressRepository extends JpaRepository<ShippingAddress,Long> {
 List<ShippingAddress> findByUserIdOrderByIsDefaultDescUpdatedAtDescIdDesc(Long userId);
 Optional<ShippingAddress> findByIdAndUserId(Long id,Long userId);
 void deleteByUserId(Long userId);
}
