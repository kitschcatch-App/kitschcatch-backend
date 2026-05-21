package com.kitschcatch.backend.domain.post.repository;

import com.kitschcatch.backend.domain.post.entity.Post;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface PostRepository extends JpaRepository<Post, Long> {

	@EntityGraph(attributePaths = "user")
	Page<Post> findAllByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

	@EntityGraph(attributePaths = "user")
	Optional<Post> findByIdAndDeletedAtIsNull(Long id);

	@EntityGraph(attributePaths = {"user", "images"})
	Optional<Post> findDetailByIdAndDeletedAtIsNull(Long id);
}
