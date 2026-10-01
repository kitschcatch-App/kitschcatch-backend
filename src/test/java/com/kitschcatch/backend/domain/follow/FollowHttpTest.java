// H2에서 실제 HTTP 팔로우 계약을 검증한다.
package com.kitschcatch.backend.domain.follow;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:issue52-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "springdoc.api-docs.enabled=true", "spring.jpa.properties.hibernate.generate_statistics=true",
    "app.s3.public-base-url=https://images.example.test"
})
class FollowHttpTest extends FollowHttpContract {}
