// 사용자 아이디와 한줄소개를 저장 및 중복 확인에 사용할 형태로 검증한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PublicProfilePolicy {

	public String normalizeUsername(String username) {
		if (username == null) {
			return null;
		}
		String normalized = username.strip().toLowerCase(Locale.ROOT);
		if (!normalized.matches("[a-z0-9_]{3,30}")) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
				"사용자 아이디는 영문, 숫자, 밑줄로 이루어진 3~30자여야 합니다.");
		}
		return normalized;
	}

	public String normalizeBio(String bio) {
		if (bio == null) {
			return null;
		}
		if (bio.codePoints().anyMatch(code -> Character.isISOControl(code)
			|| code == 0x2028 || code == 0x2029)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "소개는 한 줄이어야 합니다.");
		}
		String normalized = Normalizer.normalize(bio.strip(), Normalizer.Form.NFC);
		if (normalized.length() > 160) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "소개는 160자 이하여야 합니다.");
		}
		return normalized.isEmpty() ? null : normalized;
	}
}
