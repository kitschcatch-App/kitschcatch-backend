package com.kitschcatch.backend.domain.post.repository;

import com.kitschcatch.backend.domain.post.entity.Post;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Post p where p.id = :id")
	Optional<Post> findByIdForUpdate(@Param("id") Long id);

	@EntityGraph(attributePaths = "user")
	Page<Post> findAllByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

	@EntityGraph(attributePaths = "user")
	Optional<Post> findByIdAndDeletedAtIsNull(Long id);

	@EntityGraph(attributePaths = {"user", "images"})
	Optional<Post> findDetailByIdAndDeletedAtIsNull(Long id);
}
