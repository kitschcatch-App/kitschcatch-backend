# KitschCatch Backend

서브 컬처 굿즈 탐색, 중고 거래, 오프라인 매장 위치 정보를 한곳에서 연결하는 종합 플랫폼 `KitschCatch`의 백엔드 저장소입니다.

이 README는 발표 자료
`[22017035 김회윤] 졸업 프로젝트 1차 발표 자료.pdf`
와 현재 저장소 상태를 기준으로 정리했습니다.

## 1. 프로젝트 소개

`KitschCatch`는 가챠, 피규어, 굿즈 같은 서브 컬처 상품을 더 쉽고 빠르게 찾고 거래할 수 있도록 돕는 서비스입니다.

기존 서브 컬처 소비 경험은 다음 문제를 가지고 있습니다.

- 상품 정보가 여러 플랫폼에 흩어져 있어 탐색 비용이 큼
- 온라인 중고 거래와 오프라인 매장 정보가 분리되어 있음
- 작품, 캐릭터, 시리즈 중심의 도메인 정보가 부족함
- 거래 이력, 상품 상태 등 신뢰 정보가 부족해 안정성이 낮음

KitschCatch는 이 문제를 해결하기 위해 다음을 하나의 플랫폼에서 제공하는 것을 목표로 합니다.

- 서브 컬처 굿즈 탐색
- 중고 거래
- 위치 기반 오프라인 매장 정보

## 2. 핵심 가치

- 파편화된 서브 컬처 정보를 한곳에 통합
- 원하는 굿즈를 더 빠르게 찾을 수 있는 탐색 경험 제공
- 온라인 거래와 오프라인 방문 판단을 함께 지원
- 더 신뢰도 높은 거래 경험을 위한 구조 마련

## 3. MVP 기준 주요 기능

발표 자료 기준 MVP 및 전체 기능 범위는 다음과 같습니다.

### 사용자 영역

- 소셜 로그인
- 프로필 조회 및 수정
- 관심 상품 / 관심 매장 관리
- 거래 내역 조회
- 로그아웃 / 회원 탈퇴

### 상품 탐색

- 상품 목록 조회
- 상품 상세 조회
- 1:1 거래 문의
- 상품 결제

### 상품 관리

- 판매글 등록 / 수정 / 삭제
- 판매 상태 관리
- 판매 중 / 예약 / 판매 완료 상태 전환

### 매장 / 위치 기반 기능

- 매장 운영 정보 조회
- 매장 상품 정보 조회
- 위치 확인 기반 탐색

## 4. 백엔드 개발 방향

발표 자료에서 정의한 백엔드 방향은 다음과 같습니다.

- DDD 기반의 도메인 중심 설계 지향
- `User`, `Product`, `Order`, `Payment` 등 핵심 도메인 분리
- MVP 이후 확장을 고려한 API 및 엔티티 구조 설계
- `Dev` / `Prod` 환경 분리
- Git Flow 기반 협업
- GitHub Actions 기반 CI/CD 자동화

즉, 이 저장소는 단순 CRUD 서버가 아니라 이후 결제, 거래 상태, 상점 정보, 채팅성 기능까지 확장 가능한 구조를 목표로 합니다.

## 5. 기술 스택

현재 저장소 기준 백엔드 기술 스택은 다음과 같습니다.

- Java 21
- Spring Boot 4.0.5
- Gradle 9.4.1 Wrapper
- Spring Web
- Spring Data JPA
- Spring Validation
- Spring WebSocket
- PostgreSQL
- Lombok
- Test: Spring Boot Test, H2

## 6. 현재 저장소 상태

현재 저장소는 백엔드 초기 부트스트랩 단계입니다.

- Spring Boot 애플리케이션 기본 실행 구조 구성
- Gradle Wrapper 포함
- Web / JPA / Validation / WebSocket 의존성 추가
- 애플리케이션 이름 `kitschcatch-backend` 설정
- 환경 분리를 위한 `local` / `dev` / `prod` 프로필 설정 파일 분리
- 기본 컨텍스트 로딩 테스트 구성

아직 도메인별 패키지, API, 엔티티, 인프라 설정은 본격적으로 구현되기 전 단계입니다.

## 7. 실행 방법

### 요구 사항

- Java 21
- 프로필별 DB 환경 변수

### 설정 파일 구조

- `application.yml`: 공통 설정
- `application-local.yml`: 로컬 개발 환경
- `application-dev.yml`: 개발 서버 환경
- `application-prod.yml`: 운영 환경
- `.env.example`: 환경 변수 예시 문서

### 필수 환경 변수

- `LOCAL_DB_URL`, `LOCAL_DB_USERNAME`, `LOCAL_DB_PASSWORD`
- `DEV_DB_URL`, `DEV_DB_USERNAME`, `DEV_DB_PASSWORD`
- `PROD_DB_URL`, `PROD_DB_USERNAME`, `PROD_DB_PASSWORD`

`.env.example`을 참고해 로컬 실행 환경 변수를 준비한 뒤 실행합니다.

### 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

### 테스트

```bash
./gradlew test
```

개발 서버나 운영 서버에서는 `SPRING_PROFILES_ACTIVE=dev` 또는 `SPRING_PROFILES_ACTIVE=prod`로 실행합니다.

## 8. 권장 패키지 구조

프로젝트 가이드 문서 기준으로 다음 구조를 기본 방향으로 사용합니다.

- `controller`: HTTP 요청 / 응답 처리
- `service`: 비즈니스 로직
- `domain` 또는 `entity`: JPA 엔티티
- `repository`: 데이터 접근
- `dto`: 요청 / 응답 DTO
- `config`: 설정 클래스

## 9. 향후 구현 예정 범위

발표 자료 기준 이후 구현 및 고도화 방향은 다음과 같습니다.

- MVP 기능 완성 및 초기 사용자 검증
- 기본 결제 기능 반영
- 지도 기반 매장 탐색 기능 강화
- 모바일 앱 연동
- 안전 결제 및 수익화 구조 도입
- 프로모션 / 광고 / 추천 기능 확장
