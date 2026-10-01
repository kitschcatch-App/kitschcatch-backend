// 내부 정산 실행자와 확정된 수수료율만 설정에서 받는다.
package com.kitschcatch.backend.domain.order.settlement;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
@ConfigurationProperties("app.settlement") @Validated
public record SettlementProperties(Set<@Positive Long> operatorUserIds,@Min(0) @Max(9999) Integer feeBasisPoints) {
    public SettlementProperties { operatorUserIds=operatorUserIds==null?Set.of():Set.copyOf(operatorUserIds); }
}
