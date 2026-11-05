// 차단 방향과 관계없이 두 사용자의 접촉 제한을 판정한다.
package com.kitschcatch.backend.domain.block.service;

import com.kitschcatch.backend.domain.block.repository.UserBlockRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserInteractionPolicy {
    private final UserBlockRepository blocks;

    public boolean isBlocked(long actor, long target) {
        return actor != target && blocks.existsBetween(actor, target);
    }

    public void requireAllowed(long actor, long target) {
        if (isBlocked(actor, target)) throw new BusinessException(ErrorCode.USER_INTERACTION_BLOCKED);
    }

    public Status status(long actor, long target) {
        return statuses(actor, List.of(target)).getOrDefault(target, Status.ALLOWED);
    }

    public Map<Long, Status> statuses(long actor, Collection<Long> targets) {
        if (targets.isEmpty()) return Map.of();
        var result = new HashMap<Long, Status>();
        for (var row : blocks.findRelations(actor, targets)) {
            if (row.getBlockerId() == actor) {
                result.put(row.getBlockedId(), new Status(true, false));
            } else {
                result.putIfAbsent(row.getBlockerId(), new Status(false, false));
            }
        }
        return result;
    }

    public record Status(boolean blockedByMe, boolean allowed) {
        public static final Status ALLOWED = new Status(false, true);
    }
}
