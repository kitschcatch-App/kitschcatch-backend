-- 이슈 #53의 선택 이메일과 제공자별 독립 계정을 위한 PostgreSQL 수동 마이그레이션이다.
-- 배포 전 users 테이블이 있는 전용 대상 schema의 search_path에서 실행한다.
BEGIN;
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
DO $$
DECLARE constraint_name text;
BEGIN
  FOR constraint_name IN
    SELECT c.conname FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attname = 'email'
    WHERE c.conrelid = 'users'::regclass AND c.contype = 'u'
      AND c.conkey = ARRAY[a.attnum]::smallint[]
  LOOP
    EXECUTE format('ALTER TABLE users DROP CONSTRAINT %I', constraint_name);
  END LOOP;
END $$;
-- 기존 Hibernate의 KAKAO 단일 enum CHECK만 식별해 교체한다. 다른 업무 CHECK는 유지한다.
DO $$
DECLARE constraint_name text;
BEGIN
  FOR constraint_name IN
    SELECT c.conname FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attname = 'auth_provider'
    WHERE c.conrelid = 'users'::regclass AND c.contype = 'c'
      AND c.conkey = ARRAY[a.attnum]::smallint[]
      AND regexp_replace(replace(pg_get_expr(c.conbin, c.conrelid), '::text', ''), '[()[:space:]]', '', 'g')
        = 'auth_provider=''KAKAO'''
  LOOP
    EXECUTE format('ALTER TABLE users DROP CONSTRAINT %I', constraint_name);
  END LOOP;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'users'::regclass
      AND conname = 'ck_users_auth_provider_social_053') THEN
    ALTER TABLE users ADD CONSTRAINT ck_users_auth_provider_social_053
      CHECK (auth_provider IN ('KAKAO', 'NAVER', 'APPLE'));
  END IF;
END $$;
-- email의 독립 UNIQUE INDEX가 있다면 여기서 중단하여 명시적으로 검토한다.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attname = 'email'
    WHERE i.indrelid = 'users'::regclass AND i.indisunique AND i.indnkeyatts = 1
      AND i.indkey[0] = a.attnum
  ) THEN RAISE EXCEPTION 'Review standalone unique email index before issue 53 migration'; END IF;
END $$;
COMMIT;
