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

	public static CommunityApiException invalidPostInput() {
		return new CommunityApiException(HttpStatus.BAD_REQUEST, "COMMUNITY_POST_INVALID",
				"Community post input is invalid");
	}

	public static CommunityApiException invalidIdempotencyKey() {
		return new CommunityApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
				"Idempotency-Key is invalid");
	}

	public static CommunityApiException idempotencyKeyReused() {
		return new CommunityApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
				"Idempotency-Key was already used for a different request");
	}

	public static CommunityApiException mediaNotAttachable() {
		return new CommunityApiException(HttpStatus.CONFLICT, "COMMUNITY_MEDIA_NOT_ATTACHABLE",
				"One or more media items cannot be attached");
	}

	public static CommunityApiException versionMismatch() {
		return new CommunityApiException(HttpStatus.PRECONDITION_FAILED, "COMMUNITY_POST_VERSION_MISMATCH",
				"Community post changed before this request completed");
	}

	public static CommunityApiException invalidVersionHeader() {
		return new CommunityApiException(HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH",
				"If-Match must contain one quoted non-negative version");
	}

	public static CommunityApiException communityAccessUnavailable() {
		return new CommunityApiException(HttpStatus.FORBIDDEN, "COMMUNITY_ACCESS_UNAVAILABLE",
				"Community access is unavailable");
	}

	public static CommunityApiException profileNotFound() {
		return new CommunityApiException(HttpStatus.NOT_FOUND, "COMMUNITY_PROFILE_NOT_FOUND",
				"Community profile was not found");
	}

	public static CommunityApiException invalidProfile(String message) {
		return new CommunityApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
	}

	public static CommunityApiException invalidIfMatch() {
		return new CommunityApiException(HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH",
				"If-Match must be a quoted non-negative Community profile version");
	}

	public static CommunityApiException profileVersionRequired() {
		return new CommunityApiException(HttpStatus.PRECONDITION_FAILED, "COMMUNITY_PROFILE_VERSION_REQUIRED",
				"If-Match is required when replacing an existing Community profile");
	}

	public static CommunityApiException profileVersionMismatch(String message) {
		return new CommunityApiException(HttpStatus.PRECONDITION_FAILED, "COMMUNITY_PROFILE_VERSION_MISMATCH", message);
	}

}
