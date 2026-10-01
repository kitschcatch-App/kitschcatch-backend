// 공개 주문 번호와 사용자 존재·거래 당사자 권한을 확인한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.order.entity.PurchaseOrder;
import com.kitschcatch.backend.domain.order.repository.PurchaseOrderRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import lombok.RequiredArgsConstructor;
@Service
@RequiredArgsConstructor
public class OrderAccessService {
    private final PurchaseOrderRepository orders;
    private final UserRepository users;
    private final OrderReservationService reservations;
    @Transactional(propagation = Propagation.MANDATORY)
    public PurchaseOrder lock(long userId, String number) {
        validate(userId, number);
        var order = reservations.lockOrder(orders.findIdByOrderNumber(number)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND)));
        requireParticipant(order, userId);
        return order;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public PurchaseOrder read(long userId, String number) {
        validate(userId, number);
        var order = orders.findByOrderNumber(number)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        requireParticipant(order, userId);
        return order;
    }
    public void requireBuyer(PurchaseOrder order, long userId) {
        if (!order.getUser().getId().equals(userId)) throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
    }
    private void requireParticipant(PurchaseOrder order, long userId) {
        if (!order.getUser().getId().equals(userId) && !order.getSellerId().equals(userId))
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
    }
    private void validate(long userId, String number) {
        if (number == null || !number.matches("ORD-[A-Z0-9][A-Z0-9-]{0,45}"))
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        if (!users.existsById(userId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN);
    }
}
