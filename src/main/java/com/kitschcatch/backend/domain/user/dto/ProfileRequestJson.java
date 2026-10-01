// 프로필 JSON의 허용 필드와 문자열 타입을 검사한다.
package com.kitschcatch.backend.domain.user.dto;

import java.util.Set;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;

final class ProfileRequestJson {
	private static final Set<String> FIELDS = Set.of("nickname", "profileImageKey", "username", "bio");

	private ProfileRequestJson() {
	}

	static void validate(JsonNode root, DeserializationContext context, Class<?> type) {
		if (!root.isObject() || root.properties().stream().anyMatch(field -> !FIELDS.contains(field.getKey()))) {
			context.reportInputMismatch(type, "지원하지 않는 프로필 필드입니다.");
		}
	}

	static String text(JsonNode node, DeserializationContext context, Class<?> type) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString()) {
			return context.reportInputMismatch(type, "프로필 필드는 문자열이어야 합니다.");
		}
		return node.stringValue();
	}
}
