package com.mentalbridge.identity.registration;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.shared.validation.Utf8ByteLength;

@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {

	private final RegisterAccountService registration;
	private final VerifyEmailService verification;

	public RegistrationController(RegisterAccountService registration, VerifyEmailService verification) {
		this.registration = registration;
		this.verification = verification;
	}

	@PostMapping("/registrations")
	ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		var registered = registration.register(request.email(), request.password(), request.actorType().role(),
				idempotencyKey, correlationId(correlationId));
		var response = new RegistrationResponse(registered.accountId(), registered.status(), true,
				registered.createdAt());
		return ResponseEntity.created(URI.create("/api/v1/admin/accounts/" + registered.accountId())).body(response);
	}

	@PostMapping("/email-verifications")
	VerificationResponse verify(@Valid @RequestBody VerificationRequest request,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		var verified = verification.verify(request.challenge(), correlationId(correlationId));
		return new VerificationResponse(verified.accountId(), AccountStatus.ACTIVE, List.of(verified.role()), true);
	}

	private UUID correlationId(UUID supplied) {
		return supplied == null ? UUID.randomUUID() : supplied;
	}

	public record RegistrationRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 12, max = 128) @Utf8ByteLength(max = 72) String password,
			@NotNull PublicActorType actorType) {

		@Override
		public String toString() {
			return "RegistrationRequest[email=" + email + ", password=[REDACTED], actorType=" + actorType + "]";
		}
	}

	public enum PublicActorType {
		USER,
		SPECIALIST;

		RoleCode role() {
			return RoleCode.valueOf(name());
		}
	}

	public record RegistrationResponse(UUID accountId, AccountStatus status, boolean verificationRequired,
			Instant createdAt) {
	}

	public record VerificationRequest(@NotBlank @Size(min = 32, max = 512) String challenge) {

		@Override
		public String toString() {
			return "VerificationRequest[challenge=[REDACTED]]";
		}
	}

	public record VerificationResponse(UUID accountId, AccountStatus status, List<RoleCode> roles,
			boolean emailVerified) {
	}

}
