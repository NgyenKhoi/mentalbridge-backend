package com.mentalbridge.identity.retention;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class UnsupportedRetentionCapabilityException extends ResponseStatusException {
	public UnsupportedRetentionCapabilityException(String policyId, String reason) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, "Chính sách " + policyId + " không hỗ trợ thực thi tự động: " + reason);
	}
}

