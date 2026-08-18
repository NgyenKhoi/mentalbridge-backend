package com.mentalbridge.identity.authentication;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

	private final AuthenticateAccountService authentication;
	private final RefreshSessionService refresh;
	private final LogoutService logout;

	public AuthenticationController(AuthenticateAccountService authentication, RefreshSessionService refresh,
			LogoutService logout) {
		this.authentication = authentication;
		this.refresh = refresh;
		this.logout = logout;
	}

	@PostMapping("/login")
	TokenPairResponse login(@Valid @RequestBody LoginRequest request) {
		return response(authentication.authenticate(request.email(), request.password(), request.deviceLabel()));
	}

	@PostMapping("/refresh")
	TokenPairResponse refresh(@Valid @RequestBody RefreshRequest request,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
		return response(refresh.refresh(request.refreshToken(), idempotencyKey));
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RefreshRequest request) {
		logout.logout(UUID.fromString(jwt.getSubject()), request.refreshToken());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/logout-all")
	ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
		logout.logoutAll(UUID.fromString(jwt.getSubject()));
		return ResponseEntity.noContent().build();
	}

	private TokenPairResponse response(AuthenticateAccountService.TokenPair pair) {
		return new TokenPairResponse(pair.accessToken(), "Bearer", pair.expiresIn(), pair.refreshToken(),
				pair.refreshExpiresAt());
	}

	public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(max = 128) String password, @Size(min = 1, max = 120) String deviceLabel) {

		@Override
		public String toString() {
			return "LoginRequest[email=" + email + ", password=[REDACTED], deviceLabel=" + deviceLabel + "]";
		}
	}

	public record RefreshRequest(@NotBlank @Size(min = 43, max = 512) String refreshToken) {

		@Override
		public String toString() {
			return "RefreshRequest[refreshToken=[REDACTED]]";
		}
	}

	public record TokenPairResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
			Instant refreshExpiresAt) {

		@Override
		public String toString() {
			return "TokenPairResponse[accessToken=[REDACTED], tokenType=" + tokenType + ", expiresIn=" + expiresIn
					+ ", refreshToken=[REDACTED], refreshExpiresAt=" + refreshExpiresAt + "]";
		}
	}

}
