package com.kitschcatch.backend.domain.post.controller;

import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlRequest;
import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlResponse;
import com.kitschcatch.backend.domain.post.dto.CreatePostRequest;
import com.kitschcatch.backend.domain.post.dto.PostResponse;
import com.kitschcatch.backend.domain.post.dto.UpdatePostRequest;
import com.kitschcatch.backend.domain.post.service.PostService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
public class PostController {

	private final PostService postService;

	public PostController(PostService postService) {
		this.postService = postService;
	}

	@PostMapping("/images/presigned-urls")
	public ApiResponse<CreatePostImageUploadUrlResponse> createImageUploadUrls(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreatePostImageUploadUrlRequest request
	) {
		return ApiResponse.success(postService.createImageUploadUrls(user.userId(), request));
	}

	@PostMapping
	public ApiResponse<PostResponse> createPost(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreatePostRequest request
	) {
		return ApiResponse.created(postService.createPost(user.userId(), request));
	}

	@GetMapping
	public ApiResponse<Page<PostResponse>> getPosts(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return ApiResponse.success(postService.getPosts(PageRequest.of(page, size)));
	}

	@GetMapping("/{postId}")
	public ApiResponse<PostResponse> getPost(@PathVariable Long postId) {
		return ApiResponse.success(postService.getPost(postId));
	}

	@PatchMapping("/{postId}")
	public ApiResponse<PostResponse> updatePost(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long postId,
		@Valid @RequestBody UpdatePostRequest request
	) {
		return ApiResponse.success(postService.updatePost(user.userId(), postId, request));
	}

	@DeleteMapping("/{postId}")
	public ApiResponse<Void> deletePost(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long postId) {
		postService.deletePost(user.userId(), postId);
		return ApiResponse.success(null);
	}
}
