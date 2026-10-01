// 실제 지급 연동이 없는 기본 실행 환경에서는 정산 실행을 차단한다.
package com.kitschcatch.backend.domain.order.settlement;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.kitschcatch.backend.global.exception.*;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(SettlementProperties.class)
public class SettlementConfiguration {
    @Bean @ConditionalOnMissingBean(SettlementGateway.class)
    SettlementGateway unavailableSettlementGateway() {
        return new SettlementGateway() {
            public boolean configured() { return false; }
            public Result request(Command command) { throw new BusinessException(ErrorCode.SETTLEMENT_NOT_CONFIGURED); }
            public Result lookup(String settlementId) { throw new BusinessException(ErrorCode.SETTLEMENT_NOT_CONFIGURED); }
        };
    }
}
