package com.kitschcatch.backend.domain.auth.repository;

import com.kitschcatch.backend.domain.auth.entity.LoginNonce;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginNonceRepository extends JpaRepository<LoginNonce, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select loginNonce from LoginNonce loginNonce where loginNonce.nonceHash = :nonceHash")
	Optional<LoginNonce> findByNonceHashForUpdate(@Param("nonceHash") String nonceHash);

	default boolean consumeByRawNonce(String rawNonce) {
		return findByNonceHashForUpdate(JwtTokenProvider.hash(rawNonce))
			.filter(nonce -> nonce.isActive(LocalDateTime.now()))
			.map(nonce -> {
				nonce.consume();
				return true;
			})
			.orElse(false);
	}
}
