// 신고 중복과 계정별 한도를 동일 사용자 잠금 안에서 검증한다.
package com.kitschcatch.backend.domain.report;
import static com.kitschcatch.backend.global.exception.ErrorCode.*;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.domain.user.service.UserPairLock;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ReportProperties.class)
public class ReportService {
    private final ReportRepository reports;
    private final ReportTargetResolver targets;
    private final ReportReasons reasons;
    private final UserPairLock locks;
    private final ReportProperties properties;
    public record Result(ReportRepository.Report report, boolean created) {}
    public record Page(List<ReportRepository.Summary> content, int page, int size, long totalElements, long totalPages) {}
    @Transactional
    public Result create(long actor, ReportRequest raw) {
        ReportRequest r = normalize(raw);
        locks.requireActiveActor(actor);
        String fingerprint = fingerprint(r);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var previous = reports.existing(actor,r,now);
        if (previous.isPresent()) return repeat(previous.get(), fingerprint);
        long owner = targets.owner(actor,r.targetType(),r.targetId());
        if (actor == owner) throw new BusinessException(REPORT_SELF_NOT_ALLOWED);
        locks.lock(actor,owner);
        // 긴 잠금 대기 이후에도 제한과 보관 기한을 현재 시각으로 평가한다.
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        previous = reports.existing(actor,r,now);
        if (previous.isPresent()) return repeat(previous.get(),fingerprint);
        var snapshot = targets.snapshot(actor,owner,r);
        if (reports.countSince(actor,now.minusSeconds(60)) >= properties.minuteLimit()
            || reports.countSince(actor,now.minusSeconds(86400)) >= properties.dailyLimit())
            throw new BusinessException(REPORT_LIMIT_EXCEEDED);
        reports.removeExpiredTarget(actor,r,now);
        return new Result(reports.insert(actor,owner,r,fingerprint,snapshot,now,now.plus(properties.retentionDays(),ChronoUnit.DAYS)),true);
    }
    private Result repeat(ReportRepository.Saved saved, String fingerprint) {
        if (!saved.fingerprint().equals(fingerprint)) throw new BusinessException(REPORT_ALREADY_EXISTS);
        return new Result(saved.report(),false);
    }
    private ReportRequest normalize(ReportRequest r) {
        if (r == null || r.targetType() == null || r.targetId() <= 0) throw new BusinessException(INVALID_INPUT_VALUE);
        if (!reasons.allowed(r.targetType(),r.reasonCode())) throw new BusinessException(REPORT_REASON_INVALID);
        String description = r.description() == null ? "" : r.description().strip();
        if (description.indexOf(0)>=0 || description.codePointCount(0,description.length())>1000
            || (r.reasonCode().equals("OTHER") && description.isEmpty())) throw new BusinessException(INVALID_INPUT_VALUE);
        List<Long> ids = r.messageIds()==null ? List.of() : r.messageIds();
        if (ids.size()>10 || ids.stream().anyMatch(id -> id==null || id<=0) || new HashSet<>(ids).size()!=ids.size()
            || (r.targetType()!=ReportType.CHAT_ROOM && !ids.isEmpty())) throw new BusinessException(REPORT_MESSAGE_INVALID);
        return new ReportRequest(r.targetType(),r.targetId(),r.reasonCode(),description,ids.stream().sorted().toList());
    }
    private String fingerprint(ReportRequest r) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (r.reasonCode()+"\n"+r.description()+"\n"+r.messageIds()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(long actor,int page,int size) {
        if (page<0 || page>10000 || size<1 || size>100) throw new BusinessException(INVALID_INPUT_VALUE);
        locks.requireActiveActor(actor);
        long total=reports.total(actor);
        return new Page(reports.list(actor,page,size).stream().map(ReportRepository.Report::summary).toList(),page,size,total,(total+size-1)/size);
    }
    @Transactional(readOnly=true)
    public ReportRepository.Report detail(long actor,long id) {
        locks.requireActiveActor(actor);
        if(id<=0) throw new BusinessException(BAD_REQUEST);
        return reports.detail(actor,id).orElseThrow(() -> new BusinessException(REPORT_NOT_FOUND));
    }
}
