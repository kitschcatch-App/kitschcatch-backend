// 검색어 기록 저장과 조회·삭제·집계 및 정리를 수행한다.
package com.kitschcatch.backend.domain.search;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.*;

@Service
public class SearchService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final Clock clock;
    private final SearchProperties settings;
    public SearchService(JdbcTemplate jdbc, UserRepository users, @Qualifier("searchClock") Clock clock, SearchProperties settings) {
        this.jdbc=jdbc; this.users=users; this.clock=clock; this.settings=settings;
    }
    public record Recent(long id, String keyword, Instant lastSearchedAt) {}
    public record Items<T>(List<T> items) {}
    public record Ranked(int rank, String keyword) {}
    public record Suggestion(String keyword) {}
    public record Related(String keyword, List<Suggestion> items) {}
    public record Popular(String timezone, LocalDate windowStartDate, LocalDate windowEndDate, Instant generatedAt, List<Ranked> items) {}
    private void lock(long id) { users.findActiveByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN)); }
    private void active(long id) { if (!users.existsByIdAndWithdrawnAtIsNull(id)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN); }
    private int limit(int limit) { if (limit < 1 || limit > 20) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE); return limit; }
    @Transactional
    public Recent record(long userId, String raw) {
        var word=SearchKeyword.from(raw); lock(userId);
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        var result=jdbc.queryForObject("""
            INSERT INTO recent_searches(user_id,keyword_key,display_keyword,normalized_keyword,last_searched_at)
            VALUES (?,?,?,?,?) ON CONFLICT(user_id,keyword_key) DO UPDATE SET
            display_keyword=excluded.display_keyword, normalized_keyword=excluded.normalized_keyword,
            last_searched_at=excluded.last_searched_at
            RETURNING id,display_keyword,last_searched_at
            """, (rs,n) -> new Recent(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toInstant()),
            userId,word.key(),word.display(),word.normalized(),java.sql.Timestamp.from(now));
        jdbc.update("""
            DELETE FROM recent_searches WHERE user_id=? AND id NOT IN
            (SELECT id FROM recent_searches WHERE user_id=? ORDER BY last_searched_at DESC,id DESC LIMIT ?)
            """,userId,userId,settings.recentLimit());
        jdbc.update("""
            INSERT INTO search_daily_users(search_date,user_id,keyword_key,normalized_keyword) VALUES (?,?,?,?)
            ON CONFLICT(search_date,user_id,keyword_key) DO NOTHING
            """,LocalDate.ofInstant(now,ZONE),userId,word.key(),word.normalized());
        return result;
    }
    @Transactional(readOnly=true)
    public Items<Recent> recent(long userId) {
        active(userId);
        return new Items<>(jdbc.query("SELECT id,display_keyword,last_searched_at FROM recent_searches WHERE user_id=? ORDER BY last_searched_at DESC,id DESC LIMIT ?",
            (rs,n)->new Recent(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toInstant()),userId,settings.recentLimit()));
    }
    @Transactional
    public void delete(long userId, Long recordId) {
        if (recordId != null && recordId < 1) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        lock(userId);
        if (recordId == null) jdbc.update("DELETE FROM recent_searches WHERE user_id=?",userId);
        else jdbc.update("DELETE FROM recent_searches WHERE user_id=? AND id=?",userId,recordId);
    }
    @Transactional(readOnly=true)
    public Popular popular(long userId,int size) {
        active(userId); limit(size);
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        var today=LocalDate.ofInstant(now,ZONE); var start=today.minusDays(settings.popularDays()-1L);
        var words=jdbc.query("""
            SELECT min(normalized_keyword) AS keyword FROM search_daily_users
            WHERE search_date BETWEEN ? AND ? GROUP BY keyword_key
            HAVING count(DISTINCT user_id)>=?
            ORDER BY count(*) DESC,count(DISTINCT user_id) DESC,keyword_key ASC LIMIT ?
            """,(rs,n)->new Ranked(n+1,rs.getString(1)),start,today,settings.minimumUsers(),size);
        return new Popular(ZONE.toString(),start,today,now,words);
    }
    @Transactional(readOnly=true)
    public Related related(long userId,String raw,int size) {
        active(userId); limit(size); var word=SearchKeyword.from(raw);
        return new Related(word.display(),jdbc.query("""
            SELECT target_keyword FROM related_search_keywords
            WHERE source_key=? AND active=true AND target_key<>source_key ORDER BY priority ASC,target_key ASC LIMIT ?
            """,(rs,n)->new Suggestion(rs.getString(1)),word.key(),size));
    }
    @Transactional
    public int cleanupBatch() {
        var cutoff=LocalDate.now(clock.withZone(ZONE)).minusDays(settings.popularDays()-1L);
        return jdbc.update("DELETE FROM search_daily_users WHERE id IN (SELECT id FROM search_daily_users WHERE search_date<? ORDER BY id LIMIT 1000)",cutoff);
    }
}
