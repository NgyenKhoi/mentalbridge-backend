package com.mentalbridge.identity.retention;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DeletionRequestNotFoundException extends ResponseStatusException {
	public DeletionRequestNotFoundException(UUID requestId) {
		super(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu xóa dữ liệu: " + requestId);
	}
}

