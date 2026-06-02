package com.kitschcatch.backend.domain.post.controller;

import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlRequest;
import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlResponse;
import com.kitschcatch.backend.domain.post.dto.CreatePostRequest;
import com.kitschcatch.backend.domain.post.dto.PostResponse;
import com.kitschcatch.backend.domain.post.dto.UpdatePostRequest;
import com.kitschcatch.backend.domain.post.service.PostService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "판매 게시글", description = "판매 게시글 생성, 조회, 수정, 삭제와 이미지 업로드 URL 발급 API")
@SecurityRequirement(name = "bearerAuth")
public class PostController {

	private final PostService postService;

	public PostController(PostService postService) {
		this.postService = postService;
	}

	@PostMapping("/images/presigned-urls")
	@Operation(summary = "판매 게시글 이미지 업로드 URL 발급", description = "판매 게시글 이미지 파일을 S3에 직접 업로드할 수 있는 presigned URL을 발급합니다.")
	public ApiResponse<CreatePostImageUploadUrlResponse> createImageUploadUrls(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreatePostImageUploadUrlRequest request
	) {
		return ApiResponse.success(postService.createImageUploadUrls(user.userId(), request));
	}

	@PostMapping
	@Operation(summary = "판매 게시글 생성", description = "업로드가 완료된 이미지 키를 포함해 판매 게시글을 생성합니다.")
	public ApiResponse<PostResponse> createPost(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreatePostRequest request
	) {
		return ApiResponse.created(postService.createPost(user.userId(), request));
	}

	@GetMapping
	@Operation(summary = "판매 게시글 목록 조회", description = "삭제되지 않은 판매 게시글을 페이지 단위로 조회합니다.")
	public ApiResponse<Page<PostResponse>> getPosts(
		@Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
		@RequestParam(defaultValue = "0") int page,
		@Parameter(description = "페이지 크기", example = "20")
		@RequestParam(defaultValue = "20") int size
	) {
		return ApiResponse.success(postService.getPosts(PageRequest.of(page, size)));
	}

	@GetMapping("/{postId}")
	@Operation(summary = "판매 게시글 상세 조회", description = "판매 게시글 ID로 단일 게시글 상세 정보를 조회합니다.")
	public ApiResponse<PostResponse> getPost(
		@Parameter(description = "판매 게시글 ID", example = "10")
		@PathVariable Long postId
	) {
		return ApiResponse.success(postService.getPost(postId));
	}

	@PatchMapping("/{postId}")
	@Operation(summary = "판매 게시글 수정", description = "인증 사용자가 본인 판매 게시글의 내용, 상태, 이미지를 수정합니다.")
	public ApiResponse<PostResponse> updatePost(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "판매 게시글 ID", example = "10")
		@PathVariable Long postId,
		@Valid @RequestBody UpdatePostRequest request
	) {
		return ApiResponse.success(postService.updatePost(user.userId(), postId, request));
	}

	@DeleteMapping("/{postId}")
	@Operation(summary = "판매 게시글 삭제", description = "인증 사용자가 본인 판매 게시글을 삭제 처리합니다.")
	public ApiResponse<Void> deletePost(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(description = "판매 게시글 ID", example = "10")
		@PathVariable Long postId
	) {
		postService.deletePost(user.userId(), postId);
		return ApiResponse.success(null);
	}
}
