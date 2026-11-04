// 검색어 API의 저장 및 설정 계약을 정의한다.
package com.kitschcatch.backend.domain.search;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {
    @Bean("searchClock") Clock searchClock() { return Clock.systemUTC(); }
}
