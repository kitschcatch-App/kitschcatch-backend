package com.kitschcatch.backend.domain.user.repository;

import com.kitschcatch.backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
