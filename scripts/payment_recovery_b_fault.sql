-- 지정한 결제의 성공 전이를 PostgreSQL 커밋에서 거절하고 트랜잭션 내부 상태를 남긴다.
BEGIN;

CREATE FUNCTION reject_b_recovery_commit() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    attempted json;
BEGIN
    SELECT json_build_object(
        'payment_id', p.payment_id, 'payment', p.payment_status, 'approved_at', p.approved_at,
        'order_id', o.order_number, 'order', o.order_status,
        'post', s.product_status, 'owner', s.active_order_number,
        'attempt_id', a.attempt_id, 'attempt', a.attempt_status, 'completed_at', a.completed_at,
        'notifications', coalesce((
            SELECT json_agg(n.user_id ORDER BY n.user_id)
            FROM notifications n
            WHERE n.event_key = 'payment:' || NEW.payment_id || ':PAYMENT_SUCCESS'
        ), '[]'::json)
    )
    INTO STRICT attempted
    FROM payments p
    JOIN orders o ON o.id = p.order_id
    JOIN posts s ON s.id = o.post_id
    JOIN payment_attempts a ON a.payment_id = p.id AND a.attempt_id = p.current_attempt_id
    WHERE p.id = NEW.id;

    RAISE EXCEPTION USING ERRCODE = 'P126B', MESSAGE = 'B_COMMIT_REJECTED ' || attempted::text;
END;
$$;

CREATE CONSTRAINT TRIGGER reject_b_recovery_commit
AFTER UPDATE ON payments
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
WHEN (NEW.payment_id = :'payment_id'
      AND OLD.payment_status IS DISTINCT FROM NEW.payment_status
      AND NEW.payment_status = 'SUCCESS')
EXECUTE FUNCTION reject_b_recovery_commit();

COMMIT;
