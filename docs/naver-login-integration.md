# 네이버 로그인 연동과 사전 검수 (#73, #99)

서버는 네이버 인증 코드를 교환하고 사용자 정보를 확인한 뒤 키치캐치 토큰을 발급한다. 관련 API는 이미 구현되어 있다. 모바일에서 네이버 SDK access token만 전달하는 방식은 현재 입력 계약과 다르다.

## 네이버 등록 앱과 환경 설정

같은 네이버 애플리케이션의 `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`과 등록된 콜백 주소 `NAVER_REDIRECT_URI`를 서버 실행 환경에 주입한다. `.env.example`은 필요한 이름을 설명하며 Spring Boot가 `.env`를 자동으로 읽지는 않는다. Docker Compose 환경 파일 등 실제 프로세스의 환경 주입 경로를 사용해야 한다. 실행 중 컨테이너에는 환경 파일 변경만으로 반영되지 않으므로 설정 적용 시 컨테이너를 재생성한다.

설정값은 비밀 설정 파일에 보관한다. 로그·PR·이슈에 Client Secret, 인증 코드, state, 토큰을 기록하지 않는다. 개발자 콘솔에서 서비스 환경, 정보 제공 항목, 테스트 계정 및 검수 상태를 확인한다. 서버 설정의 존재와 네이버에서의 유효성은 별도로 검증한다.

네이버 콘솔 Callback URL, `NAVER_REDIRECT_URI`, 모바일 콜백 처리 주소를 일치시킨다. 개발용 등록 초안은 서비스 URL `https://dev-kitschcatch.p-e.kr`, 콜백 `https://dev-kitschcatch.p-e.kr/api/auth/naver/callback`이다. GET 콜백은 앱 링크가 처리되지 못했을 때 비밀값을 표시하지 않는 정적 안내를 반환한다. 인증 코드 교환이나 자동 앱 스킴 리다이렉트는 수행하지 않는다. 캐시·Referer·외부 리소스를 차단하지만 URL 쿼리의 접근 로그는 배포 계층에서도 별도 보호해야 한다. 앱 링크 도메인 연결이 완료되어야 앱으로 돌아온다.

현재 `POST /api/auth/naver/mobile-login`은 앱의 코드 제출 API이며, 네이버 리다이렉트를 받는 GET 콜백 주소가 아니다. 이 경로를 Callback URL로 등록하지 않는다. 실제 모바일 프로젝트의 기존 딥링크/인증 세션을 먼저 확인한다. HTTPS App Links/Universal Links를 쓰면 도메인 연결 파일과 앱의 패키지·서명/앱 식별자도 맞춰야 한다. 주소가 정해지기 전에 임의 콜백이나 임의 앱 스킴을 추가하지 않는다.

## 앱과 서버의 순서

1. 앱은 `POST /api/auth/naver/state`를 호출한다. 응답 `data`는 `state`, `authorizationUrl`, `expiresIn=300`이다. 앱은 state를 해당 로그인 시도에 보관하고 반환 URL을 시스템 브라우저/플랫폼 인증 세션으로 연다. 중복 탭을 방지한다.
2. 네이버 인증이 끝나면 앱은 허용한 콜백 주소와 오류 유무를 확인한다. 콜백의 state를 보관한 값과 비교한다. 로그인 시도가 없거나 state가 다르면 처리하지 않는다. 취소 또는 앱 재시작으로 state를 잃으면 새 요청부터 시작한다.
3. 앱은 콜백 `code`를 `authorizationCode`에 담아 `POST /api/auth/naver/mobile-login`에 `{"authorizationCode":"<code>","state":"<state>"}`를 보낸다. 코드는 공백 불가·최대 2048자, state는 공백 불가·43자다. 기존 서비스 토큰 없이 호출한다.
4. 서버는 설정을 확인하고 state 해시·제공자·만료·사용 여부를 검증한다. DB 잠금과 별도 트랜잭션으로 한 번만 소비한다. 이후 네이버 교환이나 회원/토큰 처리 실패에도 소비는 유지된다. 앱은 같은 요청을 자동 재전송하지 말고 새 state부터 시작한다. 설정 누락은 저장·소비 전에 503으로 실패한다.
5. 서버가 네이버 토큰 엔드포인트에 client ID/secret, code, state를 POST form으로 전송하고, 받은 access token으로 `/v1/nid/me`를 조회한다. 연결 3초·읽기 5초 제한을 사용하며 리다이렉트를 따르지 않는다. 실패 로그는 `token_exchange`/`profile_lookup` 단계만 기록한다.
6. 서버는 `(NAVER, provider_user_id)`로 기존 회원을 찾거나 생성한다. 이메일은 선택값이며 다른 제공자 계정과 이메일로 자동 연결하지 않는다. 탈퇴 계정은 로그인할 수 없다.
7. 성공 `data`는 `tokenType`, `accessToken`, `expiresIn`, `refreshToken`, `refreshTokenExpiresIn`, `user`다. `user`에는 `id`, `email`, `nickname`, `profileRegistered`가 있다. 앱은 서비스 토큰을 보안 저장소에 저장한다. `profileRegistered=false`이면 프로필 등록으로, true이면 홈으로 이동한다. 만료 시간은 응답값을 사용한다.
8. 앱은 서비스 access token으로 `GET /api/users/me`를 호출한다. 만료 시 `POST /api/auth/token/refresh`에 서비스 refresh token을 전달하고 두 토큰을 새 응답으로 교체한다. 이전 refresh token은 재사용할 수 없다. 로그아웃은 `POST /api/auth/logout`으로 refresh token을 폐기하고 로컬 토큰을 삭제한다. 이미 발급한 access token의 만료 전 즉시 폐기를 의미하지 않는다.

## 오류 처리

| HTTP/코드 | 의미와 앱 처리 |
| --- | --- |
| 503 / AUTH_007 | 세 설정 중 하나가 비어 있다. 이용 불가를 안내하고 서버 설정을 확인한다. |
| 400 / COMMON_001 | 필수값·길이 등 입력 오류다. 앱 요청을 확인한다. |
| 401 / AUTH_005 | 만료·사용된 state, 코드 교환·프로필 검증 실패다. 새 로그인 시도부터 시작한다. 현재 네이버 통신 오류도 이 계약을 사용한다. |
| 401 / AUTH_003 | 서비스 refresh token이 만료·폐기되었거나 유효하지 않다. 다시 로그인한다. |

콜백의 네이버 `error`는 앱에서 취소·실패로 처리하고 코드 제출을 중단한다. 키치캐치 토큰을 콜백 URL 쿼리에 넣지 않는다. 웹 콜백 경로를 추가한다면 인증 파라미터가 접근 로그·외부 리소스의 Referer로 전송되지 않도록 해야 한다.

## DB와 검증

실제 DB는 NAVER 제공자 CHECK, 이메일 선택값·중복 허용, 제공자/사용자 ID 유일 제약, login_nonces 및 refresh_tokens를 확인한다. 기존 [053 SQL](../src/main/resources/db/manual/053_social_login.sql)은 적용 상태를 먼저 조회한 뒤 필요한 경우 검토하여 적용한다. 테스트의 create-drop은 격리 DB에만 사용한다.

자동화 테스트는 로컬 제공자 HTTP 서버와 실제 서비스 JWT를 사용한다. 잘못된 토큰·프로필 응답은 각 사례에 새 state를 발급하고 외부 호출 횟수를 확인한다. 네이버 state 만료·실패 후 재사용·동시 제출, 회원 유지·프로필 등록·토큰 회전·로그아웃도 검증한다. 이 결과는 실제 네이버 인증 및 모바일 화면 검증과 구분한다.

실기기 완료 기준은 네이버 인증 → 앱 복귀 → 서비스 토큰 발급 → 내 정보 200, 신규 회원 프로필 등록, 기존 회원 동일 ID 로그인, 토큰 갱신 및 로그아웃이다. Android/iOS에서 앱 실행·종료 상태와 인증 취소·만료를 확인한다.

## #99 배포 점검

2026-11-09 사파리 AWS 콘솔에서 시드니 개발 EC2의 실행 상태를 확인했다. 개발 서버에서 state 발급과 HTTPS 콜백이 200, `/.well-known/assetlinks.json`이 200, `/.well-known/apple-app-site-association`이 401이었다. 실제 인증 URL의 `redirect_uri`는 문서의 개발용 콜백과 일치했다.

## 모바일 팀 전달 사항

모바일 `develop`의 `9c5afa0`에는 `POST /api/auth/naver/state`와 `POST /api/auth/naver/mobile-login` 호출이 연결되어 있었다. 다음 변경을 모바일 팀에서 적용한다. 개발 콜백은 `https://dev-kitschcatch.p-e.kr/api/auth/naver/callback`이다.

| 영역 | 모바일 적용 사항 | 확인 기준 |
| --- | --- | --- |
| Android App Links | `MainActivity`에 `android:autoVerify="true"`인 HTTPS 인텐트 필터를 추가한다. `VIEW`, `DEFAULT`, `BROWSABLE`과 개발 콜백의 호스트 및 정확한 경로(`/api/auth/naver/callback`)를 지정한다. | 설치 빌드의 패키지 `com.kitschcatch`와 SHA-256 서명 지문이 도메인의 `/.well-known/assetlinks.json`과 일치한다. `adb shell pm get-app-links com.kitschcatch`에서 `verified`를 확인한다. 개발 도메인에는 저장소 디버그 서명 지문이 등록되어 있다. |
| iOS Universal Links | 앱 타깃과 프로비저닝에 Associated Domains를 활성화하고 `applinks:dev-kitschcatch.p-e.kr`를 추가한다. `AppDelegate`에서 Universal Link의 `continue userActivity` 이벤트를 React Native `RCTLinkingManager`에 전달한다. 서명된 앱의 `application-identifier`를 백엔드에 전달한다. | 서명한 빌드에서 네이버 인증 후 앱으로 돌아온다. |
| 인증 대기 | 서버에서 받은 URL을 열기 전에 콜백 리스너를 등록한다. HTTPS 콜백 주소와 현재 시도의 `state`가 모두 일치한 성공 응답을 받으면 `POST /api/auth/naver/mobile-login`에 콜백의 `code`를 `authorizationCode`로 담고 발급받은 `state`도 함께 보낸다. 오류 콜백도 같은 주소와 `state`를 확인한 뒤 인증 대기를 끝낸다. | 다른 주소와 `state`의 콜백을 무시하고 같은 시도의 로그인 요청을 한 번만 보낸다. |
| 재시도 | 인증 중복 시작을 막고, 완료·취소·서버가 안내한 유효 시간 만료·브라우저 열기 실패·화면 종료 시 리스너와 타이머를 해제한다. 브라우저가 콜백 없이 닫힌 경우 앱에서 진행 중인 인증을 취소할 수 있게 한다. 앱 프로세스가 종료됐거나 인증에 실패하면 새 `state`로 다시 시작한다. | 인증 취소·만료 후 새로 시작할 수 있고, 종료된 시도의 콜백으로 로그인되지 않는다. |

백엔드는 전달받은 `application-identifier`를 서버 환경 변수 `NAVER_IOS_APP_ID`에 설정한다. 배포 후 `/.well-known/apple-app-site-association`이 인증과 리다이렉트 없이 JSON 200을 반환하고, 해당 앱에 네이버 콜백 경로만 연결하는지 확인한다. 미설정 또는 형식 오류 시 404를 반환한다.

테스터 인증을 시작하기 전에 배포 계층의 `/api/auth/naver/callback` 접근 로그 설정을 확인한다. Nginx 기본 형식처럼 전체 요청 줄(`$request`)을 기록하면 쿼리의 인증 코드와 `state`도 남는다. 해당 경로는 쿼리를 제외한 형식으로 기록하거나 접근 로그를 끈다. 가짜 `code`와 `state`로 콜백을 호출해 안내 응답은 200이고 값은 활성화된 프록시·로드밸런서·애플리케이션 로그에 남지 않는지 확인한다.

개발 서버 주소와 다른 도메인으로 배포할 때는 네이버 앱의 등록 콜백, 서버 `NAVER_REDIRECT_URI`, 모바일 앱 링크 설정을 함께 맞춘다. 배포된 서버에서 발급한 URL로 등록 테스터가 실제 인증을 마친 뒤, 신규 회원은 프로필 등록, 기존 회원은 홈 진입 및 동일 회원 ID로의 재로그인까지 확인한다.

## 테스터와 사전 검수

1. 네이버 개발자센터의 `내 애플리케이션`에서 배포 서버가 발급하는 인증 URL의 `client_id`와 동일한 앱을 찾는다. 검수 상태, 서비스 환경, 콜백 URL, 제공 정보를 확인한다. 같은 서비스에 기존 승인 앱이 있다면 Client ID 변경 전에 기존 회원의 네이버 식별자에 미칠 영향을 확인한다.
2. `멤버관리`에서 신규 가입과 기존 회원 재로그인에 쓸 네이버 ID를 등록한다. 시스템 브라우저의 네이버 로그인 계정이 테스터와 같은지 확인한다. 등록 테스터가 동일한 제한 화면을 보면 먼저 애플리케이션과 로그인 계정을 다시 대조한다.
3. 각 플랫폼에서 로그인 버튼 → 네이버 인증·동의 → 앱 복귀 → 프로필 등록 또는 홈 → 내 정보 조회를 확인한다. 앱 버전·서버·OS와 결과를 기록한다. 취소, 만료, 로그아웃, 기존 회원 ID 유지도 확인한다. 검수 전 미등록 계정의 개발 상태 제한을 별도로 기록한다.
4. 검수에는 실제 로그인과 가입이 끝나는 화면까지의 캡처, 키치캐치 이름과 로고, 서비스 내용을 보여주는 화면을 준비한다. 네이버 가입에 별도의 키치캐치 비밀번호를 요구하지 않는다. 기본 식별자만 사용하고 추가 항목을 요청할 경우 실제 활용 화면을 준비한다. 신청 화면의 업종별 요구 자료도 확인한다.
5. `네이버 로그인 검수 상태`에서 자료를 첨부해 요청한다. 승인 후 배포 대상 앱에서 미등록 계정이 가입과 재로그인까지 완료하는지 확인한다.

참고: [테스터와 사전 검수 안내](https://developers.naver.com/docs/login/verify/verify.md).

## 2026-11-03 적용 이력

2026-11-03 네이버에 `키치캐치 개발`을 Mobile 웹 환경으로 등록했다. 서비스 URL은 시드니 개발 서버이며 별도 실서비스 검수는 신청하지 않았다. 동의 정보는 기본 이용자 식별자만 사용했다. 자격 증명을 시드니 서버 및 시드니 전용 보호 환경 파일에 반영하고 컨테이너를 재생성했다. 설정 작업의 임시 SSH 규칙은 제거했다. 서버 JAR은 기존 develop `bd9b904` 그대로다.

실제 네이버 계정으로 발급된 코드를 교환해 서비스 로그인 200, 내 정보 200, 미등록 프로필 false를 확인했다. state 재사용 401, refresh 200, 이전 refresh 재사용 401, 로그아웃 200 및 폐기 후 refresh 401도 확인했다. 검증용 refresh 세션과 임시 토큰·자격 증명 파일은 정리했다. 개발 회원은 유지한다.

당시 모바일 develop은 네이버 SDK access token을 제출했고 배포 JAR의 GET 콜백은 500을 반환했다. iOS 서명 팀 정보는 없었다. 실제 네이버 코드 교환 검증은 별도 브라우저 흐름으로 수행했다.


공식 근거: [네이버 로그인 개발가이드](https://developers.naver.com/docs/login/devguide/devguide.md), [API 명세](https://developers.naver.com/docs/login/api/api.md), [Android App Links](https://developer.android.com/training/app-links/verify-applinks), [Apple Associated Domains](https://developer.apple.com/documentation/xcode/supporting-associated-domains).

## 2026-11-03 로컬 검증 결과

- 격리 PostgreSQL 14의 CI 동일 환경에서 `./gradlew clean build --no-daemon --console=plain` 성공. 전체 1,280개, 실패·오류·건너뜀 0개다. 미설정 API HTTP 테스트는 명시적으로 별도 H2 DB를 사용한다. 검증 DB와 임시 파일은 종료·제거했다.
- 이후 정적 콜백을 추가하고 `./gradlew test --tests '*NaverLoginConfigurationHttpTest' bootJar --no-daemon --console=plain` 성공. 미설정 API와 콜백 HTTP 2개 테스트가 통과했다. 콜백은 인증 없이 200 HTML, no-store/no-referrer/CSP와 인증 파라미터 비반사를 확인했다.
- 이 결과는 실제 네이버 등록·모바일 기기·개발 서버 배포 결과가 아니다. 네이버 등록 및 서버 설정·실제 인증 결과는 위 적용 상태를 따른다. 새 코드의 정적 콜백과 앱 화면 검증은 구분한다.
