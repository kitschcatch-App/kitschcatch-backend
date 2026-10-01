-- 기존 프로필 상태를 보존하면서 선택적 사용자 아이디와 한줄소개를 추가한다.
BEGIN;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS username VARCHAR(30),
    ADD COLUMN IF NOT EXISTS bio VARCHAR(160);

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username ON users (username);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_users_public_profile_fields' AND conrelid = 'users'::regclass
    ) THEN
        ALTER TABLE users ADD CONSTRAINT ck_users_public_profile_fields CHECK (
            (username IS NULL OR (username ~ '^[a-z0-9_]{3,30}$' AND char_length(username) BETWEEN 3 AND 30))
            AND (bio IS NULL OR (char_length(bio) BETWEEN 1 AND 160
                AND bio !~ '[[:cntrl:]]' AND position(chr(8232) in bio) = 0 AND position(chr(8233) in bio) = 0))
            AND (profile_registered_at IS NOT NULL OR (username IS NULL AND bio IS NULL))
        );
    END IF;
END $$;

COMMIT;
