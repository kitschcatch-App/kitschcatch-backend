// 상세 화면에 표시할 편의시설 코드와 이름을 정의한다.
package com.kitschcatch.backend.domain.store.entity;

public enum StoreAmenityCode {
    PARKING("주차"),
    RESTROOM("화장실"),
    WIFI("와이파이"),
    ELEVATOR("엘리베이터"),
    WHEELCHAIR_ACCESS("휠체어 접근"),
    PET_FRIENDLY("반려동물 동반");

    private final String label;

    StoreAmenityCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
