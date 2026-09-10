// 실제 HTTP와 인증 필터를 거쳐 상품 등록부터 주문 승인 및 취소까지 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kitschcatch.backend.domain.order.toss.TossPaymentResponse;
import com.kitschcatch.backend.domain.order.toss.TossPaymentsClient;
import com.kitschcatch.backend.domain.post.service.PostImageStorage;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:reservation-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"kakao.oauth.native-app-key=test-native-app-key",
	"app.orders.expiration-enabled=false"
})
class OrderReservationHttpTest {
	@LocalServerPort private int port;
	@Autowired private UserRepository userRepository;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@MockitoBean private TossPaymentsClient tossPaymentsClient;
	@MockitoBean private PostImageStorage postImageStorage;

	@Test
	void authenticatedBuyerCanReservePayAndCancelWhileSellerCannotChangeActivePost() {
		User seller = userRepository.save(user("http-seller"));
		User buyer = userRepository.save(user("http-buyer"));
		RestClient sellerClient = client(seller.getId());
		RestClient buyerClient = client(buyer.getId());
		String imageKey = "posts/" + seller.getId() + "/keyring.png";
		when(postImageStorage.isOwnedPostImageKey(seller.getId(), imageKey)).thenReturn(true);
		when(postImageStorage.exists(imageKey)).thenReturn(true);
		when(postImageStorage.imageUrl(imageKey)).thenReturn("https://cdn.example.com/keyring.png");

		Map<?, ?> createdPost = data(sellerClient.post().uri("/api/posts").body(Map.of(
			"title", "키링", "description", "미개봉", "price", 12000,
			"productCategory", "GOODS", "productCondition", "NEW", "imageKeys", List.of(imageKey)
		)).retrieve().body(Map.class));
		String postUrl = "/api/posts/" + createdPost.get("id");
		Map<String, Object> orderRequest = Map.of("postId", createdPost.get("id"), "amount", 12000, "paymentMethod", "CARD");
		var creation = buyerClient.post().uri("/api/orders").body(orderRequest).retrieve().toEntity(Map.class);
		assertThat(creation.getStatusCode().value()).isEqualTo(201);
		Map<?, ?> order = data(creation.getBody());
		String orderId = (String) order.get("orderId");
		String paymentId = (String) order.get("paymentId");
		assertThat(data(buyerClient.get().uri(postUrl).retrieve().body(Map.class)).get("productStatus")).isEqualTo("RESERVED");

		assertThatThrownBy(() -> sellerClient.patch().uri(postUrl).body(Map.of("price", 1)).retrieve().toBodilessEntity())
			.isInstanceOf(HttpClientErrorException.Conflict.class);
		assertThatThrownBy(() -> sellerClient.delete().uri(postUrl).retrieve().toBodilessEntity())
			.isInstanceOf(HttpClientErrorException.Conflict.class);
		assertThatThrownBy(() -> buyerClient.post().uri("/api/orders").body(orderRequest).retrieve().toBodilessEntity())
			.isInstanceOf(HttpClientErrorException.BadRequest.class);
		assertThatThrownBy(() -> sellerClient.get().uri("/api/payments/" + paymentId).retrieve().toBodilessEntity())
			.isInstanceOf(HttpClientErrorException.NotFound.class);

		when(tossPaymentsClient.confirm(any())).thenReturn(new TossPaymentResponse("pg-key", orderId, 12000L, "DONE"));
		Map<?, ?> approved = data(buyerClient.post().uri("/api/payments/" + paymentId + "/confirm")
			.body(Map.of("paymentId", paymentId, "paymentKey", "pg-key")).retrieve().body(Map.class));
		assertThat(approved.get("status")).isEqualTo("SUCCESS");
		assertThat(data(buyerClient.get().uri(postUrl).retrieve().body(Map.class)).get("productStatus")).isEqualTo("SOLD_OUT");

		when(tossPaymentsClient.cancel(any())).thenReturn(new TossPaymentResponse("pg-key", orderId, 12000L, "CANCELED"));
		Map<?, ?> canceled = data(buyerClient.post().uri("/api/payments/" + paymentId + "/cancel").retrieve().body(Map.class));
		assertThat(canceled.get("status")).isEqualTo("CANCELED");
		assertThat(data(buyerClient.get().uri(postUrl).retrieve().body(Map.class)).get("productStatus")).isEqualTo("ON_SALE");
		assertThat(sellerClient.patch().uri(postUrl).body(Map.of("price", 13000)).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
	}

	private RestClient client(Long userId) {
		return RestClient.builder().baseUrl("http://127.0.0.1:" + port)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId))
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
	}

	private Map<?, ?> data(Map<?, ?> response) {
		assertThat(response.get("success")).isEqualTo(true);
		return (Map<?, ?>) response.get("data");
	}

	private User user(String nickname) {
		return User.builder().nickname(nickname).email(nickname + "@example.com")
			.authProvider(AuthProvider.KAKAO).providerUserId(nickname).build();
	}
}
