// 검색어 정규화와 중복 키를 생성한다.
package com.kitschcatch.backend.domain.search;

import java.text.Normalizer;
import java.util.Locale;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.kitschcatch.backend.global.exception.*;

public record SearchKeyword(String display, String normalized, String key) {
    public static SearchKeyword from(String raw) {
        if (raw == null) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        String display = raw.strip();
        if (display.isEmpty() || display.length() > 1000 || display.codePoints().anyMatch(Character::isISOControl))
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        String normalized = Normalizer.normalize(display, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (normalized.length() > 1000) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        try {
            return new SearchKeyword(display, normalized, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
