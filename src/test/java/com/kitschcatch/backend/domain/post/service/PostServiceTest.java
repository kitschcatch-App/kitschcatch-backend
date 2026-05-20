package com.kitschcatch.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlRequest;
import com.kitschcatch.backend.domain.post.dto.CreatePostImageUploadUrlResponse;
import com.kitschcatch.backend.domain.post.dto.CreatePostRequest;
import com.kitschcatch.backend.domain.post.dto.PostImageUploadItemRequest;
import com.kitschcatch.backend.domain.post.dto.PostResponse;
import com.kitschcatch.backend.domain.post.dto.UpdatePostRequest;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.PostImage;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class PostServiceTest {

	private PostRepository postRepository;
	private UserRepository userRepository;
	private PostImageStorage postImageStorage;
	private PostService postService;
	private User seller;

	@BeforeEach
	void setUp() {
		postRepository = mock(PostRepository.class);
		userRepository = mock(UserRepository.class);
		postImageStorage = mock(PostImageStorage.class);
		postService = new PostService(postRepository, userRepository, postImageStorage);
		seller = User.builder()
			.nickname("seller")
			.email("seller@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId("seller-provider")
			.build();
		ReflectionTestUtils.setField(seller, "id", 1L);
	}

	@Test
	@DisplayName("이미지 업로드 URL은 로그인 사용자 소유 경로로 발급한다")
	void createImageUploadUrlsDelegatesToStorage() {
		when(postImageStorage.createUploadUrl(1L, "image.png", "image/png"))
			.thenReturn(new PostImageUploadUrl(
				"https://bucket.s3.ap-northeast-2.amazonaws.com/posts/1/image.png?signature=abc",
				"posts/1/image.png",
				"https://cdn.example.com/posts/1/image.png",
				300
			));

		CreatePostImageUploadUrlResponse response = postService.createImageUploadUrls(
			1L,
			new CreatePostImageUploadUrlRequest(List.of(new PostImageUploadItemRequest("image.png", "image/png")))
		);

		assertThat(response.images()).hasSize(1);
		assertThat(response.images().getFirst().imageKey()).isEqualTo("posts/1/image.png");
		assertThat(response.images().getFirst().expiresIn()).isEqualTo(300);
	}

	@Test
	@DisplayName("판매 게시글 생성은 사진을 필수로 저장한다")
	void createPostSavesPostWithImages() {
		when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
		when(postImageStorage.isOwnedPostImageKey(1L, "posts/1/image.png")).thenReturn(true);
		when(postImageStorage.exists("posts/1/image.png")).thenReturn(true);
		when(postImageStorage.imageUrl("posts/1/image.png")).thenReturn("https://cdn.example.com/posts/1/image.png");
		when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));

		PostResponse response = postService.createPost(1L, new CreatePostRequest(
			"키링 판매",
			"미개봉 굿즈입니다.",
			12000L,
			ProductCategory.GOODS,
			ProductCondition.NEW,
			List.of("posts/1/image.png")
		));

		ArgumentCaptor<Post> postCaptor = ArgumentCaptor.forClass(Post.class);
		verify(postRepository).save(postCaptor.capture());
		Post savedPost = postCaptor.getValue();

		assertThat(savedPost.getUser()).isEqualTo(seller);
		assertThat(savedPost.getProductStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(savedPost.getImages()).extracting(PostImage::getObjectKey).containsExactly("posts/1/image.png");
		assertThat(response.images()).hasSize(1);
		assertThat(response.images().getFirst().imageUrl()).isEqualTo("https://cdn.example.com/posts/1/image.png");
	}

	@Test
	@DisplayName("판매 게시글 생성은 사진이 없으면 거부한다")
	void createPostWithoutImagesThrowsException() {
		assertThatThrownBy(() -> postService.createPost(1L, new CreatePostRequest(
			"키링 판매",
			"미개봉 굿즈입니다.",
			12000L,
			ProductCategory.GOODS,
			ProductCondition.NEW,
			List.of()
		)))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(ErrorCode.POST_IMAGE_REQUIRED);
	}

	@Test
	@DisplayName("판매 게시글 수정은 전달된 이미지 목록으로 교체한다")
	void updatePostReplacesImages() {
		Post post = postWithImage("posts/1/old.png");
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));
		when(postImageStorage.isOwnedPostImageKey(1L, "posts/1/new.png")).thenReturn(true);
		when(postImageStorage.exists("posts/1/new.png")).thenReturn(true);
		when(postImageStorage.imageUrl("posts/1/new.png")).thenReturn("https://cdn.example.com/posts/1/new.png");

		PostResponse response = postService.updatePost(1L, 10L, new UpdatePostRequest(
			"수정 제목",
			null,
			10000L,
			null,
			null,
			ProductStatus.RESERVED,
			List.of("posts/1/new.png")
		));

		assertThat(post.getTitle()).isEqualTo("수정 제목");
		assertThat(post.getPrice()).isEqualTo(10000L);
		assertThat(post.getProductStatus()).isEqualTo(ProductStatus.RESERVED);
		assertThat(post.getImages()).extracting(PostImage::getObjectKey).containsExactly("posts/1/new.png");
		assertThat(response.images().getFirst().imageKey()).isEqualTo("posts/1/new.png");
	}

	@Test
	@DisplayName("판매 게시글 삭제는 작성자만 소프트 삭제한다")
	void deletePostSoftDeletesOwnedPost() {
		Post post = postWithImage("posts/1/image.png");
		when(postRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(post));

		postService.deletePost(1L, 10L);

		assertThat(post.getDeletedAt()).isNotNull();
	}

	@Test
	@DisplayName("판매 게시글 목록은 삭제되지 않은 게시글만 조회한다")
	void getPostsReadsActivePosts() {
		Post post = postWithImage("posts/1/image.png");
		when(postImageStorage.imageUrl("posts/1/image.png")).thenReturn("https://cdn.example.com/posts/1/image.png");
		when(postRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc(PageRequest.of(0, 20)))
			.thenReturn(new PageImpl<>(List.of(post), PageRequest.of(0, 20), 1));

		assertThat(postService.getPosts(PageRequest.of(0, 20)).getContent()).hasSize(1);
	}

	private Post postWithImage(String imageKey) {
		Post post = Post.builder()
			.user(seller)
			.title("키링 판매")
			.description("미개봉 굿즈입니다.")
			.price(12000L)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE)
			.build();
		post.addImage(imageKey, 0);
		return post;
	}
}
