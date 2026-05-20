package com.kitschcatch.backend.domain.auth.repository;

import com.kitschcatch.backend.domain.auth.entity.RefreshToken;
import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select refreshToken from RefreshToken refreshToken join fetch refreshToken.user where refreshToken.tokenHash = :tokenHash")
	Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

	@Modifying
	@Query("""
		delete from RefreshToken refreshToken
		where refreshToken.user = :user
			and (refreshToken.revokedAt is not null or refreshToken.expiresAt <= :now)
		""")
	int deleteInactiveByUser(@Param("user") User user, @Param("now") LocalDateTime now);
}
