package com.kitschcatch.backend.domain.post.repository;

import com.kitschcatch.backend.domain.post.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRepository extends JpaRepository<Post, Long> {
}
