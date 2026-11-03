// 기존 상품 이미지가 상품·채팅 HTTP 응답에서 유효한 GET 서명 URL로 반환되는지 검증한다.
package com.kitschcatch.backend.domain.post;

import static com.kitschcatch.backend.support.S3GetUrlAssertions.assertGetUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.security.JwtTokenProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:post-image-read-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop", "kakao.oauth.native-app-key=test-native-app-key",
	"app.orders.expiration-enabled=false", "app.payments.recovery.enabled=false",
	"app.s3.bucket=test-bucket", "app.s3.public-base-url=https://old-cdn.example.com",
	"app.s3.download-url-ttl=12m"
})
class PostImageReadHttpTest {
	@LocalServerPort int port;
	@Autowired UserRepository users;
	@Autowired PostRepository posts;
	@Autowired JwtTokenProvider tokens;
	@MockitoBean S3Client s3;
	@TestBean(methodName = "testPresigner") S3Presigner s3Presigner;

	static S3Presigner testPresigner() {
		return S3Presigner.builder().region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")))
			.build();
	}

	@Test
	void existingObjectKeysAreSignedForPostListDetailAndChatThumbnail() {
		User seller = user("seller");
		User buyer = user("buyer");
		String key = "posts/" + seller.getId() + "/기존 사진.png";
		Post post = Post.builder().user(seller).title("기존 상품").description("이미지 조회 검증").price(12000L)
			.productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build();
		post.addImage(key, 0);
		post = posts.saveAndFlush(post);
		RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + port)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.createAccessToken(buyer.getId())).build();

		Map<?, ?> detail = data(client.get().uri("/api/posts/" + post.getId()).retrieve().body(Map.class));
		assertPostImage(detail, key);
		Map<?, ?> page = data(client.get().uri("/api/posts").retrieve().body(Map.class));
		assertPostImage((Map<?, ?>) ((List<?>) page.get("content")).getFirst(), key);
		Map<?, ?> room = data(client.post().uri("/api/chat-rooms").body(Map.of("postId", post.getId()))
			.retrieve().body(Map.class));
		Map<?, ?> chat = data(client.get().uri("/api/chat-rooms/" + room.get("chatRoomId")).retrieve().body(Map.class));
		assertGetUrl(chat.get("postThumbnailImageUrl").toString(), "test-bucket", key, 720, "test-access", "test-secret");
		assertPostImage(data(client.get().uri("/api/posts/" + post.getId()).retrieve().body(Map.class)), key);
		verifyNoInteractions(s3);
	}

	private void assertPostImage(Map<?, ?> post, String key) {
		Map<?, ?> image = (Map<?, ?>) ((List<?>) post.get("images")).getFirst();
		assertThat(image.get("imageKey")).isEqualTo(key);
		assertGetUrl(image.get("imageUrl").toString(), "test-bucket", key, 720, "test-access", "test-secret");
	}

	private Map<?, ?> data(Map<?, ?> response) {
		assertThat(response.get("success")).isEqualTo(true);
		return (Map<?, ?>) response.get("data");
	}

	private User user(String name) {
		return users.saveAndFlush(User.builder().nickname(name).email(name + "@example.test")
			.authProvider(AuthProvider.KAKAO).providerUserId(name).build());
	}
}
