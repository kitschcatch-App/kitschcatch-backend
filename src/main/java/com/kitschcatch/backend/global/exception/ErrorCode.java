// 공통 예외 상황별 HTTP 상태와 내부 에러 코드를 관리하는 enum
package com.kitschcatch.backend.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
 ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND,"ADDRESS_001","배송지를 찾을 수 없습니다."),
 ADDRESS_LIMIT_EXCEEDED(HttpStatus.CONFLICT,"ADDRESS_002","배송지 등록 한도를 초과했습니다."),
 ADDRESS_DEFAULT_REQUIRED(HttpStatus.CONFLICT,"ADDRESS_003","다른 배송지를 기본으로 지정해 주세요."),


	REPORT_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "REPORT_001", "신고 대상을 찾을 수 없습니다."),
    REPORT_SELF_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "REPORT_002", "본인 또는 본인 콘텐츠는 신고할 수 없습니다."),
    REPORT_REASON_INVALID(HttpStatus.BAD_REQUEST, "REPORT_003", "이 대상에 사용할 수 없는 신고 사유입니다."),
    REPORT_ALREADY_EXISTS(HttpStatus.CONFLICT, "REPORT_004", "이미 접수된 신고가 있습니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "REPORT_005", "신고를 찾을 수 없습니다."),
    REPORT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "REPORT_006", "신고 접수 횟수를 초과했습니다."),
    REPORT_MESSAGE_INVALID(HttpStatus.BAD_REQUEST, "REPORT_007", "신고 메시지 연결 정보가 올바르지 않습니다."),

    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_002", "잘못된 요청입니다."),
	KAKAO_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_001", "카카오 로그인에 실패했습니다."),
	KAKAO_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "AUTH_002", "카카오 이메일 동의가 필요합니다."),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_003", "리프레시 토큰이 올바르지 않습니다."),
	INVALID_AUTH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_004", "인증 토큰이 올바르지 않습니다."),
	NAVER_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_005", "네이버 로그인에 실패했습니다."),
	APPLE_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_006", "Apple 로그인에 실패했습니다."),
	SOCIAL_LOGIN_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_007", "소셜 로그인 설정이 필요합니다."),
	POST_NOT_FOUND(HttpStatus.NOT_FOUND, "POST_001", "판매 게시글을 찾을 수 없습니다."),
	POST_FORBIDDEN(HttpStatus.FORBIDDEN, "POST_002", "판매 게시글에 접근할 수 없습니다."),
	POST_IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "POST_003", "판매 게시글 사진은 필수입니다."),
	POST_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "POST_004", "판매 게시글 사진 정보가 올바르지 않습니다."),
	POST_IMAGE_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "POST_005", "업로드된 판매 게시글 사진을 찾을 수 없습니다."),
	POST_TRANSACTION_IN_PROGRESS(HttpStatus.CONFLICT, "POST_006", "거래 중인 상품은 변경하거나 다시 주문할 수 없습니다."),
	S3_BUCKET_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "S3_001", "S3 버킷 설정이 필요합니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 내부 오류가 발생했습니다."),

    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_001", "알림을 찾을 수 없습니다."),

	STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE_001", "매장을 찾을 수 없습니다."),
	STORE_QUERY_INVALID(HttpStatus.BAD_REQUEST, "STORE_002", "위도, 경도 또는 조회 반경이 올바르지 않습니다."),
	STORE_REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE_REVIEW_001", "매장 리뷰를 찾을 수 없습니다."),
	STORE_REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "STORE_REVIEW_002", "이미 이 매장에 리뷰를 작성했습니다."),
	STORE_REVIEW_FORBIDDEN(HttpStatus.FORBIDDEN, "STORE_REVIEW_003", "본인의 매장 리뷰만 변경할 수 있습니다."),
	STORE_REVIEW_VERSION_CONFLICT(HttpStatus.CONFLICT, "STORE_REVIEW_004", "리뷰가 변경되었습니다. 다시 조회해 주세요."),
	STORE_REVIEW_CREATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "STORE_REVIEW_005", "이 매장에 작성할 수 있는 리뷰 횟수를 초과했습니다."),
    STORE_REVIEW_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "STORE_REVIEW_IMAGE_001", "리뷰 사진 형식, 크기 또는 연결 정보가 올바르지 않습니다."),
    STORE_REVIEW_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE_REVIEW_IMAGE_002", "리뷰 사진을 찾을 수 없습니다."),
    STORE_REVIEW_IMAGE_NOT_UPLOADED(HttpStatus.CONFLICT, "STORE_REVIEW_IMAGE_003", "사진 업로드를 먼저 완료해 주세요."),
    STORE_REVIEW_IMAGE_EXPIRED(HttpStatus.CONFLICT, "STORE_REVIEW_IMAGE_004", "사진 준비 시간이 만료되었습니다. 다시 업로드해 주세요."),
    STORE_REVIEW_IMAGE_BUSY(HttpStatus.TOO_MANY_REQUESTS, "STORE_REVIEW_IMAGE_005", "잠시 후 사진 업로드를 다시 시도해 주세요."),
    STORE_REVIEW_IMAGE_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STORE_REVIEW_IMAGE_006", "사진 저장소에 일시적으로 접근할 수 없습니다."),
    STORE_REVIEW_IMAGE_HIDDEN(HttpStatus.CONFLICT, "STORE_REVIEW_IMAGE_007", "숨김 처리된 리뷰의 사진은 변경할 수 없습니다."),

	USER_WITHDRAWAL_BLOCKED(HttpStatus.CONFLICT, "USER_057_001", "진행 중이거나 확인이 필요한 거래가 있어 탈퇴할 수 없습니다."),
	USER_INTERACTION_BLOCKED(HttpStatus.FORBIDDEN, "USER_009", "이 사용자와는 현재 채팅이나 팔로우를 할 수 없습니다."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_001", "사용자를 찾을 수 없습니다."),
	USER_PROFILE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "USER_002", "프로필이 이미 등록되어 있습니다."),
	USER_PROFILE_NOT_REGISTERED(HttpStatus.CONFLICT, "USER_003", "프로필을 먼저 등록해야 합니다."),
	USER_NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "USER_004", "이미 사용 중인 닉네임입니다."),
	USER_PROFILE_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "USER_005", "프로필 이미지 정보가 올바르지 않습니다."),
	USER_PROFILE_IMAGE_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "USER_006", "업로드된 프로필 이미지를 찾을 수 없습니다."),
	USER_PROFILE_IMAGE_FORBIDDEN(HttpStatus.FORBIDDEN, "USER_007", "다른 사용자의 프로필 이미지는 사용할 수 없습니다."),
	USER_USERNAME_DUPLICATED(HttpStatus.CONFLICT, "USER_008", "이미 사용 중인 사용자 아이디입니다."),


	//chatRoom
	CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_001", "채팅방을 찾을 수 없습니다."),
	CHAT_ROOM_SELF_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "CHAT_002", "자신의 판매 게시글에는 문의할 수 없습니다."),
	CHAT_ROOM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "CHAT_003", "채팅방에 접근할 권한이 없습니다."),
	MESSAGE_CONTENT_EMPTY(HttpStatus.BAD_REQUEST, "CHAT_004", "메시지 내용은 비어 있을 수 없습니다."),
	IMAGE_FILE_EMPTY(HttpStatus.BAD_REQUEST, "CHAT_005", "이미지 파일은 비어 있을 수 없습니다."),
	CHAT_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "CHAT_006","지원하지 않는 채팅 이미지 형식입니다."),
	CHAT_IMAGE_NOT_FOUND(HttpStatus.BAD_REQUEST, "CHAT_007","업로드된 채팅 이미지를 찾을 수 없습니다."),
	CHAT_IMAGE_FORBIDDEN(HttpStatus.FORBIDDEN, "CHAT_008","사용할 수 없는 채팅 이미지입니다."),

	ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_001", "주문을 찾을 수 없습니다."),
	ORDER_RESERVATION_INVALID(HttpStatus.CONFLICT, "ORDER_002", "유효한 상품 예약이 아닙니다."),
	ORDER_RESERVATION_EXPIRED(HttpStatus.CONFLICT, "ORDER_003", "상품 예약 시간이 만료되었습니다."),
	ORDER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "ORDER_004", "주문에 접근할 권한이 없습니다."),
    REVIEW_NOT_ALLOWED(HttpStatus.CONFLICT, "REVIEW_001", "구매 확정된 상대방 거래에만 후기를 작성할 수 있습니다."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "REVIEW_002", "이미 이 거래에 후기를 작성했습니다."),
    SETTLEMENT_INVALID_STATE(HttpStatus.CONFLICT, "SETTLEMENT_001", "현재 주문은 정산할 수 없습니다."),
    SETTLEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "SETTLEMENT_003", "정산 내역을 찾을 수 없습니다."),
    SETTLEMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "SETTLEMENT_004", "정산 실행 권한이 없습니다."),
    SETTLEMENT_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "SETTLEMENT_005", "실제 지급 제공자 또는 수수료 설정이 필요합니다."),
    SETTLEMENT_RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "SETTLEMENT_007", "검증된 판매자 수취 정보가 없습니다."),
    SETTLEMENT_RECIPIENT_INVALID(HttpStatus.CONFLICT, "SETTLEMENT_008", "수취인 소유 관계 또는 승인 상태를 확인해 주세요."),
    SETTLEMENT_PRECHECK_FAILED(HttpStatus.CONFLICT, "SETTLEMENT_009", "지급 가능 시간과 잔액을 확인한 뒤 다시 요청해 주세요."),
    SETTLEMENT_PROVIDER_FAILED(HttpStatus.BAD_GATEWAY, "SETTLEMENT_006", "지급 결과를 확인할 수 없습니다. 정산 상태를 조회해 주세요."),
    REFUND_AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "REFUND_001", "환불 금액은 주문 금액과 같아야 합니다."),
    REFUND_CONFLICT(HttpStatus.CONFLICT, "REFUND_002", "이미 접수된 환불 요청과 내용이 다릅니다."),
    REFUND_FORBIDDEN(HttpStatus.FORBIDDEN, "REFUND_004", "반품 검수 권한이 없습니다."),
    REFUND_RETURN_REQUIRED(HttpStatus.BAD_REQUEST, "REFUND_005", "반품 물품 수령 확인이 필요합니다."),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "REFUND_003", "환불 내역을 찾을 수 없습니다."),
	SHIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "SHIPMENT_001", "배송 정보를 찾을 수 없습니다."),
	SHIPMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "SHIPMENT_002", "배송 정보를 변경할 권한이 없습니다."),
	ORDER_INVALID_STATE(HttpStatus.CONFLICT, "ORDER_005", "현재 주문 상태에서는 요청을 처리할 수 없습니다."),
	ORDER_REQUEST_CONFLICT(HttpStatus.CONFLICT, "ORDER_006", "이미 접수된 요청과 내용이 다릅니다."),
	PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_001", "결제를 찾을 수 없습니다."),
	PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT_002", "결제 금액이 주문 금액과 일치하지 않습니다."),
	PAYMENT_INVALID_STATUS(HttpStatus.BAD_REQUEST, "PAYMENT_003", "결제를 처리할 수 없는 상태입니다."),
	TOSS_PAYMENTS_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT_004", "토스페이먼츠 설정이 필요합니다."),
	TOSS_PAYMENTS_REQUEST_FAILED(HttpStatus.BAD_REQUEST, "PAYMENT_005", "토스페이먼츠 요청에 실패했습니다."),
	PAYMENT_WEBHOOK_INVALID(HttpStatus.BAD_REQUEST, "PAYMENT_006", "결제 웹훅 요청이 올바르지 않습니다."),
	PAYMENT_RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "PAYMENT_007", "현재 결제는 재시도할 수 없습니다.");


	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String code, String message) {
		this.httpStatus = httpStatus;
		this.code = code;
		this.message = message;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public String getCode() {
		return code;
	}

	public String getMessage() {
		return message;
	}
}
