// 사용자 간 변경을 같은 행 잠금 순서로 직렬화하고 잠금 뒤 활성 상태를 재확인한다.
package com.kitschcatch.backend.domain.user.service;

import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserPairLock {
    private final UserRepository users;

    @Transactional(propagation = Propagation.MANDATORY)
    public Pair lock(long actor, long target) {
        long firstId = Math.min(actor, target);
        var first = users.findByIdForUpdate(firstId).orElse(null);
        var second = users.findByIdForUpdate(Math.max(actor, target)).orElse(null);
        requireActiveActor(actor);
        return firstId == actor ? new Pair(first, second) : new Pair(second, first);
    }

    public void requireActiveActor(long actor) {
        if (!users.existsByIdAndWithdrawnAtIsNull(actor)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
    }

    public void requireActiveTarget(long target) {
        if (!users.existsByIdAndWithdrawnAtIsNull(target)) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
    }

    public record Pair(User actor, User target) {}
}
