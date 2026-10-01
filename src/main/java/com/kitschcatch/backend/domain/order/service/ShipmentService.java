// 결제된 주문의 판매자만 송장을 등록·수정하고 거래 당사자에게 조회를 제공한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.order.dto.*;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.PaymentRepository;
import com.kitschcatch.backend.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class ShipmentService {
    private final OrderAccessService access;
    private final PaymentRepository payments;
    @Transactional
    public ShipmentResponse save(long userId,String number,ShipmentRequest request,boolean update) {
        var o=access.lock(userId,number);
        if (!o.getSellerId().equals(userId)) throw new BusinessException(ErrorCode.SHIPMENT_FORBIDDEN);
        var payment=payments.findByOrderIdForUpdate(o.getId()).orElse(null);
        if (o.getOrderStatus()!=OrderStatus.PAID || o.getCancelReason()!=null
            || payment==null || payment.getPaymentStatus()!=PaymentStatus.SUCCESS || payment.isRecoveryReviewRequired())
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
        if (update) {
            if (o.getShipment()==null) throw new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND);
            o.getShipment().update(request.carrierCode(),request.trackingNumber());
        } else if (o.getShipment()==null) {
            o.registerShipment(new Shipment(request.carrierCode(),request.trackingNumber()));
        } else if (!o.getShipment().matches(request.carrierCode(),request.trackingNumber())) {
            throw new BusinessException(ErrorCode.ORDER_REQUEST_CONFLICT);
        }
        return ShipmentResponse.from(o);
    }
    @Transactional(readOnly=true)
    public ShipmentResponse get(long userId,String number) {
        var o=access.read(userId,number);
        if (o.getShipment()==null) throw new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND);
        return ShipmentResponse.from(o);
    }
}
