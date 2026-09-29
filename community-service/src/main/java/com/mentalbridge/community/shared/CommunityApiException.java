package com.mentalbridge.community.shared;

import org.springframework.http.HttpStatus;

public class CommunityApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public CommunityApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public static CommunityApiException invalidCursor() {
		return new CommunityApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Feed cursor is invalid");
	}

	public static CommunityApiException postNotFound() {
		return new CommunityApiException(HttpStatus.NOT_FOUND, "COMMUNITY_POST_NOT_FOUND",
				"Community post was not found");
	}

	public static CommunityApiException invalidSubject() {
		return new CommunityApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
	}

}
