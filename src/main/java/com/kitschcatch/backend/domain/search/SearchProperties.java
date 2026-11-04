// 검색어 API의 저장 및 설정 계약을 정의한다.
package com.kitschcatch.backend.domain.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

@Validated
@ConfigurationProperties("app.search")
public record SearchProperties(@DefaultValue("20") @Min(1) @Max(100) int recentLimit,
    @DefaultValue("7") @Min(1) @Max(90) int popularDays,
    @DefaultValue("3") @Min(3) int minimumUsers) {}
