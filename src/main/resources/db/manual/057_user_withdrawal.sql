-- 회원 종료 상태를 추가하고 기존 거래·채팅 참조 및 소셜 계정 식별자를 보존한다.
BEGIN;
ALTER TABLE users ADD COLUMN IF NOT EXISTS withdrawn_at TIMESTAMP WITH TIME ZONE;
COMMENT ON COLUMN users.withdrawn_at IS '계정 종료 시각. NULL은 활성 계정. users와 거래/채팅 FK는 삭제하지 않음.';
COMMIT;
