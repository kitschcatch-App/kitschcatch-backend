// 주문 당사자에게 배송 상태를 제공하고 구독 콜백을 접수한다.
package com.kitschcatch.backend.domain.order.tracking;

import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class TrackingController {
    private final ShipmentTracking tracking;

    public record Callback(@NotBlank @Pattern(regexp = "kr\\.(cjlogistics|hanjin)") String carrierId,
                           @NotBlank @Pattern(regexp = "[0-9]{8,40}") String trackingNumber) {}

    @GetMapping("/api/orders/{orderId}/shipment/tracking")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "주문 배송 추적")
    public ApiResponse<ShipmentTracking.View> get(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable String orderId) {
        return ApiResponse.success(tracking.get(actor.userId(), orderId));
    }

    @PostMapping("/api/shipment-tracking/webhooks/{token}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(hidden = true)
    public void receive(@PathVariable UUID token, @Valid @RequestBody Callback callback) {
        tracking.callback(token, callback.carrierId(), callback.trackingNumber());
    }
}
