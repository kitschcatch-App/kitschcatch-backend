// 실제 수동 SQL로 구성한 PostgreSQL에서 공개 프로필 및 아이디 저장 경합을 검증한다.
package com.kitschcatch.backend.domain.user;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "ISSUE49_TEST_DB_URL", matches = "jdbc:postgresql:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create",
	"kakao.oauth.native-app-key=test-native-app-key", "app.orders.expiration-enabled=false",
	"app.payments.recovery.enabled=false", "springdoc.api-docs.enabled=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class PublicUserProfilePostgresHttpTest extends PublicUserProfileHttpContract {
	private static PublicProfilePostgresDatabase database;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
		database = new PublicProfilePostgresDatabase();
		registry.add("spring.datasource.url", () -> database.url);
		registry.add("spring.datasource.username", () -> database.username);
		registry.add("spring.datasource.password", () -> database.password);
		registry.add("spring.datasource.hikari.schema", () -> database.schema);
		registry.add("spring.jpa.properties.hibernate.default_schema", () -> database.schema);
	}

	@BeforeAll
	void applyManualMigration() throws Exception {
		try (var connection = database.connect(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE users DROP COLUMN username, DROP COLUMN bio");
			PublicProfilePostgresDatabase.migrate(connection, "031_user_profile.sql");
			PublicProfilePostgresDatabase.migrate(connection, "049_public_user_profile.sql");
			PublicProfilePostgresDatabase.migrate(connection, "049_public_user_profile.sql");
		}
	}

	@AfterAll
	void dropSchema() throws Exception {
		database.close();
	}
}
