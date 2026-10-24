package com.mentalbridge.care.consent;

import org.springframework.http.HttpStatus;

final class VoiceApiException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	VoiceApiException(HttpStatus status, String code, String title) {
		super(title);
		this.status = status;
		this.code = code;
	}

	HttpStatus status() { return status; }
	String code() { return code; }
}
