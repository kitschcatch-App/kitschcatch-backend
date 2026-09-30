// H2에서 거래 조회의 실제 HTTP 계약을 검증한다.
package com.kitschcatch.backend.domain.order;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:order-history-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
    "app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
    "app.s3.public-base-url=https://cdn.example.test", "app.s3.bucket=test-bucket",
    "springdoc.api-docs.enabled=true", "spring.jpa.properties.hibernate.generate_statistics=true"
})
class OrderHistoryHttpTest extends OrderHistoryHttpContract {}
