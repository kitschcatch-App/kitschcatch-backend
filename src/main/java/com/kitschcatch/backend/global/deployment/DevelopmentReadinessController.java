// 개발 배포에서 실제 실행 커밋과 DB 준비 상태를 확인한다.
package com.kitschcatch.backend.global.deployment;

import java.io.IOException;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("dev")
@RestController
public class DevelopmentReadinessController {
	private final JdbcTemplate database;
	private final String commit;

	public DevelopmentReadinessController(DataSource dataSource,
			@Value("classpath:kitschcatch-release.properties") Resource release) throws IOException {
		database = new JdbcTemplate(dataSource);
		database.setQueryTimeout(2);
		var properties = new Properties();
		try (var stream = release.getInputStream()) {
			properties.load(stream);
		}
		commit = properties.getProperty("commit");
	}

	@GetMapping("/api/health/readiness")
	public ResponseEntity<Readiness> readiness() {
		try {
			database.queryForObject("select 1", Integer.class);
			return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Readiness("UP", commit));
		} catch (DataAccessException unavailable) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
				.body(new Readiness("DOWN", commit));
		}
	}

	public record Readiness(String status, String commit) {}
}
