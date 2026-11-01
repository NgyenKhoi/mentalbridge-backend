package com.mentalbridge.identity.retention;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DeletionRetryExhaustedException extends ResponseStatusException {
	public DeletionRetryExhaustedException(int maxRetries) {
		super(HttpStatus.CONFLICT, "Đã đạt giới hạn tối đa số lần thử lại (" + maxRetries + " lần). Yêu cầu chuyển sang xử lý thủ công (MANUAL_ESCALATION_REQUIRED).");
	}
}

