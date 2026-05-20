package com.kitschcatch.backend.domain.post.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlRequest;
import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlResponse;
import com.kitschcatch.backend.domain.post.dto.CreatePostRequest;
import com.kitschcatch.backend.domain.post.dto.PostImageResponse;
import com.kitschcatch.backend.domain.post.dto.PostImageUploadUrlResponse;
import com.kitschcatch.backend.domain.post.dto.PostResponse;
import com.kitschcatch.backend.domain.post.dto.UpdatePostRequest;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.service.PostService;
import com.kitschcatch.backend.global.exception.GlobalExceptionHandler;
import com.kitschcatch.backend.global.response.ResponseStatusSetterAdvice;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PostControllerTest {

	private PostService postService;
	private MockMvc mockMvc;
	private UsernamePasswordAuthenticationToken authentication;

	@BeforeEach
	void setUp() {
		postService = mock(PostService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new PostController(postService))
			.setControllerAdvice(new ResponseStatusSetterAdvice(), new GlobalExceptionHandler())
			.build();
		authentication = new UsernamePasswordAuthenticationToken(new AuthenticatedUser(1L), null, List.of());
	}

	@Test
	@DisplayName("판매 게시글 이미지 업로드용 presigned URL을 발급한다")
	void createImageUploadUrlsReturnsPresignedUrls() throws Exception {
		when(postService.createImageUploadUrls(eq(1L), any(CreatePostImageUploadUrlRequest.class)))
			.thenReturn(new CreatePostImageUploadUrlResponse(List.of(
				new PostImageUploadUrlResponse(
					"https://bucket.s3.ap-northeast-2.amazonaws.com/posts/1/image.png?signature=abc",
					"posts/1/image.png",
					"https://cdn.example.com/posts/1/image.png",
					300
				)
			)));

		mockMvc.perform(post("/api/posts/images/presigned-urls")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "images": [
					    {
					      "originalFileName": "image.png",
					      "contentType": "image/png"
					    }
					  ]
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.images[0].uploadUrl").value(
				"https://bucket.s3.ap-northeast-2.amazonaws.com/posts/1/image.png?signature=abc"))
			.andExpect(jsonPath("$.data.images[0].imageKey").value("posts/1/image.png"))
			.andExpect(jsonPath("$.data.images[0].imageUrl").value("https://cdn.example.com/posts/1/image.png"))
			.andExpect(jsonPath("$.data.images[0].expiresIn").value(300));
	}

	@Test
	@DisplayName("판매 게시글 생성 API는 사진이 포함된 게시글을 생성한다")
	void createPostReturnsCreatedPost() throws Exception {
		when(postService.createPost(eq(1L), any(CreatePostRequest.class)))
			.thenReturn(postResponse());

		mockMvc.perform(post("/api/posts")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "키링 판매",
					  "description": "미개봉 굿즈입니다.",
					  "price": 12000,
					  "productCategory": "굿즈",
					  "productCondition": "NEW",
					  "imageKeys": ["posts/1/image.png"]
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(10))
			.andExpect(jsonPath("$.data.productCategory").value("굿즈"))
			.andExpect(jsonPath("$.data.images[0].imageUrl").value("https://cdn.example.com/posts/1/image.png"));
	}

	@Test
	@DisplayName("판매 게시글 목록 API는 삭제되지 않은 게시글 페이지를 반환한다")
	void getPostsReturnsPostPage() throws Exception {
		when(postService.getPosts(PageRequest.of(0, 20)))
			.thenReturn(new PageImpl<>(List.of(postResponse()), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/posts")
				.principal(authentication)
				.param("page", "0")
				.param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(10))
			.andExpect(jsonPath("$.data.totalElements").value(1));
	}

	@Test
	@DisplayName("판매 게시글 상세 API는 단일 게시글을 반환한다")
	void getPostReturnsPost() throws Exception {
		when(postService.getPost(10L)).thenReturn(postResponse());

		mockMvc.perform(get("/api/posts/10")
				.principal(authentication))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(10))
			.andExpect(jsonPath("$.data.images[0].imageKey").value("posts/1/image.png"));
	}

	@Test
	@DisplayName("판매 게시글 수정 API는 본인 게시글을 수정한다")
	void updatePostReturnsUpdatedPost() throws Exception {
		when(postService.updatePost(eq(1L), eq(10L), any(UpdatePostRequest.class)))
			.thenReturn(postResponse());

		mockMvc.perform(patch("/api/posts/10")
				.principal(authentication)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "키링 판매 수정",
					  "price": 10000,
					  "productStatus": "RESERVED",
					  "imageKeys": ["posts/1/image.png"]
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(10));
	}

	@Test
	@DisplayName("판매 게시글 삭제 API는 본인 게시글을 소프트 삭제한다")
	void deletePostReturnsSuccess() throws Exception {
		mockMvc.perform(delete("/api/posts/10")
				.principal(authentication))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		verify(postService).deletePost(1L, 10L);
	}

	private PostResponse postResponse() {
		LocalDateTime now = LocalDateTime.of(2026, 5, 20, 12, 0);
		return new PostResponse(
			10L,
			1L,
			"seller",
			"키링 판매",
			"미개봉 굿즈입니다.",
			12000L,
			ProductCategory.GOODS,
			ProductCondition.NEW,
			ProductStatus.ON_SALE,
			List.of(new PostImageResponse("posts/1/image.png", "https://cdn.example.com/posts/1/image.png", 0)),
			now,
			now
		);
	}
}
