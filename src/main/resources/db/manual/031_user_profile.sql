-- 사용자 프로필 등록 상태와 프로필 이미지 연결 정보를 추가한다.
BEGIN;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS nickname_key VARCHAR(50),
    ADD COLUMN IF NOT EXISTS profile_image_key VARCHAR(512),
    ADD COLUMN IF NOT EXISTS profile_registered_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_nickname_key
    ON users (nickname_key)
    WHERE nickname_key IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_users_profile_registration_fields'
          AND conrelid = 'users'::regclass
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT ck_users_profile_registration_fields
            CHECK (
                (profile_registered_at IS NULL AND nickname_key IS NULL AND profile_image_key IS NULL)
                OR (profile_registered_at IS NOT NULL AND nickname_key IS NOT NULL)
            );
    END IF;
END $$;

COMMIT;
