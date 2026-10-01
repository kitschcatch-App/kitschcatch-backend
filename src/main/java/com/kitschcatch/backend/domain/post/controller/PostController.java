package com.kitschcatch.backend.domain.post.controller;

import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlRequest;
import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlResponse;
import com.kitschcatch.backend.domain.post.dto.CreatePostRequest;
import com.kitschcatch.backend.domain.post.dto.PostResponse;
import com.kitschcatch.backend.domain.post.dto.UpdatePostRequest;
import com.kitschcatch.backend.domain.post.service.PostSearchQuery;
import com.kitschcatch.backend.domain.post.service.PostSearchService;
import com.kitschcatch.backend.domain.post.service.PostService;
import com.kitschcatch.backend.global.exception.ErrorCode;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/posts")
@Tag(name = "판매 게시글", description = "판매 게시글 생성, 조회, 수정, 삭제와 이미지 업로드 URL 발급 API")
@SecurityRequirement(name = "bearerAuth")
public class PostController {

	private final PostService postService;
	private final PostSearchService postSearchService;

	public PostController(PostService postService, PostSearchService postSearchService) {
		this.postService = postService;
		this.postSearchService = postSearchService;
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
	@Operation(summary = "판매 게시글 목록 검색", description = "필터는 AND, keyword는 제목 또는 설명의 대소문자 무시 부분 문자열입니다. 공백 검색어는 전체이며 %, _, !는 문자 그대로 검색합니다. 삭제 상품을 제외하며 상태 생략 시 모든 판매 상태를 포함합니다. LATEST는 생성 시각·ID 역순, 가격 정렬의 동률도 생성 시각·ID 역순입니다. 가격 경계 포함, 빈 결과도 200입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_001: 필터·가격·정렬·페이지 입력 오류")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_004: 유효한 사용자 access JWT 필요")
	public ApiResponse<Page<PostResponse>> getPosts(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Parameter(schema = @Schema(allowableValues = {"ON_SALE", "RESERVED", "SOLD_OUT"})) @RequestParam(required = false) String status,
		@Parameter(schema = @Schema(maxLength = 1000)) @RequestParam(required = false) String keyword,
		@Parameter(description = "기존 카테고리 enum 이름 또는 한글 라벨", schema = @Schema(allowableValues = {"ANIME_MANGA", "GAME", "GOODS", "COSPLAY", "BOOK", "MUSIC_VIDEO", "ETC", "애니/만화", "게임", "굿즈", "코스프레", "서적", "음반/영상", "기타"})) @RequestParam(required = false) String category,
		@Parameter(schema = @Schema(allowableValues = {"NEW", "LIKE_NEW", "USED", "DAMAGED"})) @RequestParam(required = false) String condition,
		@Parameter(schema = @Schema(minimum = "0")) @RequestParam(required = false) Long minPrice,
		@Parameter(schema = @Schema(minimum = "0")) @RequestParam(required = false) Long maxPrice,
		@Parameter(schema = @Schema(allowableValues = {"LATEST", "PRICE_ASC", "PRICE_DESC"}, defaultValue = "LATEST")) @RequestParam(required = false) String sort,
		@Parameter(description = "0부터 시작하는 페이지 번호", schema = @Schema(minimum = "0", maximum = "10000")) @RequestParam(defaultValue = "0") int page,
		@Parameter(schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size
	) {
		return ApiResponse.success(postSearchService.search(user.userId(), PostSearchQuery.from(
			status, keyword, category, condition, minPrice, maxPrice, sort, page, size)));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ApiResponse<Void> invalidParameter(MethodArgumentTypeMismatchException exception) {
		return ApiResponse.fail(switch (exception.getName()) {
			case "page", "size", "minPrice", "maxPrice" -> ErrorCode.INVALID_INPUT_VALUE;
			default -> ErrorCode.BAD_REQUEST;
		});
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
