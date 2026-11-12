// 인증 사용자의 배송지 등록·조회·수정·삭제 API를 제공한다.
package com.kitschcatch.backend.domain.address.controller;
import com.kitschcatch.backend.domain.address.dto.*;
import com.kitschcatch.backend.domain.address.service.ShippingAddressService;
import com.kitschcatch.backend.global.response.ApiResponse;
import com.kitschcatch.backend.global.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
@RestController @RequestMapping("/api/users/me/addresses")
@Tag(name="배송지",description="본인 주소록과 기본 배송지 관리") @SecurityRequirement(name="bearerAuth")
public class ShippingAddressController {
 private final ShippingAddressService service;
 public ShippingAddressController(ShippingAddressService service) { this.service=service; }
 @GetMapping @Operation(summary="내 배송지 목록",description="기본 배송지 우선, 최근 수정·ID 역순. 빈 목록은 빈 배열입니다.")
 public ApiResponse<List<AddressResponse>> list(@AuthenticationPrincipal AuthenticatedUser u) { return ApiResponse.success(service.list(u.userId())); }
 @GetMapping("/{id}") @Operation(summary="내 배송지 상세")
 public ApiResponse<AddressResponse> get(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable long id) { return ApiResponse.success(service.get(u.userId(),id)); }
 @PostMapping @Operation(summary="배송지 등록",description="기본 최대 10개. 첫 배송지는 자동으로 기본 지정합니다. 수령인·연락처·5자리 우편번호·기본주소 필수입니다.")
 public ApiResponse<AddressResponse> create(@AuthenticationPrincipal AuthenticatedUser u,@RequestBody AddressWriteRequest r) { return ApiResponse.created(service.create(u.userId(),r)); }
 @PatchMapping("/{id}") @Operation(summary="배송지 부분 수정",description="생략은 보존, 선택 문자열 null은 삭제. isDefault=true로 기본 변경하며 현재 기본의 false 단독 해제는 409입니다.")
 public ApiResponse<AddressResponse> update(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable long id,@RequestBody AddressWriteRequest r) { return ApiResponse.success(service.update(u.userId(),id,r)); }
 @DeleteMapping("/{id}") @Operation(summary="배송지 삭제",description="기본 배송지 삭제 시 남은 최근 수정 배송지를 기본으로 지정합니다. 주문 당시 주소는 보존됩니다.")
 public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable long id) { service.delete(u.userId(),id); return ApiResponse.success(null); }
}
