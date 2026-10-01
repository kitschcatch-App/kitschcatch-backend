# 이슈 #53 네이버·Apple 모바일 로그인 계약

기준 브랜치는 `develop`, 시작 SHA는 `5f6d18fb2d4d8fc1e2f435919a548c64d33c9627`이다. #45 등 다른 기능을 선행 조건으로 사용하지 않는다.

## 명세 근거와 모바일 입력

2026-10-01 Notion API 원문은 연결된 계정에서 404 object_not_found로 접근하지 못했다. 외부 명세는 수정하지 않았으며 다음 공식 계약과 기존 서비스 인증 응답을 기준으로 구현했다.

- [네이버 인증 요청·코드 교환](https://developers.naver.com/docs/login/api/api.md).
- [네이버 프로필 조회·토큰 유효성](https://developers.naver.com/docs/login/devguide/devguide.md).
- [Apple 사용자 검증](https://developer.apple.com/documentation/signinwithapple/verifying-a-user).
- [Apple 공개 OIDC 설정](https://appleid.apple.com/.well-known/openid-configuration).

네이버의 SDK access token만 받으면 공식 `/me` 응답에서 서버의 client ID 일치를 직접 검증할 수 없다. 안전한 기본 계약으로 모바일 브라우저의 인증 코드 흐름을 사용한다. 모바일 클라이언트는 SDK access token 제출 방식 대신 아래 흐름을 구현해야 한다.

1. `POST /api/auth/naver/state` → `data.state`, `data.authorizationUrl`, `data.expiresIn=300`.
2. 반환 URL을 모바일 브라우저에서 열고 등록된 `NAVER_REDIRECT_URI` 콜백으로 인증한다. 클라이언트도 콜백 state 일치를 확인한다.
3. `POST /api/auth/naver/mobile-login`에 `{"authorizationCode":"...","state":"..."}`를 전송한다. 서버가 등록된 client ID/secret으로 네이버 token endpoint에 POST form을 보내고 반환 access token으로 `/me`를 조회한다.
4. `token_type=Bearer`, 양수 `expires_in`, 오류 없는 교환 응답과 `resultcode=00`, 유효한 `response.id`가 모두 필요하다. 네이버 토큰은 JWT가 아니므로 issuer/JWKS를 적용하지 않는다. 만료·폐기된 토큰의 조회 거절은 로그인 실패로 처리한다.

Apple은 다음 흐름을 사용한다.

1. `POST /api/auth/apple/nonce` → `data.nonce`, `data.expiresIn=300`.
2. Apple 인증 요청의 nonce에 서버가 준 **원문**을 전달한다. 해시 nonce를 자동 허용하지 않는다. 브라우저 콜백을 쓰는 클라이언트는 state도 자체 검증한다.
3. `POST /api/auth/apple/mobile-login`에 `{"idToken":"...","nonce":"..."}`를 전송한다.
4. 설정된 Apple JWKS에서 RS256 서명과 kid를 검증하고 issuer=`https://appleid.apple.com`, 단일 audience=`APPLE_CLIENT_ID`, nonce 일치, 필수 exp/iat 및 유효 시간을 검증한다. exp 경계에는 clock skew를 허용하지 않는다. 미래 iat/nbf도 거절한다.
5. 서명으로 확인된 `sub`로 로그인한다. `email_verified=true`인 이메일만 선택 연락처로 저장한다. Apple ID 토큰 검증 후 서비스 세션을 발급하며 Apple authorization code 교환·Apple refresh token 저장·철회 감시는 이번 범위에 포함하지 않는다.

요청 nonce/state는 43자, 인증 코드는 최대 2048자, ID 토큰은 최대 16384자다. 오류는 기존 ApiResponse 형식으로 400 입력 오류, 401 AUTH_005/006 검증 실패, 503 AUTH_007 제공자 미설정을 반환한다. 외부 응답 본문이나 토큰은 오류 메시지로 전달하지 않는다. 서버 HTTP 연결/읽기 제한은 3초/5초이며 리다이렉트를 따르지 않는다.

## 사용자·토큰·동시성

사용자는 `(auth_provider, provider_user_id)` 유일 키로만 식별한다. 같은 이메일의 KAKAO/NAVER/APPLE 사용자 및 같은 제공자의 서로 다른 sub는 별도 계정이다. 이메일 미제공·미검증은 null로 저장한다. 이메일은 계정 연결이나 소유권 증거로 사용하지 않으며 이후 로그인에서 저장된 이메일/닉네임을 덮어쓰지 않는다. 기존 카카오의 이메일 동의 요구는 유지한다.

`users.email`의 NOT NULL/UNIQUE를 제거한다. 기존 계정 이메일·ID와 다른 유일 제약은 유지한다. 가짜 이메일은 만들지 않는다. 소셜 기본 닉네임은 제공자명과 임의 접미사이며 정규화 닉네임 유일 키는 프로필 등록에서 설정한다. 기존 프로필 등록 여부 응답, 서비스 JWT 발급·회전·로그아웃을 재사용한다.

nonce/state는 32바이트 난수 원문을 클라이언트에 반환하고 DB에는 `SHA256(PROVIDER:원문)`을 저장한다. 제공자 간 혼용을 거절한다. 기존 login_nonces 행의 비관적 잠금으로 300초 유효한 challenge를 한 번만 사용한다. 로그인 실패 시 challenge 소비와 서비스 refresh token 저장은 함께 롤백된다. 네이버 코드 교환 자체는 외부 동작이라 DB 롤백으로 되돌릴 수 없다. 새 사용자는 별도 트랜잭션에서 생성하고 유일 키 경합 실패 뒤 승자를 다시 조회한다. 이후 토큰 발급 실패 시 아직 프로필이 없는 계정이 남을 수 있으며 재로그인으로 같은 계정을 사용한다.

## 설정·수동 SQL

- `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`, `NAVER_REDIRECT_URI`.
- `APPLE_CLIENT_ID`: 이 서버가 허용하는 단일 앱 ID/Services ID.
- 제공자 URI 기본값은 공식 HTTPS 주소다. 테스트에서만 로컬 URI로 덮어쓴다. 실제 자격 증명은 저장소에 추가하지 않는다.
- 기존 DB에는 `src/main/resources/db/manual/053_social_login.sql`을 코드 배포 전에 적용해야 한다. 운영 적용은 수행하지 않았다. 기존 `login_nonces`, refresh_tokens 및 사용자 제공자 유일 키는 선행 인증 스키마에 존재해야 한다.
- SQL은 트랜잭션 안에서 이메일 제약을 제거하고 기존 KAKAO 단일 enum CHECK를 교체한다. 두 번 실행 가능하다. 다른 CHECK와 제공자 유일 키를 보존한다. 이메일의 독립 UNIQUE INDEX는 자동 삭제하지 않고 오류로 롤백하므로 적용 대상에서 별도로 검토해야 한다.
- 중복/null 이메일과 NAVER/APPLE 계정이 생긴 뒤 구버전 제약으로 되돌리는 것은 데이터 정리가 필요하다. 자동 역마이그레이션은 제공하지 않는다.

## 검증과 한계

`SocialLoginHttpTest`는 실제 랜덤 HTTP 포트, 로컬 인증 서버/JWKS, 서명된 제공자 ID 토큰과 실제 서비스 JWT를 사용한다. 기본은 이슈 전용 H2 DB이며 `ISSUE53_TEST_DB_URL`을 설정하면 동일 검증을 전용 PostgreSQL에서 실행한다. PostgreSQL 사용자 이름은 테스트 전용 `issue53`, 암호는 비어 있다. 이 테스트는 create-drop을 사용하므로 운영/공유 DB를 전달하면 안 된다.

정상·이메일 미제공/중복·위조 서명·잘못된 issuer/audience·만료/필수 claim·nonce 재사용/혼용/만료·네이버 교환/프로필 오류·동시 생성·동시 replay·서비스 refresh 회전/로그아웃·실제 JWT 프로필 API·카카오 JWKS 회귀를 검증한다. 추가로 JWKS 장애/알 수 없는 kid/키 회전, PostgreSQL SQL 재실행·기존 데이터/제약 보존·독립 이메일 인덱스에 따른 롤백을 검증한다.

실제 네이버·Apple 앱 등록, 모바일 화면/SDK, 운영 자격 증명, 실사용자 로그인·철회는 검증하지 않았다. S3·Toss 실제 연동은 기존과 같이 미완료다. 운영 DB 적용·배포·실제 푸시/메시지·병합은 수행하지 않는다. 전체 테스트의 다른 이슈 PostgreSQL 환경 조건 테스트는 별도 기록하며 이 이슈 DB로 대신 실행하지 않는다.
