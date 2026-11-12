// 매장 사진 키가 지정된 매장 경로와 이미지 확장자를 사용하는지 검사한다.
package com.kitschcatch.backend.domain.store.service;

import java.util.regex.Pattern;

public final class StoreImageKey {
    private static final Pattern FORMAT = Pattern.compile(
        "stores/([1-9][0-9]{0,18})/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)");

    private StoreImageKey() {}

    public static boolean matches(long storeId, String key) {
        if (key == null || key.length() > 180) return false;
        var matcher = FORMAT.matcher(key);
        return matcher.matches() && matcher.group(1).equals(Long.toString(storeId));
    }
}
