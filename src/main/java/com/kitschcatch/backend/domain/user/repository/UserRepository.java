package com.kitschcatch.backend.domain.user.repository;

import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByAuthProviderAndProviderUserId(AuthProvider authProvider, String providerUserId);
}
