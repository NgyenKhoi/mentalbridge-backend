package com.mentalbridge.identity.account;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.identity.account-status-authorization")
public record AccountStatusAuthorizationProperties(
		@Pattern(regexp = "(?:|[!-~]{32,512})") String careServiceToken,
		@Pattern(regexp = "(?:|[!-~]{32,512})") String communityServiceToken,
		@Pattern(regexp = "(?:|[!-~]{32,512})") String consultationServiceToken) {

	public AccountStatusAuthorizationProperties {
		careServiceToken = normalize(careServiceToken);
		communityServiceToken = normalize(communityServiceToken);
		consultationServiceToken = normalize(consultationServiceToken);
		var configured = java.util.stream.Stream.of(careServiceToken, communityServiceToken, consultationServiceToken)
				.filter(token -> !token.isEmpty()).toList();
		if (configured.stream().distinct().count() != configured.size()) {
			throw new IllegalArgumentException("configured account-status service tokens must be distinct");
		}
	}

	String tokenFor(String serviceId) {
		return switch (serviceId) {
			case "care-service" -> careServiceToken;
			case "community-service" -> communityServiceToken;
			case "consultation-service" -> consultationServiceToken;
			default -> "";
		};
	}

	private static String normalize(String value) {
		return value == null ? "" : value;
	}

	@Override
	public String toString() {
		return "AccountStatusAuthorizationProperties[tokens=REDACTED]";
	}
}
