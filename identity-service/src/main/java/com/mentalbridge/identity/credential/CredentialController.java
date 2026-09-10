package com.mentalbridge.identity.credential;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.identity.shared.validation.Utf8ByteLength;

@RestController
public class CredentialController {

	private final CredentialLifecycleService credentials;

	public CredentialController(CredentialLifecycleService credentials) {
		this.credentials = credentials;
	}

	@PostMapping("/api/v1/auth/email-verification-requests")
	ResponseEntity<Void> requestEmailVerification(@Valid @RequestBody EmailRequest request,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		credentials.requestEmailVerification(request.email(), correlationId(correlationId));
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/api/v1/auth/password-recovery-requests")
	ResponseEntity<Void> requestPasswordRecovery(@Valid @RequestBody EmailRequest request,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		credentials.requestPasswordRecovery(request.email(), correlationId(correlationId));
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/api/v1/auth/password-resets")
	ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
		credentials.resetPassword(request.challenge(), request.newPassword());
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/api/v1/account/password")
	ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody PasswordChangeRequest request) {
		credentials.changePassword(UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newPassword());
		return ResponseEntity.noContent().build();
	}

	private UUID correlationId(UUID supplied) {
		return supplied == null ? UUID.randomUUID() : supplied;
	}

	public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
		@Override
		public String toString() {
			return "EmailRequest[email=[REDACTED]]";
		}
	}

	public record PasswordResetRequest(@NotBlank @Size(min = 32, max = 512) String challenge,
			@NotBlank @Size(min = 12, max = 128) @Utf8ByteLength(max = 72) String newPassword) {
		@Override
		public String toString() {
			return "PasswordResetRequest[challenge=[REDACTED], newPassword=[REDACTED]]";
		}
	}

	public record PasswordChangeRequest(@NotBlank @Size(max = 128) String currentPassword,
			@NotBlank @Size(min = 12, max = 128) @Utf8ByteLength(max = 72) String newPassword) {
		@Override
		public String toString() {
			return "PasswordChangeRequest[currentPassword=[REDACTED], newPassword=[REDACTED]]";
		}
	}
}
