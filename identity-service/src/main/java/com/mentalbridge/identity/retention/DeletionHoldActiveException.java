package com.mentalbridge.identity.retention;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DeletionHoldActiveException extends ResponseStatusException {
	public DeletionHoldActiveException(String reason) {
		super(HttpStatus.CONFLICT, "Yêu cầu xóa dữ liệu đang bị khóa bởi lệnh Hold: " + reason);
	}
}

