package com.kitschcatch.backend.domain.post.entity;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ProductCategory {
	ANIME_MANGA("애니/만화"),
	GAME("게임"),
	GOODS("굿즈"),
	COSPLAY("코스프레"),
	BOOK("서적"),
	MUSIC_VIDEO("음반/영상"),
	ETC("기타");

	private final String label;

	ProductCategory(String label) {
		this.label = label;
	}

	@JsonValue
	public String getLabel() {
		return label;
	}
}
