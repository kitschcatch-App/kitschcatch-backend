-- 검수 CSV 적용 이력과 출처·검수 정보 및 대상 식별자를 보존한다.
BEGIN;
CREATE TABLE IF NOT EXISTS store_csv_imports (
    id UUID PRIMARY KEY,
    source VARCHAR(1000) NOT NULL CHECK (length(trim(source)) > 0),
    reviewer VARCHAR(200) NOT NULL CHECK (length(trim(reviewer)) > 0),
    reviewed_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_by TEXT NOT NULL DEFAULT CURRENT_USER,
    stores_sha256 VARCHAR(64) NOT NULL CHECK (stores_sha256 ~ '^[0-9a-f]{64}$'),
    hours_sha256 VARCHAR(64) NOT NULL CHECK (hours_sha256 ~ '^[0-9a-f]{64}$'),
    store_ids BIGINT[] NOT NULL,
    hour_keys TEXT[] NOT NULL
);
COMMIT;
