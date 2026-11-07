// 신고 대상의 접근 권한을 확인하고 서버에 저장된 내용만 기록한다.
package com.kitschcatch.backend.domain.report;
import static com.kitschcatch.backend.global.exception.ErrorCode.*;
import com.kitschcatch.backend.global.exception.BusinessException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class ReportTargetResolver {
    private final JdbcTemplate jdbc;
    public long owner(long actor, ReportType type, long id) {
        List<Long> owners = switch (type) {
            case USER -> jdbc.queryForList("select id from users where id=? and withdrawn_at is null", Long.class, id);
            case POST -> jdbc.queryForList("select user_id from posts where id=? and deleted_at is null", Long.class, id);
            case STORE_REVIEW -> jdbc.queryForList("select author_id from store_reviews where id=? and deleted_at is null and hidden_at is null", Long.class, id);
            case CHAT_ROOM -> jdbc.queryForList("select case when buyer_id=? then seller_id else buyer_id end from chat_rooms where id=? and (buyer_id=? or seller_id=?)", Long.class, actor, id, actor, actor);
        };
        if (owners.isEmpty()) throw new BusinessException(REPORT_TARGET_NOT_FOUND);
        return owners.getFirst();
    }
    public Map<String, Object> snapshot(long actor, long owner, ReportRequest r) {
        // 사용자 잠금 이후 대상 행을 잠가 수정·삭제와 기록 생성을 직렬화한다.
        String table = switch (r.targetType()) { case USER -> "users"; case POST -> "posts"; case CHAT_ROOM -> "chat_rooms"; case STORE_REVIEW -> "store_reviews"; };
        jdbc.queryForList("select id from " + table + " where id=? for update", Long.class, r.targetId());
        if (owner(actor, r.targetType(), r.targetId()) != owner) throw new BusinessException(REPORT_TARGET_NOT_FOUND);
        if (r.targetType() != ReportType.CHAT_ROOM && !Boolean.TRUE.equals(jdbc.queryForObject(
            "select withdrawn_at is null from users where id=?", Boolean.class, owner))) throw new BusinessException(REPORT_TARGET_NOT_FOUND);
        Map<String,Object> snapshot = new LinkedHashMap<>(switch (r.targetType()) {
            case USER -> jdbc.queryForMap("select id,nickname,username,bio,profile_image_key from users where id=?", r.targetId());
            case POST -> jdbc.queryForMap("select id,user_id,title,description,price,cast(created_at as text) as created_at,cast(updated_at as text) as updated_at from posts where id=?", r.targetId());
            case CHAT_ROOM -> jdbc.queryForMap("select id,post_id,buyer_id,seller_id from chat_rooms where id=?", r.targetId());
            case STORE_REVIEW -> jdbc.queryForMap("select id,store_id,author_id,rating,content,version,cast(created_at as text) as created_at,cast(updated_at as text) as updated_at from store_reviews where id=?", r.targetId());
        });
        if (r.targetType() == ReportType.POST) snapshot.put("images", jdbc.queryForList(
            "select id,object_key,sort_order from post_images where post_id=? order by sort_order,id", r.targetId()));
        if (r.targetType() == ReportType.STORE_REVIEW) snapshot.put("images", jdbc.queryForList(
            "select id,object_key,sort_order from store_review_images where review_id=? and state='ATTACHED' order by sort_order,id", r.targetId()));
        if (r.targetType() == ReportType.CHAT_ROOM) {
            List<Map<String,Object>> messages = new ArrayList<>();
            for (long id : r.messageIds().stream().sorted().toList()) {
                var rows = jdbc.queryForList("select id,sender_id,message_type,content,image_url,cast(created_at as text) as created_at from chat_messages where id=? and chat_room_id=?", id, r.targetId());
                if (rows.isEmpty()) throw new BusinessException(REPORT_MESSAGE_INVALID);
                var message = new LinkedHashMap<>(rows.getFirst());
                // 만료 서명 URL과 임의 외부 주소를 증거에 복사하지 않는다.
                Object raw = message.remove("image_url");
                if (raw instanceof String url) {
                    try {
                        var uri = java.net.URI.create(url);
                        if (uri.getPath() != null && uri.getPath().contains("/chats/"))
                            message.put("imageReferencePath", uri.getPath());
                    } catch (IllegalArgumentException ignored) { }
                }
                messages.add(message);
            }
            snapshot.put("messages", messages);
        }
        return snapshot;
    }
}
