package com.kitschcatch.backend.domain.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import jakarta.persistence.PersistenceUnitUtil;
import java.lang.reflect.Method;
import java.util.Optional;
import org.hibernate.annotations.BatchSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.EntityGraph;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:post-repository;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class PostRepositoryTest {

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	@Test
	@DisplayName("게시글 목록 조회는 작성자를 함께 로딩하고 이미지는 batch size로 지연 로딩한다")
	void findAllActivePostsLoadsUserAndKeepsImagesBatchLoadable() throws Exception {
		User user = saveUser();
		Post post = savePost(user, "posts/1/image.png");
		entityManager.flush();
		entityManager.clear();

		Post foundPost = postRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc(PageRequest.of(0, 20))
			.getContent()
			.getFirst();
		PersistenceUnitUtil persistenceUnitUtil = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();

		assertThat(foundPost.getId()).isEqualTo(post.getId());
		assertThat(persistenceUnitUtil.isLoaded(foundPost.getUser())).isTrue();
		assertThat(persistenceUnitUtil.isLoaded(foundPost, "images")).isFalse();
		assertThat(batchSizeOnPostImages()).isEqualTo(20);
	}

	@Test
	@DisplayName("게시글 상세 조회는 작성자와 이미지를 함께 로딩한다")
	void findDetailPostLoadsUserAndImages() {
		User user = saveUser();
		Post post = savePost(user, "posts/1/image.png");
		entityManager.flush();
		entityManager.clear();

		Optional<Post> foundPost = postRepository.findDetailByIdAndDeletedAtIsNull(post.getId());
		PersistenceUnitUtil persistenceUnitUtil = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();

		assertThat(foundPost).isPresent();
		assertThat(persistenceUnitUtil.isLoaded(foundPost.get().getUser())).isTrue();
		assertThat(persistenceUnitUtil.isLoaded(foundPost.get(), "images")).isTrue();
	}

	@Test
	@DisplayName("게시글 상세 조회와 일반 조회는 서로 다른 fetch graph를 사용한다")
	void repositoryMethodsUseSeparateEntityGraphs() throws Exception {
		Method listMethod = PostRepository.class.getMethod(
			"findAllByDeletedAtIsNullOrderByCreatedAtDesc",
			org.springframework.data.domain.Pageable.class
		);
		Method detailMethod = PostRepository.class.getMethod("findDetailByIdAndDeletedAtIsNull", Long.class);

		assertThat(listMethod.getAnnotation(EntityGraph.class).attributePaths()).containsExactly("user");
		assertThat(detailMethod.getAnnotation(EntityGraph.class).attributePaths()).containsExactly("user", "images");
	}

	private User saveUser() {
		return userRepository.save(User.builder()
			.nickname("seller")
			.email("seller@example.com")
			.authProvider(AuthProvider.KAKAO)
			.providerUserId("seller-provider")
			.build());
	}

	private Post savePost(User user, String imageKey) {
		Post post = Post.builder()
			.user(user)
			.title("키링 판매")
			.description("미개봉 굿즈입니다.")
			.price(12000L)
			.productCategory(ProductCategory.GOODS)
			.productCondition(ProductCondition.NEW)
			.productStatus(ProductStatus.ON_SALE)
			.build();
		post.addImage(imageKey, 0);
		return postRepository.save(post);
	}

	private int batchSizeOnPostImages() throws Exception {
		return Post.class.getDeclaredField("images")
			.getAnnotation(BatchSize.class)
			.size();
	}
}
