// 대상별 신고 사유와 화면 표시 정보를 하나의 정의로 제공한다.
package com.kitschcatch.backend.domain.report;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class ReportReasons {
    public record Reason(String code, String label, boolean descriptionRequired, int maxDescriptionLength) {}
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("SPAM", "광고·도배입니다.");
        LABELS.put("HARASSMENT", "욕설·괴롭힘이 있습니다.");
        LABELS.put("FRAUD_SUSPECTED", "사기가 의심됩니다.");
        LABELS.put("IMPERSONATION", "다른 사람을 사칭합니다.");
        LABELS.put("PROHIBITED_ITEM", "판매 금지 상품입니다.");
        LABELS.put("COUNTERFEIT_SUSPECTED", "위조 상품이 의심됩니다.");
        LABELS.put("INAPPROPRIATE_CONTENT", "부적절한 내용이 있습니다.");
        LABELS.put("PERSONAL_INFORMATION", "개인정보가 노출되어 있습니다.");
        LABELS.put("OFF_TOPIC", "매장과 관련 없는 리뷰입니다.");
        LABELS.put("OTHER", "기타 사유입니다.");
    }
    public List<Reason> list(ReportType type) {
        return LABELS.entrySet().stream().filter(e -> allowed(type, e.getKey()))
            .map(e -> new Reason(e.getKey(), e.getValue(), e.getKey().equals("OTHER"), 1000)).toList();
    }
    public boolean allowed(ReportType type, String code) {
        if (type == null || code == null || !LABELS.containsKey(code)) return false;
        return switch (code) {
            case "HARASSMENT" -> type != ReportType.POST;
            case "FRAUD_SUSPECTED" -> type != ReportType.STORE_REVIEW;
            case "IMPERSONATION" -> type == ReportType.USER;
            case "PROHIBITED_ITEM", "COUNTERFEIT_SUSPECTED" -> type == ReportType.POST;
            case "OFF_TOPIC" -> type == ReportType.STORE_REVIEW;
            default -> true;
        };
    }
}
