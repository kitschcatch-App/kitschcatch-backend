// 배송지 필수화 설정에서도 기존 주소록 계약과 주문 입력 검증을 확인한다.
package com.kitschcatch.backend.domain.address;
import static org.assertj.core.api.Assertions.assertThat;
import com.kitschcatch.backend.domain.post.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "kakao.oauth.native-app-key=test-key","spring.jpa.hibernate.ddl-auto=create-drop",
 "app.orders.expiration-enabled=false","springdoc.api-docs.enabled=true","app.orders.shipping-address-required=true"})
class ShippingAddressRequiredHttpTest extends ShippingAddressHttpTest {
 @Test void newOrderRequiresAnOwnedAddress() throws Exception {
  long seller=user();
  var post=posts.save(Post.builder().user(users.findById(seller).orElseThrow()).title("필수 배송지 상품").description("설명").price(1000L).productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW).productStatus(ProductStatus.ON_SALE).build());
  String prefix="{\"postId\":"+post.getId()+",\"amount\":1000,\"paymentMethod\":\"CARD\"";
  assertThat(call(owner,"POST","/api/orders",prefix+"}").status()).isEqualTo(400);
  assertThat(call(owner,"POST","/api/orders",prefix+",\"shippingAddressId\":99999999}").status()).isEqualTo(404);
  assertThat(call(owner,"POST","/api/orders",prefix+",\"shippingAddressId\":"+create()+"}").status()).isEqualTo(201);
 }
}
