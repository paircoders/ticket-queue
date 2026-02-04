-- User Service DDL

-- 1. users Table
CREATE TABLE user_service.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    email_hash VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(50) NOT NULL,
    phone_hash VARCHAR(255) NOT NULL,
    ci VARCHAR(512),
    ci_hash VARCHAR(255),
    di VARCHAR(64),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    last_login_at TIMESTAMP,
    deleted_at TIMESTAMP,

    CONSTRAINT users_email_hash_unique UNIQUE (email_hash),
    CONSTRAINT users_ci_hash_unique UNIQUE (ci_hash),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DORMANT', 'DELETED'))
);

-- 휴면 계정 배치 최적화
CREATE INDEX idx_users_last_login ON user_service.users(last_login_at) WHERE status = 'ACTIVE';

-- updated_at Trigger
CREATE TRIGGER trg_users_updated_at
BEFORE UPDATE ON user_service.users
FOR EACH ROW EXECUTE FUNCTION user_service.update_timestamp();

COMMENT ON TABLE user_service.users IS '계정 관리';
COMMENT ON COLUMN user_service.users.id IS '계정 UUID';
COMMENT ON COLUMN user_service.users.email IS '이메일 (AES-256-GCM 암호화)';
COMMENT ON COLUMN user_service.users.email_hash IS '이메일 해시 (HMAC-SHA256, 검색용)';
COMMENT ON COLUMN user_service.users.password_hash IS '비밀번호 (BCrypt 단방향 해시)';
COMMENT ON COLUMN user_service.users.name IS '사용자명 (AES-256-GCM 암호화)';
COMMENT ON COLUMN user_service.users.phone IS '휴대폰번호 (AES-256-GCM 암호화)';
COMMENT ON COLUMN user_service.users.phone_hash IS '휴대폰번호 해시 (HMAC-SHA256, 검색용)';
COMMENT ON COLUMN user_service.users.ci IS '본인인증 CI (AES-256-GCM 암호화)';
COMMENT ON COLUMN user_service.users.ci_hash IS '본인인증 CI 해시 (HMAC-SHA256, 중복체크용)';
COMMENT ON COLUMN user_service.users.di IS '본인인증 DI';
COMMENT ON COLUMN user_service.users.role IS '권한 (USER/ADMIN)';
COMMENT ON COLUMN user_service.users.status IS '계정 상태 (ACTIVE/DORMANT/DELETED)';
COMMENT ON COLUMN user_service.users.created_at IS '생성일시';
COMMENT ON COLUMN user_service.users.updated_at IS '수정일시';
COMMENT ON COLUMN user_service.users.last_login_at IS '마지막 로그인 일시';
COMMENT ON COLUMN user_service.users.deleted_at IS '삭제일시';


-- 2. refresh_tokens Table
CREATE TABLE user_service.refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES user_service.users(id) ON DELETE CASCADE,
    token_family UUID NOT NULL,
    refresh_token VARCHAR(512) NOT NULL, 
    access_token_jti VARCHAR(255),
    issued_at TIMESTAMP NOT NULL DEFAULT now(), 
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false,
    revoked_at TIMESTAMP,

    CONSTRAINT refresh_tokens_token_unique UNIQUE (refresh_token),
    CONSTRAINT chk_tokens_revoked CHECK (
        (revoked = false AND revoked_at IS NULL) OR
        (revoked = true AND revoked_at IS NOT NULL)
    )
);

-- 사용자 기준 일괄 무표화
CREATE INDEX idx_refresh_tokens_user_revoked ON user_service.refresh_tokens(user_id, revoked) WHERE revoked = false;
-- 재사용 감지 시 token_family 일괄 무효화
CREATE INDEX idx_refresh_tokens_family ON user_service.refresh_tokens(token_family);
-- 만료 토큰 삭제 배치
CREATE INDEX idx_refresh_tokens_expires ON user_service.refresh_tokens(expires_at) WHERE revoked = false;

COMMENT ON TABLE user_service.refresh_tokens IS 'Refresh Token 관리';
COMMENT ON COLUMN user_service.refresh_tokens.id IS '토큰관리 UUID';
COMMENT ON COLUMN user_service.refresh_tokens.user_id IS '계정 UUID';
COMMENT ON COLUMN user_service.refresh_tokens.token_family IS 'RTR 추적용 UUID (동일 세션 그룹핑)';
COMMENT ON COLUMN user_service.refresh_tokens.refresh_token IS '발급된 Refresh Token 문자열';
COMMENT ON COLUMN user_service.refresh_tokens.access_token_jti IS '발급된 Access Token의 고유 식별자(JTI)';
COMMENT ON COLUMN user_service.refresh_tokens.issued_at IS '발급일시';
COMMENT ON COLUMN user_service.refresh_tokens.expires_at IS '만료일시';
COMMENT ON COLUMN user_service.refresh_tokens.revoked IS '토큰 무효화 여부 (로그아웃, RTR 교체, 탈취 감지 시 true로 변경)';
COMMENT ON COLUMN user_service.refresh_tokens.revoked_at IS '토큰 무효화 일시';


-- 3. login_history Table
CREATE TABLE user_service.login_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES user_service.users(id) ON DELETE CASCADE,
    ip_address VARCHAR(45),
    user_agent TEXT,
    login_method VARCHAR(20) NOT NULL,
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE user_service.login_history IS '로그인 이력 관리';
COMMENT ON COLUMN user_service.login_history.id IS '로그인 이력 UUID';
COMMENT ON COLUMN user_service.login_history.user_id IS '계정 UUID';
COMMENT ON COLUMN user_service.login_history.ip_address IS '접속 IP 주소 (IPv4/IPv6 지원)';
COMMENT ON COLUMN user_service.login_history.user_agent IS '접속 기기 및 브라우저 정보 (User-Agent)';
COMMENT ON COLUMN user_service.login_history.login_method IS '로그인 방식 (EMAIL)';
COMMENT ON COLUMN user_service.login_history.success IS '로그인 성공여부';
COMMENT ON COLUMN user_service.login_history.failure_reason IS '로그인 실패 사유';
COMMENT ON COLUMN user_service.login_history.created_at IS '접속 일시';

-- Ownership Transfer
ALTER TABLE user_service.users OWNER TO user_svc_user;
ALTER TABLE user_service.refresh_tokens OWNER TO user_svc_user;
ALTER TABLE user_service.login_history OWNER TO user_svc_user;
