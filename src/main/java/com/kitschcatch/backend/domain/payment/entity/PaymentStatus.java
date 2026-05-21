package com.kitschcatch.backend.domain.payment.entity;

public enum PaymentStatus {
	READY,
	REQUESTED,
	CONFIRMING,
	APPROVED,
	CANCELING,
	CANCELED,
	FAILED
}
