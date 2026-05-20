package com.kitschcatch.backend.domain.post.repository;

import com.kitschcatch.backend.domain.post.entity.Post;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRepository extends JpaRepository<Post, Long> {

	Page<Post> findAllByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

	Optional<Post> findByIdAndDeletedAtIsNull(Long id);
}
