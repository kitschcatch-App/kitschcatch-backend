// 사용자 닉네임을 저장·중복 확인에 사용할 형태로 정규화한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.text.Normalizer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NicknamePolicy {

	public String normalize(String nickname) {
		if (!StringUtils.hasText(nickname)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "닉네임은 비어 있을 수 없습니다.");
		}
		String normalized = Normalizer.normalize(nickname.trim(), Normalizer.Form.NFC);
		if (normalized.isBlank() || normalized.length() > 50) {
			throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE, "닉네임은 1자 이상 50자 이하여야 합니다.");
		}
		return normalized;
	}
}
