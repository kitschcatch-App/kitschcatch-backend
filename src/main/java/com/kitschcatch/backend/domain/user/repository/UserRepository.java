package com.kitschcatch.backend.domain.user.repository;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByAuthProviderAndProviderUserId(AuthProvider authProvider, String providerUserId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select user from User user where user.id = :userId")
	Optional<User> findByIdForUpdate(@Param("userId") Long userId);

	boolean existsByNicknameKeyAndIdNot(String nicknameKey, Long userId);

	boolean existsByUsernameAndIdNot(String username, Long userId);
}
