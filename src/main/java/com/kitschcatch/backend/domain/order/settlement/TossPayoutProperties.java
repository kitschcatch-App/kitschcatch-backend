// 토스 지급대행 전용 키와 제한 시간을 관리하고 로그에서 키를 숨긴다.
package com.kitschcatch.backend.domain.order.settlement;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@ConfigurationProperties("app.settlement.toss") @Validated
public record TossPayoutProperties(boolean enabled,String baseUrl,String secretKey,String securityKey,
    @Min(100) @Max(60000) int timeoutMillis,@Min(1) @Max(100) int lookupMaxPages) {
    public boolean configured() {
        return enabled && secretKey!=null && !secretKey.isBlank() && securityKey!=null && securityKey.matches("[0-9a-fA-F]{64}");
    }
    @Override public String toString() { return "TossPayoutProperties[enabled="+enabled+", keys=REDACTED]"; }
}
