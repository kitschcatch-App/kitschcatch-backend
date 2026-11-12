-- 운영 DB의 제한된 세션에서 psql -v reviewer_id=... -v enabled=true -v reason=... -f 로 실행한다.
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('report_review.user_id', :'reviewer_id', true),
       set_config('report_review.enabled', :'enabled', true),
       set_config('report_review.reason', :'reason', true);
DO $$
DECLARE
    reviewer_id BIGINT := current_setting('report_review.user_id')::bigint;
    enable_review BOOLEAN := current_setting('report_review.enabled')::boolean;
    change_reason TEXT := btrim(current_setting('report_review.reason'));
    withdrawn TIMESTAMPTZ;
BEGIN
    IF change_reason = '' OR char_length(change_reason) > 1000 THEN
        RAISE EXCEPTION '권한 변경 사유는 1~1000자여야 합니다.';
    END IF;
    SELECT withdrawn_at INTO withdrawn FROM users WHERE id = reviewer_id FOR UPDATE;
    IF NOT FOUND OR (enable_review AND withdrawn IS NOT NULL) THEN
        RAISE EXCEPTION '권한을 변경할 사용자 계정이 올바르지 않습니다.';
    END IF;

    IF enable_review THEN
        INSERT INTO store_review_moderators(user_id, granted_at)
        VALUES (reviewer_id, clock_timestamp())
        ON CONFLICT (user_id) DO UPDATE SET granted_at = EXCLUDED.granted_at, revoked_at = NULL
        WHERE store_review_moderators.revoked_at IS NOT NULL;
    ELSE
        UPDATE store_review_moderators SET revoked_at = clock_timestamp()
        WHERE user_id = reviewer_id AND revoked_at IS NULL;
    END IF;

    IF FOUND THEN
        INSERT INTO store_review_moderator_events(user_id, enabled, database_actor, reason)
        VALUES (reviewer_id, enable_review, session_user, change_reason);
    END IF;
END $$;
COMMIT;
