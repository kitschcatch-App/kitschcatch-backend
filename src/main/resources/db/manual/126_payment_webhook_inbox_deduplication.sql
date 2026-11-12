-- 기존 수신함 기록을 보존하면서 이벤트 종류와 본문 해시에 유일 제약을 추가한다.
BEGIN;

SET LOCAL lock_timeout = '5s';
LOCK TABLE payment_webhook_events IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    existing_definition text;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO existing_definition
    FROM pg_constraint
    WHERE conrelid = 'payment_webhook_events'::regclass
      AND conname = 'uk_payment_webhook_events_event_type_hash';

    IF existing_definition IS NOT NULL THEN
        IF existing_definition <> 'UNIQUE (event_type, event_hash)' THEN
            RAISE EXCEPTION '웹훅 수신함에 같은 이름의 다른 제약이 있습니다.';
        END IF;
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1 FROM payment_webhook_events
        GROUP BY event_type, event_hash
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION '웹훅 수신함에 중복 이벤트가 있습니다. 처리 이력을 확인한 뒤 다시 적용하세요.';
    END IF;

    ALTER TABLE payment_webhook_events
        ADD CONSTRAINT uk_payment_webhook_events_event_type_hash UNIQUE (event_type, event_hash);
END;
$$;

COMMIT;
