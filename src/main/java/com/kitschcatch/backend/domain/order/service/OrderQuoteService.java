// 활성 사용자와 상품 잠금 순서를 지키며 서버 견적과 고정 배송비를 관리한다.
package com.kitschcatch.backend.domain.order.service;
import com.kitschcatch.backend.domain.order.controller.OrderQuoteController.*;
import com.kitschcatch.backend.domain.order.dto.PriceBreakdown;
import com.kitschcatch.backend.domain.order.entity.*;
import com.kitschcatch.backend.domain.order.repository.OrderQuoteRepository;
import com.kitschcatch.backend.domain.post.entity.*;
import com.kitschcatch.backend.domain.post.repository.PostRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.domain.address.service.ShippingAddressService;
import com.kitschcatch.backend.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class OrderQuoteService {
 private final PostRepository posts;
 private final UserRepository users;
 private final ShippingAddressService addresses;
 private final OrderQuoteRepository quotes;
 private Post post(long id) {if(id<=0)throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);return posts.findByIdForUpdate(id).filter(p->p.getDeletedAt()==null).orElseThrow(()->new BusinessException(ErrorCode.POST_NOT_FOUND));}
 @Transactional public QuoteResponse quote(long userId,QuoteRequest r) {
  users.findActiveByIdForUpdate(userId).orElseThrow(()->new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
  var post=post(r.postId());
  if(post.getUser().getId().equals(userId) || post.getProductStatus()!=ProductStatus.ON_SALE)throw new BusinessException(ErrorCode.ORDER_INVALID_STATE);
  var address=addresses.forOrder(userId,r.shippingAddressId());
  OrderPricing pricing;
  try {pricing=OrderPricing.calculate(post.getPrice(),ShippingPolicy.AMOUNT);}
  catch(IllegalArgumentException|ArithmeticException e) {throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);}
  var q=quotes.save(new OrderQuote(userId,post.getId(),address.getId(),address.getUpdatedAt(),pricing));
  return new QuoteResponse(q.getId(),PriceBreakdown.from(pricing),q.getExpiresAt());
 }
 @Transactional public ShippingResponse shipping(long postId) {post(postId);return new ShippingResponse(ShippingPolicy.AMOUNT);}
 @Transactional public ShippingResponse shipping(long userId,long postId,long amount) {
  users.findActiveByIdForUpdate(userId).orElseThrow(()->new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));
  var p=post(postId);
  if(!p.getUser().getId().equals(userId))throw new BusinessException(ErrorCode.POST_FORBIDDEN);
  if(p.getActiveOrderNumber()!=null || p.getProductStatus()!=ProductStatus.ON_SALE)throw new BusinessException(ErrorCode.POST_TRANSACTION_IN_PROGRESS);
  if(amount!=ShippingPolicy.AMOUNT)throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
  p.changeShipping(amount);return new ShippingResponse(p.getShippingAmount());
 }
}
