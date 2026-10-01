// H2에서 공개 프로필 HTTP 계약과 기존 프로필 API 호환성을 검증한다.
package com.kitschcatch.backend.domain.user;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:public-user-profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
	"app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false", "springdoc.api-docs.enabled=true"
})
class PublicUserProfileHttpTest extends PublicUserProfileHttpContract {
}
