// 반품 검수와 승인 권한을 가진 운영자 목록을 설정에서 받는다.
package com.kitschcatch.backend.domain.order.service;
import java.util.Set;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@ConfigurationProperties("app.refunds") @Validated
public record RefundProperties(Set<@Positive Long> operatorUserIds) {
    public RefundProperties { operatorUserIds=operatorUserIds==null?Set.of():Set.copyOf(operatorUserIds); }
}
