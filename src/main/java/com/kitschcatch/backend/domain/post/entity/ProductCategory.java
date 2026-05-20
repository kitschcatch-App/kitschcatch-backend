package com.kitschcatch.backend.domain.post.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
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

	@JsonCreator
	public static ProductCategory from(String value) {
		for (ProductCategory category : values()) {
			if (category.name().equals(value) || category.label.equals(value)) {
				return category;
			}
		}
		throw new IllegalArgumentException("Unknown product category: " + value);
	}
}
