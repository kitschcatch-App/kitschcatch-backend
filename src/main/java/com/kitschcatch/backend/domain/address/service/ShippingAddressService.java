// 사용자 잠금 안에서 주소록 제한과 기본 배송지를 유지한다.
package com.kitschcatch.backend.domain.address.service;
import com.kitschcatch.backend.domain.address.dto.*;
import com.kitschcatch.backend.domain.address.entity.ShippingAddress;
import com.kitschcatch.backend.domain.address.repository.ShippingAddressRepository;
import com.kitschcatch.backend.domain.user.repository.UserRepository;
import com.kitschcatch.backend.global.exception.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class ShippingAddressService {
 private final ShippingAddressRepository addresses;
 private final UserRepository users;
 private final int max;
 public ShippingAddressService(ShippingAddressRepository addresses,UserRepository users,@Value("${app.addresses.max-count:10}") int max) {
  if(max<1) throw new IllegalArgumentException("배송지 제한은 양수여야 합니다.");
  this.addresses=addresses; this.users=users; this.max=max;
 }
 private void active(long userId) { if(!users.existsByIdAndWithdrawnAtIsNull(userId)) throw new BusinessException(ErrorCode.INVALID_AUTH_TOKEN); }
 private void lock(long userId) { users.findActiveByIdForUpdate(userId).orElseThrow(()->new BusinessException(ErrorCode.INVALID_AUTH_TOKEN)); }
 private ShippingAddress owned(long userId,long id) { if(id<=0) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE); return addresses.findByIdAndUserId(id,userId).orElseThrow(()->new BusinessException(ErrorCode.ADDRESS_NOT_FOUND)); }
 @Transactional(readOnly=true) public List<AddressResponse> list(long userId) { active(userId); return addresses.findByUserIdOrderByIsDefaultDescUpdatedAtDescIdDesc(userId).stream().map(AddressResponse::from).toList(); }
 @Transactional(readOnly=true) public AddressResponse get(long userId,long id) { active(userId); return AddressResponse.from(owned(userId,id)); }
 @Transactional public AddressResponse create(long userId,AddressWriteRequest request) {
  lock(userId); var all=addresses.findByUserIdOrderByIsDefaultDescUpdatedAtDescIdDesc(userId);
  if(all.size()>=max) throw new BusinessException(ErrorCode.ADDRESS_LIMIT_EXCEEDED);
  var a=new ShippingAddress(userId); apply(a,request);
  if(all.isEmpty() || Boolean.TRUE.equals(request.defaultValue())) { clearDefault(all); a.setDefault(true); }
  return AddressResponse.from(addresses.saveAndFlush(a));
 }
 @Transactional public AddressResponse update(long userId,long id,AddressWriteRequest request) {
  lock(userId); var a=owned(userId,id); Boolean value=request.defaultValue();
  if(Boolean.FALSE.equals(value) && a.isDefault()) throw new BusinessException(ErrorCode.ADDRESS_DEFAULT_REQUIRED);
  apply(a,request);
  if(Boolean.TRUE.equals(value) && !a.isDefault()) { clearDefault(addresses.findByUserIdOrderByIsDefaultDescUpdatedAtDescIdDesc(userId)); a.setDefault(true); }
  return AddressResponse.from(addresses.saveAndFlush(a));
 }
 @Transactional public void delete(long userId,long id) {
  lock(userId); var a=owned(userId,id); boolean wasDefault=a.isDefault(); addresses.delete(a); addresses.flush();
  if(wasDefault) { var rest=addresses.findByUserIdOrderByIsDefaultDescUpdatedAtDescIdDesc(userId); if(!rest.isEmpty()) rest.getFirst().setDefault(true); }
 }
 // 주문 생성도 사용자 행을 먼저 잠가 배송지 변경과 순서대로 처리한다.
 @Transactional public ShippingAddress forOrder(long userId,long id) { lock(userId); return owned(userId,id); }
 private void clearDefault(List<ShippingAddress> all) { all.forEach(a->a.setDefault(false)); addresses.flush(); }
 private void apply(ShippingAddress a,AddressWriteRequest r) {
  String label=r.text("label",a.getLabel(),false,30), name=r.text("recipientName",a.getRecipientName(),true,50);
  String phone=r.text("recipientPhone",a.getRecipientPhone(),true,30), postal=r.text("postalCode",a.getPostalCode(),true,5);
  String line1=r.text("addressLine1",a.getAddressLine1(),true,255), line2=r.text("addressLine2",a.getAddressLine2(),false,255), memo=r.text("deliveryMemo",a.getDeliveryMemo(),false,200);
  r.defaultValue();
  if(phone!=null) phone=phone.replaceAll("[-\\s]", "");
  if(name==null || phone==null || !phone.matches("[0-9]{9,12}") || postal==null || !postal.matches("[0-9]{5}") || line1==null) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
  a.change(label,name,phone,postal,line1,line2,memo);
 }
}
