// 신고 접수 한도와 보관 기한을 양수 설정으로 검증한다.
package com.kitschcatch.backend.domain.report;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("app.reports")
public record ReportProperties(int minuteLimit, int dailyLimit, int retentionDays) {
    public ReportProperties {
        if (minuteLimit < 1 || dailyLimit < 1 || retentionDays < 1 || retentionDays > 3650)
            throw new IllegalArgumentException("신고 한도와 보관 기한 설정이 올바르지 않습니다.");
    }
}
