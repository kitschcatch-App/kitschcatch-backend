// 토스 지급대행 HTTP 어댑터를 등록하며 설정이 없으면 외부 호출을 차단한다.
package com.kitschcatch.backend.domain.order.settlement;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.client.RestClient;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({SettlementProperties.class,TossPayoutProperties.class})
public class SettlementConfiguration {
    @Bean @ConditionalOnMissingBean(SettlementGateway.class)
    SettlementGateway settlementGateway(RestClient.Builder builder,TossPayoutProperties properties) {
        return new HttpTossPayoutGateway(builder,properties);
    }
}
