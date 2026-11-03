// 나노초 입력에서도 최초 도메인 상태와 DB 재조회 시각이 일치하는지 검증한다.
package com.kitschcatch.backend.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.kitschcatch.backend.domain.order.entity.OrderStatus;
import com.kitschcatch.backend.domain.order.entity.PgProvider;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.entity.SettlementRecipient;
import com.kitschcatch.backend.domain.order.entity.Shipment;
import com.kitschcatch.backend.domain.post.entity.Post;
import com.kitschcatch.backend.domain.post.entity.ProductCategory;
import com.kitschcatch.backend.domain.post.entity.ProductCondition;
import com.kitschcatch.backend.domain.post.entity.ProductStatus;
import com.kitschcatch.backend.domain.user.entity.AuthProvider;
import com.kitschcatch.backend.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:stored-timestamp-precision;MODE=PostgreSQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class StoredTimestampPrecisionTest {
    @Autowired EntityManager entities;

    @ParameterizedTest
    @ValueSource(ints = {123456789, 999999999})
    void registeredProfileTimeSurvivesReloadAndPartialUpdate(int nanos) {
        var user = user("profile");
        user.registerProfile("등록 사용자", "등록 사용자", null, time(nanos).toInstant(ZoneOffset.UTC));
        var first = user.getProfileRegisteredAt();
        flushAndClear();

        var loaded = entities.find(User.class, user.getId());
        assertThat(loaded.getProfileRegisteredAt()).isEqualTo(first);
        loaded.updatePublicProfile("collector", true, "소개", true);
        flushAndClear();
        assertThat(entities.find(User.class, user.getId()).getProfileRegisteredAt()).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(ints = {123456789, 999999999})
    void shipmentRegistrationAndUpdateTimesSurviveReload(int nanos) {
        var order = order();
        var shipment = atTime(time(nanos), () -> new Shipment("CJ_LOGISTICS", "123456789012"));
        order.registerShipment(shipment);
        var first = shipment.getRegisteredAt();
        flushAndClear();

        var loaded = entities.find(PurchaseOrder.class, order.getId()).getShipment();
        assertThat(loaded.getRegisteredAt()).isEqualTo(first);
        assertThat(loaded.getUpdatedAt()).isEqualTo(first);
        var updated = atTime(time(nanos).plusSeconds(1), () -> {
            loaded.update("HANJIN", "987654321098");
            return loaded.getUpdatedAt();
        });
        loaded.update("HANJIN", "987654321098");
        assertThat(loaded.getUpdatedAt()).isEqualTo(updated);
        flushAndClear();
        var reloaded = entities.find(PurchaseOrder.class, order.getId()).getShipment();
        assertThat(reloaded.getRegisteredAt()).isEqualTo(first);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updated);
    }

    @ParameterizedTest
    @ValueSource(ints = {123456789, 999999999})
    void purchaseConfirmationTimeSurvivesReloadAndRepeat(int nanos) {
        var order = order();
        var first = atTime(time(nanos), () -> {
            order.confirmPurchase();
            return order.getConfirmedAt();
        });
        flushAndClear();

        var loaded = entities.find(PurchaseOrder.class, order.getId());
        assertThat(loaded.getConfirmedAt()).isEqualTo(first);
        loaded.confirmPurchase();
        assertThat(loaded.getConfirmedAt()).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(ints = {123456789, 999999999})
    void recipientVerificationTimeSurvivesReload(int nanos) {
        var seller = user("recipient");
        var recipient = atTime(time(nanos), () -> new SettlementRecipient(seller.getId(), "seller_123", seller.getId()));
        var first = recipient.getVerifiedAt();
        entities.persist(recipient);
        flushAndClear();

        assertThat(entities.find(SettlementRecipient.class, seller.getId()).getVerifiedAt()).isEqualTo(first);
    }

    private static LocalDateTime time(int nanos) {
        return LocalDateTime.of(2026, 10, 1, 12, 34, 59, nanos);
    }

    private static <T> T atTime(LocalDateTime now, Supplier<T> action) {
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(now);
            return action.get();
        }
    }

    private User user(String name) {
        var user = User.builder().nickname(name).email(name + "@example.test")
            .authProvider(AuthProvider.KAKAO).providerUserId(name).build();
        entities.persist(user);
        return user;
    }

    private PurchaseOrder order() {
        var seller = user("seller");
        var buyer = user("buyer");
        var post = Post.builder().user(seller).title("시간 검증 상품").description("시간 정밀도 검증")
            .price(12000L).productCategory(ProductCategory.GOODS).productCondition(ProductCondition.NEW)
            .productStatus(ProductStatus.ON_SALE).build();
        entities.persist(post);
        var order = PurchaseOrder.builder().orderNumber("ORD-TIME-CHECK").user(buyer).post(post)
            .amount(12000L).pgProvider(PgProvider.TOSS_PAYMENTS).orderStatus(OrderStatus.PAID).build();
        entities.persist(order);
        return order;
    }

    private void flushAndClear() {
        entities.flush();
        entities.clear();
    }
}
