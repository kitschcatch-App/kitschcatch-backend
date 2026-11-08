// 배송지 입력의 필드 생략과 명시적 null 및 JSON 타입을 보존한다.
package com.kitschcatch.backend.domain.address.dto;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.kitschcatch.backend.global.exception.*;
import java.util.*;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(description="배송지 등록·부분 수정. 생략 필드는 보존하며 선택 문자열의 null은 삭제합니다.", implementation=AddressWriteRequest.Fields.class)
public class AddressWriteRequest {
 public record Fields(
  @Schema(maxLength=30,description="배송지 이름") String label,
  @Schema(maxLength=50,description="수령인. 등록 시 필수") String recipientName,
  @Schema(description="연락처. 하이픈·공백 제거 후 숫자 9~12자리, 등록 시 필수") String recipientPhone,
  @Schema(pattern="[0-9]{5}",description="5자리 우편번호. 등록 시 필수") String postalCode,
  @Schema(maxLength=255,description="기본주소. 등록 시 필수") String addressLine1,
  @Schema(maxLength=255,description="상세주소. null로 삭제") String addressLine2,
  @Schema(maxLength=200,description="배송 요청사항. null로 삭제") String deliveryMemo,
  @Schema(description="기본 배송지 여부. 첫 등록은 자동 true") Boolean isDefault) {}
 private static final Set<String> ALLOWED=Set.of("label","recipientName","recipientPhone","postalCode","addressLine1","addressLine2","deliveryMemo","isDefault");
 private final Map<String,Object> fields;
 @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
 public AddressWriteRequest(Map<String,Object> fields) {
  if(fields==null || fields.isEmpty() || !ALLOWED.containsAll(fields.keySet())) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
  this.fields=new HashMap<>(fields);
 }
 public boolean has(String key) { return fields.containsKey(key); }
 public String text(String key,String current,boolean required,int max) {
  Object v=fields.get(key);
  if(!has(key)) return current;
  if(v!=null && !(v instanceof String)) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
  String s=v==null?null:((String)v).strip();
  if(s!=null && s.isEmpty()) s=null;
  if((required && s==null) || (s!=null && s.length()>max)) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
  return s;
 }
 public Boolean defaultValue() {
  if(!has("isDefault")) return null;
  if(!(fields.get("isDefault") instanceof Boolean b)) throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
  return b;
 }
}
