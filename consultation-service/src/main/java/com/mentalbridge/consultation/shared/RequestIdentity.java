package com.mentalbridge.consultation.shared;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

public final class RequestIdentity {

	private RequestIdentity() {
	}

	public static UUID subject(Jwt jwt) {
		try {
			var subject = jwt.getSubject();
			if (subject == null) throw new IllegalArgumentException("JWT subject is missing");
			return UUID.fromString(subject);
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}

	public static long requiredVersion(String ifMatch) {
		if (ifMatch == null || !ifMatch.matches("\\\"[0-9]+\\\"")) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "PROFILE_VERSION_REQUIRED",
					"If-Match must contain the quoted current profile version");
		}
		return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
	}

	public static Long optionalVersion(String ifMatch) {
		return ifMatch == null ? null : requiredVersion(ifMatch);
	}
}
